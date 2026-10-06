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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.core.designsystem.StatusChip
import dev.herdr.mobile.core.designsystem.StatusDot
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.InputPanelPage
import dev.herdr.mobile.terminal.view.HerdrTerminalView
import dev.herdr.mobile.terminal.view.TerminalBridge
import kotlinx.coroutines.launch

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

    Column(modifier = modifier.fillMaxSize()) {
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
            onInterrupt = { viewModel.interrupt() },
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
                )
            }
        }
        BottomInputPanel(
            state = state,
            onSendBytes = { viewModel.sendBytes(it) },
            onSendText = { viewModel.sendText(it) },
            onPageChange = { viewModel.setInputPage(it) },
        )
    }
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
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
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = state.workspace ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        workspace.tabs.forEach { tab ->
            StatusChip(
                label = tab.displayLabel,
                status = tab.status,
                selected = tab.id == state.target?.tabId,
                onClick = { onSelectTab(workspace.id, tab.id) },
            )
        }
        IconButton(onClick = onAddTab, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "New tab in ${workspace.label}")
        }
        // Dedicated interrupt: raw ETX on the stream, REST fallback in the ViewModel.
        IconButton(onClick = onInterrupt, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Interrupt (Ctrl-C) in ${state.tab?.displayLabel ?: "terminal"}")
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
private fun TerminalSurface(
    state: TerminalUiState,
    onSendBytes: (ByteArray) -> Unit,
) {
    val backend = state.backend ?: return
    val settings = state.settings
    var bridge by remember(backend) { mutableStateOf<TerminalBridge?>(null) }
    var viewRef by remember { mutableStateOf<HerdrTerminalView?>(null) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val scheme = remember(state.settings.terminalColorScheme) { state.terminalScheme() }

    // View size → grid size → backend resize, debounced by the bridge/daemon coalescing.
    var viewSizePx by remember { mutableStateOf(0 to 0) }

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
        },
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                viewSizePx = size.width to size.height
                val view = viewRef ?: return@onSizeChanged
                val (cols, rows) = view.gridFor(size.width, size.height)
                val b = bridge
                if (b == null) {
                    val created = TerminalBridge(
                        backend = backend,
                        colorScheme = scheme,
                        boldIsBright = settings.boldIsBright,
                        scrollbackLimit = settings.scrollbackLimit,
                    )
                    bridge = created
                    created.start(cols, rows)
                    scope.launch {
                        created.frame.collect { snapshot -> view.render(snapshot) }
                    }
                    scope.launch { backend.resize(cols, rows) }
                } else {
                    b.resize(cols, rows)
                }
            },
    )

    DisposableEffect(backend) {
        onDispose {
            bridge?.release()
            bridge = null
        }
    }

    // Silence unused warnings for the planned remote-scroll sync hook.
    LaunchedEffect(viewSizePx) {
        snapshotFlow { viewSizePx }.collect { }
    }
}

@Composable
private fun BottomInputPanel(
    state: TerminalUiState,
    onSendBytes: (ByteArray) -> Unit,
    onSendText: (String) -> Unit,
    onPageChange: (InputPanelPage) -> Unit,
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
    LaunchedEffect(pagerState.currentPage) {
        onPageChange(pages[pagerState.currentPage])
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
                            onSend = onSendBytes,
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
                        onSend = onSendBytes,
                        haptic = settings.hapticFeedback,
                    )

                    InputPanelPage.CJK_INPUT -> CjkInputPanel(onSend = onSendText)
                }
            }
        }
    }
}
