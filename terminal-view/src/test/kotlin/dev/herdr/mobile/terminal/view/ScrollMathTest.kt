package dev.herdr.mobile.terminal.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Scroll distance, as arithmetic rather than a device check.
 *
 * The rules the user feels:
 * - a drag of N screen-pixels moves N/row-pitch rows, so the same gesture moves
 *   the same amount on every pane;
 * - sub-row and sub-page leftovers are carried, never rounded away per event
 *   (rounding per event is what made a slow drag stall and a fast one jump);
 * - lifting the finger after a sub-page drag still scrolls one page.
 *
 * Pitch measured on the device: 37 px rows, ~1658 px view height.
 */
class ScrollMathTest {

    @Test
    fun `a drag moves rows in proportion to its distance`() {
        // 400 px on a 37 px pitch: about eleven rows, like the manual check.
        val drag = rowsForDrag(distancePx = 400f, remainderPx = 0f, pitchPx = 37f)
        assertEquals(10, drag.rows)
        assertEquals(400f - 10 * 37f, drag.remainderPx, 0.001f)
    }

    @Test
    fun `a full screen drag moves one screen of rows`() {
        val viewHeight = 1658f
        val pitch = 37f
        val drag = rowsForDrag(viewHeight, 0f, pitch)
        assertEquals((viewHeight / pitch).toInt(), drag.rows)
    }

    @Test
    fun `small movements accumulate instead of being lost`() {
        var remainder = 0f
        var rows = 0
        repeat(10) {
            val step = rowsForDrag(4f, remainder, 37f)
            remainder = step.remainderPx
            rows += step.rows
        }
        // 40 px of slow drag must still move one row, not zero.
        assertEquals(1, rows)
    }

    @Test
    fun `direction is preserved`() {
        assertEquals(-10, rowsForDrag(-400f, 0f, 37f).rows)
    }

    @Test
    fun `unknown pitch never moves`() {
        assertEquals(0, rowsForDrag(400f, 12f, 0f).rows)
        assertEquals(12f, rowsForDrag(400f, 12f, 0f).remainderPx, 0.001f)
    }

    @Test
    fun `pages come from whole screens with the remainder carried`() {
        // 43 rows on a 30-row host viewport: one page now, 13 rows kept.
        val first = pagesForRows(rows = 43, rowsPerPage = 30, remainderRows = 0)
        assertEquals(1, first.pages)
        assertEquals(13, first.remainderRows)

        // The next coalesced flush of 20 rows completes a second page.
        val second = pagesForRows(rows = 20, rowsPerPage = 30, remainderRows = first.remainderRows)
        assertEquals(1, second.pages)
        assertEquals(3, second.remainderRows)
    }

    @Test
    fun `a short drag emits no page until the finger lifts`() {
        val drag = pagesForRows(rows = 11, rowsPerPage = 44, remainderRows = 0)
        assertEquals(0, drag.pages)
        assertEquals(1, flushPage(drag.remainderRows))
        assertEquals(0, flushPage(0))
        assertEquals(-1, flushPage(-5))
    }
}
