package dev.herdr.mobile.terminal.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stride calibration: how many transcript lines one wheel notch moves.
 *
 * The agents disagree (a notch can be 1, 3 or more rows), and assuming the
 * wrong number is what made Codex run at half speed. The host measures the
 * actual shift and feeds it back here.
 */
class ScrollMechanismTest {

    @Test
    fun `learning a mechanism and forgetting it`() {
        ScrollLearning.reset()
        ScrollLearning.remember("pi", ScrollMechanism.PAGE_KEY)
        assertEquals(ScrollMechanism.PAGE_KEY, ScrollLearning.winner(" PI "))
        ScrollLearning.forget("pi")
        assertNull(ScrollLearning.winner("pi"))
    }
}
