package dev.herdr.mobile

import android.content.Context
import dev.herdr.mobile.core.model.AgentStatus
import dev.herdr.mobile.core.model.SemanticEvent
import dev.herdr.mobile.core.network.HerdrClient
import dev.herdr.mobile.notifications.HerdrNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Watches alert-worthy semantic events and posts system notifications while the app is
 * alive (foreground, cached, or pinned by the monitor service — a dead process with
 * monitoring off gets no alerts).
 *
 * Two paths, one dedupe key (paneId -> last notified status):
 * - event flow (exact): every daemon event, no coalescing loss — a fast
 *   working->done->working still alerts the done.
 * - snapshot-diff backstop: refetch jumps (cold start, reconnect, gap) carry
 *   transitions with no event; the diff catches them.
 * Either path skips when the pane's status already notified (prevents double
 * alerts for the same transition). The dedupe map persists across restarts: the
 * first snapshot diffs against it instead of blanket-suppressing, so panes that
 * finished while dead still alert exactly once.
 */
class NotificationRelay(
    private val context: Context,
    private val settings: SettingsRepositoryImpl,
    private val client: HerdrClient,
    private val scope: CoroutineScope,
) {
    // Concurrent: the event path and the snapshot-diff path run on
    // Dispatchers.Default (multi-threaded); a plain HashMap races (lost
    // dedupe = double alerts, torn reads = skipped alerts, CME = dead path).
    private val lastNotified = java.util.concurrent.ConcurrentHashMap<String, AgentStatus>()
    private var seeded = false

    init {
        scope.launch {
            client.events.collect { event ->
                val paneId = event.paneId
                if (!event.isAlertWorthy || paneId == null) return@collect
                val status = event.status ?: return@collect
                if (lastNotified[paneId] == status) return@collect
                lastNotified[paneId] = status
                persist()
                maybeNotify(event)
            }
        }
        scope.launch {
            var previous: Map<String, AgentStatus> = emptyMap()
            // Persisted map from the last process: diffed at seed so dead-window
            // completions alert once instead of being blanket-suppressed. Fresh
            // installs read empty — but then every existing done would alert on
            // first launch (launch storm), so an EMPTY persisted map still seeds
            // silently; only panes that ADVANCED vs a non-empty map alert.
            val persisted = runCatching { settings.loadNotified() }.getOrDefault(emptyMap())
                .mapValues { AgentStatus.fromWire(it.value) }
            client.state.collect { state ->
                val snapshot = state.snapshot ?: return@collect
                val current = snapshot.workspaces
                    .flatMap { it.tabs }
                    .flatMap { it.panes }
                    .associate { it.id to it.status }
                if (!seeded) {
                    previous = current
                    if (persisted.isEmpty()) {
                        lastNotified.putAll(current)
                    } else {
                        for (pane in snapshot.workspaces.flatMap { it.tabs }.flatMap { it.panes }) {
                            val old = persisted[pane.id]
                            lastNotified[pane.id] = pane.status
                            // Advanced while dead (working->done with no live
                            // process to see it): alert now, exactly once —
                            // lastNotified already records it, so the event
                            // path and later diffs skip the duplicate.
                            if (old != null && old != pane.status) {
                                val tab = snapshot.tab(pane.tabId)
                                val workspace = snapshot.workspace(pane.workspaceId)
                                maybeNotify(
                                    SemanticEvent(
                                        seq = snapshot.seq,
                                        type = SemanticEvent.TYPE_PANE_STATUS_CHANGED,
                                        workspaceId = pane.workspaceId,
                                        tabId = pane.tabId,
                                        paneId = pane.id,
                                        status = pane.status,
                                    ),
                                    workspace?.label,
                                    tab?.displayLabel,
                                )
                            }
                        }
                    }
                    persist()
                    seeded = true
                    return@collect
                }
                if (previous.isNotEmpty()) {
                    for (pane in snapshot.workspaces.flatMap { it.tabs }.flatMap { it.panes }) {
                        val old = previous[pane.id]
                        if (old != null && old != pane.status &&
                            lastNotified[pane.id] != pane.status
                        ) {
                            lastNotified[pane.id] = pane.status
                            persist()
                            val tab = snapshot.tab(pane.tabId)
                            val workspace = snapshot.workspace(pane.workspaceId)
                            val event = SemanticEvent(
                                seq = snapshot.seq,
                                type = SemanticEvent.TYPE_PANE_STATUS_CHANGED,
                                workspaceId = pane.workspaceId,
                                tabId = pane.tabId,
                                paneId = pane.id,
                                status = pane.status,
                            )
                            maybeNotify(event, workspace?.label, tab?.displayLabel)
                        }
                    }
                }
                // Panes that vanished take their dedupe keys with them: a
                // same-id pane re-created later is a new transition source.
                lastNotified.keys.retainAll(current.keys)
                previous = current
            }
        }
    }

    /** Best-effort write-through: transitions are rare, DataStore writes are cheap. */
    private fun persist() {
        val copy = HashMap(lastNotified).mapValues { it.value.wire }
        scope.launch {
            runCatching { settings.saveNotified(copy) }
        }
    }

    private fun maybeNotify(event: SemanticEvent, workspaceLabel: String? = null, tabLabel: String? = null) {
        val s = settings.settings.value
        // Monitoring OFF = no alerts, even in the cached-process window where
        // the link is still up (or while UI is open — the user sees live dots).
        if (!s.backgroundMonitoring) return
        if (!HerdrNotifications.shouldNotify(event, s.notifyDone, s.notifyBlocked)) return
        val snapshot = client.state.value.snapshot
        val ws = workspaceLabel ?: snapshot?.workspace(event.workspaceId)?.label
        val tab = tabLabel ?: snapshot?.tab(event.tabId)?.displayLabel
        HerdrNotifications.notify(context, event, ws, tab)
    }
}
