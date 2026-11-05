package dev.herdr.mobile.terminal.view

import dev.herdr.mobile.core.model.AgentMatrix

/**
 * What the app has learned about scrolling, per pane app identity.
 *
 * Process-wide and in-memory: a wrong lesson must not outlive the app, and
 * re-learning costs one gesture.
 */
object ScrollLearning {
    private fun key(agent: String?): String = agent?.trim()?.lowercase().orEmpty()

    /** Measured lines moved per wheel notch (defaults to the agent table). */
    private val rowsPerNotch = java.util.concurrent.ConcurrentHashMap<String, Double>()

    fun rowsPerNotch(agent: String?): Double =
        rowsPerNotch[key(agent)] ?: AgentMatrix.wheelRowsPerEvent(agent).toDouble()

    /**
     * Fold one observation into the per-agent stride. Wheel gestures are
     * accumulated before this is called, so [notches] and [movedRows] describe
     * the same gesture; the estimate is clamped and exponentially smoothed so
     * a single odd repaint cannot derail it.
     */
    fun observeRowsPerNotch(agent: String?, notches: Int, movedRows: Int) {
        if (notches <= 0 || movedRows <= 0) return
        val sample = movedRows.toDouble() / notches
        if (sample < MIN_ROWS_PER_NOTCH || sample > MAX_ROWS_PER_NOTCH) return
        val previous = rowsPerNotch(agent)
        rowsPerNotch[key(agent)] = previous * 0.5 + sample * 0.5
    }

    fun reset() {
        rowsPerNotch.clear()
    }

    private const val MIN_ROWS_PER_NOTCH = 0.25
    private const val MAX_ROWS_PER_NOTCH = 12.0
}
