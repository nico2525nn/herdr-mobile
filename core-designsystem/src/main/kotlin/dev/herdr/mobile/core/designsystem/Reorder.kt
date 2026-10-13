package dev.herdr.mobile.core.designsystem

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
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
import kotlinx.coroutines.launch
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
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
    return remember {
        ReorderState(
            listState = listState,
            order = order,
            draggingKey = draggingKey,
            dragOffsetPx = dragOffsetPx,
            vertical = vertical,
            scrollBlocker = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(
                    available: androidx.compose.ui.geometry.Offset,
                    source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
                ): androidx.compose.ui.geometry.Offset =
                    if (draggingKey.value != null) available else androidx.compose.ui.geometry.Offset.Zero
            },
            handleModifier = { item ->
                val itemKey = key(item)
                Modifier.pointerInput(itemKey) {
                    // Manual long-press-drag (NOT detectDragGesturesAfterLongPress):
                    // the stock helper cancels when moves arrive consumed in the
                    // Main pass, which scrollable parents do to rail drags
                    // (observed: arm then instant cancel, zero motion). This loop
                    // reads RAW deltas (consumption flags ignored) and claims
                    // leftovers itself; the outer pre-scroll blocker starves the
                    // parent scroll so nothing double-moves.
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Waits the long-press timeout itself; null on early
                        // move/UP (plain tap/scroll — not ours).
                        if (awaitLongPressOrCancellation(down.id) == null) {
                            return@awaitEachGesture
                        }
                        lastDragAt.value = System.currentTimeMillis()
                        vertical.value = listState.layoutInfo.orientation == Orientation.Vertical
                        movedPastSlop.value = false
                        draggingKey.value = itemKey
                        dragOffsetPx.floatValue = 0f
                        scrollDir.intValue = 0
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        var slopAccum = 0f
                        var done = false
                        while (!done) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.changedToUpIgnoreConsumed()) {
                                done = true
                                break
                            }
                            // Raw delta even if an ancestor consumed it.
                            val pos = change.position
                            val prev = change.previousPosition
                            val dy = pos.y - prev.y
                            val dx = pos.x - prev.x
                            val main = if (vertical.value) dy else dx
                            if (!movedPastSlop.value) {
                                slopAccum += main
                                if (kotlin.math.abs(slopAccum) < slop) continue
                                movedPastSlop.value = true
                            }
                            dragOffsetPx.floatValue += main
                            lastDragAt.value = System.currentTimeMillis()
                            change.consume()
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
                        }
                        val moved = movedPastSlop.value
                        val to = order.value.indexOfFirst { key(it) == itemKey }
                        draggingKey.value = null
                        scrollDir.intValue = 0
                        dragOffsetPx.floatValue = 0f
                        if (moved) {
                            if (to >= 0) onDrop(item, to)
                        } else if (onLongPressWithoutMove != null) {
                            // Deferred past the gesture: opening a menu
                            // synchronously inside UP-handling lets the
                            // in-flight UP interact with the newborn UI
                            // (instant suicide-dismiss or phantom clicks).
                            val cb = onLongPressWithoutMove
                            scope.launch {
                                kotlinx.coroutines.delay(50)
                                cb?.invoke(item)
                            }
                        }
                    }
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
     * Attach to the lazy container via `Modifier.nestedScroll`. While a drag
     * is armed it swallows pre-scroll so the parent never sees (or consumes)
     * the moves. A plain boolean flag — no recomposition on arm, so it can't
     * cancel the very gesture it protects (unlike flipping
     * `userScrollEnabled`, which reconfigures the row mid-gesture and kills
     * working drags — observed).
     */
    val scrollBlocker: androidx.compose.ui.input.nestedscroll.NestedScrollConnection,
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
