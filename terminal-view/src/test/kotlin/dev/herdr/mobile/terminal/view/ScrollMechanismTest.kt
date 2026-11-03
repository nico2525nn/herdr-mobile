package dev.herdr.mobile.terminal.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wheel stride calibration: how many transcript lines one notch moves.
 *
 * Restores the property that made scrolling smooth before the routing rewrite:
 * the host measures the actual shift and feeds it back, so a wrong default
 * cannot make a drag run at half (or double) speed.
 */
class ScrollMechanismTest {

    @Test
    fun `stride starts at the xterm default`() {
        ScrollLearning.reset()
        assertEquals(3.0, rowsPerNotch("codex"), 0.001)
    }

    @Test
    fun `a measurement is folded in and converges`() {
        ScrollLearning.reset()
        ScrollLearning.observeRowsPerNotch("codex", notches = 4, movedRows = 4)
        assertEquals(2.0, rowsPerNotch("codex"), 0.001)
        ScrollLearning.observeRowsPerNotch("codex", notches = 4, movedRows = 4)
        assertEquals(1.5, rowsPerNotch("codex"), 0.001)
    }

    @Test
    fun `noise and impossible samples are ignored`() {
        ScrollLearning.reset()
        ScrollLearning.observeRowsPerNotch("codex", notches = 0, movedRows = 5)
        ScrollLearning.observeRowsPerNotch("codex", notches = 3, movedRows = 0)
        ScrollLearning.observeRowsPerNotch("codex", notches = 1, movedRows = 100)
        assertEquals(3.0, rowsPerNotch("codex"), 0.001)
    }

    @Test
    fun `learning a mechanism and forgetting it`() {
        ScrollLearning.reset()
        ScrollLearning.remember("pi", ScrollMechanism.PAGE_KEY)
        assertEquals(ScrollMechanism.PAGE_KEY, ScrollLearning.winner(" PI "))
        ScrollLearning.forget("pi")
        assertNull(ScrollLearning.winner("pi"))
    }
}
