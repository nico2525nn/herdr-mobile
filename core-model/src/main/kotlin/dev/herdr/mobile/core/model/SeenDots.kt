package dev.herdr.mobile.core.model

/**
 * Client-side "seen" tracking for terminal done dots.
 *
 * Herdr's `done` is sticky by design: it means "the last run finished" and only clears
 * when the next run starts (a transition to working). Live detection may already say idle
 * (`herdr agent explain`) while the stored `agent_status` stays done — that is not a bug
 * on either side, but on a phone a days-old done reads as "unread result".
 *
 * So the app remembers which done panes the user has OPENED and renders those dots
 * muted. Memory-only on purpose: a process restart forgets, exactly like the
 * notification relay's silent first-snapshot seed. No persistence, no migration.
 *
 * Keys are pane ids. A key is only meaningful while the pane's status is still done;
 * callers check [isSeenDone] (key present AND status done), so a pane that moves on
 * automatically stops rendering muted without any cleanup call.
 */
object SeenDots {
    private val seen = mutableSetOf<String>()
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    /**
     * Bumps on every mark: screens collect it so a mark recomposes dots.
     * Without this the mark is invisible to Compose (same inputs → skipped).
     */
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    /** Mark [paneId] as opened by the user. Thread-safe: called from UI threads only. */
    @Synchronized
    fun mark(paneId: String) {
        if (seen.add(paneId)) _version.value += 1
    }

    /** True when [paneId] was opened AND its current [status] is still done. */
    @Synchronized
    fun isSeenDone(paneId: String, status: AgentStatus): Boolean =
        status == AgentStatus.DONE && paneId in seen

    @Synchronized
    fun clear() {
        seen.clear()
        _version.value += 1
    }
}

/**
 * True when this tab rolls up to done AND every done pane in it was opened.
 * A tab with no panes, or a non-done roll-up, is never "seen done".
 */
fun Tab.isSeenDone(): Boolean {
    if (status != AgentStatus.DONE || panes.isEmpty()) return false
    val donePanes = panes.filter { it.status == AgentStatus.DONE }
    if (donePanes.isEmpty()) return false
    return donePanes.all { SeenDots.isSeenDone(it.id, it.status) }
}

/**
 * True when this workspace rolls up to done AND every done pane under it was opened.
 */
fun Workspace.isSeenDone(): Boolean {
    if (status != AgentStatus.DONE) return false
    val donePanes = tabs.flatMap { it.panes }.filter { it.status == AgentStatus.DONE }
    if (donePanes.isEmpty()) return false
    return donePanes.all { SeenDots.isSeenDone(it.id, it.status) }
}
