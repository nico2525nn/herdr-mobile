package io.github.sagernet.libghostty

import org.junit.Assert.assertEquals
import org.junit.Test

class WheelMathTest {

    @Test
    fun `fractional stride keeps the remainder across small deltas`() {
        // 2.5 rows per notch: one row is not a notch, two rows are not, but
        // three rows are one with 0.5 rows left for the next event.
        assertEquals(0 to 1f, takeWheelNotches(1f, 2.5f, 4))
        assertEquals(0 to 2f, takeWheelNotches(2f, 2.5f, 4))
        assertEquals(1 to 0.5f, takeWheelNotches(3f, 2.5f, 4))
        assertEquals(1 to 1f, takeWheelNotches(3.5f, 2.5f, 4))
    }

    @Test
    fun `a fling that hits the cap drops the excess instead of queuing it`() {
        assertEquals(4 to 0f, takeWheelNotches(100f, 3f, 4))
    }

    @Test
    fun `negative deltas preserve sign and remainder`() {
        assertEquals(-1 to -0.5f, takeWheelNotches(-3f, 2.5f, 4))
    }

    @Test
    fun `mouse wheel path keeps its remainder too`() {
        assertEquals(0 to 2, takeMouseWheelNotches(2, 3, 4))
        assertEquals(1 to 0, takeMouseWheelNotches(3, 3, 4))
        assertEquals(1 to 1, takeMouseWheelNotches(4, 3, 4))
        assertEquals(4 to 0, takeMouseWheelNotches(100, 3, 4))
    }
}
