package dev.herdr.mobile

import android.content.Context
import dev.herdr.mobile.core.model.AgentStatus
import dev.herdr.mobile.core.model.SemanticEvent
import dev.herdr.mobile.core.model.UiFocusState
import dev.herdr.mobile.core.network.HerdrClient
import dev.herdr.mobile.notifications.HerdrNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
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
    )

    // Concurrent: the event path and the snapshot-diff path run on
    // Dispatchers.Default (multi-threaded); a plain HashMap races (lost
    // dedupe = double alerts, torn reads = skipped alerts, CME = dead path).
    private val lastNotified = java.util.concurrent.ConcurrentHashMap<String, NotifiedState>()
    // Serializes the two collectors' check->dismiss->record->notify sequence.
    // The event path fires before its snapshot is folded, so without this both
    // paths can read the same stale status and buzz twice for one transition.
    private val transitionLock = Any()
    private val persistRequests = MutableStateFlow<Map<String, String>?>(null)
    private var seeded = false
    @Volatile private var resetEpoch = 0

    init {
        scope.launch {
            // Latest snapshot wins; an older queued write must not land after
            // a newer one and resurrect a stale status.
            persistRequests.filterNotNull().collectLatest { runCatching { settings.saveNotified(it) } }
        }
        scope.launch {
            client.events.collect { event ->
                val paneId = event.paneId
                if (!event.isAlertWorthy || paneId == null) return@collect
                val status = event.status ?: return@collect
                transitionAndNotify(paneId, status, event.workspaceId, event)
            }
        }
        scope.launch {
            var previous: Map<String, AgentStatus> = emptyMap()
            var seenEpoch = resetEpoch
            // Persisted map from the last process: diffed at seed so dead-window
            // completions alert once instead of being blanket-suppressed. Fresh
            // installs read empty — but then every existing done would alert on
            // first launch (launch storm), so an EMPTY persisted map still seeds
            // silently; only panes that ADVANCED vs a non-empty map alert.
            // Values are "wire|workspaceId|tabId" (pre-dismiss builds wrote bare
            // wire — those parse with null ids and simply skip retraction).
            client.state.collect { state ->
                val snapshot = state.snapshot ?: return@collect
                if (resetEpoch != seenEpoch) {
                    seenEpoch = resetEpoch
                    seeded = false
                    previous = emptyMap()
                }
                val current = snapshot.workspaces
                    .flatMap { it.tabs }
                    .flatMap { it.panes }
                    .associate { it.id to it.status }
                if (!seeded) {
                    // After a host switch the persisted map belongs to the old
                    // host; seed silently instead of diffing two hosts' ids.
                    // Loaded outside the critical section (it suspends).
                    val persisted = if (seenEpoch == 0) {
                        runCatching { settings.loadNotified() }.getOrDefault(emptyMap())
                            .mapValues { parseNotified(it.value) }
                    } else {
                        emptyMap()
                    }
                    synchronized(transitionLock) {
                    previous = current
                    if (persisted.isEmpty()) {
                        for (pane in snapshot.workspaces.flatMap { it.tabs }.flatMap { it.panes }) {
                            lastNotified[pane.id] =
                                NotifiedState(pane.status, pane.workspaceId)
                        }
                    } else {
                        for (pane in snapshot.workspaces.flatMap { it.tabs }.flatMap { it.panes }) {
                            val old = persisted[pane.id]
                            lastNotified[pane.id] =
                                NotifiedState(pane.status, pane.workspaceId)
                            if (old != null && old.status != pane.status) {
                                // Changed while dead. Retract the stale shade
                                // entry first (it survives process death —
                                // e.g. done read on desktop, now idle here),
                                // then alert the new state if worthy.
                                HerdrNotifications.dismissPane(context, old.workspaceId, pane.id)
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
                                    context, old.workspaceId, paneId,
                                )
                            }
                        }
                    }
                    persistLocked()
                    seeded = true
                    return@collect
                    }
                }
                if (previous.isNotEmpty()) {
                    for (pane in snapshot.workspaces.flatMap { it.tabs }.flatMap { it.panes }) {
                        val old = previous[pane.id]
                        if (old != null && old != pane.status) {
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
                            transitionAndNotify(
                                pane.id,
                                pane.status,
                                pane.workspaceId,
                                event,
                                workspace?.label,
                                tab?.displayLabel,
                            )
                        }
                    }
                }
                // Panes that vanished take their dedupe keys with them: a
                // same-id pane re-created later is a new transition source.
                // Retract first — a closed-while-done tab must not leave its
                // alert in the shade forever.
                synchronized(transitionLock) {
                    val vanished = lastNotified.keys - current.keys
                    for (paneId in vanished) {
                        val old = lastNotified.remove(paneId)
                        if (old != null) {
                            HerdrNotifications.dismissPane(context, old.workspaceId, paneId)
                        }
                    }
                    if (vanished.isNotEmpty()) persistLocked()
                }
                previous = current
            }
        }
    }

    /**
     * Decide and apply one pane status transition atomically. Returns true when
     * this call was the one that recorded (and possibly posted) the status.
     */
    private fun transitionAndNotify(
        paneId: String,
        status: AgentStatus,
        workspaceId: String?,
        event: SemanticEvent,
        workspaceLabel: String? = null,
        tabLabel: String? = null,
    ): Boolean = synchronized(transitionLock) {
        val prev = lastNotified[paneId]
        if (prev?.status == status) return@synchronized false
        // Leave-status first: a done/blocked alert for this pane is now stale.
        if (prev != null) {
            HerdrNotifications.dismissPane(context, prev.workspaceId, paneId)
        }
        lastNotified[paneId] = NotifiedState(status, workspaceId)
        persistLocked()
        maybeNotify(event, workspaceLabel, tabLabel)
        true
    }

    /** Host switch: ids from the previous host must not leak into this one. */
    fun resetForTargetChange() {
        synchronized(transitionLock) {
            // Cancel the old host's shade entries first: without events from
            // that host they can never be retracted later.
            for ((paneId, state) in lastNotified) {
                HerdrNotifications.dismissPane(context, state.workspaceId, paneId)
            }
            resetEpoch++
            lastNotified.clear()
            seeded = false
            persistLocked()
        }
    }

    /** Write-through: transitions are rare; a conflating writer keeps order. */
    private fun persistLocked() {
        val copy = HashMap(lastNotified).mapValues {
            "${it.value.status.wire}|${it.value.workspaceId}"
        }
        persistRequests.value = copy
    }

    private fun parseNotified(raw: String): NotifiedState {
        val parts = raw.split('|')
        return NotifiedState(
            status = AgentStatus.fromWire(parts.getOrNull(0)),
            workspaceId = parts.getOrNull(1)?.takeUnless { it == "null" },
        )
    }

    private fun maybeNotify(event: SemanticEvent, workspaceLabel: String? = null, tabLabel: String? = null) {
        val s = settings.settings.value
        // Monitoring OFF = no alerts, even in the cached-process window where
        // the link is still up (or while UI is open — the user sees live dots).
        if (!s.backgroundMonitoring) return
        // The user is already looking at this pane: a heads-up + sound for the
        // very screen in front of them is noise. The status is still recorded,
        // so leaving it later does not re-alert a stale transition.
        if (UiFocusState.isShowing(event.paneId)) return
        if (!HerdrNotifications.shouldNotify(event, s.notifyDone, s.notifyBlocked)) return
        val snapshot = client.state.value.snapshot
        val ws = workspaceLabel ?: snapshot?.workspace(event.workspaceId)?.label
        val tab = tabLabel ?: snapshot?.tab(event.tabId)?.displayLabel
        HerdrNotifications.notify(context, event, ws, tab)
    }
}
