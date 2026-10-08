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
 * alive (foreground or cached process — there is no foreground service, so a dead
 * process gets no alerts; documented, not a bug).
 *
 * Two paths, one dedupe key (paneId -> last notified status):
 * - event flow (exact): every daemon event, no coalescing loss — a fast
 *   working->done->working still alerts the done.
 * - snapshot-diff backstop: refetch jumps (cold start, reconnect, gap) carry
 *   transitions with no event; the diff catches them.
 * Either path skips when the pane's status already notified (prevents double
 * alerts for the same transition). First snapshot seeds silently (no launch storm).
 */
class NotificationRelay(
    private val context: Context,
    private val settings: SettingsRepositoryImpl,
    private val client: HerdrClient,
    scope: CoroutineScope,
) {
    private val lastNotified = mutableMapOf<String, AgentStatus>()
    private var seeded = false

    init {
        scope.launch {
            client.events.collect { event ->
                val paneId = event.paneId
                if (!event.isAlertWorthy || paneId == null) return@collect
                val status = event.status ?: return@collect
                if (lastNotified[paneId] == status) return@collect
                lastNotified[paneId] = status
                maybeNotify(event)
            }
        }
        scope.launch {
            var previous: Map<String, AgentStatus> = emptyMap()
            client.state.collect { state ->
                val snapshot = state.snapshot ?: return@collect
                val current = snapshot.workspaces
                    .flatMap { it.tabs }
                    .flatMap { it.panes }
                    .associate { it.id to it.status }
                if (!seeded) {
                    // First snapshot: seed both maps silently. Cold start and
                    // process restart must not replay old dones as new alerts.
                    previous = current
                    lastNotified.putAll(current)
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

    private fun maybeNotify(event: SemanticEvent, workspaceLabel: String? = null, tabLabel: String? = null) {
        val s = settings.settings.value
        if (!HerdrNotifications.shouldNotify(event, s.notifyDone, s.notifyBlocked)) return
        val snapshot = client.state.value.snapshot
        val ws = workspaceLabel ?: snapshot?.workspace(event.workspaceId)?.label
        val tab = tabLabel ?: snapshot?.tab(event.tabId)?.displayLabel
        HerdrNotifications.notify(context, event, ws, tab)
    }
}
