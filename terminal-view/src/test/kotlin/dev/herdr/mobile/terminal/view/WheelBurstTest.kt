package dev.herdr.mobile.terminal.view

import org.junit.Assert.assertEquals
import org.junit.Test

class WheelBurstTest {

    private var now = 0L
    private var scheduled: (() -> Unit)? = null
    private var scheduledAfter = -1L
    private var cancels = 0
    private val emitted = mutableListOf<Int>()

    private fun burst(throttleMs: Long = 16): WheelBurst = WheelBurst(
        throttleMs = throttleMs,
        emit = { emitted += it },
        postDelayed = { delayMs, action ->
            scheduledAfter = delayMs
            scheduled = action
        },
        cancelPending = {
            cancels++
            scheduled = null
        },
        clock = { now },
    )

    @Test
    fun `a leading gesture emits immediately`() {
        val b = burst()
        assertEquals(1, b.addRows(rows = 1, rowsPerNotch = 1.0))
        assertEquals(listOf(1), emitted)
    }

    @Test
    fun `fractional notches carry across gestures`() {
        val b = burst()
        assertEquals(0, b.addRows(rows = 1, rowsPerNotch = 3.0))
        assertEquals(emptyList<Int>(), emitted)
        now = 100
        assertEquals(1, b.addRows(rows = 3, rowsPerNotch = 3.0))
        assertEquals(listOf(1), emitted)
    }

    @Test
    fun `notches inside the throttle window are batched into one burst`() {
        val b = burst(throttleMs = 16)
        b.addRows(rows = 1, rowsPerNotch = 1.0)
        now = 4
        b.addRows(rows = 1, rowsPerNotch = 1.0)
        assertEquals(listOf(1), emitted)
        assertEquals(12L, scheduledAfter)
        now = 16
        scheduled?.invoke()
        assertEquals(listOf(1, 1), emitted)
    }

    @Test
    fun `a sub-notch tail still fires one notch in the drag direction`() {
        val b = burst()
        b.addRows(rows = -1, rowsPerNotch = 3.0)
        b.flushTail()
        assertEquals(listOf(-1), emitted)
    }

    @Test
    fun `flush carries the pending notches and cancels the timer`() {
        val b = burst(throttleMs = 16)
        b.addRows(rows = 1, rowsPerNotch = 1.0)
        now = 1
        b.addRows(rows = 1, rowsPerNotch = 1.0)
        b.flush()
        // The first add emitted, so exactly one is left for the flush.
        assertEquals(1, emitted.last())
        assertEquals(2, emitted.sum())
        assertEquals(true, cancels > 0)
        assertEquals(null, scheduled)
    }

    @Test
    fun `a zero stride ignores rows instead of dividing by zero`() {
        val b = burst()
        assertEquals(0, b.addRows(rows = 5, rowsPerNotch = 0.0))
        assertEquals(emptyList<Int>(), emitted)
    }
}
