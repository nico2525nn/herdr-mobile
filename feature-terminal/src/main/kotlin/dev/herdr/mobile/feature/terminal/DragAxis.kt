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
