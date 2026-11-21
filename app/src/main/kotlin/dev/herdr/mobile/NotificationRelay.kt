package dev.herdr.mobile

import android.content.Context
import dev.herdr.mobile.core.model.AgentStatus
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.SemanticEvent
import dev.herdr.mobile.core.model.UiFocusState
import dev.herdr.mobile.core.network.HerdrClient
import dev.herdr.mobile.notifications.HerdrNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        /** Seq of the live event that recorded this; -1 for persisted state. */
        val seq: Long = -1,
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
    /** Do not overwrite the persisted map before the first seed merges it. */
    private var persistenceReady = false
    @Volatile private var resetEpoch = 0
    // Separate cursors: a snapshot that jumps ahead must not reject the
    // short DONE event it skipped. Events still reject snapshots older than
    // the newest event, so a late REST payload cannot regress the state.
    private val eventCursor = NotificationCursor()
    private val snapshotCursor = NotificationCursor()
    private var eventHigh = -1L
    private var cursorEpoch = -1L

    init {
        scope.launch {
            // Turning a category off must retract alerts already in the
            // shade; they never transition again, so the status path cannot.
            settings.settings
                .map { Triple(it.backgroundMonitoring, it.notifyDone, it.notifyBlocked) }
                .distinctUntilChanged()
                .collect { (monitoring, done, blocked) ->
                    synchronized(transitionLock) {
                        for ((paneId, state) in lastNotified) {
                            if (shouldRetractAlert(state.status, monitoring, done, blocked)) {
                                HerdrNotifications.dismissPane(context, state.workspaceId, paneId)
                            }
                        }
                    }
                }
        }
        scope.launch {
            // Latest snapshot wins; an older queued write must not land after
            // a newer one and resurrect a stale status.
            persistRequests.filterNotNull().collectLatest { runCatching { settings.saveNotified(it) } }
        }
        scope.launch {
            client.stampedEvents.collect { stamped ->
                val event = stamped.event
                val paneId = event.paneId
                val status = event.status
                if (paneId == null || status == null) {
                    // Still advance the cursor for non-status events; the
                    // transition path does seq + notification state in one
                    // critical section.
                    synchronized(transitionLock) {
                        acceptEventLocked(event.seq, stamped.connectionEpoch)
                    }
                    return@collect
                }
                // Process working/idle too, not only alert-worthy statuses:
                // a DONE alert must be retracted as soon as the pane leaves
                // done, even if no snapshot diff observed the intermediate.
                transitionAndNotify(
                    paneId, status, event.workspaceId, event,
                    connectionEpoch = stamped.connectionEpoch,
                    fromEvent = true,
                )
            }
        }
        scope.launch {
            var seenEpoch = resetEpoch
            // Persisted map from the last process: diffed at seed so dead-window
            // completions alert once instead of being blanket-suppressed. Fresh
            // installs read empty — but then every existing done would alert on
            // first launch (launch storm), so an EMPTY persisted map still seeds
            // silently; only panes that ADVANCED vs a non-empty map alert.
            // Values are "wire|workspaceId|tabId" (pre-dismiss builds wrote bare
            // wire — those parse with null ids and simply skip retraction).
            client.state.collect { state ->
                // A snapshot left behind during a redial is not authoritative;
                // processing it under the new connection epoch would reset the
                // cursor and could replay stale alerts.
                if (state.connection != ConnectionState.CONNECTED) return@collect
                val snapshot = state.snapshot ?: return@collect
                val epoch = resetEpoch
                // The persisted map belongs to the last process and loading it
                // suspends. Load it outside the lock, then re-check the epoch,
                // seq and live map under the lock before applying. The old code
                // admitted the seq first, so a slower snapshot could overwrite
                // a newer event that landed while the load was in flight.
                val needsSeedLoad = synchronized(transitionLock) {
                    if (epoch != seenEpoch) {
                        seenEpoch = epoch
                        seeded = false
                    }
                    !seeded && seenEpoch == 0
                }
                val persisted = if (needsSeedLoad) {
                    runCatching { settings.loadNotified() }.getOrDefault(emptyMap())
                        .mapValues { parseNotified(it.value) }
                } else {
                    emptyMap()
                }
                synchronized(transitionLock) {
                    if (epoch != resetEpoch) return@collect
                    if (state.connectionEpoch != client.state.value.connectionEpoch) {
                        return@collect
                    }
                    if (!acceptSnapshotLocked(snapshot.seq, state.connectionEpoch)) return@collect
                    val current = snapshot.workspaces
                        .flatMap { it.tabs }
                        .flatMap { it.panes }
                        .associate { it.id to it.status }
                    if (!seeded) {
                        // After a host switch the persisted map belongs to the
                        // old host; seed silently instead of diffing two hosts.
                        for (pane in snapshot.workspaces.flatMap { it.tabs }.flatMap { it.panes }) {
                            val live = lastNotified[pane.id]
                            val old = persisted[pane.id]
                            if (live != null) {
                                // A live event processed while the persisted
                                // map was loading is newer unless this snapshot
                                // has a higher seq. Never overwrite it blindly:
                                // that left a DONE alert alive when the newer
                                // snapshot said working.
                                if (live.seq > snapshot.seq) continue
                                // Even when a live event is authoritative,
                                // retract a different process-death status so
                                // a stale DONE does not survive the seed.
                                if (old != null && old.status != pane.status &&
                                    (old.status == AgentStatus.DONE || old.status == AgentStatus.BLOCKED)
                                ) {
                                    HerdrNotifications.dismissPane(context, old.workspaceId, pane.id)
                                }
                                if (live.status != pane.status) {
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
                                        connectionEpoch = state.connectionEpoch,
                                    )
                                } else {
                                    lastNotified[pane.id] = live.copy(seq = snapshot.seq)
                                }
                                continue
                            }
                            lastNotified[pane.id] =
                                NotifiedState(pane.status, pane.workspaceId, snapshot.seq)
                            if (old != null && old.status != pane.status) {
                                // Changed while dead: retract the stale shade
                                // entry, then alert the new state once.
                                HerdrNotifications.dismissPane(context, old.workspaceId, pane.id)
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
                        for ((paneId, old) in persisted) {
                            if (!current.containsKey(paneId)) {
                                HerdrNotifications.dismissPane(
                                    context, old.workspaceId, paneId,
                                )
                            }
                        }
                        persistenceReady = settings.storageHealthy
                        persistLocked()
                        seeded = true
                        val currentSettings = settings.settings.value
                        for ((paneId, notified) in lastNotified) {
                            if (shouldRetractAlert(
                                    notified.status,
                                    currentSettings.backgroundMonitoring,
                                    currentSettings.notifyDone,
                                    currentSettings.notifyBlocked,
                                )
                            ) {
                                HerdrNotifications.dismissPane(context, notified.workspaceId, paneId)
                            }
                        }
                        return@collect
                    }
                    // Compare against what was actually notified, not the
                    // previous snapshot: an event may have posted DONE and a
                    // later snapshot may skip straight back to working. A
                    // snapshot-only diff would see working == previous working
                    // and leave the stale DONE alert in the shade.
                    for (pane in snapshot.workspaces.flatMap { it.tabs }.flatMap { it.panes }) {
                        if (lastNotified[pane.id]?.status == pane.status) continue
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
                            connectionEpoch = state.connectionEpoch,
                        )
                    }
                    // Panes that vanished take their dedupe keys with them.
                    val vanished = lastNotified.keys - current.keys
                    for (paneId in vanished) {
                        val old = lastNotified.remove(paneId)
                        if (old != null) {
                            HerdrNotifications.dismissPane(context, old.workspaceId, paneId)
                        }
                    }
                    if (vanished.isNotEmpty()) persistLocked()
                }
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
        connectionEpoch: Long,
        fromEvent: Boolean = false,
    ): Boolean = synchronized(transitionLock) {
        val accepted = if (fromEvent) {
            acceptEventLocked(event.seq, connectionEpoch)
        } else {
            acceptSnapshotLocked(event.seq, connectionEpoch)
        }
        if (!accepted) return@synchronized false
        val prev = lastNotified[paneId]
        if (prev?.status == status) return@synchronized false
        // Leave-status first: a done/blocked alert for this pane is now stale.
        if (prev != null) {
            HerdrNotifications.dismissPane(context, prev.workspaceId, paneId)
        }
        lastNotified[paneId] = NotifiedState(status, workspaceId, event.seq)
        persistLocked()
        maybeNotify(event, workspaceLabel, tabLabel)
        if (fromEvent) {
            // A snapshot may already have folded past this event (a short
            // working->done->working). Apply its newer state now rather than
            // leaving a DONE alert that no later event will retract.
            val state = client.state.value
            val snapshot = state.snapshot
            if (snapshot != null && snapshot.seq >= event.seq &&
                state.connectionEpoch == connectionEpoch
            ) {
                val currentPane = snapshot.pane(paneId)
                if (currentPane == null) {
                    // The pane was removed before the event was consumed;
                    // retract the transient alert and drop its dedupe key.
                    if (lastNotified[paneId]?.status == status) {
                        HerdrNotifications.dismissPane(context, workspaceId, paneId)
                        lastNotified.remove(paneId)
                        persistLocked()
                    }
                } else if (lastNotified[paneId]?.status != currentPane.status) {
                    val pane = currentPane
                    run {
                        transitionAndNotify(
                            paneId,
                            pane.status,
                            pane.workspaceId,
                            SemanticEvent(
                                seq = snapshot.seq,
                                type = SemanticEvent.TYPE_PANE_STATUS_CHANGED,
                                workspaceId = pane.workspaceId,
                                tabId = pane.tabId,
                                paneId = pane.id,
                                status = pane.status,
                            ),
                            snapshot.workspace(pane.workspaceId)?.label,
                            snapshot.tab(pane.tabId)?.displayLabel,
                            connectionEpoch = connectionEpoch,
                            fromEvent = false,
                        )
                    }
                }
            }
        }
        true
    }

    /** Host switch: ids from the previous host must not leak into this one. */
    fun resetForTargetChange() {
        synchronized(transitionLock) {
            eventCursor.reset()
            snapshotCursor.reset()
            eventHigh = -1L
            cursorEpoch = -1L
            // Cancel the old host's shade entries first: without events from
            // that host they can never be retracted later.
            for ((paneId, state) in lastNotified) {
                HerdrNotifications.dismissPane(context, state.workspaceId, paneId)
            }
            resetEpoch++
            lastNotified.clear()
            seeded = false
            persistenceReady = settings.storageHealthy
            persistLocked()
        }
    }

    private fun acceptEventLocked(seq: Long, connectionEpoch: Long): Boolean {
        if (connectionEpoch != client.state.value.connectionEpoch) return false
        if (cursorEpoch != connectionEpoch) {
            cursorEpoch = connectionEpoch
            eventHigh = -1L
            eventCursor.reset()
            snapshotCursor.reset()
        }
        if (!eventCursor.accept(seq, connectionEpoch)) return false
        eventHigh = eventHigh.coerceAtLeast(seq)
        return true
    }

    private fun acceptSnapshotLocked(seq: Long, connectionEpoch: Long): Boolean {
        if (connectionEpoch != client.state.value.connectionEpoch) return false
        if (cursorEpoch != connectionEpoch) {
            cursorEpoch = connectionEpoch
            eventHigh = -1L
            eventCursor.reset()
            snapshotCursor.reset()
        }
        // A snapshot older than an already accepted event would regress the
        // notification state; only accept the newer authoritative seq.
        if (seq < eventHigh) return false
        return snapshotCursor.accept(seq, connectionEpoch)
    }

    /** Write-through: transitions are rare; a conflating writer keeps order. */
    private fun persistLocked() {
        // Before the first seed, lastNotified is only the live delta. Writing
        // it would erase other panes from the process-death map.
        if (!persistenceReady) return
        val copy = HashMap(lastNotified).mapValues {
            "${it.value.status.wire}|${it.value.workspaceId}"
        }
        persistRequests.value = copy
    }

    private fun parseNotified(raw: String): NotifiedState {
        // Split only at the first pipe: a where-derived workspace id may
        // itself contain '|'. The old split('|') truncated it on restart.
        val separator = raw.indexOf('|')
        val status = if (separator >= 0) raw.substring(0, separator) else raw
        val workspace = if (separator >= 0) raw.substring(separator + 1) else null
        return NotifiedState(
            status = AgentStatus.fromWire(status),
            workspaceId = workspace?.takeUnless { it == "null" || it.isEmpty() },
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

/**
 * Monotonic event/snapshot cursor. A daemon restart can lower seq back to 0
 * on the same target, so a new link generation starts a fresh cursor instead
 * of rejecting everything until the old high-water mark is passed.
 */
internal class NotificationCursor {
    private var newest = -1L
    private var link = -1L

    fun accept(seq: Long, connectionEpoch: Long): Boolean {
        if (connectionEpoch != link) {
            link = connectionEpoch
            newest = -1L
        }
        if (seq < newest) return false
        newest = seq
        return true
    }

    fun reset() {
        newest = -1L
        link = -1L
    }
}

/** A posted alert is stale when its category (or monitoring) was turned off. */
internal fun shouldRetractAlert(
    status: AgentStatus,
    monitoring: Boolean,
    notifyDone: Boolean,
    notifyBlocked: Boolean,
): Boolean = !monitoring ||
    (status == AgentStatus.DONE && !notifyDone) ||
    (status == AgentStatus.BLOCKED && !notifyBlocked)
