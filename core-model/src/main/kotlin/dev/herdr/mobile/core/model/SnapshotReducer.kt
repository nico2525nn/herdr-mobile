package dev.herdr.mobile.core.model

/**
 * Applies semantic events to a cached [SessionSnapshot].
 *
 * The daemon guarantees a strictly increasing [SemanticEvent.seq]. A client therefore only
 * keeps applying events while they arrive contiguously; the first gap invalidates the cache
 * and forces a snapshot refetch, because a patched cache with a hole in it can never be
 * trusted for status colours or notifications.
 */
object SnapshotReducer {

    sealed interface Outcome {
        /** The event was folded in. */
        data class Applied(val snapshot: SessionSnapshot) : Outcome

        /** Sequence gap detected. [expectedSeq] is the next sequence number the client needs. */
        data class Gap(val expectedSeq: Long, val receivedSeq: Long) : Outcome

        /** The event explicitly demands a refetch. */
        data object Refetch : Outcome
    }

    fun apply(snapshot: SessionSnapshot, event: SemanticEvent): Outcome {
        if (event.isSnapshotInvalidating) return Outcome.Refetch
        if (event.seq <= snapshot.seq) return Outcome.Applied(snapshot)
        if (event.seq != snapshot.seq + 1) {
            return Outcome.Gap(expectedSeq = snapshot.seq + 1, receivedSeq = event.seq)
        }
        if (event.isStructural) return Outcome.Refetch
        return Outcome.Applied(fold(snapshot, event).copy(seq = event.seq))
    }

    private fun fold(snapshot: SessionSnapshot, event: SemanticEvent): SessionSnapshot {
        val workspaces = when (event.type) {
            SemanticEvent.TYPE_WORKSPACE_CLOSED ->
                snapshot.workspaces.filterNot { it.id == event.workspaceId }

            SemanticEvent.TYPE_WORKSPACE_RENAMED ->
                snapshot.workspaces.map {
                    if (it.id == event.workspaceId && event.label != null) it.copy(label = event.label) else it
                }

            SemanticEvent.TYPE_WORKSPACE_STATUS_CHANGED ->
                snapshot.workspaces.map { if (it.id == event.workspaceId) it.withStatus(event) else it }

            SemanticEvent.TYPE_TAB_STATUS_CHANGED ->
                snapshot.workspaces.map { w ->
                    if (w.id == event.workspaceId) w.withTabStatus(event) else w
                }

            SemanticEvent.TYPE_PANE_STATUS_CHANGED ->
                snapshot.workspaces.map { w ->
                    if (w.id == event.workspaceId) w.withPaneStatus(event) else w
                }

            SemanticEvent.TYPE_TAB_RENAMED ->
                snapshot.workspaces.map { w ->
                    if (w.id == event.workspaceId) {
                        w.copy(tabs = w.tabs.map { t ->
                            if (t.id == event.tabId && event.label != null) t.copy(label = event.label) else t
                        })
                    } else {
                        w
                    }
                }

            else -> {
                // Focus, layout, metadata and other non-structural, non-status events change
                // nothing this client renders; keep the snapshot and advance the cursor.
                return snapshot.copy(
                    revision = event.revision ?: snapshot.revision,
                )
            }
        }
        return snapshot.copy(revision = event.revision ?: snapshot.revision, workspaces = workspaces)
    }

    private fun Workspace.withStatus(event: SemanticEvent): Workspace =
        copy(status = event.status ?: status)

    private fun Workspace.withTabStatus(event: SemanticEvent): Workspace {
        val tabs = tabs.map { tab ->
            if (tab.id == event.tabId) tab.copy(status = event.status ?: tab.status) else tab
        }
        return copy(
            tabs = tabs,
            status = AgentStatus.rollUp(tabs.map { it.status }) ?: status,
        )
    }

    private fun Workspace.withPaneStatus(event: SemanticEvent): Workspace {
        var touched = false
        val tabs = tabs.map { tab ->
            var tabTouched = false
            val panes = tab.panes.map { pane ->
                if (pane.id == event.paneId) {
                    touched = true
                    tabTouched = true
                    pane.copy(status = event.status ?: pane.status)
                } else {
                    pane
                }
            }
            if (!tabTouched) {
                tab
            } else {
                tab.copy(panes = panes, status = AgentStatus.rollUp(panes.map { it.status }) ?: tab.status)
            }
        }
        if (!touched) return this
        return copy(
            tabs = tabs,
            status = AgentStatus.rollUp(tabs.map { it.status }) ?: status,
        )
    }
}