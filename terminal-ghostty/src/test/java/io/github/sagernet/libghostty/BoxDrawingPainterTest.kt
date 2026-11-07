package io.github.sagernet.libghostty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BoxDrawingPainterTest {

    @Test
    fun `corners and rails are bridged`() {
        for (cp in listOf(0x2500, 0x2502, 0x250C, 0x2510, 0x2514, 0x2518, 0x251C, 0x253C)) {
            assertNotEquals("U+%04X".format(cp), 0, BoxDrawingPainter.segmentsFor(cp))
        }
    }

    @Test
    fun `dashed and diagonal glyphs stay with the font`() {
        for (cp in listOf(0x2504, 0x2506, 0x254C, 0x254D, 0x2571, 0x2572, 0x2573)) {
            assertEquals("U+%04X".format(cp), 0, BoxDrawingPainter.segmentsFor(cp))
        }
    }

    @Test
    fun `non-box codepoints are untouched`() {
        assertEquals(0, BoxDrawingPainter.segmentsFor('A'.code))
        assertEquals(0, BoxDrawingPainter.segmentsFor(0x24FF))
        assertEquals(0, BoxDrawingPainter.segmentsFor(0x2580))
    }

    @Test
    fun `heavy rails keep their double rails`() {
        // U+2501 (heavy horizontal) and U+2503 (heavy vertical) must have arms.
        assertNotEquals(0, BoxDrawingPainter.segmentsFor(0x2501))
        assertNotEquals(0, BoxDrawingPainter.segmentsFor(0x2503))
    }
}
