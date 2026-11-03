package dev.herdr.mobile.feature.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.core.designsystem.PeerTabChip
import dev.herdr.mobile.core.designsystem.StatusDot
import dev.herdr.mobile.core.designsystem.contentDescription
import dev.herdr.mobile.core.designsystem.text
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.Tab
import dev.herdr.mobile.core.model.Workspace

@Composable
internal fun workspaceCard(
    workspace: Workspace,
    onOpenWorkspace: (workspaceId: String, tabId: String?) -> Unit,
    onOpenTab: (workspaceId: String, tabId: String) -> Unit,
    screenOrigin: androidx.compose.ui.geometry.Offset,
    onMenu: (HomeTabMenuTarget) -> Unit,
    onWsMenu: (HomeWsMenuTarget) -> Unit,
    handleModifier: (Workspace) -> Modifier = { Modifier },
) {
    WorkspaceCard(
        workspace = workspace,
        onOpenWorkspace = { onOpenWorkspace(workspace.id, workspace.activeTabId) },
        onOpenTab = { tab -> onOpenTab(workspace.id, tab.id) },
        handleModifier = handleModifier(workspace),
        onWorkspaceLongPress = { pressWindow ->
            val anchor = androidx.compose.ui.unit.IntOffset(
                (pressWindow.x - screenOrigin.x).toInt(),
                (pressWindow.y - screenOrigin.y).toInt(),
            )
            onWsMenu(HomeWsMenuTarget(workspace.id, workspace.label, anchor))
        },
        onTabLongPress = { tab, pressWindow ->
            // Window point minus our origin = anchor-box offset.
            val anchor = androidx.compose.ui.unit.IntOffset(
                (pressWindow.x - screenOrigin.x).toInt(),
                (pressWindow.y - screenOrigin.y).toInt(),
            )
            onMenu(HomeTabMenuTarget(workspace.id, tab.id, tab.displayLabel, anchor))
        },
    )
}

/**
 * Phone list with drag-reorder (handle in each card header). Local-visual
 * drag, single server move on drop — see [rememberReorderState]. Grid
 * (tablet) stays static — 2D drag needs its own geometry.
 */
@Composable
internal fun ReorderableWorkspaceList(
    state: HomeUiState,
    viewModel: HomeViewModel,
    onOpenWorkspace: (workspaceId: String, tabId: String?) -> Unit,
    onOpenTab: (workspaceId: String, tabId: String) -> Unit,
    screenOrigin: androidx.compose.ui.geometry.Offset,
    onTabMenu: (HomeTabMenuTarget) -> Unit,
    onWsMenu: (HomeWsMenuTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot ?: return
    val reorder = dev.herdr.mobile.core.designsystem.rememberReorderState(
        items = snapshot.workspaces,
        key = { it.id },
        onDrop = { ws, to -> viewModel.moveWorkspace(ws.id, to) },
    )
    LazyColumn(
        state = reorder.listState,
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(reorder.scrollBlocker),
        contentPadding = PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.showOfflineBanner || state.stale) {
            item(key = "banner") {
                OfflineBanner(
                    connection = state.connection,
                    stale = state.stale,
                    error = state.error,
                )
            }
        }
        items(
            items = reorder.order.value,
            key = { it.id },
        ) { workspace ->
            Box(
                modifier = Modifier.animateItem() then reorder.draggedModifier(workspace) { it.id },
            ) {
                workspaceCard(
                    workspace,
                    onOpenWorkspace,
                    onOpenTab,
                    screenOrigin,
                    onTabMenu,
                    onWsMenu,
                    handleModifier = { reorder.handleModifier(it) },
                )
            }
        }
        item(key = "bottom-space") { Spacer(Modifier.height(8.dp)) }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkspaceCard(
    workspace: Workspace,
    onOpenWorkspace: () -> Unit,
    onOpenTab: (Tab) -> Unit,
    onTabLongPress: (Tab, androidx.compose.ui.geometry.Offset) -> Unit,
    onWorkspaceLongPress: (androidx.compose.ui.geometry.Offset) -> Unit,
    modifier: Modifier = Modifier,
    /** Drag-reorder handle gesture (Home list builds it; grid passes empty). */
    handleModifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .clickable(
                    onClickLabel = "Open ${workspace.label} in terminal",
                    onClick = onOpenWorkspace,
                )
                .padding(16.dp),
        ) {
            var headerOrigin by remember(workspace.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            var headerWidthPx by remember(workspace.id) { mutableStateOf(0) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val headerInteractions = remember(workspace.id) { MutableInteractionSource() }
            var headerPress by remember(workspace.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            androidx.compose.runtime.LaunchedEffect(headerInteractions) {
                headerInteractions.interactions.collect { interaction ->
                    if (interaction is PressInteraction.Press) {
                        headerPress = interaction.pressPosition
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .onGloballyPositioned {
                        headerOrigin = it.positionInWindow()
                        headerWidthPx = it.size.width
                    }
                    .combinedClickable(
                        interactionSource = headerInteractions,
                        indication = null,
                        onClickLabel = "Open ${workspace.label} in terminal",
                        onLongClickLabel = "${workspace.label} actions",
                        onLongClick = {
                            // Presses starting on the drag handle (trailing 40dp
                            // icon + 24dp chevron) belong to reorder, not the menu:
                            // both detectors see the long-press, so the menu must
                            // yield its strip or every drag also opens it.
                            val handleStripPx = with(density) { 64.dp.toPx() }
                            if (headerPress.x < headerWidthPx - handleStripPx) {
                                onWorkspaceLongPress(headerOrigin + headerPress)
                            }
                        },
                        onClick = onOpenWorkspace,
                    ),
            ) {
                StatusDot(status = workspace.status)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                        .semantics {
                            contentDescription =
                                "${workspace.label}, ${workspace.status.contentDescription()}"
                        },
                ) {
                    Text(
                        text = workspace.label,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    workspace.displayPath?.let { path ->
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "Reorder ${workspace.label}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(40.dp)
                        .padding(8.dp)
                        .then(handleModifier),
                )
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (workspace.tabs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    workspace.tabs.forEach { tab ->
                        var chipOrigin by remember(tab.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                        PeerTabChip(
                            label = tab.displayLabel,
                            subtitle = tab.harnessNames.joinToString(",").takeIf { it.isNotEmpty() },
                            status = tab.status,
                            onClick = { onOpenTab(tab) },
                            modifier = Modifier.onGloballyPositioned { chipOrigin = it.positionInWindow() },
                            onLongClick = { press ->
                                onTabLongPress(tab, chipOrigin + press)
                            },
                        )
                    }
                }
            }
        }
    }
}
