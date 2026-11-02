package dev.herdr.mobile.terminal.view

/** Rows to scroll plus the leftover pixels carried into the next callback. */
data class DragScroll(val rows: Int, val remainderPx: Float)

/**
 * Convert a drag distance into whole rows, carrying the sub-row remainder.
 *
 * The unit is the renderer's row pitch, the same one Termux uses locally, so a
 * remote pane moves the same distance as a local one. A non-positive pitch
 * (before attach) yields no movement rather than a divide-by-zero.
 */
fun rowsForDrag(distancePx: Float, remainderPx: Float, pitchPx: Float): DragScroll {
    if (pitchPx <= 0f) return DragScroll(0, remainderPx)
    val total = remainderPx + distancePx
    val rows = (total / pitchPx).toInt()
    return DragScroll(rows, total - rows * pitchPx)
}

/** Whole pages to emit plus the leftover rows carried into the next request. */
data class PageScroll(val pages: Int, val remainderRows: Int)

/**
 * Convert a row delta into whole pages for panes whose only lever is PgUp/PgDn.
 *
 * One page is one screenful, so the remainder must survive between requests:
 * converting per coalesced request made every flush scroll a full page and shot
 * a long drag through the whole scrollback.
 */
fun pagesForRows(rows: Int, rowsPerPage: Int, remainderRows: Int): PageScroll {
    if (rowsPerPage <= 0) return PageScroll(0, remainderRows)
    val total = remainderRows + rows
    val pages = total / rowsPerPage
    return PageScroll(pages, total - pages * rowsPerPage)
}

/**
 * A sub-page gesture still has to do something when the finger lifts, otherwise
 * a small flick looks broken. Sign follows the gesture.
 */
fun flushPage(remainderRows: Int): Int = when {
    remainderRows > 0 -> 1
    remainderRows < 0 -> -1
    else -> 0
}
