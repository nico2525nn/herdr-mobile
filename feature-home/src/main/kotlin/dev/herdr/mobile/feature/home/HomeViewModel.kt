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
