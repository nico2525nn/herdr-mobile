package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.AgentReport
import dev.herdr.mobile.core.model.AgentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The session's command surface: one REST round trip per call, no caching, no retry.
 *
 * Every call resolves the endpoint installed by the current link at call time, so commands
 * follow the same target the snapshot came from.
 */
internal class SessionCommands(
    private val state: LinkState,
    private val transport: DaemonTransport,
) {
    private fun api(): DaemonApi =
        transport.api(state.endpoint ?: throw DaemonException("not_connected", "No endpoint is open"))

    suspend fun sendInput(paneId: String, text: String) =
        withContext(Dispatchers.IO) { api().sendInput(paneId, text) }

    suspend fun interrupt(paneId: String) =
        withContext(Dispatchers.IO) { api().interrupt(paneId) }

    suspend fun reportAgent(paneId: String, status: AgentStatus, message: String?, agent: String? = null) =
        withContext(Dispatchers.IO) {
            api().reportAgent(AgentReport(paneId = paneId, status = status, message = message, agent = agent))
        }

    suspend fun createTab(workspaceId: String, label: String?): String =
        withContext(Dispatchers.IO) { api().createTab(workspaceId, label) }

    suspend fun closeTab(tabId: String) =
        withContext(Dispatchers.IO) { api().closeTab(tabId) }

    suspend fun focusTab(tabId: String) =
        withContext(Dispatchers.IO) { api().focusTab(tabId) }

    suspend fun renameTab(tabId: String, label: String) =
        withContext(Dispatchers.IO) { api().renameTab(tabId, label) }

    /** [focus] = false creates a workspace without moving the desktop's focus. */
    suspend fun createWorkspace(label: String?, cwd: String?, focus: Boolean = true): String =
        withContext(Dispatchers.IO) { api().createWorkspace(label, cwd, focus) }

    suspend fun renameWorkspace(workspaceId: String, label: String) =
        withContext(Dispatchers.IO) { api().renameWorkspace(workspaceId, label) }

    suspend fun closeWorkspace(workspaceId: String) =
        withContext(Dispatchers.IO) { api().closeWorkspace(workspaceId) }

    suspend fun moveTab(tabId: String, insertIndex: Long) =
        withContext(Dispatchers.IO) { api().moveTab(tabId, insertIndex) }

    suspend fun moveWorkspace(workspaceId: String, insertIndex: Long) =
        withContext(Dispatchers.IO) { api().moveWorkspace(workspaceId, insertIndex) }

    suspend fun resizePane(paneId: String, cols: Int, rows: Int) =
        withContext(Dispatchers.IO) { api().resizePane(paneId, cols, rows) }

    /** Fresh update check (network hit). Snapshot block stays the source of truth. */
    suspend fun herdrUpdateStatus() =
        withContext(Dispatchers.IO) { api().herdrUpdateStatus() }

    /** Run the install; returns the updater's own log on success. */
    suspend fun runHerdrUpdate(): String =
        withContext(Dispatchers.IO) { api().runHerdrUpdate() }
}
