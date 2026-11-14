package io.github.sagernet.libghostty

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A clipped redraw band in view coordinates, or null for a full invalidate. */
internal data class DirtyPaintBand(val top: Int, val bottom: Int)

/**
 * Union of the rows the engine marked dirty, the cursor's old row and its new
 * row. Returning null means the caller must invalidate the whole view (full
 * repaint, selection, images, geometry transition, or no row information).
 */
internal fun dirtyPaintBand(
    full: Boolean,
    rowCount: Int,
    dirtyMinRow: Int,
    dirtyMaxRow: Int,
    cursorY: Int,
    previousCursorY: Int,
    cellHeight: Int,
    gridOffsetY: Float,
    viewHeight: Int,
): DirtyPaintBand? {
    if (full || rowCount <= 0 || cellHeight <= 0) return null
    val rows = listOf(dirtyMinRow, dirtyMaxRow, cursorY, previousCursorY).filter { it >= 0 }
    if (rows.isEmpty()) return null
    val first = rows.min()
    val last = rows.max()
    val top = max(0, (gridOffsetY + first * cellHeight).toInt())
    val bottom = min(viewHeight, ceil(gridOffsetY + (last + 1) * cellHeight).toInt())
    return if (bottom > top) DirtyPaintBand(top, bottom) else null
}
