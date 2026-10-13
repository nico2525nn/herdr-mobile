package dev.herdr.mobile.core.designsystem

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex

/**
 * Long-press-drag reorder for lazy lists, orientation-agnostic (LazyColumn and
 * LazyRow share [LazyListState] 1-D layout info, so the same math serves both).
 *
 * The drag reorders LOCALLY (visual only, animated by the caller's
 * `Modifier.animateItem()`); on drop a single [onDrop] fires and the server
 * confirms via refetch. No optimistic patching: concurrent remote moves win on
 * the next snapshot. Snapshot updates mid-drag (status flicker) never teleport
 * the list — resync waits for idle.
 *
 * Entry is long-press (never plain drag) so scrolling is untouched. Edge
 * auto-scroll runs while the pointer sits in the outer 120px.
 */
@Composable
fun <T> rememberReorderState(
    items: List<T>,
    key: (T) -> Any,
    onDrop: (item: T, toIndex: Int) -> Unit,
    /**
     * Fires when a press ends without moving past slop (a plain long-press).
     * Rails use it to open the long-press menu on UP instead of on press:
     * opening on press steals the gesture stream into the popup and makes
     * drag-takeover impossible. Note it fires from the cancel path, not the
     * end path: the detector reports press-without-drag as a drag that never
     * started (cancel), and end runs only after real motion.
     */
    onLongPressWithoutMove: ((item: T) -> Unit)? = null,
): ReorderState<T> {
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    // Stable identity (NO key): the bag holds this object forever. Keying it
    // would orphan the UI on the old object after every resync — the list
    // would freeze on stale order while the state moved on.
    val order: MutableState<List<T>> = remember { mutableStateOf(items) }
    val draggingKey: MutableState<Any?> = remember { mutableStateOf(null) }
    // Last arming/motion instant. The resync gate must not trust the gesture
    // lifecycle alone: if end/cancel never run (UP stolen by a nested
    // clickable), a stuck arm would freeze resync forever.
    val lastDragAt = remember { mutableStateOf(0L) }
    LaunchedEffect(items) {
        // Resync when idle — or when the arm is older than 2s without motion
        // (stuck arm self-heals; a live drag moves faster than that).
        if (draggingKey.value == null ||
            System.currentTimeMillis() - lastDragAt.value > 2_000
        ) {
            order.value = items
        }
    }
    val dragOffsetPx = remember { mutableFloatStateOf(0f) }
    val movedPastSlop = remember { mutableStateOf(false) }
    val scrollDir = remember { mutableIntStateOf(0) }
    LaunchedEffect(draggingKey.value) {
        while (draggingKey.value != null) {
            val dir = scrollDir.intValue
            if (dir != 0) listState.scrollBy(dir * 18f)
            kotlinx.coroutines.delay(16)
        }
    }
    val vertical = remember { mutableStateOf(true) }
    val scrollEnabled = remember { androidx.compose.runtime.derivedStateOf { draggingKey.value == null } }
    return remember {
        ReorderState(
            listState = listState,
            order = order,
            draggingKey = draggingKey,
            dragOffsetPx = dragOffsetPx,
            vertical = vertical,
            scrollEnabled = scrollEnabled,
            handleModifier = { item ->
                val itemKey = key(item)
                Modifier.pointerInput(itemKey) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            lastDragAt.value = System.currentTimeMillis()
                            vertical.value = listState.layoutInfo.orientation == Orientation.Vertical
                            movedPastSlop.value = false
                            draggingKey.value = itemKey
                            dragOffsetPx.floatValue = 0f
                            scrollDir.intValue = 0
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragCancel = {
                            // No motion = plain long-press (release): menu. Real
                            // mid-drag interruption also lands here: reset, no menu.
                            val wasPress = !movedPastSlop.value
                            draggingKey.value = null
                            scrollDir.intValue = 0
                            dragOffsetPx.floatValue = 0f
                            if (wasPress) {
                                onLongPressWithoutMove?.invoke(item)
                            } else {
                                order.value = items
                            }
                        },
                        onDragEnd = {
                            // End runs only after real motion (press-without-move
                            // takes the cancel path above, never lands here).
                            val to = order.value.indexOfFirst { key(it) == itemKey }
                            draggingKey.value = null
                            scrollDir.intValue = 0
                            dragOffsetPx.floatValue = 0f
                            if (to >= 0) onDrop(item, to)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val main = if (vertical.value) dragAmount.y else dragAmount.x
                            dragOffsetPx.floatValue += main
                            lastDragAt.value = System.currentTimeMillis()
                            if (!movedPastSlop.value &&
                                kotlin.math.abs(dragOffsetPx.floatValue) > 24f
                            ) {
                                movedPastSlop.value = true
                            }
                            val info = listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key == itemKey }
                            if (info != null) {
                                val center = info.offset + dragOffsetPx.floatValue + info.size / 2f
                                val target = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull {
                                        center in it.offset.toFloat()..(it.offset + it.size).toFloat()
                                    }
                                val fromIdx = order.value.indexOfFirst { key(it) == itemKey }
                                val toIdx = target?.let { t ->
                                    order.value.indexOfFirst { key(it) == t.key }
                                } ?: -1
                                if (toIdx >= 0 && toIdx != fromIdx) {
                                    order.value = order.value.toMutableList().apply {
                                        add(toIdx, removeAt(fromIdx))
                                    }
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                val viewport = listState.layoutInfo.viewportEndOffset -
                                    listState.layoutInfo.viewportStartOffset
                                val absCenter = info.offset + dragOffsetPx.floatValue + info.size / 2f -
                                    listState.layoutInfo.viewportStartOffset
                                scrollDir.intValue = when {
                                    absCenter < 120 -> -1
                                    absCenter > viewport - 120 -> 1
                                    else -> 0
                                }
                            }
                        },
                    )
                }
            },
        )
    }
}

/** Live reorder state. All fields are observable snapshots. */
class ReorderState<T>(
    val listState: LazyListState,
    val order: State<List<T>>,
    val draggingKey: State<Any?>,
    val dragOffsetPx: State<Float>,
    val vertical: State<Boolean>,
    /**
     * False while a drag is armed: pass to the lazy container's
     * `userScrollEnabled`. Without the lock the parent wins the nested-scroll
     * race, consumes the moves, and the detector cancels instantly (observed:
     * arm → cancel in 277ms, zero motion delivered).
     */
    val scrollEnabled: State<Boolean>,
    /** Gesture modifier for the item's drag handle (or whole item). */
    val handleModifier: (T) -> Modifier,
) {
    /** True while [item] is the dragged one. */
    fun isDragging(item: T, key: (T) -> Any): Boolean = draggingKey.value == key(item)

    /** Follows-the-finger elevation for the dragged item (axis-aware). */
    fun draggedModifier(item: T, key: (T) -> Any): Modifier =
        if (!isDragging(item, key)) {
            Modifier
        } else {
            Modifier
                .graphicsLayer {
                    if (vertical.value) translationY = dragOffsetPx.value else translationX = dragOffsetPx.value
                    scaleX = 1.02f
                    scaleY = 1.02f
                }
                .zIndex(1f)
        }
}
