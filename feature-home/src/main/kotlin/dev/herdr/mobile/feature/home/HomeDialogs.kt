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
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.Tab
import dev.herdr.mobile.core.model.Workspace

/** Which Home workspace a long-press menu refers to. */
internal data class HomeWsMenuTarget(
    val workspaceId: String,
    val label: String,
    val anchor: androidx.compose.ui.unit.IntOffset,
)

@Composable
internal fun HomeWsActionsMenu(
    target: HomeWsMenuTarget,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
) {
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
internal fun HomeWsRenameDialog(
    target: HomeWsMenuTarget,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var label by remember(target.workspaceId) { mutableStateOf(target.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename workspace") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Workspace name") },
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
internal fun HomeWsCloseDialog(
    target: HomeWsMenuTarget,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    // Unlike a tab, a workspace holds every tab — closing stops all its
    // agents. Confirm explicitly; there is no undo.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Close workspace?") },
        text = { Text("\"${target.label}\" and all its tabs will be closed. Running agents stop.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
internal fun HomeWsCreateDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String?, cwd: String?) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("") }
    var cwd by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New workspace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    label = { Text("Directory (optional)") },
                    placeholder = { Text("~/project") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label.ifBlank { null }, cwd.ifBlank { null }) }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
