package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.background

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.core.designsystem.CandleGlow
import dev.herdr.mobile.core.designsystem.LocalUiCopy
import dev.herdr.mobile.core.designsystem.rememberElapsedSeconds
import dev.herdr.mobile.core.designsystem.UiMessage
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.UiFocusState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Deep-interaction screen: compact workspace/tab rails, the terminal surface, and the
 * two-page bottom input panel. The rails never grow under selection; overflow scrolls
 * horizontally.
 */
private val TabMenuTargetSaver: Saver<TabMenuTarget?, Any> = Saver(
    save = { target ->
        target?.let { listOf(it.workspaceId, it.tabId, it.label, it.targetGeneration) }
    },
    restore = { raw ->
        val parts = raw as? List<*> ?: return@Saver null
        if (parts.size != 4) null
        else TabMenuTarget(
            workspaceId = parts[0] as? String ?: return@Saver null,
            tabId = parts[1] as? String ?: return@Saver null,
            label = parts[2] as? String ?: return@Saver null,
            targetGeneration = parts[3] as? Long ?: return@Saver null,
        )
    },
)

private val WorkspaceMenuTargetSaver: Saver<WorkspaceMenuTarget?, Any> = Saver(
    save = { target ->
        target?.let { listOf(it.workspaceId, it.label, it.targetGeneration) }
    },
    restore = { raw ->
        val parts = raw as? List<*> ?: return@Saver null
        if (parts.size != 3) null
        else WorkspaceMenuTarget(
            workspaceId = parts[0] as? String ?: return@Saver null,
            label = parts[1] as? String ?: return@Saver null,
            targetGeneration = parts[2] as? Long ?: return@Saver null,
        )
    },
)

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onNavigateHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tabMenu by rememberSaveable(stateSaver = TabMenuTargetSaver) {
        mutableStateOf<TabMenuTarget?>(null)
    }
    var renameTarget by rememberSaveable(stateSaver = TabMenuTargetSaver) {
        mutableStateOf<TabMenuTarget?>(null)
    }
    var workspaceMenu by rememberSaveable(stateSaver = WorkspaceMenuTargetSaver) {
        mutableStateOf<WorkspaceMenuTarget?>(null)
    }
    var workspaceRenameTarget by rememberSaveable(stateSaver = WorkspaceMenuTargetSaver) {
        mutableStateOf<WorkspaceMenuTarget?>(null)
    }
    var workspaceCloseTarget by rememberSaveable(stateSaver = WorkspaceMenuTargetSaver) {
        mutableStateOf<WorkspaceMenuTarget?>(null)
    }
    var backendState by remember { mutableStateOf<BackendState?>(null) }
    var frozenFrame by remember { mutableStateOf<FrozenTerminalFrame?>(null) }
    // A frame belongs to exactly one target and backend. A tab/pane switch or
    // a backend replacement must never show the previous pane's screen.
    LaunchedEffect(state.target, state.backend) { frozenFrame = null }
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
    var cjkFieldFocused by remember { mutableStateOf(false) }
    // Do not mark a summon handled while there is no backend: the request
    // should fire when the surface reattaches, not disappear.
    // Screen-level IME hygiene: the keyboard must not outlive its pane. Closing
    // the last workspace/tab (backend becomes null) or leaving the screen used
    // to leave it up, floating over Home with no terminal behind it.
    val rootView = androidx.compose.ui.platform.LocalView.current
    fun hideImeNow() {
        val imm = rootView.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        imm.hideSoftInputFromWindow(rootView.windowToken, 0)
    }
    // A tab/pane switch momentarily has no backend; the keyboard belongs to
    // the screen, not the old pane, so only a missing target closes it.
    LaunchedEffect(state.target) {
        if (state.target == null) hideImeNow()
    }
    DisposableEffect(rootView) {
        onDispose { hideImeNow() }
    }

    // Suppress alerts only when the pane is actually attached. A failed attach
    // must not hide its DONE notification merely because the route was chosen.
    var backendAttached by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    LaunchedEffect(state.backend) {
        val backend = state.backend
        if (backend == null) {
            backendAttached = false
            return@LaunchedEffect
        }
        backend.state.collect { backendAttached = it is BackendState.Attached }
    }
    DisposableEffect(state.target, backendAttached) {
        UiFocusState.paneId = state.target?.paneId?.takeIf { backendAttached }
        onDispose {
            if (UiFocusState.paneId == state.target?.paneId) UiFocusState.paneId = null
        }
    }

    // App backgrounded (Home button, task switch) does NOT dispose this screen,
    // so neither DisposableEffect nor onCleared runs. Release the controller or
    // its resize lock leaks until the process dies.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    DisposableEffect(lifecycle, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                UiFocusState.terminalVisible = false
                // A focused CJK field makes the IME come back on resume, even
                // though the user dismissed it before leaving. Focus dies with
                // the backgrounded screen; the terminal keeps its own view
                // focus and no IME request is replayed.
                focusManager.clearFocus(force = true)
                viewModel.releaseForBackground()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                UiFocusState.terminalVisible = true
                viewModel.resumeAfterBackground()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // One inset, not two: navigationBarsPadding() + imePadding() stack when
    // the keyboard is up (the IME inset already includes the nav bar), which
    // left a dead band under the floating panel. Use the larger of the IME and
    // the full safe-drawing bottom: subtracting a fixed 24dp from the
    // navigation inset put the controls under three-button navigation and
    // bottom cutouts.
    val screenDensity = androidx.compose.ui.platform.LocalDensity.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    // The terminal owns the full safe-drawing insets. Passing the app Scaffold
    // padding as well double-counted the bottom/IME; passing only
    // navigationBars left the status bar/cutout out.
    val safeInsets = WindowInsets.safeDrawing
    val imeInsetPx = WindowInsets.ime.getBottom(screenDensity)
    val insetBottom = maxOf(imeInsetPx, safeInsets.getBottom(screenDensity))
    Column(
        modifier = modifier.fillMaxSize()
            .windowInsetsPadding(
                WindowInsets(
                    left = safeInsets.getLeft(screenDensity, layoutDirection),
                    top = safeInsets.getTop(screenDensity),
                    right = safeInsets.getRight(screenDensity, layoutDirection),
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
            onMoveWorkspace = { workspaceId, to ->
                viewModel.moveWorkspace(workspaceId, to, state.targetGeneration)
            },
            onWorkspaceLongPress = { workspaceId, label ->
                workspaceMenu = WorkspaceMenuTarget(workspaceId, label, state.targetGeneration)
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
            resetKey = state.reorderReset,
        )
        TabRail(
            state = state,
            onSelectTab = { workspaceId, tabId -> viewModel.openTab(workspaceId, tabId) },
            onSummonKeyboard = { keyboardShowRequest++ },
            onAddTab = {
                val workspaceId = state.target?.workspaceId ?: return@TabRail
                viewModel.createTab(workspaceId, label = null, state.targetGeneration)
            },
            onTabLongPress = { workspaceId, tabId, label ->
                tabMenu = TabMenuTarget(workspaceId, tabId, label, state.targetGeneration)
            },
            menuTarget = tabMenu,
            onDismissMenu = { tabMenu = null },
            onRenameMenu = {
                renameTarget = it
                tabMenu = null
            },
            onCloseMenu = {
                viewModel.closeTab(it.workspaceId, it.tabId, it.targetGeneration)
                tabMenu = null
            },
            onMoveTab = { tabId, to ->
                val workspaceId = state.target?.workspaceId ?: return@TabRail
                viewModel.moveTab(workspaceId, tabId, to, state.targetGeneration)
            },
            resetKey = state.reorderReset,
        )
        // A tab can hold several Herdr panes; the tab rail always opens the
        // active one, so this is the only way to reach the others.
        state.tab?.takeIf { it.panes.size > 1 }?.let { tab ->
            val workspaceId = state.target?.workspaceId
            if (workspaceId != null) {
                PaneRail(
                    panes = tab.panes,
                    activePaneId = state.target?.paneId,
                    onSelectPane = { paneId ->
                        viewModel.openTarget(
                            TerminalTarget(workspaceId, tab.id, paneId),
                        )
                    },
                )
            }
        }
        val terminalFailure = state.statusMessage is UiMessage.AttachFailed ||
            state.statusMessage is UiMessage.ReconnectTimedOut ||
            state.statusMessage is UiMessage.StreamChanged
        val detached = backendState as? BackendState.Detached
        val busyLink = state.connection == ConnectionState.CONNECTING ||
            state.connection == ConnectionState.RECONNECTING
        val elapsed = rememberElapsedSeconds(busyLink && state.backend != null)
        val linkStatus = terminalLinkStatus(
            connection = state.connection,
            stale = state.stale,
            failure = state.statusMessage,
            detachedReason = detached?.reason,
            elapsedSeconds = elapsed,
            hasBackend = state.backend != null,
        )
        // The row overlays the terminal and the terminal view is shifted down
        // by the same 32dp with a graphics translation. No layout height and
        // no PTY resize change when the row appears or disappears.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds(),
        ) {
            val backend = state.backend
            if (backend == null) {
                val permanent = (state.statusMessage as? UiMessage.AttachFailed)
                    ?.detail
                    ?.contains("not found", ignoreCase = true) == true
                TerminalPlaceholder(
                    connection = state.connection,
                    hasTarget = state.target != null,
                    label = state.tab?.displayLabel,
                    error = state.error,
                    failure = state.statusMessage,
                    onRetry = if (permanent) null else ({ viewModel.retryAttach() }),
                    onBack = onNavigateHome,
                )
            } else {
                TerminalSurface(
                    state = state,
                    viewModel = viewModel,
                    onRetry = { viewModel.retryAttach() },
                    onBack = onNavigateHome,
                    onBackendState = { backendState = it },
                    linkRowVisible = linkStatus != null,
                    onFrameCaptured = { bitmap ->
                        val target = state.target
                        val backend = state.backend
                        frozenFrame = if (bitmap != null && target != null && backend != null) {
                            FrozenTerminalFrame(backend, target, bitmap)
                        } else {
                            null
                        }
                    },
                    keyboardShowRequest = keyboardShowRequest,
                    keyboardShowHandled = keyboardShowHandled,
                    onKeyboardShowHandled = { keyboardShowHandled = it },
                    inputTick = viewModel.inputTick,
                    cjkFocus = cjkFocus,
                    cjkFieldFocused = cjkFieldFocused,
                )
            }
            // The frozen full frame is fitted into the area below the 32dp
            // status strip. Both the top and the bottom row stay visible; the
            // live view and its PTY are never resized.
            val visibleFrame = frozenFrame?.takeIf {
                linkStatus != null && it.backend === state.backend && it.target == state.target
            }
            visibleFrame?.let { frozen ->
                Image(
                    bitmap = frozen.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = TerminalLinkRowHeight)
                        .align(Alignment.TopStart),
                )
            }
            if (state.backend != null && linkStatus != null) {
                CandleGlow(
                    tone = linkStatus.tone,
                    intensity = 0.5f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .align(Alignment.TopCenter),
                )
            }
            if (linkStatus != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TerminalLinkRowHeight)
                        .background(Color.Black)
                        .align(Alignment.TopCenter),
                ) {
                    CandleGlow(
                        tone = linkStatus.tone,
                        intensity = 0.7f,
                        modifier = Modifier.matchParentSize(),
                    )
                    TerminalLinkRow(
                        status = linkStatus,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
            }
            val transient = state.statusMessage
            if (transient != null && !terminalFailure) {
                androidx.compose.material3.Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = transient.let { LocalUiCopy.current.render(it) },
                            modifier = Modifier.weight(1f),
                        )
                        if (transient is UiMessage.TargetChanged) {
                            TextButton(onClick = onNavigateHome) {
                                Text("Choose")
                            }
                        }
                    }
                }
            }
        }
        BottomInputPanel(
            viewModel = viewModel,
            state = state,
            onSendBytes = { viewModel.sendBytes(it) },
            onSendText = { viewModel.sendText(it) },
            onExtraKey = { viewModel.sendExtraKey(it) },
            onPageChange = { viewModel.setInputPage(it) },
            cjkFocus = cjkFocus,
            onCjkFocusChanged = { cjkFieldFocused = it },
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            title = "Rename tab",
            initial = target.label,
            onDismiss = { renameTarget = null },
            onConfirm = { label ->
                viewModel.renameTab(target.tabId, label, target.targetGeneration)
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
                viewModel.renameWorkspace(target.workspaceId, label, target.targetGeneration)
                workspaceRenameTarget = null
            },
        )
    }
    workspaceCloseTarget?.let { target ->
        WorkspaceCloseDialog(
            label = target.label,
            onDismiss = { workspaceCloseTarget = null },
            onConfirm = {
                viewModel.closeWorkspace(target.workspaceId, target.targetGeneration)
                workspaceCloseTarget = null
            },
        )
    }
}

private data class FrozenTerminalFrame(
    val backend: ConnectionTerminalBackend,
    val target: TerminalTarget,
    val bitmap: android.graphics.Bitmap,
)
