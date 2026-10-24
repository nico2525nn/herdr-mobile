package dev.herdr.mobile.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.network.HerdrClient
import dev.herdr.mobile.core.network.HerdrClientState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What Home renders. One screen, one state, no fake selection anywhere. */
data class HomeUiState(
    val connection: ConnectionState = ConnectionState.IDLE,
    val snapshot: SessionSnapshot? = null,
    val stale: Boolean = false,
    val error: String? = null,
    val lastSyncAt: Long = 0,
) {
    val workspaceCount: Int get() = snapshot?.workspaces?.size ?: 0
    val tabCount: Int get() = snapshot?.tabCount ?: 0
    val showOfflineBanner: Boolean
        get() = connection == ConnectionState.RECONNECTING ||
            connection == ConnectionState.CONNECTING ||
            (connection == ConnectionState.FAILED && snapshot == null)
    /** Latest verified Herdr release, when newer than the connected one. */
    val updateAvailable: String?
        get() {
            val update = snapshot?.update ?: return null
            val latest = update.latestVersion?.trim()?.ifEmpty { null } ?: return null
            val current = snapshot?.herdr?.version?.trim()?.ifEmpty { null } ?: return latest
            fun parts(v: String): List<Long> = v.trimStart('v').split('.').map {
                it.takeWhile(Char::isDigit).toLongOrNull() ?: 0
            }
            val a = parts(current)
            val b = parts(latest)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return if (y > x) latest else null
            }
            return null
        }
}

class HomeViewModel(
    private val client: HerdrClient,
) : ViewModel() {
    private val _message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun closeTab(tabId: String) {
        viewModelScope.launch {
            runCatching { client.closeTab(tabId) }
                .onFailure { _message.value = "Cannot close tab: ${it.message}" }
        }
    }

    fun renameTab(tabId: String, label: String) {
        viewModelScope.launch {
            runCatching { client.renameTab(tabId, label.trim()) }
                .onFailure { _message.value = "Cannot rename tab: ${it.message}" }
        }
    }

    // Workspace ops sync like tabs: the daemon emits a structural event and
    // every client refetches authoritatively. No local patching, no fake
    // optimistic card — the created workspace appears on the next snapshot
    // (same path as a workspace created on desktop or another phone).
    fun createWorkspace(label: String?, cwd: String?) {
        viewModelScope.launch {
            runCatching { client.createWorkspace(label?.trim()?.ifEmpty { null }, cwd?.trim()?.ifEmpty { null }) }
                .onFailure { _message.value = "Cannot create workspace: ${it.message}" }
        }
    }

    fun renameWorkspace(workspaceId: String, label: String) {
        viewModelScope.launch {
            runCatching { client.renameWorkspace(workspaceId, label.trim()) }
                .onFailure { _message.value = "Cannot rename workspace: ${it.message}" }
        }
    }

    /**
     * Drag-reorder: move [workspaceId] to visual position [toIndex]. No local
     * patching — the daemon emits workspace.moved and every client refetches
     * authoritatively (same path as a desktop reorder).
     */
    fun moveWorkspace(workspaceId: String, toIndex: Int) {
        val workspaces = uiState.value.snapshot?.workspaces ?: return
        val from = workspaces.indexOfFirst { it.id == workspaceId }
        if (from < 0 || from == toIndex) return
        viewModelScope.launch {
            runCatching {
                client.moveWorkspace(
                    workspaceId,
                    dev.herdr.mobile.core.model.Reorder.insertIndex(from, toIndex, workspaces.size),
                )
            }.onFailure {
                _message.value = "Cannot move workspace: ${it.message}"
            }
        }
    }

    fun closeWorkspace(workspaceId: String) {
        viewModelScope.launch {
            runCatching { client.closeWorkspace(workspaceId) }
                .onFailure { _message.value = "Cannot close workspace: ${it.message}" }
        }
    }

    private val _updateRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val updateRunning: StateFlow<Boolean> = _updateRunning

    /** Run the host update. Long call; the daemon re-checks and the pill clears on refetch. */
    fun runHerdrUpdate() {
        if (_updateRunning.value) return
        viewModelScope.launch {
            _updateRunning.value = true
            runCatching { client.runHerdrUpdate() }
                .onFailure { _message.value = "Update failed: ${it.message}" }
            _updateRunning.value = false
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    val uiState: StateFlow<HomeUiState> = client.state
        .map { s: HerdrClientState ->
            HomeUiState(
                connection = s.connection,
                snapshot = s.snapshot,
                stale = s.stale,
                error = s.error,
                lastSyncAt = s.lastSyncAt,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    class Factory(private val client: HerdrClient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(client) as T
    }
}
