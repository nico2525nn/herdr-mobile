package dev.herdr.mobile.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.core.model.AgentStatus

/**
 * A tappable status-labelled pill: dot on the left edge, short label, optional trailing slot.
 *
 * Used for workspace chips in Terminal, tab chips on Home and in Terminal, and the `+` slot
 * is deliberately *not* a chip — it is a plain icon button, because it carries no state.
 *
 * Visual height starts at 32dp; callers widen the hit area with [Modifier] padding rather
 * than growing the chip, so rail geometry never shifts under selection. The selected corner
 * follows the measured height, so accessibility font scaling keeps the pill fully rounded.
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
    onLongClick: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    /** TalkBack/switch-access path for a menu whose pointer path is a reorder gesture. */
    onMenuAction: (() -> Unit)? = null,
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
    // M3 expressive chip: 12dp unselected, fully rounded selected. Measure the
    // actual height instead of assuming 32dp: labelLarge grows with font
    // scaling, and a fixed 16dp corner would stop being a pill.
    var chipHeightPx by androidx.compose.runtime.remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val fullCorner = with(density) {
        if (chipHeightPx > 0) (chipHeightPx / 2f).toDp() else 16.dp
    }
    val corner by animateDpAsState(
        targetValue = if (selected) fullCorner else 12.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "status-chip-shape",
    )
    val shape = RoundedCornerShape(corner)
    // Midnight maps the chip containers to black. A hairline keeps the
    // unselected and selected chips visible without adding a grey surface.
    val amoled = HerdrTheme.amoled
    val chipBorder = when {
        !amoled -> null
        selected -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    // combinedClickable has no press-offset overload (function 1.11): the
    // interaction source carries the latest press point for menu anchoring.
    val interactions = androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    var pressPoint by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    }
    androidx.compose.runtime.LaunchedEffect(interactions) {
        interactions.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.PressInteraction.Press) {
                pressPoint = interaction.pressPosition
            }
        }
    }
    Surface(
        modifier = modifier
            .onSizeChanged { chipHeightPx = it.height }
            .heightIn(min = 32.dp)
            .clip(shape)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        enabled = enabled,
                        role = Role.Tab,
                        onClickLabel = label,
                        onLongClickLabel = "$label actions",
                        interactionSource = interactions,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        onLongClick = { onLongClick.invoke(pressPoint) },
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
            .semantics {
                // The visible label is the node's text (merged from the Row
                // below), so it must NOT be repeated as a contentDescription —
                // TalkBack would read it twice. The state goes in
                // stateDescription, which TalkBack speaks right after the
                // text: "1 · codex, Working".
                //
                // stateDescription carries the selection announcement; it is
                // the only path that runs for both `selectable` and the
                // long-press (`combinedClickable`) chip. Do not also set the
                // `selected` semantics property: TalkBack would repeat it.
                if (onMenuAction != null) {
                    // The pointer long-press stays empty so the reorder
                    // detector owns the gesture; accessibility still gets the
                    // real menu both as the standard long-click and as an
                    // explicit action.
                    onLongClick(label = "Actions") {
                        onMenuAction.invoke()
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(label = "Actions") {
                            onMenuAction.invoke()
                            true
                        },
                    )
                }
                stateDescription = if (selected) {
                    "${status.contentDescription()}, selected"
                } else {
                    status.contentDescription()
                }
            },
        shape = shape,
        color = container,
        contentColor = content,
        border = chipBorder,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(status = status, diameter = 12.dp, describe = false)
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing?.invoke()
        }
    }
}

/**
 * The Home variant: every tab is a peer, so there is no selected state at all. The only
 * differentiator is the dot. Optional [subtitle] (harness names) renders after the label
 * in a quieter tone — "1 · codex".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeerTabChip(
    label: String,
    status: AgentStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onLongClick: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
) {
    StatusChip(
        label = if (subtitle != null) "$label · $subtitle" else label,
        status = status,
        selected = false,
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick,
    )
}
