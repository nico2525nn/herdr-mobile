package dev.herdr.mobile.feature.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.core.designsystem.ExpressiveStatusGlyph
import dev.herdr.mobile.core.designsystem.HerdrTheme
import dev.herdr.mobile.core.designsystem.PeerTabChip
import dev.herdr.mobile.core.designsystem.StatusDot
import dev.herdr.mobile.core.designsystem.contentDescription
import dev.herdr.mobile.core.designsystem.rememberElapsedSeconds
import dev.herdr.mobile.core.designsystem.text
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.Tab
import dev.herdr.mobile.core.model.Workspace

/** Which Home tab a long-press menu refers to. */
internal data class HomeTabMenuTarget(
    val workspaceId: String,
    val tabId: String,
    val label: String,
    val anchor: androidx.compose.ui.unit.IntOffset,
    /** Target that produced this row; destructive menu actions carry it. */
    val targetGeneration: Long,
)

@Composable
internal fun HomeTabActionsMenu(
    target: HomeTabMenuTarget,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
) {
    // Same anchor trick as the Terminal rail: unanchored menus render at
    // the window origin. Offset = press point relative to this screen.
    Box(
        modifier = Modifier
            .offset { target.anchor }
            .size(1.dp),
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
}

@Composable
internal fun HomeTabRenameDialog(
    target: HomeTabMenuTarget,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var label by remember(target.tabId) { mutableStateOf(target.label) }
    AlertDialog(
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

/**
 * Update pill under the header: shows the verified next version with a
 * download action that runs `herdr update --handoff` on the daemon host.
 * Absent entirely when up to date or unknown (no "current" state shown).
 */
@Composable
internal fun HerdrUpdatePill(
    latest: String,
    running: Boolean,
    onUpdate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 8.dp),
    ) {
        androidx.compose.material3.FilledTonalButton(
            onClick = onUpdate,
            enabled = !running,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            if (running) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (running) "Updating…" else "Update $latest",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
internal fun HomeHeader(
    workspaceCount: Int,
    tabCount: Int,
    connection: ConnectionState,
    stale: Boolean,
    error: String?,
    onStatusClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val busy = connection == ConnectionState.CONNECTING ||
        connection == ConnectionState.RECONNECTING
    val elapsed = rememberElapsedSeconds(busy)
    // A transport retry with no snapshot still reports CONNECTING; once the
    // first dial fails, the error is the truth and the header must say so
    // instead of spinning "Connecting…" forever.
    val failed = connection == ConnectionState.FAILED ||
        (connection == ConnectionState.CONNECTING && error != null)
    // The second line is one slot: either the counts or the link state.
    // State must not become a second, removable surface above the content.
    val status = when {
        failed -> connectionProblem(error)
        connection == ConnectionState.CONNECTING -> "Connecting…"
        connection == ConnectionState.RECONNECTING ->
            if (elapsed > 0) "Reconnecting… ${elapsed}s" else "Reconnecting…"
        connection == ConnectionState.FAILED -> connectionProblem(error)
        stale -> "Refreshing…"
        else -> null
    }
    val tabs = if (tabCount == 1) "1 active tab" else "$tabCount active tabs"
    val summary = status ?: when {
        workspaceCount == 0 -> "No workspaces"
        workspaceCount == 1 -> "1 workspace · $tabs"
        else -> "$workspaceCount workspaces · $tabs"
    }
    // A plain wrapping header instead of LargeTopAppBar: Material3 1.4.0's
    // public large bar reuses its title slot as the collapsed row, so a
    // subtitle/error column would inflate both rows and clip at large font
    // scales. The flexible title+subtitle bar is internal in 1.4.0.
    Surface(
        color = Color.Transparent,
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
            ) {
                Text(
                    text = "Herdr",
                    style = MaterialTheme.typography.displaySmall,
                )
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy && !failed) {
                        ExpressiveStatusGlyph(
                            color = MaterialTheme.colorScheme.primary,
                            size = 16.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(
                                if (onStatusClick != null) {
                                    Modifier.clickable(onClick = onStatusClick)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }
    }
}

/** True when there is no profile to retry yet. */
internal fun isUnconfigured(error: String?): Boolean =
    error?.contains("No host profile", ignoreCase = true) == true

/** "Can't reach 192.168.1.4:8765" when the error names an endpoint. */
private val hostPattern = Regex("""(?:to\s+)(/?[A-Za-z0-9.\-\[\]:]+:\d+)""")

internal fun connectionProblem(error: String?): String {
    if (isUnconfigured(error)) return "No host configured"
    val host = error
        ?.let { hostPattern.find(it)?.groupValues?.getOrNull(1) }
        ?.trimStart('/')
    return if (host.isNullOrEmpty()) "Can't connect" else "Can't reach $host"
}

/** One short cause clause, stripped of transport noise already in the header. */
internal fun connectionDetail(error: String?): String = when {
    error == null -> "The daemon is not reachable."
    isUnconfigured(error) -> "Add a host profile in Settings, then come back."
    error.contains("Connection refused", ignoreCase = true) -> "Connection refused."
    error.contains("timed out", ignoreCase = true) ||
        error.contains("timeout", ignoreCase = true) -> "The host did not answer."
    error.contains("unauthorized", ignoreCase = true) -> "The host rejected the token."
    else -> error
}

@Composable
internal fun HomeOfflineBody(
    error: String?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val unconfigured = isUnconfigured(error)
    // The header already names the state and the endpoint. The body adds only
    // the cause and the action; repeating the headline here was redundant.
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier
                .padding(start = 16.dp, top = 20.dp, end = 16.dp)
                .widthIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = connectionDetail(error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (onRetry != null && !unconfigured) {
                Button(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Retry")
                }
            }
        }
    }
}

/**
 * First-load skeleton at the real card geometry. It communicates "connected,
 * still loading" without a spinner or a centred logo in an empty field.
 */
@Composable
internal fun HomeSkeleton(modifier: Modifier = Modifier) {
    val bone = if (HerdrTheme.amoled) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val edge = if (HerdrTheme.amoled) {
        Modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
            shape = MaterialTheme.shapes.large,
        )
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(3) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(edge)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.42f)
                        .height(20.dp)
                        .clip(CircleShape)
                        .background(bone),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .width(64.dp)
                                .height(26.dp)
                                .clip(CircleShape)
                                .background(bone),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeEmptyBody(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(20.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "No workspaces on this Herdr server.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
