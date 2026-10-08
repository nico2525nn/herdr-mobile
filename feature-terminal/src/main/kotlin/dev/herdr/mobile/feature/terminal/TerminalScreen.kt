package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.core.designsystem.StatusChip
import dev.herdr.mobile.core.designsystem.StatusDot
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.InputPanelPage
import dev.herdr.mobile.terminal.view.HerdrTerminalView
import dev.herdr.mobile.terminal.view.BackendState
import dev.herdr.mobile.terminal.view.TerminalBridge
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
    // Incremented when the input panel wants the terminal surface to take IME
    // focus back (leaving CJK page). Counter, not boolean: two consecutive
    // requests must both fire even with no value change in between.
    var terminalFocusRequest by remember { mutableStateOf(0) }

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

    // navigationBarsPadding keeps the panel above the gesture bar when the
    // keyboard is hidden; imePadding lifts it above the keyboard when shown.
    // The terminal Box above shrinks accordingly, so its bottom edge always
    // sits exactly on top of the panel — never behind the keyboard.
    Column(modifier = modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
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
        )
        TabRail(
            state = state,
            onSelectTab = { workspaceId, tabId -> viewModel.openTab(workspaceId, tabId) },
            onAddTab = {
                val workspaceId = state.target?.workspaceId ?: return@TabRail
                viewModel.createTab(workspaceId, label = null)
            },
            onTabLongPress = { workspaceId, tabId, label ->
                tabMenu = TabMenuTarget(workspaceId, tabId, label)
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
                    onRetry = { viewModel.retryAttach() },
                    terminalFocusRequest = terminalFocusRequest,
                )
            }
        }
        BottomInputPanel(
            state = state,
            onSendBytes = { viewModel.sendBytes(it) },
            onSendText = { viewModel.sendText(it) },
            onExtraKey = { viewModel.sendExtraKey(it) },
            onPageChange = { viewModel.setInputPage(it) },
            onReturnFocusToTerminal = { terminalFocusRequest++ },
        )
    }

    tabMenu?.let { target ->
        TabActionsMenu(
            target = target,
            onDismiss = { tabMenu = null },
            onRename = {
                renameTarget = target
                tabMenu = null
            },
            onClose = {
                viewModel.closeTab(target.workspaceId, target.tabId)
                tabMenu = null
            },
        )
    }
    renameTarget?.let { target ->
        TabRenameDialog(
            target = target,
            onDismiss = { renameTarget = null },
            onConfirm = { label ->
                viewModel.renameTab(target.tabId, label)
                renameTarget = null
            },
        )
    }
}

/** Which tab a long-press menu refers to. */
private data class TabMenuTarget(
    val workspaceId: String,
    val tabId: String,
    val label: String,
)

@Composable
private fun TabActionsMenu(
    target: TabMenuTarget,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Rename \"${target.label}\"") },
            onClick = onRename,
        )
        DropdownMenuItem(
            text = { Text("Close \"${target.label}\"") },
            onClick = onClose,
        )
    }
}

@Composable
private fun TabRenameDialog(
    target: TabMenuTarget,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var label by remember(target.tabId) { mutableStateOf(target.label) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename tab") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Tab name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label) },
                enabled = label.trim().isNotEmpty(),
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun WorkspaceRail(
    state: TerminalUiState,
    onNavigateHome: () -> Unit,
    onSelectWorkspace: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onNavigateHome, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Home, contentDescription = "Back to Home")
        }
        snapshot.workspaces.forEach { workspace ->
            StatusChip(
                label = workspace.label,
                status = workspace.status,
                selected = workspace.id == state.target?.workspaceId,
                onClick = { onSelectWorkspace(workspace.id) },
            )
        }
    }
}

@Composable
private fun TabRail(
    state: TerminalUiState,
    onSelectTab: (workspaceId: String, tabId: String) -> Unit,
    onAddTab: () -> Unit,
    onTabLongPress: (workspaceId: String, tabId: String, label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = state.workspace ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        workspace.tabs.forEach { tab ->
            val harness = tab.harnessNames.joinToString(",").takeIf { it.isNotEmpty() }
            StatusChip(
                label = if (harness != null) "${tab.displayLabel} · $harness" else tab.displayLabel,
                status = tab.status,
                selected = tab.id == state.target?.tabId,
                onClick = { onSelectTab(workspace.id, tab.id) },
                onLongClick = { onTabLongPress(workspace.id, tab.id, tab.displayLabel) },
            )
        }
        IconButton(onClick = onAddTab, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "New tab in ${workspace.label}")
        }
    }
}

