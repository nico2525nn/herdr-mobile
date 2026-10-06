package dev.herdr.mobile.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.InputPanelPage
import dev.herdr.mobile.core.model.Pane
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.model.Tab
import dev.herdr.mobile.core.model.TerminalColorScheme
import dev.herdr.mobile.core.model.Workspace
import dev.herdr.mobile.core.network.HerdrClient
import dev.herdr.mobile.terminal.emulator.TerminalThemes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which pane Terminal shows right now, and how it got there. */
data class TerminalTarget(
    val workspaceId: String,
    val tabId: String,
    val paneId: String,
)

/** Everything the Terminal screen renders. */
data class TerminalUiState(
    val snapshot: SessionSnapshot? = null,
    val connection: ConnectionState = ConnectionState.IDLE,
    val target: TerminalTarget? = null,
    val backend: ConnectionTerminalBackend? = null,
    val settings: AppSettings = AppSettings(),
    val inputPage: InputPanelPage = InputPanelPage.EXTRA_KEYS,
    val statusMessage: String? = null,
) {
    val workspace: Workspace? get() = snapshot?.workspace(target?.workspaceId)
    val tab: Tab? get() = snapshot?.tab(target?.tabId)
    val pane: Pane? get() = snapshot?.pane(target?.paneId)

    fun terminalScheme(): dev.herdr.mobile.terminal.emulator.TerminalColorScheme =
        when (settings.terminalColorScheme) {
            TerminalColorScheme.DARK -> TerminalThemes.dark()
            TerminalColorScheme.LIGHT -> TerminalThemes.light()
            TerminalColorScheme.SOLARIZED_DARK -> TerminalThemes.solarizedDark()
            TerminalColorScheme.SOLARIZED_LIGHT -> TerminalThemes.solarizedLight()
            TerminalColorScheme.TOKYO_NIGHT -> TerminalThemes.tokyoNight()
            TerminalColorScheme.DRACULA -> TerminalThemes.dracula()
            TerminalColorScheme.GRUVBOX_DARK -> TerminalThemes.gruvboxDark()
            TerminalColorScheme.CAMPBELL -> TerminalThemes.campbell()
        }
}

class TerminalViewModel(
    private val client: HerdrClient,
    initialWorkspaceId: String?,
    initialTabId: String?,
    private val settingsFlow: StateFlow<AppSettings>,
) : ViewModel() {

    private val targetFlow = MutableStateFlow<TerminalTarget?>(null)
    private val backendFlow = MutableStateFlow<ConnectionTerminalBackend?>(null)
    private val pageFlow = MutableStateFlow(InputPanelPage.EXTRA_KEYS)
    private val messageFlow = MutableStateFlow<String?>(null)

    private var attachJob: Job? = null

    val uiState: StateFlow<TerminalUiState> = combine(
        client.state,
        targetFlow,
        backendFlow,
        settingsFlow,
        pageFlow,
        messageFlow,
    ) { flows: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        val clientState = flows[0] as dev.herdr.mobile.core.network.HerdrClientState
        val target = flows[1] as TerminalTarget?
        val backend = flows[2] as ConnectionTerminalBackend?
        val settings = flows[3] as AppSettings
        val page = flows[4] as InputPanelPage
        val message = flows[5] as String?
        TerminalUiState(
            snapshot = clientState.snapshot,
            connection = clientState.connection,
            target = target,
            backend = backend,
            settings = settings,
            inputPage = page,
            statusMessage = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TerminalUiState())

    init {
        viewModelScope.launch {
            // Resolve the initial target once a snapshot exists.
            client.state.collect { s ->
                val snapshot = s.snapshot ?: return@collect
                if (targetFlow.value == null) {
                    val target = resolveTarget(snapshot, initialWorkspaceId, initialTabId)
                    if (target != null) {
                        openTarget(target)
                    }
                }
            }
        }
    }

    private fun resolveTarget(
        snapshot: SessionSnapshot,
        workspaceId: String?,
        tabId: String?,
    ): TerminalTarget? {
        val workspace = snapshot.workspace(workspaceId)
            ?: snapshot.workspaces.firstOrNull()
            ?: return null
        val tab = snapshot.tab(tabId?.takeIf { snapshot.tab(it)?.workspaceId == workspace.id })
            ?: workspace.tabs.firstOrNull { it.id == workspace.activeTabId }
            ?: workspace.tabs.firstOrNull()
            ?: return null
        val pane = tab.activePane ?: return null
        return TerminalTarget(workspace.id, tab.id, pane.id)
    }

    /** Switch workspace/tab. The old stream is released before the new one opens. */
    fun openTarget(target: TerminalTarget) {
        attachJob?.cancel()
        targetFlow.value = target
        attachJob = viewModelScope.launch {
            runCatching { backendFlow.value?.release() }
            backendFlow.value = null
            val connection = try {
                client.openTerminal(target.paneId, cols = 90, rows = 30)
            } catch (e: Exception) {
                messageFlow.value = "Cannot attach: ${e.message}"
                return@launch
            }
            messageFlow.value = null
            backendFlow.value = ConnectionTerminalBackend(connection, viewModelScope)
        }
    }

    fun openTab(workspaceId: String, tabId: String) {
        val snapshot = client.state.value.snapshot ?: return
        val tab = snapshot.tab(tabId) ?: return
        val pane = tab.activePane ?: return
        openTarget(TerminalTarget(workspaceId, tabId, pane.id))
    }

    fun setInputPage(page: InputPanelPage) {
        pageFlow.value = page
    }

    fun sendText(text: String) {
        val backend = backendFlow.value ?: return
        viewModelScope.launch { runCatching { backend.sendText(text) } }
    }

    fun sendBytes(data: ByteArray) {
        val backend = backendFlow.value ?: return
        viewModelScope.launch { runCatching { backend.send(data) } }
    }

    fun interrupt() {
        val paneId = targetFlow.value?.paneId ?: return
        viewModelScope.launch {
            // Fastest path first: raw ETX byte. The REST fallback covers panes whose
            // controller ignores it.
            val backend = backendFlow.value
            if (backend != null) {
                runCatching { backend.send(byteArrayOf(0x03)) }
            } else {
                runCatching { client.interrupt(paneId) }
            }
        }
    }

    fun createTab(workspaceId: String, label: String?) {
        viewModelScope.launch {
            runCatching { client.createTab(workspaceId, label) }
                .onSuccess { tabId -> openTab(workspaceId, tabId) }
                .onFailure { messageFlow.value = "Cannot create tab: ${it.message}" }
        }
    }

    override fun onCleared() {
        attachJob?.cancel()
        val backend = backendFlow.value
        backendFlow.value = null
        if (backend != null) {
            // The ViewModel scope is already going away; release on a best-effort basis.
            viewModelScope.launch { runCatching { backend.release() } }
        }
    }

    class Factory(
        private val client: HerdrClient,
        private val workspaceId: String?,
        private val tabId: String?,
        private val settings: StateFlow<AppSettings>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TerminalViewModel(client, workspaceId, tabId, settings) as T
    }
}
