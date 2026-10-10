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
 *
 * Retraction is the mirror image, and it is what makes read-ack clear alerts on
 * EVERY device: opening a done pane flips it idle server-side, each watching
 * device sees the leave-status transition and retracts its own copy. No
 * cross-device messaging, no local-only cancel that leaves other phones buzzing.
 * Both paths retract, so the backstop heals missed retractions exactly like it
 * heals missed posts; the seed diff retracts alerts the user already cleared
 * elsewhere (desktop read, another phone) while this process was dead.
 * Notifications survive process death, so the seed diff is the only thing that
 * can clean a shade left dirty by a dead-window transition.
 */
class NotificationRelay(
    private val context: Context,
    private val settings: SettingsRepositoryImpl,
    private val client: HerdrClient,
    private val scope: CoroutineScope,
) {
    /** Last notified status plus the ids needed to retract it (ids carry status). */
    private data class NotifiedState(
        val status: AgentStatus,
        val workspaceId: String?,
        val tabId: String?,
    )

    // Concurrent: the event path and the snapshot-diff path run on
    // Dispatchers.Default (multi-threaded); a plain HashMap races (lost
    // dedupe = double alerts, torn reads = skipped alerts, CME = dead path).
    private val lastNotified = java.util.concurrent.ConcurrentHashMap<String, NotifiedState>()
    private var seeded = false

    init {
        scope.launch {
            client.events.collect { event ->
                val paneId = event.paneId
                if (!event.isAlertWorthy || paneId == null) return@collect
                val status = event.status ?: return@collect
                val prev = lastNotified[paneId]
                if (prev?.status == status) return@collect
                // Leave-status first: a done/blocked alert for this pane is
                // now stale (read-ack elsewhere, resolved, re-driven). Retract
                // before recording so a crash between the two can only leave a
                // stale alert, never lose a fresh one.
                if (prev != null && prev.status != status) {
                    HerdrNotifications.dismissPane(
                        context, prev.workspaceId, prev.tabId, paneId,
                    )
                }
                lastNotified[paneId] = NotifiedState(status, event.workspaceId, event.tabId)
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
            // Values are "wire|workspaceId|tabId" (pre-dismiss builds wrote bare
            // wire — those parse with null ids and simply skip retraction).
            val persisted = runCatching { settings.loadNotified() }.getOrDefault(emptyMap())
                .mapValues { parseNotified(it.value) }
            client.state.collect { state ->
                val snapshot = state.snapshot ?: return@collect
                val current = snapshot.workspaces
                    .flatMap { it.tabs }
                    .flatMap { it.panes }
                    .associate { it.id to it.status }
                if (!seeded) {
                    previous = current
                    if (persisted.isEmpty()) {
                        for (pane in snapshot.workspaces.flatMap { it.tabs }.flatMap { it.panes }) {
                            lastNotified[pane.id] =
                                NotifiedState(pane.status, pane.workspaceId, pane.tabId)
                        }
                    } else {
                        for (pane in snapshot.workspaces.flatMap { it.tabs }.flatMap { it.panes }) {
                            val old = persisted[pane.id]
                            lastNotified[pane.id] =
                                NotifiedState(pane.status, pane.workspaceId, pane.tabId)
                            if (old != null && old.status != pane.status) {
                                // Changed while dead. Retract the stale shade
                                // entry first (it survives process death —
                                // e.g. done read on desktop, now idle here),
                                // then alert the new state if worthy.
                                HerdrNotifications.dismissPane(
                                    context, old.workspaceId, old.tabId, pane.id,
                                )
                                // Advanced while dead (working->done with no live
                                // process to see it): alert now, exactly once —
                                // lastNotified already records it, so the event
                                // path and later diffs skip the duplicate.
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
                        // Vanished while dead (closed tab): retract whatever the
                        // shade still holds for panes that no longer exist.
                        for ((paneId, old) in persisted) {
                            if (!current.containsKey(paneId)) {
                                HerdrNotifications.dismissPane(
                                    context, old.workspaceId, old.tabId, paneId,
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
                            lastNotified[pane.id]?.status != pane.status
                        ) {
                            HerdrNotifications.dismissPane(
                                context, pane.workspaceId, pane.tabId, pane.id,
                            )
                            lastNotified[pane.id] =
                                NotifiedState(pane.status, pane.workspaceId, pane.tabId)
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
                // Retract first — a closed-while-done tab must not leave its
                // alert in the shade forever.
                val vanished = lastNotified.keys - current.keys
                for (paneId in vanished) {
                    val old = lastNotified.remove(paneId)
                    if (old != null) {
                        HerdrNotifications.dismissPane(
                            context, old.workspaceId, old.tabId, paneId,
                        )
                    }
                }
                if (vanished.isNotEmpty()) persist()
                previous = current
            }
        }
    }

    /** Best-effort write-through: transitions are rare, DataStore writes are cheap. */
    private fun persist() {
        val copy = HashMap(lastNotified).mapValues {
            "${it.value.status.wire}|${it.value.workspaceId}|${it.value.tabId}"
        }
        scope.launch {
            runCatching { settings.saveNotified(copy) }
        }
    }

    private fun parseNotified(raw: String): NotifiedState {
        val parts = raw.split('|')
        return NotifiedState(
            status = AgentStatus.fromWire(parts.getOrNull(0)),
            workspaceId = parts.getOrNull(1)?.takeUnless { it == "null" },
            tabId = parts.getOrNull(2)?.takeUnless { it == "null" },
        )
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
