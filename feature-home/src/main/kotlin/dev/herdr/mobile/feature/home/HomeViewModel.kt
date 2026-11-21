package dev.herdr.mobile.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.herdr.mobile.core.designsystem.UiMessage
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.model.Versions
import dev.herdr.mobile.core.network.HerdrClient
import dev.herdr.mobile.core.network.HerdrSession
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
    /** Increments when a reorder command fails so the list can restore order. */
    val reorderReset: Long = 0,
) {
    val workspaceCount: Int get() = snapshot?.workspaces?.size ?: 0
    val tabCount: Int get() = snapshot?.tabCount ?: 0
    val showOfflineBanner: Boolean
        get() = snapshot == null && (
            connection == ConnectionState.RECONNECTING ||
                connection == ConnectionState.CONNECTING ||
                connection == ConnectionState.IDLE ||
                connection == ConnectionState.FAILED ||
                error != null
            )
    /** Latest verified Herdr release, when newer than the connected one. */
    val updateAvailable: String?
        get() {
            val update = snapshot?.update ?: return null
            val latest = update.latestVersion?.trim()?.ifEmpty { null } ?: return null
            val current = snapshot?.herdr?.version?.trim()?.ifEmpty { null } ?: return latest
            return if (Versions.isNewer(current, latest)) latest else null
        }
}

class HomeViewModel(
    private val client: HerdrSession,
) : ViewModel() {
    private val _message = kotlinx.coroutines.flow.MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message
    private val _reorderReset = kotlinx.coroutines.flow.MutableStateFlow(0L)

    fun closeTab(tabId: String) {
        viewModelScope.launch {
            runCatching { client.closeTab(tabId) }
                .onSuccess { _message.value = null }
                .onFailure { _message.value = UiMessage.CommandFailed(UiMessage.CommandFailed.Command.CLOSE_TAB, it.message) }
        }
    }

    fun renameTab(tabId: String, label: String) {
        viewModelScope.launch {
            runCatching { client.renameTab(tabId, label.trim()) }
                .onSuccess { _message.value = null }
                .onFailure { _message.value = UiMessage.CommandFailed(UiMessage.CommandFailed.Command.RENAME_TAB, it.message) }
        }
    }

    // Workspace ops sync like tabs: the daemon emits a structural event and
    // every client refetches authoritatively. No local patching, no fake
    // optimistic card — the created workspace appears on the next snapshot
    // (same path as a workspace created on desktop or another phone).
    /**
     * A command built from an old profile's snapshot must not be sent to the
     * new profile's endpoint. Re-check the target generation inside the
     * coroutine; a target switch between the UI click and the IO hop then
     * drops the command instead of acting on a same-id object on another host.
     */
    private fun launchForCurrentTarget(block: suspend (Long) -> Unit) {
        val generation = client.targetGeneration
        viewModelScope.launch {
            if (client.targetGeneration != generation) return@launch
            block(generation)
        }
    }

    fun createWorkspace(label: String?, cwd: String?) {
        launchForCurrentTarget {
            runCatching { client.createWorkspace(label?.trim()?.ifEmpty { null }, cwd?.trim()?.ifEmpty { null }) }
                .onSuccess { _message.value = null }
                .onFailure { _message.value = UiMessage.CommandFailed(UiMessage.CommandFailed.Command.CREATE_WORKSPACE, it.message) }
        }
    }

    fun renameWorkspace(workspaceId: String, label: String) {
        launchForCurrentTarget {
            runCatching { client.renameWorkspace(workspaceId, label.trim()) }
                .onSuccess { _message.value = null }
                .onFailure { _message.value = UiMessage.CommandFailed(UiMessage.CommandFailed.Command.RENAME_WORKSPACE, it.message) }
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
        launchForCurrentTarget { generation ->
            runCatching {
                client.moveWorkspaceAt(
                    workspaceId,
                    dev.herdr.mobile.core.model.Reorder.insertIndex(from, toIndex, workspaces.size),
                    generation,
                )
            }.onSuccess { _message.value = null }
                .onFailure {
                    _message.value = UiMessage.CommandFailed(UiMessage.CommandFailed.Command.MOVE_WORKSPACE, it.message)
                    _reorderReset.value += 1
                }
        }
    }

    fun closeWorkspace(workspaceId: String) {
        launchForCurrentTarget { generation ->
            runCatching { client.closeWorkspaceAt(workspaceId, generation) }
                .onSuccess { _message.value = null }
                .onFailure { _message.value = UiMessage.CommandFailed(UiMessage.CommandFailed.Command.CLOSE_WORKSPACE, it.message) }
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
                .onSuccess { _message.value = null }
                .onFailure { _message.value = UiMessage.CommandFailed(UiMessage.CommandFailed.Command.UPDATE_HERDR, it.message) }
            _updateRunning.value = false
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    val uiState: StateFlow<HomeUiState> = kotlinx.coroutines.flow.combine(
        client.state,
        _reorderReset,
    ) { s: HerdrClientState, reset: Long ->
        HomeUiState(
            connection = s.connection,
            snapshot = s.snapshot,
            stale = s.stale,
            error = s.error,
            lastSyncAt = s.lastSyncAt,
            reorderReset = reset,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    class Factory(private val client: HerdrClient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(client) as T
    }
}
