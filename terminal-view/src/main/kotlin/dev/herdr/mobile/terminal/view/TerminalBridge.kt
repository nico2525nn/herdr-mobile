package dev.herdr.mobile.terminal.view

import dev.herdr.mobile.terminal.emulator.TerminalColorScheme
import dev.herdr.mobile.terminal.emulator.TerminalEmulator
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
import kotlinx.coroutines.withContext

/**
 * Owns one [TerminalEmulator] fed by one [TerminalBackend].
 *
 * All emulator mutation happens on a single-threaded context; the View only reads immutable
 * snapshots ([frame]) published after each mutation. Input goes straight to the backend and
 * never touches the emulator.
 */
class TerminalBridge(
    private val backend: TerminalBackend,
    colorScheme: TerminalColorScheme,
    boldIsBright: Boolean,
    scrollbackLimit: Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Single-threaded mutation keeps ordering trivially correct.
    private val emulatorContext = Dispatchers.Default.limitedParallelism(1)

    private val emulator = TerminalEmulator(colorScheme, boldIsBright).also {
        it.setScrollbackLimit(scrollbackLimit)
        it.onResponse = { bytes ->
            scope.launch { runCatching { backend.send(bytes) } }
        }
    }

    private val _frame = MutableStateFlow(FrameSnapshot.empty())
    /** Latest fully-rendered viewport. Updated only when the emulator revision changes. */
    val frame: StateFlow<FrameSnapshot> = _frame.asStateFlow()

    private val _bell = MutableStateFlow(0L)
    /** Increments on every BEL. The View consumes it for haptic/audio feedback. */
    val bell: StateFlow<Long> = _bell.asStateFlow()

    private var pump: Job? = null
    private var started = false
    /** Invoked when release() runs, so hosts can cancel per-bridge collectors. */
    var onRelease: (() -> Unit)? = null

    /**
     * Starts frame pumping. Must be called exactly once per bridge; a second
     * call is a programming error and is ignored instead of duplicating all
     * emulator input with a second collector.
     */
    fun start(cols: Int, rows: Int) {
        if (started) return
        started = true
        scope.launch(emulatorContext) {
            emulator.resize(cols, rows)
            publish()
        }
        pump = scope.launch {
            backend.bytes.collect { data ->
                withContext(emulatorContext) {
                    val before = emulator.revision
                    emulator.write(data)
                    if (emulator.bell) {
                        emulator.bell = false
                        _bell.update { it + 1 }
                    }
                    // Skip the full-grid copy when nothing renderable changed:
                    // publish() deep-copies rows×cols cells per chunk.
                    if (emulator.revision != before) {
                        publish()
                    }
                }
            }
        }
    }

    private fun publish() {
        _frame.value = FrameSnapshot(
            rows = emulator.lines.map { it.toList() },
            cursorRow = emulator.cursorRow,
            cursorCol = emulator.cursorCol,
            cursorVisible = emulator.cursorVisible,
            usingAlternateScreen = emulator.usingAlternateScreen,
            scrollbackOffset = emulator.scrollbackOffset,
            revision = emulator.revision,
            cols = emulator.cols,
            rowCount = emulator.rows,
        )
    }

    fun resize(cols: Int, rows: Int) {
        scope.launch(emulatorContext) {
            emulator.resize(cols, rows)
            publish()
        }
        scope.launch { runCatching { backend.resize(cols, rows) } }
    }

    /**
     * Local scrollback scroll ONLY. The Herdr-side `terminal.scroll` is deliberately not
     * called: it produces no frames on plain shells (verified: zero bytes back), so
     * calling it alongside the local offset double-scrolls / corrupts the remote view
     * state for no visible effect. Alt-screen apps have no local scrollback (offset
     * clamps to 0 against the empty ring), so scroll there is correctly a no-op.
     */
    fun scrollBy(lines: Int) {
        scope.launch(emulatorContext) {
            emulator.scrollBy(lines)
            publish()
        }
    }

    suspend fun send(data: ByteArray) = backend.send(data)

    suspend fun sendText(text: String) = backend.sendText(text)

    suspend fun mouse(action: String, button: String, column: Int, row: Int) =
        backend.mouse(action, button, column, row)

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
