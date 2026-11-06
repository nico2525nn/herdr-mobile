package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.core.designsystem.LocalUiCopy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
        state.statusMessage?.let { message ->
            Text(
                text = message.let { LocalUiCopy.current.render(it) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
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
                    inputTick = viewModel.inputTick,
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
