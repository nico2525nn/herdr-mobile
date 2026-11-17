package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.core.model.PaneScroll
import kotlin.math.max

/**
 * Herdr TUI host scrollback position, drawn over the terminal. The engine's
 * own scrollbar describes the local prelude, not the remote host viewport; in
 * REMOTE_HOST mode this bar is the source of truth and supports drag-to-offset.
 */
@Composable
internal fun RemoteScrollbar(
    scroll: PaneScroll,
    onScrollTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(scroll)
    var dragOffset by remember { mutableStateOf<Long?>(null) }
    val laneWidth = 24.dp
    val thumbWidth = 3.dp
    val minThumb = 24.dp
    val semanticsOffset = (dragOffset ?: scroll.offsetFromBottom)
        .coerceIn(0L, scroll.maxOffsetFromBottom)
    val trackColor = Color.White.copy(alpha = 0.08f)
    val thumbColor = Color.White.copy(alpha = 0.38f)

    Canvas(
        modifier = modifier
            .width(laneWidth)
            .fillMaxHeight()
            .semantics {
                contentDescription = "Host scrollback"
                stateDescription = "$semanticsOffset of ${scroll.maxOffsetFromBottom}"
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragOffset = current.offsetFromBottom },
                    onDragEnd = { dragOffset = null },
                    onDragCancel = { dragOffset = null },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val height = size.height.toFloat().coerceAtLeast(1f)
                        val totalRows = max(1L, current.maxOffsetFromBottom + current.viewportRows)
                        val deltaRows = (-dragAmount.y / height * totalRows).toLong()
                        val next = ((dragOffset ?: current.offsetFromBottom) + deltaRows)
                            .coerceIn(0L, current.maxOffsetFromBottom)
                        dragOffset = next
                        onScrollTo(next)
                    },
                )
            },
    ) {
        val totalRows = max(1L, current.maxOffsetFromBottom + current.viewportRows)
        val trackHeight = size.height
        val trackTop = 0f
        val thumbHeight = max(
            minThumb.toPx(),
            trackHeight * current.viewportRows.toFloat() / totalRows,
        ).coerceAtMost(trackHeight)
        val offset = (dragOffset ?: current.offsetFromBottom)
            .coerceIn(0L, current.maxOffsetFromBottom)
        val thumbTop = trackTop +
            (current.maxOffsetFromBottom - offset).toFloat() / totalRows * trackHeight
        val x = size.width - thumbWidth.toPx() - 1.dp.toPx()
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(x, trackTop),
            size = Size(thumbWidth.toPx(), trackHeight),
            cornerRadius = CornerRadius(thumbWidth.toPx() / 2f),
        )
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(x, thumbTop.coerceIn(0f, trackHeight - thumbHeight)),
            size = Size(thumbWidth.toPx(), thumbHeight),
            cornerRadius = CornerRadius(thumbWidth.toPx() / 2f),
        )
    }
}
