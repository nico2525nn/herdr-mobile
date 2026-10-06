package dev.herdr.mobile.feature.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

    Column(modifier = modifier.fillMaxSize()) {
        HomeHeader(
            workspaceCount = state.workspaceCount,
            tabCount = state.tabCount,
            stale = state.stale,
            connection = state.connection,
            error = state.error,
        )
        when {
            state.snapshot == null && state.showOfflineBanner -> {
                HomeOfflineBody(error = state.error, modifier = Modifier.fillMaxSize())
            }

            state.snapshot == null -> {
                HomeEmptyBody(modifier = Modifier.fillMaxSize())
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
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
                        items = state.snapshot!!.workspaces,
                        key = { it.id },
                    ) { workspace ->
                        WorkspaceCard(
                            workspace = workspace,
                            onOpenWorkspace = { onOpenWorkspace(workspace.id, workspace.activeTabId) },
                            onOpenTab = { tab -> onOpenTab(workspace.id, tab.id) },
                        )
                    }
                    item(key = "bottom-space") { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    workspaceCount: Int,
    tabCount: Int,
    stale: Boolean,
    connection: ConnectionState,
    error: String?,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text = "Herdr",
            style = MaterialTheme.typography.displaySmall,
        )
        val subtitle = when {
            workspaceCount == 0 && connection == ConnectionState.CONNECTED ->
                "No workspaces"

            workspaceCount == 0 -> connection.name.lowercase().replaceFirstChar { it.uppercase() }
            else -> "$workspaceCount workspaces · $tabCount active tabs"
        }
        Text(
            text = if (stale) "$subtitle · stale" else subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null && workspaceCount > 0) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HomeOfflineBody(error: String?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Not connected",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = error ?: "Add a host profile in Settings, then come back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeEmptyBody(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(20.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "No workspaces on this Herdr server.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OfflineBanner(connection: ConnectionState, stale: Boolean, error: String?) {
    val text = when {
        connection == ConnectionState.CONNECTING -> "Connecting…"
        connection == ConnectionState.RECONNECTING && stale -> "Reconnecting — showing last known state"
        connection == ConnectionState.RECONNECTING -> "Reconnecting…"
        stale -> "Stale snapshot — reconnecting"
        else -> error ?: "Connection issue"
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceCard(
    workspace: Workspace,
    onOpenWorkspace: () -> Unit,
    onOpenTab: (Tab) -> Unit,
    modifier: Modifier = Modifier,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                        PeerTabChip(
                            label = tab.displayLabel,
                            status = tab.status,
                            onClick = { onOpenTab(tab) },
                        )
                    }
                }
            }
        }
    }
}
