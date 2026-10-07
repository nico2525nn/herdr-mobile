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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
            // Release the old backend UNCANCELLABLY: TerminalSocket.release()
            // suspends (direct send + flush beat) and a rapid tab switch would
            // otherwise cancel us mid-release — leaving released=true but the
            // socket never closed, with the pump suspended forever.
            val old = backendFlow.value
            backendFlow.value = null
            if (old != null) {
                try {
                    withContext(NonCancellable) { old.release() }
                } catch (_: Exception) {
                }
            }
            // A cancelled (superseded) job must not publish: without this check
            // two racing attaches both reach backendFlow and the loser leaks a
            // live controller while last-writer-wins shows the wrong pane.
            ensureActive()
            val connection = try {
                client.openTerminal(target.paneId, cols = 90, rows = 30)
            } catch (e: Exception) {
                messageFlow.value = "Cannot attach: ${e.message}"
                return@launch
            }
            // Cancelled between openTerminal (non-suspending: creates+connects)
            // and publish: release the orphan or its controller + resize lock
            // leak on the daemon (no idle timeout there).
            if (!isActive) {
                try {
                    withContext(NonCancellable) { connection.release() }
                } catch (_: Exception) {
                }
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

    /**
     * Re-attach the current target after a failure/disconnect. Drops the dead
     * backend first so the new attach starts clean — without this a Detached
     * screen has no recovery path except leaving and re-entering.
     */
    fun retryAttach() {
        val target = targetFlow.value ?: return
        // Clear synchronously so the placeholder shows immediately, then attach.
        backendFlow.value = null
        openTarget(target)
    }

    fun setInputPage(page: InputPanelPage) {
        pageFlow.value = page
    }

    /**
     * Called when the app goes to background (Activity ON_STOP) while the terminal
     * screen is visible. The Compose tree is NOT disposed in this case, so neither
     * DisposableEffect nor onCleared runs — without this, the controller (and its
     * resize lock) leaks until the process dies. Best effort with a timeout; the
     * daemon also cleans up dead sockets, so a missed call here is recoverable.
     */
    fun releaseForBackground() {
        // Cancel a pending attach FIRST: openTerminal/connect are non-suspending,
        // so an in-flight attachJob would otherwise publish a live socket after
        // we return — leaking a controller while resumeAfterBackground no-ops.
        attachJob?.cancel()
        val backend = backendFlow.value
        if (backend == null) return
        backendFlow.value = null
        val releaser = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        releaser.launch {
            try {
                withTimeout(5_000) { backend.release() }
            } catch (_: Exception) {
            } finally {
                releaser.cancel()
            }
        }
    }

    /**
     * Called on ON_START after [releaseForBackground]. Re-attaches the kept target:
     * without this the screen sits on "Attaching…" forever after returning from
     * background. No-op when nothing was released or no target exists.
     */
    fun resumeAfterBackground() {
        val target = targetFlow.value
        val hasBackend = backendFlow.value != null
        if (target == null || hasBackend) return
        openTarget(target)
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

    fun closeTab(workspaceId: String, tabId: String) {
        viewModelScope.launch {
            runCatching { client.closeTab(tabId) }
                .onSuccess {
                    // Move to a surviving tab; the closed one disappears on the next event.
                    val snapshot = client.state.value.snapshot
                    val workspace = snapshot?.workspace(workspaceId)
                    val next = workspace?.tabs?.firstOrNull { it.id != tabId }
                    val nextPane = next?.activePane
                    if (next != null && nextPane != null) {
                        openTarget(TerminalTarget(workspaceId, next.id, nextPane.id))
                    }
                }
                .onFailure { messageFlow.value = "Cannot close tab: ${it.message}" }
        }
    }

    fun renameTab(tabId: String, label: String) {
        viewModelScope.launch {
            runCatching { client.renameTab(tabId, label.trim()) }
                .onFailure { messageFlow.value = "Cannot rename tab: ${it.message}" }
        }
    }

    override fun onCleared() {
        attachJob?.cancel()
        val backend = backendFlow.value
        backendFlow.value = null
        if (backend != null) {
            // viewModelScope is cancelled with us; cleanup must not depend on it.
            // runBlocking is wrong here too (main thread risk), so use a fresh
            // scope with a bounded wait: best effort, but actually attempted.
            val releaser = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            releaser.launch {
                try {
                    withTimeout(5_000) { backend.release() }
                } catch (_: Exception) {
                } finally {
                    releaser.cancel()
                }
            }
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
