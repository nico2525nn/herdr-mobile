package dev.herdr.mobile.feature.terminal

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.view.TerminalView
import dev.herdr.mobile.terminal.view.BackendState
import dev.herdr.mobile.terminal.view.RemoteTermuxSession
import dev.herdr.mobile.terminal.view.TermuxSchemes
import dev.herdr.mobile.terminal.view.TermuxTerminalHost
import dev.herdr.mobile.terminal.view.TermuxViewClient
import dev.herdr.mobile.terminal.view.installBoxDrawingOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState

@Composable
internal fun TerminalSurface(
    state: TerminalUiState,
    onSendBytes: (ByteArray) -> Unit,
    onSendText: (String) -> Unit,
    onSendPaste: (String) -> Unit,
    onRetry: () -> Unit,
    keyboardShowRequest: Int,
    keyboardShowHandled: Int,
    onKeyboardShowHandled: (Int) -> Unit,
    /** Keystroke counter as a flow: reading it as state would recompose the
     *  whole surface on every key press. */
    inputTick: kotlinx.coroutines.flow.Flow<Int>,
    onGridChanged: (cols: Int, rows: Int) -> Unit,
    /** Shared with the bottom panel so page switches can retarget the IME. */
    cjkFocus: androidx.compose.ui.focus.FocusRequester?,
) {
    val backend = state.backend ?: return
    val backendState by backend.state.collectAsStateWithLifecycle(initialValue = BackendState.Idle)
    val clipboard = LocalClipboardManager.current
    // Attach failure / disconnect must never render as a blank terminal: show the
    // reason with a retry instead, and stop feeding a dead backend to the view.
    // lagged_resync is transient (daemon dropped burst frames): retry at once.
    val detached = backendState as? BackendState.Detached
    if (detached?.reason == "lagged_resync") {
        LaunchedEffect(backend) { onRetry() }
    }
    when (val bs = backendState) {
        is BackendState.Failed -> {
            TerminalErrorBody("Attach failed (${bs.code}): ${bs.message}", onRetry)
            return
        }
        is BackendState.Detached -> {
            TerminalErrorBody("Detached (${bs.reason})", onRetry)
            return
        }
        // Prelude live-screen fetch failed: the grid starts BLANK (no bytes
        // yet, possibly never on an idle pane). Without this branch the user
        // stares at an empty terminal with no explanation and no recourse
        // (the reported notification-tap blank that survived reconnects).
        // History-only failure stays silent (diagnostics overlay shows it).
        is BackendState.Attached -> {
            if (!bs.visibleOk) {
                TerminalErrorBody("Live screen unavailable — the pane may be gone", onRetry)
                return
            }
        }
        else -> {}
    }
    val settings = state.settings
    val appIdentity = state.pane?.agent?.trim()?.takeIf { it.isNotEmpty() }
    LaunchedEffect(appIdentity) {
        if (appIdentity != null && !dev.herdr.mobile.core.model.AgentMatrix.isKnownApp(appIdentity)) {
            Log.w(
                "AgentMatrix",
                "Unknown core app identity '$appIdentity'; using safe page-key/emulator-bit defaults",
            )
        }
    }
    var bridge by remember(backend) { mutableStateOf<RemoteTermuxSession?>(null) }
    // DisposableEffect(backend) re-runs on backend change, and by onDispose
    // time composition has already created the NEXT bridge: keep the live one
    // outside the remember(backend) reset or the release targets null/next.
    val live = remember { LiveSession() }
    var viewRef by remember { mutableStateOf<TerminalView?>(null) }
    var hostRef by remember { mutableStateOf<TermuxTerminalHost?>(null) }
    var viewClientRef by remember { mutableStateOf<TermuxViewClient?>(null) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val liveAgent = rememberUpdatedState(appIdentity)
    val liveSendBytes = rememberUpdatedState(onSendBytes)
    val liveSendText = rememberUpdatedState(onSendText)
    val liveSendPaste = rememberUpdatedState(onSendPaste)

    TerminalImeEffects(
        host = { hostRef },
        view = { viewRef },
        cjkFocus = cjkFocus,
        inputPage = state.inputPage,
        paneId = state.pane?.id,
        keyboardShowRequest = keyboardShowRequest,
        keyboardShowHandled = keyboardShowHandled,
        onKeyboardShowHandled = onKeyboardShowHandled,
    )

    // Scroll routing follows the pane's identity as it arrives: the snapshot
    // can land after the stream is attached (agent detection is asynchronous),
    // and a one-shot write from the session-creation effect left such panes on
    // the local path forever.
    val scrollInfo = state.pane?.scroll
    LaunchedEffect(hostRef, appIdentity, scrollInfo) {
        hostRef?.diagnosticsAgent = appIdentity
        hostRef?.hostScrollable = state.pane?.hostScrollable == true
        hostRef?.hostScrollKnown = scrollInfo != null
        hostRef?.scrollMode = state.settings.scrollMode
        hostRef?.hostViewportRows = scrollInfo?.viewportRows?.toInt() ?: 0
        if (hostRef?.remoteEffective == true) hostRef?.resetLocalViewport()
    }
    // User input snaps a scrolled viewport home (back at the prompt). Collected
    // as a flow, not state: a keystroke must not recompose the terminal.
    LaunchedEffect(inputTick) {
        inputTick.collect { tick -> if (tick > 0) hostRef?.snapToBottom() }
    }

    val scheme = remember(state.settings.terminalColorScheme) { state.terminalScheme() }
    val typeface = remember(settings.terminalFont) {
        android.graphics.Typeface.create(settings.terminalFont.familyName, android.graphics.Typeface.NORMAL)
    }
    val textPx = (settings.terminalFontSizeSp * density.density).toInt()
    // What the live view already has. Plain fields, not snapshot state: this is
    // read/written from the AndroidView update pass, which runs on every
    // recomposition (a keystroke used to land here and reset the palette).
    val applied = remember { ViewStyle() }

    // View size → grid size → backend resize, debounced by the bridge/daemon coalescing.
    var viewSizePx by remember { mutableStateOf(0 to 0) }
    // First traversal complete (view.post in the factory): creating the
    // session before layout yields a transient grid.
    var viewLaidOut by remember { mutableStateOf(false) }

    // The session belongs to the backend, not to the view size: create it
    // eagerly once layout is known (viewSizePx persists across tab switches,
    // so identical-layout switches attach immediately). NEVER start at the
    // 80x24 fallback: Termux growth-resize (24→30) consumes transcript rows
    // into the screen, which would eat the prelude history the daemon just
    // banked. Waiting for the first layout costs one frame and sizes exactly.
    LaunchedEffect(backend, viewRef, viewSizePx, viewLaidOut) {
        val view = viewRef
        val host = hostRef
        val (w, h) = viewSizePx
        if (bridge == null && view != null && host != null && w > 0 && h > 0 && viewLaidOut) {
            TermuxSchemes.apply(scheme)
            val created = RemoteTermuxSession(
                backend = backend,
                view = view,
                host = host,
                scrollbackLimit = settings.scrollbackLimit,
                onCopyText = { text ->
                    if (text.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(text))
                    }
                },
                // Termux toolbar PASTE: clipboard text goes through the same
                // paste normalization as CJK commits (strip ESC/C1, newlines
                // to CR), then to the network. Stock Termux pastes into its
                // local PTY at this exact hook. When the app enabled 2004
                // (agents, TUIs), wrap in the bracket markers so the block
                // lands as one paste (multiline into the input box, not
                // line-by-line submits); plain shells never set the mode and
                // get the raw text exactly as before. Pi and OpenCode have a measured server-side paste path
                // whose DECSET is not visible locally; AgentMatrix forces
                // brackets for those kinds and trusts the emulator bit for
                // the other mapped agents.
                // Core bracketed payloads use binary DATA so markers survive
                // text normalization; TUI keeps its existing input.text path.
                onPasteRequest = {
                    val clip = clipboard.getText()?.text.orEmpty()
                    if (clip.isNotEmpty()) {
                        val emu = live.session?.emulator
                        val bracket = dev.herdr.mobile.terminal.view.TerminalKeyEncoder
                            .isBracketedPasteMode(emu) ||
                            dev.herdr.mobile.core.model.AgentMatrix.alwaysBracket(state.pane?.agent)
                        when (
                            val payload = dev.herdr.mobile.terminal.view.TerminalKeyEncoder
                                .pastePayload(
                                    clip,
                                    bracketed = bracket,
                                    binaryBackend = backend.supportsBinaryPaste,
                                )
                        ) {
                            is dev.herdr.mobile.terminal.view.TerminalKeyEncoder.PastePayload.Bytes ->
                                onSendBytes(payload.value)
                            is dev.herdr.mobile.terminal.view.TerminalKeyEncoder.PastePayload.Text ->
                                onSendPaste(payload.value)
                        }
                    }
                },
            )
            created.sessionClient.cursorStyle = settings.cursorStyle
            created.emulator.mColors.reset()
            view.attachSession(created.termuxSession)
            // The view computed the grid synchronously in attach (updateSize):
            // start the pump with ITS numbers, never our own metrics (a
            // disagreeing size resizes mid-prelude through the column-change
            // reflow, which drops banked transcript rows).
            val cols = created.emulator.mColumns
            val rows = created.emulator.mRows
            bridge = created
            live.session = created
            created.start(cols, rows)
        }
    }

    // Rebind every composition in update(): the factory closure runs once and
    // captured `bridge` State goes stale after a tab switch (remember yields a
    // NEW State object). Stale callbacks tap/scroll the released backend,
    // silently dropped by runCatching.
    /**
     * Binds the view's callbacks ONCE per view. Captured callbacks go through
     * `rememberUpdatedState` holders, so a recomposition never has to rebind
     * (the old code did, allocating eight lambdas per frame and still needing
     * the rebind to avoid stale closures).
     */
    fun bindTermuxCallbacks(viewClient: TermuxViewClient, host: TermuxTerminalHost) {
        viewClient.onDirectInput = { text -> liveSendText.value(text) }
        viewClient.onDirectBytes = { bytes -> liveSendBytes.value(bytes) }
        viewClient.onDirectDelete = { liveSendBytes.value(byteArrayOf(0x7F)) }
        viewClient.onTapCell = { col, row ->
            // Mouse-mode apps (vim, less, tmux): forward the tap as a
            // left-button press. Non-mouse apps ignore it server-side.
            val b = live.session
            if (b != null) {
                scope.launch { runCatching { b.mouse("down", "left", col, row) } }
            }
        }
        viewClient.onZoomFont = { _ ->
            // Font zoom is applied through settings; the host clamps it.
        }
        viewClient.onPageScroll = { up -> host.pageRemote(up) }
        host.onRemoteScroll = { lines, unit ->
            live.session?.scrollRemote(
                lines,
                allowPageKey = dev.herdr.mobile.core.model.AgentMatrix.allowPageKey(
                    appIdentity = liveAgent.value,
                    altActive = host.altActive,
                ),
                unit = unit,
            )
        }
        host.onRemoteWheel = { notches ->
            live.session?.scrollWheel(notches)
        }
        // Full-screen panes: type the page key. Sending it through Herdr's
        // scroll control does not reach apps without host scrollback; the same
        // bytes on the input channel do (verified on codex: PgUp scrolls).
        host.onPageKey = { up, pages ->
            val key = if (up) PAGE_UP_BYTES else PAGE_DOWN_BYTES
            val payload = ByteArray(key.size * pages)
            for (i in 0 until pages) key.copyInto(payload, i * key.size)
            liveSendBytes.value(payload)
        }
    }

    AndroidView(
        factory = { context ->
            // Init order matters (Termux NPEs otherwise): text size first
            // (updateSize derefs the renderer), then the view client (updateSize
            // calls onEmulatorSet), then attach (in the session effect above).
            val viewClient = TermuxViewClient()
            val view = TerminalView(context, null)
            // Mirror Termux's focusableInTouchMode XML attribute
            // (`app/src/main/res/layout/activity_termux.xml:31`); this view is
            // created programmatically, so set focusability explicitly for
            // hardware input and the inherited IME connection
            // (`TerminalView.java:297-390`). A tap's focus request is upstream
            // (`TerminalView.java:151-160`); explicit keyboard summon uses
            // host.showKeyboard(). No on-focus soft-input override is installed.
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            val px = (settings.terminalFontSizeSp * density.density).toInt()
            view.setTextSize(px)
            view.setTypeface(android.graphics.Typeface.create(settings.terminalFont.familyName, android.graphics.Typeface.NORMAL))
            view.setTerminalViewClient(viewClient)
            viewClient.view = view
            view.setBackgroundColor(TermuxSchemes.backgroundAndroid(scheme))
            val host = TermuxTerminalHost(view)
            viewClient.host = host
            host.textSizePx = px
            viewRef = view
            hostRef = host
            viewClientRef = viewClient
            bindTermuxCallbacks(viewClient, host)
            // Box-drawing bridge: as the view's foreground it is re-recorded
            // with every terminal redraw (a parent overlay would go stale).
            view.installBoxDrawingOverlay()
            view.post { viewLaidOut = true }
            view
        },
        update = { view ->
            // Settings follow live, but the expensive parts run only when they
            // actually changed: this pass executes on every recomposition.
            // Termux has no line-height multiplier (terminalLineHeight is
            // accepted as unsupported).
            val session = bridge
            if (applied.bridge !== session) {
                applied.bridge = session
                // A new session copies the palette; clear any per-session OSC
                // recolors from the previous one.
                session?.emulator?.mColors?.reset()
            }
            if (applied.scheme !== scheme) {
                applied.scheme = scheme
                TermuxSchemes.apply(scheme)
                view.setBackgroundColor(TermuxSchemes.backgroundAndroid(scheme))
                // TerminalColors is process-wide: the session must re-copy it.
                session?.emulator?.mColors?.reset()
                view.invalidate()
            }
            if (applied.font !== typeface || applied.textPx != textPx) {
                applied.font = typeface
                applied.textPx = textPx
                view.setTextSize(textPx)
                view.setTypeface(typeface)
                view.invalidate()
                hostRef?.textSizePx = textPx
            }
            if (applied.cursorStyle != settings.cursorStyle) {
                applied.cursorStyle = settings.cursorStyle
                session?.sessionClient?.cursorStyle = settings.cursorStyle
            }
            // Sticky CTRL/ALT feed the view client (Termux applies them to the
            // next hardware key when set).
            viewClientRef?.ctrlDown = dev.herdr.mobile.terminal.view.TerminalKeyEncoder.Modifier.CTRL in state.stickyModifiers
            viewClientRef?.altDown = dev.herdr.mobile.terminal.view.TerminalKeyEncoder.Modifier.ALT in state.stickyModifiers
        },
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                viewSizePx = size.width to size.height
                // Publish the live grid for the NEXT attach (daemon prelude
                // fill must match the real grid or history under-banks).
                val px = hostRef?.textSizePx ?: 0
                if (px > 0) {
                    val (gc, gr) = gridFor(px, size.width, size.height, settings.terminalFont.familyName)
                    onGridChanged(gc, gr)
                }
                // After layout (view.updateSize ran first): forward the
                // view-computed grid. Never resize the emulator from here.
                viewRef?.post { live.session?.syncBackendSize() }
            },
    )

    TerminalDiagnostics(
        host = hostRef,
        backendState = backendState,
        visible = state.settings.showScrollDiagnostics,
    )

    DisposableEffect(backend) {
        onDispose {
            // The bridge release suspends until the release frame reaches the
            // backend; run it on a fresh scope with a timeout, never on a scope
            // that is being torn down with us. Read from the holder, NOT bridge:
            // remember(backend) has already reset by onDispose time.
            val toRelease = live.session
            live.session = null
            if (toRelease != null) {
                val releaser = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                releaser.launch {
                    try {
                        withTimeout(5_000) { toRelease.release() }
                    } catch (_: Exception) {
                    } finally {
                        releaser.cancel()
                    }
                }
            }
        }
    }

    // BEL -> haptic/audio feedback. Gated on the bellVibration setting; without
    // a collector the bridge counter increments into the void.
    if (settings.bellVibration || settings.hapticFeedback) {
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        LaunchedEffect(bridge) {
            val b = bridge ?: return@LaunchedEffect
            var last = 0L
            b.bell.collect { count ->
                if (last != 0L && count != last) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                }
                last = count
            }
        }
    }

    // Terminal bottom edge: the Box weight above shrinks with imePadding, so
    // the grid's last row always sits on the input panel, never behind it.
}

/** xterm page keys, the sequence every audited TUI (and less/vim) understands. */
private val PAGE_UP_BYTES = byteArrayOf(0x1B, '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte())
private val PAGE_DOWN_BYTES = byteArrayOf(0x1B, '['.code.toByte(), '6'.code.toByte(), '~'.code.toByte())

/**
 * Holds the one live session across the `remember(backend)` reset. Not
 * snapshot state: nothing in composition depends on it.
 */
private class LiveSession {
    var session: RemoteTermuxSession? = null
}

/**
 * What the live [TerminalView] already reflects. Reset with the composition;
 * read/written from the AndroidView update pass, never from composition.
 */
private class ViewStyle {
    var bridge: RemoteTermuxSession? = null
    var scheme: dev.herdr.mobile.terminal.emulator.TerminalColorScheme? = null
    var font: android.graphics.Typeface? = null
    var textPx: Int = -1
    var cursorStyle: dev.herdr.mobile.core.model.CursorStyle? = null
}
