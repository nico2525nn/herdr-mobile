package dev.herdr.mobile.feature.terminal

import android.util.Log
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.core.designsystem.StatusChip
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.terminal.view.ImeTrigger
import dev.herdr.mobile.terminal.view.imeCommand
import dev.herdr.mobile.core.model.InputPanelPage
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

/**
 * Deep-interaction screen: compact workspace/tab rails, the terminal surface, and the
 * two-page bottom input panel. The rails never grow under selection; overflow scrolls
 * horizontally.
 */
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onNavigateHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tabMenu by remember { mutableStateOf<TabMenuTarget?>(null) }
    var renameTarget by remember { mutableStateOf<TabMenuTarget?>(null) }
    var workspaceMenu by remember { mutableStateOf<WorkspaceMenuTarget?>(null) }
    var workspaceRenameTarget by remember { mutableStateOf<WorkspaceMenuTarget?>(null) }
    var workspaceCloseTarget by remember { mutableStateOf<WorkspaceMenuTarget?>(null) }
    // Manual keyboard summon (CJK panel button). Counter, not boolean: two
    // consecutive requests must both fire. Nothing else shows the IME — no
    // tap, no swipe, no focus change (Termux behavior). Page swipes never
    // hide it either: Extra Keys + soft keyboard is the sticky-chord flow
    // (arm CTRL, type C), and auto-hide broke it.
    var keyboardShowRequest by remember { mutableStateOf(0) }
    // Highest request already delivered to the view. The surface subtree is
    // recreated on every pane switch (new backend), so its LaunchedEffect
    // restarts with the old counter value still > 0 — without this ledger a
    // tab switch re-summoned the keyboard forever after the first summon.
    var keyboardShowHandled by remember { mutableStateOf(0) }
    // One requester for the CJK draft field, shared by the terminal surface
    // (page-switch IME retargeting) and the bottom panel (field attachment).
    val cjkFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    // Screen-level IME hygiene: the keyboard must not outlive its pane. Closing
    // the last workspace/tab (backend becomes null) or leaving the screen used
    // to leave it up, floating over Home with no terminal behind it.
    val rootView = androidx.compose.ui.platform.LocalView.current
    fun hideImeNow() {
        val imm = rootView.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        imm.hideSoftInputFromWindow(rootView.windowToken, 0)
    }
    LaunchedEffect(state.backend) {
        if (state.backend == null) hideImeNow()
    }
    DisposableEffect(rootView) {
        onDispose { hideImeNow() }
    }

    // App backgrounded (Home button, task switch) does NOT dispose this screen,
    // so neither DisposableEffect nor onCleared runs. Release the controller or
    // its resize lock leaks until the process dies.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                viewModel.releaseForBackground()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                viewModel.resumeAfterBackground()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // One inset, not two: navigationBarsPadding() + imePadding() stack when
    // the keyboard is up (the IME inset already includes the nav bar), which
    // left a dead band under the floating panel. Their union takes the larger
    // of the two, so the panel edge lands on the gesture area or the keyboard.
    // With the keyboard hidden the raw gesture inset (48dp) leaves a second
    // dead band under a floating card, so 24dp of it is handed back: the card
    // hugs the pill the way the Home bottom bar does.
    val screenDensity = androidx.compose.ui.platform.LocalDensity.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val navInsets = WindowInsets.navigationBars
    val imeInsetPx = WindowInsets.ime.getBottom(screenDensity)
    val navInsetPx = navInsets.getBottom(screenDensity)
    val insetBottom = if (imeInsetPx > 0) {
        imeInsetPx
    } else {
        (navInsetPx - with(screenDensity) { 24.dp.roundToPx() }).coerceAtLeast(0)
    }
    Column(
        modifier = modifier.fillMaxSize()
            .windowInsetsPadding(
                WindowInsets(
                    left = navInsets.getLeft(screenDensity, layoutDirection),
                    top = navInsets.getTop(screenDensity),
                    right = navInsets.getRight(screenDensity, layoutDirection),
                    bottom = insetBottom,
                ),
            ),
    ) {
        WorkspaceRail(
            state = state,
            onNavigateHome = onNavigateHome,
            onSelectWorkspace = { workspaceId ->
                val snapshot = state.snapshot ?: return@WorkspaceRail
                val workspace = snapshot.workspace(workspaceId) ?: return@WorkspaceRail
                val tab = workspace.tabs.firstOrNull { it.id == workspace.activeTabId }
                    ?: workspace.tabs.firstOrNull()
                    ?: return@WorkspaceRail
                val pane = tab.activePane ?: return@WorkspaceRail
                viewModel.openTarget(TerminalTarget(workspaceId, tab.id, pane.id))
            },
            onMoveWorkspace = { workspaceId, to -> viewModel.moveWorkspace(workspaceId, to) },
            onWorkspaceLongPress = { workspaceId, label ->
                workspaceMenu = WorkspaceMenuTarget(workspaceId, label)
            },
            menuTarget = workspaceMenu,
            onDismissMenu = { workspaceMenu = null },
            onRenameMenu = {
                workspaceRenameTarget = it
                workspaceMenu = null
            },
            onCloseMenu = {
                workspaceCloseTarget = it
                workspaceMenu = null
            },
        )
        TabRail(
            state = state,
            onSelectTab = { workspaceId, tabId -> viewModel.openTab(workspaceId, tabId) },
            onSummonKeyboard = { keyboardShowRequest++ },
            onAddTab = {
                val workspaceId = state.target?.workspaceId ?: return@TabRail
                viewModel.createTab(workspaceId, label = null)
            },
            onTabLongPress = { workspaceId, tabId, label ->
                tabMenu = TabMenuTarget(workspaceId, tabId, label)
            },
            menuTarget = tabMenu,
            onDismissMenu = { tabMenu = null },
            onRenameMenu = {
                renameTarget = it
                tabMenu = null
            },
            onCloseMenu = {
                viewModel.closeTab(it.workspaceId, it.tabId)
                tabMenu = null
            },
            onMoveTab = { tabId, to ->
                val workspaceId = state.target?.workspaceId ?: return@TabRail
                viewModel.moveTab(workspaceId, tabId, to)
            },
        )
        state.statusMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val backend = state.backend
            if (backend == null) {
                TerminalPlaceholder(
                    connection = state.connection,
                    hasTarget = state.target != null,
                )
            } else {
                TerminalSurface(
                    state = state,
                    onSendBytes = { viewModel.sendBytes(it) },
                    onSendText = { viewModel.sendText(it) },
                    onSendPaste = { viewModel.sendPaste(it) },
                    onRetry = { viewModel.retryAttach() },
                    keyboardShowRequest = keyboardShowRequest,
                    keyboardShowHandled = keyboardShowHandled,
                    onKeyboardShowHandled = { keyboardShowHandled = it },
                    inputTick = viewModel.inputTick.collectAsStateWithLifecycle().value,
                    onGridChanged = { cols, rows -> viewModel.setAttachGrid(cols, rows) },
                    cjkFocus = cjkFocus,
                )
            }
            // Floats over the terminal's top rows; sub-second drops never show.
            ConnectionBadge(
                connection = state.connection,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            )
        }
        BottomInputPanel(
            viewModel = viewModel,
            state = state,
            onSendBytes = { viewModel.sendBytes(it) },
            onSendText = { viewModel.sendText(it) },
            onExtraKey = { viewModel.sendExtraKey(it) },
            onPageChange = { viewModel.setInputPage(it) },
            cjkFocus = cjkFocus,
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            title = "Rename tab",
            initial = target.label,
            onDismiss = { renameTarget = null },
            onConfirm = { label ->
                viewModel.renameTab(target.tabId, label)
                renameTarget = null
            },
        )
    }
    workspaceRenameTarget?.let { target ->
        RenameDialog(
            title = "Rename workspace",
            initial = target.label,
            onDismiss = { workspaceRenameTarget = null },
            onConfirm = { label ->
                viewModel.renameWorkspace(target.workspaceId, label)
                workspaceRenameTarget = null
            },
        )
    }
    workspaceCloseTarget?.let { target ->
        WorkspaceCloseDialog(
            label = target.label,
            onDismiss = { workspaceCloseTarget = null },
            onConfirm = {
                viewModel.closeWorkspace(target.workspaceId)
                workspaceCloseTarget = null
            },
        )
    }
}

