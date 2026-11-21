package dev.herdr.mobile.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class DragAxisTest {

    @Test
    fun `a small horizontal lead followed by a deep downward flick is vertical`() {
        // Regression: the pager used to claim at the first slop crossing when
        // abs(dx) >= abs(dy), locking a later downward PGUP flick to the page.
        assertEquals(DragAxis.NONE, resolveDragAxis(9f, 1f, 16f))
        assertEquals(DragAxis.VERTICAL, resolveDragAxis(12f, 80f, 16f))
    }

    @Test
    fun `a clear horizontal swipe is horizontal`() {
        assertEquals(DragAxis.HORIZONTAL, resolveDragAxis(80f, 12f, 16f))
    }

    @Test
    fun `a diagonal stays unclaimed`() {
        assertEquals(DragAxis.NONE, resolveDragAxis(40f, 35f, 16f))
    }

    @Test
    fun `a horizontal lead below the pager threshold does not end a dual-key flick`() {
        // 1.6x slop horizontally is HORIZONTAL for the generic classifier, but
        // the key must keep waiting: the pager only decides at 2x slop, and a
        // subsequent deep downward move is a PGDN flick.
        assertEquals(DragAxis.NONE, keyDragAxis(25.6f, 0f, 16f))
        assertEquals(DragAxis.VERTICAL, keyDragAxis(25.6f, 80f, 16f))
    }
}
