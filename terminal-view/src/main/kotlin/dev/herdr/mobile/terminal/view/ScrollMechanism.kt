package dev.herdr.mobile.terminal.view

/**
 * How a scroll request can reach a pane. The app never knows statically which
 * one a TUI honours (PgUp may be inert, wheel bytes may be garbage, the host
 * may keep no scrollback), so the set is tried in order and the winner is
 * remembered per app.
 */
enum class ScrollMechanism {
    /** Absolute host offset: rows go to the daemon, it calls `pane.scroll`. */
    HOST,

    /** PgUp/PgDn in whole screens; the only lever without host scrollback. */
    PAGE_KEY,

    /** SGR wheel notches for apps that asked for mouse reports. */
    WHEEL,
}

/**
 * Candidate order for a pane.
 *
 * Host scrolling is line-exact, so it goes first when the pane reports
 * scrollback. Otherwise the app's viewport has to move: wheel is the finer of
 * the two (one notch can be one row) and page keys the coarser, so try wheel
 * first — it degrades to nothing more gracefully than a full-screen jump when
 * the app ignores it.
 */
fun scrollMechanisms(hostScrollable: Boolean, wheelAgent: Boolean): List<ScrollMechanism> = buildList {
    if (hostScrollable) add(ScrollMechanism.HOST)
    add(ScrollMechanism.PAGE_KEY)
    if (wheelAgent) add(ScrollMechanism.WHEEL)
}

/**
 * The next mechanism to try after [failed] proved inert, or null when every
 * candidate has been ruled out (the pane simply cannot scroll).
 */
fun nextMechanism(
    candidates: List<ScrollMechanism>,
    failed: Set<ScrollMechanism>,
): ScrollMechanism? = candidates.firstOrNull { it !in failed }

/**
 * What the app has learned about scrolling, per pane app identity.
 *
 * Process-wide and deliberately in-memory: a wrong lesson must not outlive the
 * app, and re-learning costs one gesture. Panes without an identity share the
 * `""` bucket (plain shells), which is the right granularity for host vs local.
 */
object ScrollLearning {
    private val winners = java.util.concurrent.ConcurrentHashMap<String, ScrollMechanism>()

    private fun key(agent: String?): String =
        agent?.trim()?.lowercase().orEmpty()

    fun winner(agent: String?): ScrollMechanism? = winners[key(agent)]

    fun remember(agent: String?, mechanism: ScrollMechanism) {
        winners[key(agent)] = mechanism
    }

    fun forget(agent: String?) {
        winners.remove(key(agent))
    }

    fun reset() = winners.clear()
}
