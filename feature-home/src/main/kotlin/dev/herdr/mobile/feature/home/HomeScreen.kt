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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
import dev.herdr.mobile.core.designsystem.LocalUiCopy
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.Tab
import dev.herdr.mobile.core.model.Workspace

/**
 * Semantic overview: workspace cards with peer tab chips, never a terminal grid.
 *
 * - Tapping a card body opens the workspace's last active tab in Terminal.
 * - Tapping a tab chip opens exactly that tab.
 * - No chip is ever rendered selected: on Home every tab is a peer and the only
 *   differentiator is the status dot.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenWorkspace: (workspaceId: String, tabId: String?) -> Unit,
    onOpenTab: (workspaceId: String, tabId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val updateRunning by viewModel.updateRunning.collectAsStateWithLifecycle()
    // Command errors are transient feedback, not a permanent banner: clear
    // them after a few seconds (or when the next command succeeds).
    LaunchedEffect(message) {
        if (message != null) {
            delay(5_000)
            viewModel.clearMessage()
        }
    }
    var tabMenu by remember { mutableStateOf<HomeTabMenuTarget?>(null) }
    var renameTarget by remember { mutableStateOf<HomeTabMenuTarget?>(null) }
    var wsMenu by remember { mutableStateOf<HomeWsMenuTarget?>(null) }
    var wsRenameTarget by remember { mutableStateOf<HomeWsMenuTarget?>(null) }
    var wsCloseTarget by remember { mutableStateOf<HomeWsMenuTarget?>(null) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    // Screen origin in window coords: the anchor Box renders here, so press
    // points (window coords) are offset back by this to land on the chip.
    var screenOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { screenOrigin = it.positionInWindow() },
    ) {
        HomeHeader(
            workspaceCount = state.workspaceCount,
            tabCount = state.tabCount,
            stale = state.stale,
            connection = state.connection,
            error = state.error,
        )
        state.updateAvailable?.let { latest ->
            HerdrUpdatePill(
                latest = latest,
                running = updateRunning,
                onUpdate = { viewModel.runHerdrUpdate() },
            )
        }
        message?.let { shown ->
            Text(
                text = shown.let { LocalUiCopy.current.render(it) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        when {
            state.snapshot == null && state.showOfflineBanner -> {
                HomeOfflineBody(error = state.error, modifier = Modifier.fillMaxSize())
            }

            state.snapshot == null -> {
                HomeEmptyBody(modifier = Modifier.fillMaxSize())
            }

            else -> {
                // Width-adaptive: phones keep the single-column list; tablets
                // tile the same cards (2 cols ≥600dp, 3 cols ≥840dp). The card
                // (incl. tab chips + longpress anchors) is reused verbatim —
                // anchors are window-coords, so grid placement needs no math.
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val columns = when {
                        maxWidth < 600.dp -> 1
                        maxWidth < 840.dp -> 2
                        else -> 3
                    }
                    if (columns == 1) {
                        ReorderableWorkspaceList(
                            state = state,
                            viewModel = viewModel,
                            onOpenWorkspace = onOpenWorkspace,
                            onOpenTab = onOpenTab,
                            screenOrigin = screenOrigin,
                            onTabMenu = { tabMenu = it },
                            onWsMenu = { wsMenu = it },
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (state.showOfflineBanner || state.stale) {
                                item(key = "banner", span = { GridItemSpan(maxLineSpan) }) {
                                    OfflineBanner(
                                        connection = state.connection,
                                        stale = state.stale,
                                        error = state.error,
                                    )
                                }
                            }
                            items(
                                items = state.snapshot!!.workspaces,
                                key = { it.id },
                            ) { workspace ->
                                workspaceCard(
                                    workspace,
                                    onOpenWorkspace,
                                    onOpenTab,
                                    screenOrigin,
                                    { tabMenu = it },
                                    { wsMenu = it },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

        // New workspace: small (rounded-square) FAB, bottom-end. Always
        // visible — while offline the create call fails with a surfaced
        // message instead of a dead button.
        SmallFloatingActionButton(
            onClick = { showCreate = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "New workspace")
        }
    } // Box

    tabMenu?.let { target ->
        HomeTabActionsMenu(
            target = target,
            onDismiss = { tabMenu = null },
            onRename = {
                renameTarget = target
                tabMenu = null
            },
            onClose = {
                viewModel.closeTab(target.tabId)
                tabMenu = null
            },
        )
    }
    renameTarget?.let { target ->
        HomeTabRenameDialog(
            target = target,
            onDismiss = { renameTarget = null },
            onConfirm = { label ->
                viewModel.renameTab(target.tabId, label)
                renameTarget = null
            },
        )
    }
    wsMenu?.let { target ->
        HomeWsActionsMenu(
            target = target,
            onDismiss = { wsMenu = null },
            onRename = {
                wsRenameTarget = target
                wsMenu = null
            },
            onClose = {
                wsCloseTarget = target
                wsMenu = null
            },
        )
    }
    wsRenameTarget?.let { target ->
        HomeWsRenameDialog(
            target = target,
            onDismiss = { wsRenameTarget = null },
            onConfirm = { label ->
                viewModel.renameWorkspace(target.workspaceId, label)
                wsRenameTarget = null
            },
        )
    }
    wsCloseTarget?.let { target ->
        HomeWsCloseDialog(
            target = target,
            onDismiss = { wsCloseTarget = null },
            onConfirm = {
                viewModel.closeWorkspace(target.workspaceId)
                wsCloseTarget = null
            },
        )
    }
    if (showCreate) {
        HomeWsCreateDialog(
            onDismiss = { showCreate = false },
            onConfirm = { label, cwd ->
                viewModel.createWorkspace(label, cwd)
                showCreate = false
            },
        )
    }
}
