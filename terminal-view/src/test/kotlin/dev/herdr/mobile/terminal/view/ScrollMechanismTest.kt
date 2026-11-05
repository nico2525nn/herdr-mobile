package dev.herdr.mobile.terminal.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wheel stride: how many transcript rows one notch advances.
 *
 * The default comes from the audited agent table (codex/pi/opencode: one row
 * per notch, qwen: three). A wrong default is what made a drag run at half
 * speed, so the host also folds in measurements when it can see the shift.
 */
class ScrollMechanismTest {

    @Test
    fun `stride starts from the agent table`() {
        ScrollLearning.reset()
        assertEquals(1.0, rowsPerNotch("codex"), 0.001)
        // Pi moves two rows per notch (measured on device).
        assertEquals(2.0, rowsPerNotch("pi"), 0.001)
        assertEquals(3.0, rowsPerNotch("qwen"), 0.001)
        assertEquals(1.0, rowsPerNotch("unknown-agent"), 0.001)
    }

    @Test
    fun `a measurement is folded in and converges`() {
        ScrollLearning.reset()
        // qwen starts at 3; observing one row per notch pulls it down smoothly.
        ScrollLearning.observeRowsPerNotch("qwen", notches = 4, movedRows = 4)
        assertEquals(2.0, rowsPerNotch("qwen"), 0.001)
        ScrollLearning.observeRowsPerNotch("qwen", notches = 4, movedRows = 4)
        assertEquals(1.5, rowsPerNotch("qwen"), 0.001)
    }

    @Test
    fun `measurements are per app`() {
        ScrollLearning.reset()
        ScrollLearning.observeRowsPerNotch("qwen", notches = 2, movedRows = 6)
        assertEquals(3.0, rowsPerNotch("qwen"), 0.001)
        assertEquals(1.0, rowsPerNotch("codex"), 0.001)
    }

    @Test
    fun `noise and impossible samples are ignored`() {
        ScrollLearning.reset()
        ScrollLearning.observeRowsPerNotch("qwen", notches = 0, movedRows = 5)
        ScrollLearning.observeRowsPerNotch("qwen", notches = 3, movedRows = 0)
        ScrollLearning.observeRowsPerNotch("qwen", notches = 1, movedRows = 100)
        assertEquals(3.0, rowsPerNotch("qwen"), 0.001)
    }


}