@Composable
private fun TerminalSurface(
    state: TerminalUiState,
    onSendBytes: (ByteArray) -> Unit,
    onSendText: (String) -> Unit,
    onSendPaste: (String) -> Unit,
    onRetry: () -> Unit,
    keyboardShowRequest: Int,
    keyboardShowHandled: Int,
    onKeyboardShowHandled: (Int) -> Unit,
    inputTick: Int,
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
    // DisposableEffect(backend) re-runs on backend change — but by onDispose
    // time, remember(backend) has ALREADY reset `bridge` to null, so reading
    // it there releases nothing (leak) or the wrong bridge. Capture the live
    // bridge in a ref that survives the reset.
    val bridgeRef = remember { mutableStateOf<RemoteTermuxSession?>(null) }
    var viewRef by remember { mutableStateOf<TerminalView?>(null) }
    var hostRef by remember { mutableStateOf<TermuxTerminalHost?>(null) }
    var viewClientRef by remember { mutableStateOf<TermuxViewClient?>(null) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // ---- Soft keyboard ---------------------------------------------------
    // All policy is in terminal-view's ImePolicy (pure, unit-tested). These
    // effects only turn an event into a command and hand it to the one
    // dispatcher below; no other code touches focus or the IME.
    @OptIn(ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible
    fun applyIme(cmd: dev.herdr.mobile.terminal.view.ImeCommand) {
        if (cmd.hide) hostRef?.dismissKeyboard()
        if (cmd.hideUnrequested) hostRef?.hideKeyboardIfUnrequested()
        when (cmd.focus) {
            dev.herdr.mobile.terminal.view.ImeTarget.CJK_FIELD -> cjkFocus?.requestFocus()
            dev.herdr.mobile.terminal.view.ImeTarget.TERMINAL -> {
                hostRef?.keyboard?.focusTerminal() ?: viewRef?.requestFocus()
            }
            dev.herdr.mobile.terminal.view.ImeTarget.NONE -> Unit
        }
        if (cmd.show) hostRef?.showKeyboard()
    }
    var lastInputPage by remember { mutableStateOf(state.inputPage) }
    LaunchedEffect(state.inputPage) {
        if (lastInputPage == state.inputPage) return@LaunchedEffect
        lastInputPage = state.inputPage
        applyIme(
            imeCommand(ImeTrigger.PAGE_SWITCH, state.inputPage, imeVisible),
        )
    }
    LaunchedEffect(state.pane?.id) {
        applyIme(imeCommand(ImeTrigger.PANE_SWITCH, state.inputPage, imeVisible))
    }
    // The IME going away on its own re-arms the summon latch.
    LaunchedEffect(imeVisible) {
        if (!imeVisible) hostRef?.keyboard?.onImeHidden()
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val pageState = rememberUpdatedState(state.inputPage)
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                applyIme(imeCommand(ImeTrigger.FOREGROUND, pageState.value, imeVisible))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Keyboard button: the one deliberate summon. Guard 0 so composition does
    // not steal focus on first mount.
    LaunchedEffect(keyboardShowRequest) {
        if (keyboardShowRequest > keyboardShowHandled) {
            onKeyboardShowHandled(keyboardShowRequest)
            applyIme(imeCommand(ImeTrigger.KEYBOARD_BUTTON, state.inputPage, imeVisible))
        }
    }
    // User input snaps a scrolled viewport home (back at the prompt). Guard 0:
    // the initial value must not yank on mount.
    LaunchedEffect(inputTick) {
        if (inputTick > 0) {
            hostRef?.snapToBottom()
        }
    }

    val scheme = remember(state.settings.terminalColorScheme) { state.terminalScheme() }

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
                        val emu = bridgeRef.value?.emulator
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
            bridgeRef.value = created
            created.start(cols, rows)
        }
    }

    // Rebind every composition in update(): the factory closure runs once and
    // captured `bridge` State goes stale after a tab switch (remember yields a
    // NEW State object). Stale callbacks tap/scroll the released backend,
    // silently dropped by runCatching.
    fun bindTermuxCallbacks(viewClient: TermuxViewClient, host: TermuxTerminalHost) {
        viewClient.onDirectInput = { text -> onSendText(text) }
        viewClient.onDirectBytes = { bytes -> onSendBytes(bytes) }
        viewClient.onDirectDelete = { onSendBytes(byteArrayOf(0x7F)) }
        viewClient.onTapCell = { col, row ->
            // Mouse-mode apps (vim, less, tmux): forward the tap as a
            // left-button press. Non-mouse apps ignore it server-side.
            val b = bridgeRef.value
            if (b != null) {
                scope.launch { runCatching { b.mouse("down", "left", col, row) } }
            }
        }
        viewClient.onZoomFont = { _ ->
            // Font zoom is applied through settings; the host clamps it.
        }
        viewClient.onPageScroll = { up -> host.pageRemote(up) }
        host.onRemoteScroll = { lines, unit ->
            bridgeRef.value?.scrollRemote(
                lines,
                allowPageKey = dev.herdr.mobile.core.model.AgentMatrix.allowPageKey(
                    appIdentity = appIdentity,
                    altActive = host.altActive,
                ),
                unit = unit,
            )
        }
        host.onRemoteWheel = { notches ->
            bridgeRef.value?.scrollWheel(notches)
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
            // Scheme/font/cursor follow settings live (Termux has no line-height
            // multiplier: terminalLineHeight is accepted as unsupported).
            TermuxSchemes.apply(scheme)
            bridge?.emulator?.mColors?.reset()
            view.setBackgroundColor(TermuxSchemes.backgroundAndroid(scheme))
            view.invalidate()
            val px = (settings.terminalFontSizeSp * density.density).toInt()
            view.setTextSize(px)
            view.setTypeface(android.graphics.Typeface.create(settings.terminalFont.familyName, android.graphics.Typeface.NORMAL))
            hostRef?.textSizePx = px
            bridge?.sessionClient?.cursorStyle = settings.cursorStyle
            // Sticky CTRL/ALT feed the view client (Termux applies them to the
            // next hardware key when set).
            viewClientRef?.ctrlDown = dev.herdr.mobile.terminal.view.TerminalKeyEncoder.Modifier.CTRL in state.stickyModifiers
            viewClientRef?.altDown = dev.herdr.mobile.terminal.view.TerminalKeyEncoder.Modifier.ALT in state.stickyModifiers
            // The host decides scroll ownership itself (ScrollRoute.scrollOwner):
            // it only needs the pane identity and the app's wheel stride.
            hostRef?.remoteScrollHost = appIdentity != null
            hostRef?.diagnosticsAgent = appIdentity
            // Line-exact host scrolling when the daemon reports scrollback.
            hostRef?.hostScrollable = state.pane?.hostScrollable == true
            hostRef?.hostViewportRows = state.pane?.scroll?.viewportRows?.toInt() ?: 0
            hostRef?.wheelRowsPerEvent =
                dev.herdr.mobile.core.model.AgentMatrix.wheelRowsPerEvent(appIdentity)
            val vc = viewClientRef
            val h = hostRef
            if (vc != null && h != null) bindTermuxCallbacks(vc, h)
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
                viewRef?.post { bridgeRef.value?.syncBackendSize() }
            },
    )

    // Scroll diagnostics overlay (debug toggle; state only, never content).
    // Ticks on every frame + scroll so the numbers stay live while dragging.
    if (state.settings.showScrollDiagnostics) {
        var diagTick by remember { mutableStateOf(0) }
        LaunchedEffect(bridge, backendState) {
            // Recompose the overlay whenever frames flow: poll the view at
            // 4Hz while visible (cheap getters, no allocation).
            while (true) {
                kotlinx.coroutines.delay(250)
                diagTick++
            }
        }
        @Suppress("UNUSED_EXPRESSION")
        diagTick
        val attached = backendState as? BackendState.Attached
        val diagText = remember(diagTick, backendState, hostRef) {
            val v = hostRef
            buildString {
                append("hist=")
                append(v?.snapshotHistorySize ?: -1)
                append(" alt=")
                append(v?.snapshotUsingAlt ?: false)
                append(" mse=")
                append(if (v?.mouseTracking == true) 1 else 0)
                append(" top=")
                append(v?.currentTopRow ?: 0)
                append(" ev=")
                append(v?.scrollEvents ?: 0)
                append(" rows=")
                append(v?.scrolledRows ?: 0)
                append(" rem=")
                append(v?.remoteScrolls ?: 0)
                if (v?.remoteScrollHost == true) append("(R)")
                if (attached != null) {
                    append(" pre=")
                    append(attached.historyRows)
                    if (attached.historyTruncated) append("+")
                    attached.historyError?.let { append(" ERR:").append(it.take(24)) }
                } else {
                    append(" pre=?")
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            ScrollDiagnosticsOverlay(text = diagText, visible = true)
        }
    }

    DisposableEffect(backend) {
        onDispose {
            // The bridge release suspends until the release frame reaches the
            // backend; run it on a fresh scope with a timeout, never on a scope
            // that is being torn down with us. Read from bridgeRef, NOT bridge:
            // remember(backend) has already reset by onDispose time.
            val toRelease = bridgeRef.value
            bridgeRef.value = null
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
