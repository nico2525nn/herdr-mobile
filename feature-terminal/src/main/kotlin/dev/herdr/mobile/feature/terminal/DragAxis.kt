package dev.herdr.mobile.feature.terminal

import kotlin.math.abs

internal enum class DragAxis { NONE, HORIZONTAL, VERTICAL }

/**
 * Decide which gesture owns a drag, using total travel rather than the first
 * sample past touch slop. A small horizontal lead that is immediately followed
 * by a large downward flick must never keep horizontal ownership.
 *
 * The 1.5x dominance band leaves diagonals unclaimed instead of letting a
 * one-pixel lead decide; below [threshold] the gesture is still ambiguous.
 */
internal fun resolveDragAxis(dx: Float, dy: Float, threshold: Float): DragAxis {
    val ax = abs(dx)
    val ay = abs(dy)
    if (maxOf(ax, ay) < threshold) return DragAxis.NONE
    return when {
        ax > ay * 1.5f -> DragAxis.HORIZONTAL
        ay > ax * 1.5f -> DragAxis.VERTICAL
        else -> DragAxis.NONE
    }
}

/**
 * A dual-action key only ever claims a vertical gesture. A horizontal lead
 * below the pager's 2x-slop threshold is left unclaimed so the same gesture can
 * still become a downward flick; once the pager claims it, consumption cancels
 * the key detector.
 */
internal fun keyDragAxis(dx: Float, dy: Float, slop: Float): DragAxis =
    if (resolveDragAxis(dx, dy, slop * 1.5f) == DragAxis.VERTICAL) {
        DragAxis.VERTICAL
    } else {
        DragAxis.NONE
    }
