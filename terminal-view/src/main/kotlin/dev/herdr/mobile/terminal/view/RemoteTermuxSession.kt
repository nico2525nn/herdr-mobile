package dev.herdr.mobile.terminal.view

import android.os.Looper
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns one forkless Termux session fed by one [TerminalBackend].
 *
 * Termux's [TerminalSession] normally forks a local PTY; here it is a never-started
 * shell whose only job is satisfying `TerminalView.attachSession()`. The emulator is
 * constructed directly (public ctor) with a [TermuxOutput] sink and injected via one
 * reflective field write (`mEmulator` has no setter and the class is final). With
 * `mShellPid == 0`, `session.write()` drops (desired: view input is intercepted by
 * [TermuxViewClient] and sent to the network) and `updateSize()` takes the resize
 * branch (never forks; the `setPtyWindowSize` ioctl against fd 0 is a no-op, but it
 * triggers the JNI `loadLibrary`, so `libtermux.so` must ship — it comes in the AAR).
 *
 * Threading: Termux has NO locking — the emulator is mutated ONLY on the main
 * thread ([pump] collects on Dispatchers.Main.immediate). View reads on draw.
 * Never call `finishIfRunning()` (would `kill(0)` our own process group).
 *
 * Replaces the old bridge: same lifecycle (`start` once, `release`),
 * same `bell` counter, same fire-and-forget `scrollRemote`/`mouse`.
 */
class RemoteTermuxSession(
    private val backend: TerminalBackend,
    val view: com.termux.view.TerminalView,
    private val host: TermuxTerminalHost,
    scrollbackLimit: Int,
    /** OSC-52 / selection copy sink (screen clipboard). */
    onCopyText: ((String) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val sessionClient = TermuxSessionClient()
    val output = TermuxOutput(
        send = { backend.send(it) },
        scope = scope,
    )

    private val session: TerminalSession
    /** The forkless session, for `view.attachSession()`. */
    val termuxSession: TerminalSession get() = session
    val emulator: TerminalEmulator

    private val _bell = MutableStateFlow(0L)
    /** Increments on every BEL. The screen consumes it for haptic/audio feedback. */
    val bell: StateFlow<Long> = _bell.asStateFlow()

    private var pump: Job? = null
    private var started = false

    /** Last grid pushed to the backend (resize watcher diffs against it). */
    private var lastCols = 0
    private var lastRows = 0

    /** Invoked when release() runs, so hosts can cancel per-session collectors. */
    var onRelease: (() -> Unit)? = null

    init {
        // Fail fast on library upgrades that move the field (R8 keeps it; see
        // proguard-rules.pro). Pinned to terminal-emulator v0.118.3.
        val field = try {
            TerminalSession::class.java.getDeclaredField("mEmulator").apply {
                isAccessible = true
            }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Termux TerminalSession.mEmulator gone — pinned v0.118.3 API changed",
                e,
            )
        }
        // transcriptRows clamps 100..50000 inside Termux; pass ours through.
        session = TerminalSession("", "", null, null, scrollbackLimit, sessionClient)
        // Cell px unknown until layout; 0 is fine (resize() corrects on attach).
        emulator = TerminalEmulator(output, 80, 24, 0, 0, scrollbackLimit, sessionClient)
        field.set(session, emulator)

        // The emulator calls ONLY the TerminalOutput for bell/colors/OSC-52
        // (mSession.onBell()/onColorsChanged()/onCopyTextToClipboard); the
        // session-client twins fire solely via explicit session.onX() calls
        // (selection copy uses onCopyTextToClipboard — wired). Bumping _bell
        // in both would double-buzz if Termux ever dual-routes.
        output.onBell = { _bell.update { it + 1 } }
        output.onColorsChanged = { view.postInvalidate() }
        output.onTitleChanged = { view.postInvalidate() }
        output.onCopyText = onCopyText
        sessionClient.onColorsChanged = { view.postInvalidate() }
        sessionClient.onCopyText = onCopyText
    }

    /**
     * Starts byte pumping. Must be called exactly once per session; a second
     * call is ignored instead of duplicating all emulator input.
     */
    fun start(cols: Int, rows: Int) {
        if (started) return
        started = true
        lastCols = cols
        lastRows = rows
        session.updateSize(cols, rows, 0, 0)
        pump = scope.launch {
            backend.bytes.collect { data ->
                // Main thread only (scope is Main.immediate): Termux has no
                // locking and the view reads the emulator on draw.
                check(Looper.myLooper() == Looper.getMainLooper()) {
                    "Termux pump left the main thread"
                }
                emulator.append(data, data.size)
                // Publish live flags for remote-scroll routing + diagnostics.
                host.altActive = emulator.isAlternateBufferActive
                host.mouseTracking = emulator.isMouseTrackingActive
                view.onScreenUpdated()
                // Resize watcher: the view recomputes the grid internally on
                // layout (no callback); forward changes to the backend.
                val (colsNow, rowsNow) = emulator.grid()
                if (colsNow != lastCols || rowsNow != lastRows) {
                    lastCols = colsNow
                    lastRows = rowsNow
                    runCatching { backend.resize(colsNow, rowsNow) }
                }
            }
        }
        scope.launch { runCatching { backend.resize(cols, rows) } }
    }

    /**
     * Forward the VIEW-computed grid to the backend (layout path). The Termux
     * view resizes its own emulator on every layout (updateSize) — the host
     * must NOT second-guess it with its own metrics (a disagreeing size would
     * resize mid-stream through the column-change reflow, which drops banked
     * transcript rows). Call after layout (view.post) so updateSize ran first.
     */
    fun syncBackendSize() {
        val (colsNow, rowsNow) = emulator.grid()
        if (colsNow == lastCols && rowsNow == lastRows) return
        lastCols = colsNow
        lastRows = rowsNow
        scope.launch { runCatching { backend.resize(colsNow, rowsNow) } }
    }

    suspend fun send(data: ByteArray) = backend.send(data)

    suspend fun sendText(text: String) = backend.sendText(text)

    suspend fun mouse(action: String, button: String, column: Int, row: Int) =
        backend.mouse(action, button, column, row)

    /**
     * Forward a scroll gesture to Herdr (alt-screen TUIs + agent panes; shells
     * scroll local history in-view). Fire-and-forget: repaint frames arrive as
     * normal bytes; the local offset stays pinned at live.
     */
    fun scrollRemote(lines: Int) {
        if (lines == 0) return
        scope.launch { runCatching { backend.scrollRemote(lines) } }
    }

    /**
     * Release the backend connection. Suspends until the release frame has been
     * handed to the backend — never fire-and-forget on a scope that dies with us,
     * or the release never reaches Herdr and the direct-attach resize lock leaks.
     */
    suspend fun release() {
        pump?.cancel()
        pump = null
        runCatching { onRelease?.invoke() }
        runCatching { backend.release() }
        scope.cancel()
    }

}
