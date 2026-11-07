package dev.herdr.mobile.feature.terminal

import android.graphics.Typeface
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.core.model.InputPanelPage
import dev.herdr.mobile.core.model.ScrollMode
import dev.herdr.mobile.feature.terminal.theme.TerminalColorScheme
import io.github.sagernet.libghostty.GhosttyExtraKeysState
import io.github.sagernet.libghostty.GhosttyTerminalView
import io.github.sagernet.libghostty.GhosttyTerminalView.ScrollRouting
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow

/**
 * The terminal surface: one libghostty engine + view per attached backend.
 *
 * All emulation lives in libghostty-vt (same engine Herdr itself runs): the
 * screen supplies bytes, theme/font, and the keyboard summon, and the engine
 * answers with rendering, key encoding, selection and mode-aware scrolling.
 */
@Composable
internal fun TerminalSurface(
    state: TerminalUiState,
    viewModel: TerminalViewModel,
    onRetry: () -> Unit,
    keyboardShowRequest: Int,
    keyboardShowHandled: Int,
    onKeyboardShowHandled: (Int) -> Unit,
    inputTick: Flow<Int>,
    cjkFocus: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val backend = state.backend ?: return
    val backendState by backend.state.collectAsStateWithLifecycle(initialValue = BackendState.Idle)
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
        // stares at an empty terminal with no explanation and no recourse.
        is BackendState.Attached -> {
            if (!bs.visibleOk) {
                TerminalErrorBody("Live screen unavailable — the pane may be gone", onRetry)
                return
            }
        }
        else -> {}
    }

    val settings = state.settings
    val scheme: TerminalColorScheme = remember(state.settings.terminalColorScheme) {
        state.terminalScheme()
    }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val bellEnabled = settings.bellVibration || settings.hapticFeedback
    var bellCount by remember { mutableIntStateOf(0) }

    val controller = remember(backend) {
        GhosttyTerminalController(
            context = context,
            scrollbackLimit = settings.scrollbackLimit,
            cursorStyle = settings.cursorStyle.toGhosttyCursorStyle(),
            typeface = Typeface.create(settings.terminalFont.familyName, Typeface.NORMAL),
            fontSizeSp = settings.terminalFontSizeSp,
            scheme = scheme,
            onInput = { bytes -> viewModel.sendEngineBytes(bytes) },
            onResize = { cols, rows ->
                viewModel.setAttachGrid(cols, rows)
                viewModel.resizeBackend(cols, rows)
            },
            onBell = { bellCount++ },
        )
    }

    // Attach only replays text (the daemon's prelude carries no DECSET
    // history), so gesture routing cannot wait for the engine to discover the
    // app's modes: it comes from the pane facts the snapshot already reports.
    val routing = remember(
        settings.scrollMode,
        state.pane?.scroll,
        state.pane?.agent,
        state.pane?.hostScrollable,
    ) { scrollRoutingFor(settings.scrollMode, state.pane) }
    LaunchedEffect(controller, routing) {
        controller.view.scrollRouting = routing
        controller.view.allowRawWheel =
            dev.herdr.mobile.core.model.AgentMatrix.useWheelScroll(state.pane?.agent)
    }
    LaunchedEffect(controller) {
        controller.view.onRemoteScroll = { rows -> viewModel.scrollRemote(rows) }
    }

    // Theme, font and cursor follow settings live; each setter is cheap and
    // idempotent, and the engine repaints once per frame.
    LaunchedEffect(controller, scheme) { controller.applyTheme(scheme) }
    LaunchedEffect(controller, settings.terminalFont, settings.terminalFontSizeSp) {
        controller.applyFont(
            Typeface.create(settings.terminalFont.familyName, Typeface.NORMAL),
            settings.terminalFontSizeSp,
        )
    }
    LaunchedEffect(controller, settings.cursorStyle) {
        controller.applyCursorStyle(settings.cursorStyle.toGhosttyCursorStyle())
    }

    // Bytes → engine → frame. The replay buffer exists only for the attach
    // race; once the pump is draining it, release it.
    LaunchedEffect(backend, controller) {
        backend.bytes.collect { data -> controller.feed(data) }
    }
    // The replay cache exists for the attach race; dropping it on the very
    // first frame can discard the rest of a multi-frame prelude.
    LaunchedEffect(backend) {
        delay(2_000)
        runCatching { backend.clearReplayCache() }
    }

    val extraKeys = remember { GhosttyExtraKeysState() }
    LaunchedEffect(controller) {
        controller.view.extraKeysState = extraKeys
        controller.view.listener = object : GhosttyTerminalView.Listener {
            override fun onFontSizeChanged(view: GhosttyTerminalView, fontSizeSp: Float) {
                viewModel.onViewFontSizeChanged(fontSizeSp.toInt())
            }
        }
    }
    val stickyNow = androidx.compose.runtime.rememberUpdatedState(state.stickyModifiers)
    LaunchedEffect(controller) {
        kotlinx.coroutines.coroutineScope {
            var ctrlSeen = false
            launch {
                extraKeys.ctrl.collect { latch ->
                    // Skip the StateFlow's initial value: a fresh controller
                    // starts INACTIVE and must not clear a panel latch that was
                    // armed before the tab switch.
                    if (ctrlSeen && latch == GhosttyExtraKeysState.StickyState.INACTIVE &&
                        TerminalKeyEncoder.Modifier.CTRL in stickyNow.value
                    ) {
                        viewModel.clearStickyModifiers()
                    }
                    ctrlSeen = true
                }
            }
            var altSeen = false
            launch {
                extraKeys.alt.collect { latch ->
                    if (altSeen && latch == GhosttyExtraKeysState.StickyState.INACTIVE &&
                        TerminalKeyEncoder.Modifier.ALT in stickyNow.value
                    ) {
                        viewModel.clearStickyModifiers()
                    }
                    altSeen = true
                }
            }
        }
    }
    LaunchedEffect(controller, state.stickyModifiers) {
        val ctrl = TerminalKeyEncoder.Modifier.CTRL in state.stickyModifiers
        val alt = TerminalKeyEncoder.Modifier.ALT in state.stickyModifiers
        syncSticky(extraKeys.ctrl.value, ctrl) { extraKeys.toggleCtrl() }
        syncSticky(extraKeys.alt.value, alt) { extraKeys.toggleAlt() }
    }
    // Panel page switches retarget a visible IME: the CJK draft field and the
    // terminal surface are distinct focus targets.
    @OptIn(ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(state.inputPage, imeVisible, controller) {
        if (!imeVisible) return@LaunchedEffect
        if (state.inputPage == InputPanelPage.CJK_INPUT) {
            cjkFocus?.requestFocus()
        } else {
            controller.view.requestFocus()
        }
    }

    // One deliberate summon per request (the ledger stops re-summons on
    // recomposition after a pane switch).
    LaunchedEffect(keyboardShowRequest, controller) {
        if (keyboardShowRequest > keyboardShowHandled) {
            onKeyboardShowHandled(keyboardShowRequest)
            controller.showKeyboard()
        }
    }

    // User input snaps a scrolled viewport home. Collected as a flow: a
    // keystroke must not recompose the terminal.
    LaunchedEffect(inputTick, controller) {
        inputTick.collect { tick -> if (tick > 0) controller.scrollToBottom() }
    }

    if (bellEnabled) {
        LaunchedEffect(bellCount) {
            if (bellCount > 0) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    // AndroidView captures `factory` on first composition; without this key a
    // tab/workspace switch (new backend → new controller) kept the OLD view,
    // still bound to the closed session, so the terminal looked dead.
    key(controller) {
        AndroidView(
            factory = { controller.view },
            modifier = modifier.fillMaxSize(),
        )
    }

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    TerminalDiagnostics(
        controller = controller,
        backendState = backendState,
        visible = settings.showScrollDiagnostics,
    )
}

/** Force the engine latch to match the app's sticky state. */
private fun syncSticky(
    current: GhosttyExtraKeysState.StickyState,
    armed: Boolean,
    toggle: () -> Unit,
) {
    var state = current
    var attempts = 0
    while ((state != GhosttyExtraKeysState.StickyState.INACTIVE) != armed && attempts < 3) {
        toggle()
        state = when (state) {
            GhosttyExtraKeysState.StickyState.INACTIVE -> GhosttyExtraKeysState.StickyState.ARMED
            GhosttyExtraKeysState.StickyState.ARMED -> GhosttyExtraKeysState.StickyState.LOCKED
            GhosttyExtraKeysState.StickyState.LOCKED -> GhosttyExtraKeysState.StickyState.INACTIVE
        }
        attempts++
    }
}
