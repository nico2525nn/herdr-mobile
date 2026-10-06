package dev.herdr.mobile.core.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.mobile.core.model.AgentStatus

/**
 * A tappable status-labelled pill: dot on the left edge, short label, optional trailing slot.
 *
 * Used for workspace chips in Terminal, tab chips on Home and in Terminal, and the `+` slot
 * is deliberately *not* a chip — it is a plain icon button, because it carries no state.
 *
 * Visual height is fixed at 32dp; callers widen the hit area with [Modifier] padding rather
 * than growing the chip, so rail geometry never shifts under selection.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatusChip(
    label: String,
    status: AgentStatus,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(CircleShape)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        enabled = enabled,
                        role = Role.Tab,
                        onClickLabel = label,
                        onLongClickLabel = "$label actions",
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                } else {
                    Modifier.selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                },
            )
            .semantics { contentDescription = "$label, ${status.contentDescription()}" },
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(status = status, diameter = 14.dp, describe = false)
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing?.invoke()
        }
    }
}

/**
 * The Home variant: every tab is a peer, so there is no selected state at all. The only
 * differentiator is the dot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeerTabChip(
    label: String,
    status: AgentStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    StatusChip(
        label = label,
        status = status,
        selected = false,
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick,
    )
}
