package dev.herdr.mobile.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One semantic change forwarded by the daemon.
 *
 * Every event carries a strictly increasing [seq]. Clients apply events in order; a jump in
 * [seq] means the client missed one or more events and must refetch the snapshot instead of
 * patching a cache it can no longer trust.
 */
@Serializable
data class SemanticEvent(
    val seq: Long,
    val type: String,
    val at: String? = null,
    @SerialName("workspaceId") val workspaceId: String? = null,
    @SerialName("tabId") val tabId: String? = null,
    @SerialName("paneId") val paneId: String? = null,
    val status: AgentStatus? = null,
    val label: String? = null,
    val message: String? = null,
    val revision: Long? = null,
    val detail: JsonObject? = null,
) {
    val isSnapshotInvalidating: Boolean
        get() = type == TYPE_SNAPSHOT_REQUIRED || type == TYPE_STREAM_READY

    /**
     * Creation/removal events carry entities the client cannot reconstruct locally; the only
     * honest reaction is an authoritative refetch, never a hand-built patch.
     */
    val isStructural: Boolean
        get() = type == TYPE_WORKSPACE_CREATED ||
            type == TYPE_WORKSPACE_CLOSED ||
            type == TYPE_TAB_CREATED ||
            type == TYPE_TAB_CLOSED ||
            type == TYPE_PANE_CREATED ||
            type == TYPE_PANE_CLOSED

    /** Status transitions that a user would want to know about while away. */
    val isAlertWorthy: Boolean
        get() = type == TYPE_PANE_STATUS_CHANGED ||
            type == TYPE_TAB_STATUS_CHANGED ||
            type == TYPE_WORKSPACE_STATUS_CHANGED

    companion object {
        const val TYPE_STREAM_READY = "stream.ready"
        const val TYPE_SNAPSHOT_REQUIRED = "snapshot.required"

        const val TYPE_WORKSPACE_CREATED = "workspace.created"
        const val TYPE_WORKSPACE_CLOSED = "workspace.closed"
        const val TYPE_WORKSPACE_RENAMED = "workspace.renamed"
        const val TYPE_WORKSPACE_STATUS_CHANGED = "workspace.status_changed"

        const val TYPE_TAB_CREATED = "tab.created"
        const val TYPE_TAB_CLOSED = "tab.closed"
        const val TYPE_TAB_RENAMED = "tab.renamed"
        const val TYPE_TAB_STATUS_CHANGED = "tab.status_changed"

        const val TYPE_PANE_CREATED = "pane.created"
        const val TYPE_PANE_CLOSED = "pane.closed"
        const val TYPE_PANE_STATUS_CHANGED = "pane.status_changed"
    }
}