package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.core.designsystem.StatusChip

/** Which tab a long-press menu refers to. */
internal data class TabMenuTarget(
    val workspaceId: String,
    val tabId: String,
    val label: String,
)

/** Which workspace a long-press menu refers to. */
internal data class WorkspaceMenuTarget(
    val workspaceId: String,
    val label: String,
)

@Composable
internal fun WorkspaceRail(
    state: TerminalUiState,
    onNavigateHome: () -> Unit,
    onSelectWorkspace: (String) -> Unit,
    onMoveWorkspace: (workspaceId: String, toIndex: Int) -> Unit,
    onWorkspaceLongPress: (workspaceId: String, label: String) -> Unit,
    menuTarget: WorkspaceMenuTarget?,
    onDismissMenu: () -> Unit,
    onRenameMenu: (WorkspaceMenuTarget) -> Unit,
    onCloseMenu: (WorkspaceMenuTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot ?: return
    // Home stays pinned left while the workspace chips scroll under it.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onNavigateHome, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Home, contentDescription = "Back to Home")
        }
        val reorder = dev.herdr.mobile.core.designsystem.rememberReorderState(
            items = snapshot.workspaces,
            key = { it.id },
            onDrop = { ws, to -> onMoveWorkspace(ws.id, to) },
            // Menu opens on UP-after-press (see TabRail): a press-time popup
            // would steal the gesture stream from the drag takeover.
            onLongPressWithoutMove = { ws -> onWorkspaceLongPress(ws.id, ws.label) },
        )
        androidx.compose.foundation.lazy.LazyRow(
            state = reorder.listState,
            modifier = Modifier
                .weight(1f)
                .nestedScroll(reorder.scrollBlocker),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(
                items = reorder.order.value,
                key = { it.id },
            ) { workspace ->
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.animateItem() then
                        reorder.draggedModifier(workspace) { it.id },
                ) {
                    StatusChip(
                        label = workspace.label,
                        status = workspace.status,
                        selected = workspace.id == state.target?.workspaceId,
                        modifier = reorder.handleModifier(workspace),
                        onClick = { onSelectWorkspace(workspace.id) },
                        // Consume long-presses: the menu opens on UP via the
                        // reorder detector, like the tab rail.
                        onLongClick = {},
                    )
                    val target = menuTarget?.takeIf { it.workspaceId == workspace.id }
                    if (target != null) {
                        // Menu lives inside the chip: Compose anchors the popup
                        // to this layout and clamps it on screen. The old
                        // frame/window-offset math could silently miss (menu
                        // never appeared) depending on the workspace.
                        DropdownMenu(expanded = true, onDismissRequest = onDismissMenu) {
                            DropdownMenuItem(
                                text = { Text("Rename \"${target.label}\"") },
                                onClick = { onRenameMenu(target) },
                            )
                            DropdownMenuItem(
                                text = { Text("Close \"${target.label}\"") },
                                onClick = { onCloseMenu(target) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TabRail(
    state: TerminalUiState,
    onSelectTab: (workspaceId: String, tabId: String) -> Unit,
    onSummonKeyboard: () -> Unit,
    onAddTab: () -> Unit,
    onTabLongPress: (workspaceId: String, tabId: String, label: String) -> Unit,
    onMoveTab: (tabId: String, toIndex: Int) -> Unit,
    menuTarget: TabMenuTarget?,
    onDismissMenu: () -> Unit,
    onRenameMenu: (TabMenuTarget) -> Unit,
    onCloseMenu: (TabMenuTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = state.workspace ?: return

    // + stays pinned right while the tab chips scroll under it.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val reorder = dev.herdr.mobile.core.designsystem.rememberReorderState(
            items = workspace.tabs,
            key = { it.id },
            onDrop = { tab, to -> onMoveTab(tab.id, to) },
            // Menu opens on UP-after-press, not on press: an open popup steals
            // the gesture stream and drag-takeover becomes impossible.
            onLongPressWithoutMove = { tab ->
                onTabLongPress(workspace.id, tab.id, tab.displayLabel)
            },
        )
        androidx.compose.foundation.lazy.LazyRow(
            state = reorder.listState,
            modifier = Modifier
                .weight(1f)
                .nestedScroll(reorder.scrollBlocker),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(
                items = reorder.order.value,
                key = { it.id },
            ) { tab ->
                val harness = tab.harnessNames.joinToString(",").takeIf { it.isNotEmpty() }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.animateItem() then
                        reorder.draggedModifier(tab) { it.id },
                ) {
                    StatusChip(
                        label = if (harness != null) "${tab.displayLabel} · $harness" else tab.displayLabel,
                        status = tab.status,
                        selected = tab.id == state.target?.tabId,
                        modifier = reorder.handleModifier(tab),
                        onClick = { onSelectTab(workspace.id, tab.id) },
                        // Consume long-presses (menu opens on UP via the drag
                        // detector instead): without this a dwell falls through
                        // to tap and the UP races the drag-end callback.
                        onLongClick = {},
                    )
                    val target = menuTarget?.takeIf { it.tabId == tab.id }
                    if (target != null) {
                        DropdownMenu(expanded = true, onDismissRequest = onDismissMenu) {
                            DropdownMenuItem(
                                text = { Text("Rename \"${target.label}\"") },
                                onClick = { onRenameMenu(target) },
                            )
                            DropdownMenuItem(
                                text = { Text("Close \"${target.label}\"") },
                                onClick = { onCloseMenu(target) },
                            )
                        }
                    }
                }
            }
        }
        IconButton(onClick = onSummonKeyboard, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Keyboard, contentDescription = "Terminal keyboard")
        }
        IconButton(onClick = onAddTab, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "New tab in ${workspace.label}")
        }
    }
}
