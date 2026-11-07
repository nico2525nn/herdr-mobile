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

    suspend fun createTab(workspaceId: String, label: String?, focus: Boolean = true): String

    suspend fun closeTab(tabId: String)

    suspend fun renameTab(tabId: String, label: String)

    suspend fun createWorkspace(label: String?, cwd: String?, focus: Boolean = true): String

    suspend fun closeWorkspace(workspaceId: String)

    suspend fun renameWorkspace(workspaceId: String, label: String)

    suspend fun moveTab(tabId: String, insertIndex: Long)

    suspend fun moveWorkspace(workspaceId: String, insertIndex: Long)

    suspend fun interrupt(paneId: String)

    suspend fun herdrUpdateStatus(): dev.herdr.mobile.core.model.HerdrUpdate

    suspend fun runHerdrUpdate(): String

    /** Latest cached snapshot, or null before the first sync. */
    val snapshot: SessionSnapshot? get() = state.value.snapshot
}
