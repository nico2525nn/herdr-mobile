package dev.herdr.mobile

import android.content.Context
import dev.herdr.mobile.core.model.SemanticEvent
import dev.herdr.mobile.core.network.HerdrClient
import dev.herdr.mobile.notifications.HerdrNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Watches alert-worthy semantic events and posts system notifications while the app is
 * alive. Kept deliberately thin: filtering lives in [HerdrNotifications.shouldNotify], and
 * the payload carries ids only — never terminal output.
 */
class NotificationRelay(
    private val context: Context,
    private val settings: SettingsRepositoryImpl,
    private val client: HerdrClient,
    scope: CoroutineScope,
) {
    private var lastSeq = -1L

    init {
        scope.launch {
            client.state
                .map { it.snapshot to it.connection }
                .distinctUntilChanged { a, b -> a.first?.seq == b.first?.seq }
                .collect { (snapshot, _) ->
                    // Snapshot-level observation only fires on new revisions; per-event
                    // observation below is the real trigger. This collector keeps the relay
                    // alive across client restarts without extra bookkeeping.
                    snapshot?.let { lastSeq = maxOf(lastSeq, it.seq) }
                }
        }
        scope.launch {
            // Event-driven path: poll the client's event cursor through snapshot seq.
            // The client folds events internally, so the relay diffs status transitions by
            // comparing consecutive snapshots pane by pane.
            var previous: Map<String, dev.herdr.mobile.core.model.AgentStatus> = emptyMap()
            client.state.collect { state ->
                val snapshot = state.snapshot ?: return@collect
                val current = snapshot.workspaces
                    .flatMap { it.tabs }
                    .flatMap { it.panes }
                    .associate { it.id to it.status }
                if (previous.isNotEmpty()) {
                    val settings = settings.settings.value
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
                            if (HerdrNotifications.shouldNotify(
                                    event,
                                    settings.notifyDone,
                                    settings.notifyBlocked,
                                    settings.notifyFailed,
                                )
                            ) {
                                HerdrNotifications.notify(
                                    context,
                                    event,
                                    workspace?.label,
                                    tab?.displayLabel,
                                )
                            }
                        }
                    }
                }
                previous = current
            }
        }
    }
}
