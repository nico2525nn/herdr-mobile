package io.github.sagernet.libghostty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirtyPaintTest {
    @Test
    fun `dirty rows plus both cursor rows form one band`() {
        assertEquals(
            DirtyPaintBand(top = 20, bottom = 90),
            dirtyPaintBand(
                full = false, rowCount = 3, dirtyMinRow = 5, dirtyMaxRow = 6,
                cursorY = 2, previousCursorY = 8, cellHeight = 10,
                gridOffsetY = 0f, viewHeight = 500,
            ),
        )
    }

    @Test
    fun `full or cursorless frames invalidate everything`() {
        assertNull(dirtyPaintBand(true, 3, 5, 5, 5, 5, 10, 0f, 500))
        assertNull(dirtyPaintBand(false, 0, -1, -1, 0, 0, 10, 0f, 500))
    }

    @Test
    fun `band is clipped to the view`() {
        assertEquals(
            DirtyPaintBand(top = 0, bottom = 5),
            dirtyPaintBand(false, 3, 0, 0, -1, 0, 10, -5f, 500),
        )
        assertEquals(
            DirtyPaintBand(top = 490, bottom = 500),
            dirtyPaintBand(false, 60, 49, 49, 49, 49, 10, 0f, 500),
        )
    }
}