@Composable
private fun TerminalPlaceholder(connection: ConnectionState, hasTarget: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = when {
                !hasTarget && connection == ConnectionState.CONNECTED -> "No pane to attach"
                connection == ConnectionState.CONNECTED -> "Attaching…"
                connection == ConnectionState.FAILED -> "Connection failed"
                else -> "Connecting…"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TerminalErrorBody(message: String, onRetry: (() -> Unit)? = null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(20.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun TerminalSurface(
    state: TerminalUiState,
    onSendBytes: (ByteArray) -> Unit,
    onSendText: (String) -> Unit,
    onRetry: () -> Unit,
    terminalFocusRequest: Int,
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
        else -> {}
    }
    val settings = state.settings
    var bridge by remember(backend) { mutableStateOf<TerminalBridge?>(null) }
    // DisposableEffect(backend) re-runs on backend change — but by onDispose
    // time, remember(backend) has ALREADY reset `bridge` to null, so reading
    // it there releases nothing (leak) or the wrong bridge. Capture the live
    // bridge in a ref that survives the reset.
    val bridgeRef = remember { mutableStateOf<TerminalBridge?>(null) }
    var viewRef by remember { mutableStateOf<HerdrTerminalView?>(null) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Panel asked for IME focus back (left CJK page): take it, keyboard and all.
    // Guard 0 so composition doesn't steal focus on first mount.
    LaunchedEffect(terminalFocusRequest) {
        if (terminalFocusRequest > 0) {
            viewRef?.requestFocus()
            viewRef?.showKeyboard()
        }
    }

    val scheme = remember(state.settings.terminalColorScheme) { state.terminalScheme() }

    // View size → grid size → backend resize, debounced by the bridge/daemon coalescing.
    var viewSizePx by remember { mutableStateOf(0 to 0) }

    // The bridge belongs to the backend, not to the view size: create it
    // eagerly (with the last known size, or a sane default) so a tab switch
    // with an identical layout — where onSizeChanged never fires — still
    // attaches. Size updates only resize the existing bridge.
    LaunchedEffect(backend) {
        if (bridge == null) {
            val (w, h) = viewSizePx
            val view = viewRef
            val (cols, rows) = view?.gridFor(w, h) ?: (80 to 24)
            val created = TerminalBridge(
                backend = backend,
                colorScheme = scheme,
                boldIsBright = settings.boldIsBright,
                scrollbackLimit = settings.scrollbackLimit,
            )
            bridge = created
            bridgeRef.value = created
            created.start(cols, rows)
            // Backend-scoped, not composition-scoped: when the backend
            // changes, DisposableEffect releases the old bridge AND this
            // collector dies with it. A composition-scoped launch would
            // leak one collector per pane switch, each able to render
            // stale snapshots over the new bridge's output.
            launch {
                try {
                    created.frame.collect { snapshot -> viewRef?.render(snapshot) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // onRelease cancels this collector: must propagate, or the
                    // cancel never completes and the old collector keeps rendering
                    // stale snapshots over the new bridge's output.
                    throw e
                } catch (_: Exception) {
                }
            }.also { collector ->
                // Tie the collector to the bridge lifetime explicitly.
                created.onRelease = { collector.cancel() }
            }
            launch { runCatching { backend.resize(cols, rows) } }
        }
    }

    AndroidView(
        factory = { context ->
            HerdrTerminalView(context).also { view ->
                viewRef = view
                view.setColorScheme(scheme)
                view.setTerminalFont(
                    settings.terminalFont.familyName,
                    settings.terminalFontSizeSp.toFloat(),
                    settings.terminalLineHeight,
                )
                view.setCursorStyle(settings.cursorStyle)
                view.onDirectInput = { text -> onSendText(text) }
                view.onDirectDelete = { onSendBytes(byteArrayOf(0x7F)) }
                view.onTapCell = { col, row ->
                    // Mouse-mode apps (vim, less, tmux): forward the tap as a
                    // left-button press. Non-mouse apps ignore it server-side.
                    val b = bridge
                    if (b != null) {
                        scope.launch { runCatching { b.mouse("down", "left", col, row) } }
                    }
                }
                view.onSelection = { text ->
                    // Copy to clipboard; the user pastes via the CJK panel.
                    if (text.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(text))
                    }
                }
                view.onScrollLines = { lines ->
                    bridge?.scrollBy(lines)
                    if (lines != 0) {
                        // Keep the remote viewport in sync when the user scrolls locally.
                    }
                }
                view.onZoomFont = { delta ->
                    // Font zoom is applied through settings; the host clamps it.
                }
            }
        },
        update = { view ->
            view.setColorScheme(scheme)
            view.setTerminalFont(
                settings.terminalFont.familyName,
                settings.terminalFontSizeSp.toFloat(),
                settings.terminalLineHeight,
            )
            view.setCursorStyle(settings.cursorStyle)
            // Rebind every composition: the factory closure runs once and its
            // captured `bridge` State goes stale after a tab switch (remember
            // yields a NEW State object). Stale callbacks tap/scroll the
            // released backend, silently dropped by runCatching.
            view.onDirectInput = { text -> onSendText(text) }
            view.onDirectDelete = { onSendBytes(byteArrayOf(0x7F)) }
            view.onTapCell = { col, row ->
                val b = bridge
                if (b != null) {
                    scope.launch { runCatching { b.mouse("down", "left", col, row) } }
                }
            }
            view.onSelection = { text ->
                if (text.isNotEmpty()) {
                    clipboard.setText(AnnotatedString(text))
                }
            }
            view.onScrollLines = { lines -> bridge?.scrollBy(lines) }
            view.onZoomFont = { _ -> }
        },
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                viewSizePx = size.width to size.height
                val view = viewRef ?: return@onSizeChanged
                val (cols, rows) = view.gridFor(size.width, size.height)
                bridge?.resize(cols, rows)
            },
    )

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

@Composable
private fun BottomInputPanel(
    state: TerminalUiState,
    onSendBytes: (ByteArray) -> Unit,
    onSendText: (String) -> Unit,
    onExtraKey: (dev.herdr.mobile.terminal.view.TerminalKeyEncoder.Key) -> Unit,
    onPageChange: (InputPanelPage) -> Unit,
    onReturnFocusToTerminal: () -> Unit,
) {
    val settings = state.settings
    val pages = buildList {
        if (settings.extraKeysEnabled) add(InputPanelPage.EXTRA_KEYS)
        if (settings.cjkInputEnabled) add(InputPanelPage.CJK_INPUT)
    }
    if (pages.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        return
    }
    val pagerState = rememberPagerState(
        initialPage = pages.indexOf(state.inputPage).coerceAtLeast(0),
        pageCount = { pages.size },
    )
    // When a panel is disabled in Settings, pages shrinks: clamp the pager or
    // pages[currentPage] throws IndexOutOfBounds on a stale page 1.
    LaunchedEffect(pages.size) {
        if (pagerState.currentPage >= pages.size) {
            pagerState.scrollToPage(0)
        }
    }
    LaunchedEffect(pagerState.currentPage, pages.size) {
        pages.getOrNull(pagerState.currentPage)?.let { onPageChange(it) }
    }
    // Leaving the CJK page returns input focus to the terminal surface: the CJK
    // field held the IME target, and without this the keyboard stays bound to a
    // hidden field — native Enter/typing goes nowhere until the user re-taps.
    var previousPage by remember { mutableStateOf(pagerState.currentPage) }
    LaunchedEffect(pagerState.currentPage) {
        val prev = pages.getOrNull(previousPage)
        val cur = pages.getOrNull(pagerState.currentPage)
        previousPage = pagerState.currentPage
        if (prev == InputPanelPage.CJK_INPUT && cur == InputPanelPage.EXTRA_KEYS) {
            onReturnFocusToTerminal()
        }
    }
    Surface(
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (pages.size > 1) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { index ->
                    when (pages[index]) {
                        InputPanelPage.EXTRA_KEYS -> ExtraKeysPanel(
                            onKey = onExtraKey,
                            armed = state.stickyModifiers,
                            haptic = settings.hapticFeedback,
                        )

                        InputPanelPage.CJK_INPUT -> CjkInputPanel(onSend = onSendText)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pages.forEachIndexed { index, _ ->
                        StatusDot(
                            status = state.tab?.status
                                ?: state.workspace?.status
                                ?: dev.herdr.mobile.core.model.AgentStatus.UNKNOWN,
                            diameter = if (index == pagerState.currentPage) 7.dp else 5.dp,
                            describe = false,
                        )
                        Spacer(Modifier.size(4.dp))
                    }
                }
            } else {
                when (pages.first()) {
                    InputPanelPage.EXTRA_KEYS -> ExtraKeysPanel(
                        onKey = onExtraKey,
                        armed = state.stickyModifiers,
                        haptic = settings.hapticFeedback,
                    )

                    InputPanelPage.CJK_INPUT -> CjkInputPanel(onSend = onSendText)
                }
            }
        }
    }
}
