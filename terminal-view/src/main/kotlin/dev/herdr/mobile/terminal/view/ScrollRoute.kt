package dev.herdr.mobile.terminal.view

import dev.herdr.mobile.core.model.AgentMatrix

/**
 * Where a scroll gesture should go.
 *
 * Decided from what the transport can actually prove, not from an app name:
 *
 * - [HOST] — herdr reports scrollback for the pane
 *   (`max_offset_from_bottom > 0`). The host offset is line-exact and works for
 *   any app, so it always wins.
 * - [APP_INPUT] — the pane is a full-screen program: it enabled the alternate
 *   screen and/or mouse tracking, or the mirror never saw those flags because
 *   the TUI backend replays frames instead of bytes. Full-screen apps paint
 *   their own viewport, so the only levers are wheel notches and page keys.
 * - [LOCAL] — a plain shell with a real transcript in this emulator.
 * - [NONE] — nothing to move.
 */
enum class ScrollSource { HOST, APP_INPUT, LOCAL, NONE }

fun scrollSource(
    hostScrollKnown: Boolean,
    hostScrollable: Boolean,
    agentPane: Boolean,
    altScreen: Boolean,
    mouseTracking: Boolean,
    localHistoryRows: Int,
): ScrollSource = when {
    // The host's report is authoritative and covers every app: scrollback
    // means the host can move lines (shells and main-screen apps); no
    // scrollback means the pane is a full-screen program painting its own
    // viewport. This is the signal that does not need app labels or a DECSET
    // the TUI mirror never sees.
    hostScrollKnown && hostScrollable -> ScrollSource.HOST
    hostScrollKnown -> ScrollSource.APP_INPUT
    hostScrollable -> ScrollSource.HOST
    altScreen || mouseTracking -> ScrollSource.APP_INPUT
    agentPane -> ScrollSource.APP_INPUT
    localHistoryRows > 0 -> ScrollSource.LOCAL
    else -> ScrollSource.APP_INPUT
}

/**
 * How input reaches a full-screen app.
 *
 * Every TUI audited here (codex, omp, opencode, grok, pi) enables SGR mouse
 * reporting (`?1000h`/`?1002h`/`?1003h` + `?1006h`) and the alternate screen,
 * so a wheel notch is the fine-grained lever and a page key is the coarse one.
 * The order is measured at runtime: wheel first, page keys when a wheel gesture
 * moves nothing.
 */
enum class ScrollMechanism { HOST, WHEEL, PAGE_KEY }

/**
 * The one lever for a full-screen pane, from the audited per-agent table.
 *
 * This is deliberately not probed at runtime: the earlier probe mistook a live
 * repaint for "wheel did nothing" and silently downgraded codex/pi to whole
 * pages. The table is the behaviour these apps actually implement (codex, pi,
 * opencode and qwen scroll on SGR wheel reports; omp, grok and claude take page
 * keys), and the app never gets to second-guess it.
 */
fun scrollMechanisms(
    hostScrollable: Boolean,
    agentPane: Boolean,
    wheelAgent: Boolean,
): List<ScrollMechanism> = buildList {
    if (hostScrollable) {
        add(ScrollMechanism.HOST)
    } else {
        add(if (wheelAgent) ScrollMechanism.WHEEL else ScrollMechanism.PAGE_KEY)
    }
}

/** The next mechanism to try after [failed] proved inert, or null. */
fun nextMechanism(
    candidates: List<ScrollMechanism>,
    failed: Set<ScrollMechanism>,
): ScrollMechanism? = candidates.firstOrNull { it !in failed }

/**
 * What the app has learned about scrolling, per pane app identity.
 *
 * Process-wide and in-memory: a wrong lesson must not outlive the app, and
 * re-learning costs one gesture.
 */
object ScrollLearning {
    private fun key(agent: String?): String = agent?.trim()?.lowercase().orEmpty()

    private val winners = java.util.concurrent.ConcurrentHashMap<String, ScrollMechanism>()

    /** Measured lines moved per wheel notch (defaults to the xterm norm). */
    private val rowsPerNotch = java.util.concurrent.ConcurrentHashMap<String, Double>()

    fun winner(agent: String?): ScrollMechanism? = winners[key(agent)]

    fun remember(agent: String?, mechanism: ScrollMechanism) {
        winners[key(agent)] = mechanism
    }

    fun forget(agent: String?) {
        winners.remove(key(agent))
    }

    fun rowsPerNotch(agent: String?): Double = rowsPerNotch[key(agent)] ?: DEFAULT_ROWS_PER_NOTCH

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
        winners.clear()
        rowsPerNotch.clear()
    }

    private const val DEFAULT_ROWS_PER_NOTCH = 3.0
    private const val MIN_ROWS_PER_NOTCH = 0.25
    private const val MAX_ROWS_PER_NOTCH = 12.0
}

/** Wheel stride for [agent], used to convert rows into notches. */
fun rowsPerNotch(agent: String?): Double = ScrollLearning.rowsPerNotch(agent)

/** True when the app is known to scroll through mouse-wheel reports. */
fun wheelScrollable(agent: String?): Boolean = AgentMatrix.useWheelScroll(agent)
