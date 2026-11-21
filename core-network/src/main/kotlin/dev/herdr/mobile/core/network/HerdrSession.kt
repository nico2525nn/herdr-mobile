package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.SessionSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * What a terminal screen needs from the session client.
 *
 * The concrete [HerdrClient] owns the websocket, the snapshot cache and the
 * reconnect policy — none of which a screen's state machine should have to
 * fake. Depending on this narrow contract lets the view-model's target/attach
 * rules be tested against a scripted session instead of a live daemon.
 */
interface HerdrSession {

    /** Snapshot + link state, as the daemon reports it. */
    val state: StateFlow<HerdrClientState>

    /** Bumped whenever the daemon target (host/pane routing) changes. */
    val targetGeneration: Long

    /** Bumped whenever the link is redialled. */
    val linkGeneration: Long

    /** Ask for a fresh snapshot (best effort). */
    fun refresh()

    /** Nudge a dead/backoff link to reconnect now. */
    fun kick()

    /** Open the terminal stream for [paneId]; throws [DaemonException] when offline. */
    fun openTerminal(
        paneId: String,
        cols: Int,
        rows: Int,
        takeover: Boolean = false,
    ): TerminalConnection

    suspend fun focusTab(tabId: String)

    /** Focus exactly this pane (read-ack for split tabs; tab focus is not enough). */
    suspend fun focusPane(paneId: String)

    suspend fun focusPaneAt(paneId: String, targetGeneration: Long) {
        if (this.targetGeneration != targetGeneration) return
        focusPane(paneId)
    }

    suspend fun createTab(workspaceId: String, label: String?, focus: Boolean = true): String

    suspend fun closeTab(tabId: String)

    suspend fun closeTabAt(tabId: String, targetGeneration: Long) {
        if (this.targetGeneration != targetGeneration) return
        closeTab(tabId)
    }

    suspend fun renameTab(tabId: String, label: String)

    suspend fun renameTabAt(tabId: String, label: String, targetGeneration: Long) {
        if (this.targetGeneration != targetGeneration) return
        renameTab(tabId, label)
    }

    suspend fun createWorkspace(label: String?, cwd: String?, focus: Boolean = true): String

    suspend fun closeWorkspace(workspaceId: String)

    /** Close only if the target that produced [workspaceId] is still current. */
    suspend fun closeWorkspaceAt(workspaceId: String, targetGeneration: Long) {
        if (this.targetGeneration != targetGeneration) return
        closeWorkspace(workspaceId)
    }

    suspend fun renameWorkspace(workspaceId: String, label: String)

    suspend fun renameWorkspaceAt(
        workspaceId: String,
        label: String,
        targetGeneration: Long,
    ) {
        if (this.targetGeneration != targetGeneration) return
        renameWorkspace(workspaceId, label)
    }

    suspend fun moveTab(tabId: String, insertIndex: Long)

    suspend fun moveWorkspace(workspaceId: String, insertIndex: Long)

    /** Reorder only within the target that produced [workspaceId]. */
    suspend fun moveWorkspaceAt(
        workspaceId: String,
        insertIndex: Long,
        targetGeneration: Long,
    ) {
        if (this.targetGeneration != targetGeneration) return
        moveWorkspace(workspaceId, insertIndex)
    }

    suspend fun interrupt(paneId: String)

    suspend fun herdrUpdateStatus(): dev.herdr.mobile.core.model.HerdrUpdate

    suspend fun runHerdrUpdate(): String

    /** Latest cached snapshot, or null before the first sync. */
    val snapshot: SessionSnapshot? get() = state.value.snapshot
}
