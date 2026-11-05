package dev.herdr.mobile.terminal.view

/**
 * Wheel notches, coalesced per frame.
 *
 * A drag produces rows; the app consumes notches. Fractional notches carry
 * across gestures, and whole notches are batched into one burst per
 * [throttleMs] because apps redraw once per report (one socket frame per notch
 * made opencode crawl). Timing is injected so the coalescing is unit-tested.
 */
internal class WheelBurst(
    private val throttleMs: Long,
    private val emit: (notches: Int) -> Unit,
    private val postDelayed: (delayMs: Long, action: () -> Unit) -> Unit,
    private val cancelPending: () -> Unit,
    private val clock: () -> Long,
) {
    /** Fractional-row carry across gestures. */
    private var remainder = 0.0

    private var pending = 0
    private var lastEmitMs = 0L
    private var hasEmitted = false
    private var queued = false

    /**
     * Convert [rows] into notches for [rowsPerNotch] and queue them.
     * Returns how many notches this call produced (0 when the carry is still
     * below one).
     */
    fun addRows(rows: Int, rowsPerNotch: Double): Int {
        if (rowsPerNotch <= 0.0) return 0
        remainder += rows / rowsPerNotch
        val notches = remainder.toInt()
        if (notches == 0) return 0
        remainder -= notches
        pending += notches
        val now = clock()
        if (!hasEmitted || now - lastEmitMs >= throttleMs) {
            flush()
        } else if (!queued) {
            queued = true
            postDelayed(throttleMs - (now - lastEmitMs)) { flush() }
        }
        return notches
    }

    /** Send whatever is queued now (finger lifted). */
    fun flush() {
        cancelPending()
        queued = false
        val notches = pending
        pending = 0
        if (notches == 0) return
        lastEmitMs = clock()
        hasEmitted = true
        emit(notches)
    }

    /**
     * A short drag produces less than one notch; without this the gesture
     * would do nothing at all. One notch in the gesture's direction is the
     * smallest unit a full-screen app understands.
     */
    fun flushTail() {
        if (remainder != 0.0) {
            pending += if (remainder > 0) 1 else -1
            remainder = 0.0
        }
        flush()
    }
}
