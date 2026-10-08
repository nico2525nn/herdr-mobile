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
import dev.herdr.mobile.terminal.view.TerminalKeyEncoder
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
import kotlinx.coroutines.flow.first
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
    /** Sticky CTRL/ALT from Extra Keys: applies to the next extra key AND the next soft-keyboard char. */
    val stickyModifiers: Set<TerminalKeyEncoder.Modifier> = emptySet(),
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
    initialPaneId: String? = null,
    private val settingsFlow: StateFlow<AppSettings>,
) : ViewModel() {

    private val targetFlow = MutableStateFlow<TerminalTarget?>(null)
    private val backendFlow = MutableStateFlow<ConnectionTerminalBackend?>(null)
    private val pageFlow = MutableStateFlow(InputPanelPage.EXTRA_KEYS)
    private val messageFlow = MutableStateFlow<String?>(null)
    private val stickyFlow =
        MutableStateFlow<Set<TerminalKeyEncoder.Modifier>>(emptySet())

    /**
     * Increments on every user input send (text, bytes, extra keys). The
     * terminal surface collects it to snap a scrolled viewport back to live:
     * typing means the user is back at the prompt. Output never touches it,
     * so reading history while output flows doesn't yank.
     */
    private val _inputTick = MutableStateFlow(0)
    val inputTick: StateFlow<Int> = _inputTick.asStateFlow()

    private fun tickInput() {
        _inputTick.value += 1
    }

    // Declared BEFORE init: viewModelScope.launch on the main thread starts
    // undispatched, so the first StateFlow emit can reach flushPending while
    // the constructor is still running. A later declaration NPEs on monitor-enter.
    private val pendingInput = ArrayDeque<suspend (ConnectionTerminalBackend) -> Unit>(64)

    private var attachJob: Job? = null

    val uiState: StateFlow<TerminalUiState> = combine(
        client.state,
        targetFlow,
        backendFlow,
        settingsFlow,
        pageFlow,
        messageFlow,
        stickyFlow,
    ) { flows: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        val clientState = flows[0] as dev.herdr.mobile.core.network.HerdrClientState
        val target = flows[1] as TerminalTarget?
        val backend = flows[2] as ConnectionTerminalBackend?
        val settings = flows[3] as AppSettings
        val page = flows[4] as InputPanelPage
        val message = flows[5] as String?
        val sticky = flows[6] as Set<TerminalKeyEncoder.Modifier>
        TerminalUiState(
            snapshot = clientState.snapshot,
            connection = clientState.connection,
            target = target,
            backend = backend,
            settings = settings,
            inputPage = page,
            statusMessage = message,
            stickyModifiers = sticky,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TerminalUiState())

    init {
        viewModelScope.launch {
            // Resolve the initial target once a snapshot exists.
            client.state.collect { s ->
                val snapshot = s.snapshot ?: return@collect
                if (targetFlow.value == null) {
                    val target = resolveTarget(snapshot, initialWorkspaceId, initialTabId, initialPaneId)
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
        paneId: String? = null,
    ): TerminalTarget? {
        val workspace = snapshot.workspace(workspaceId)
            ?: snapshot.workspaces.firstOrNull()
            ?: return null
        val tab = snapshot.tab(tabId?.takeIf { snapshot.tab(it)?.workspaceId == workspace.id })
            ?: workspace.tabs.firstOrNull { it.id == workspace.activeTabId }
            ?: workspace.tabs.firstOrNull()
            ?: return null
        // Deep link carries the alerting pane: prefer it over the tab's active
        // pane, or a notification tap opens (and inputs into) the wrong agent.
        val pane = paneId?.let { id -> tab.panes.firstOrNull { it.id == id } }
            ?: tab.activePane ?: return null
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
                // "no endpoint is open" = transient link gap (backgrounded tunnel,
                // reconnect beat): kick the client, wait for CONNECTED once, and
                // retry a single time. Genuine failures still surface below.
                val retry = e is dev.herdr.mobile.core.network.DaemonException &&
                    e.code == "not_connected"
                if (!retry) {
                    messageFlow.value = "Cannot attach: ${e.message}"
                    return@launch
                }
                client.kick()
                try {
                    withTimeout(30_000) {
                        client.state.first { it.connection == ConnectionState.CONNECTED }
                    }
                } catch (_: Exception) {
                    messageFlow.value = "Reconnect timed out — tap Retry"
                    return@launch
                }
                ensureActive()
                try {
                    client.openTerminal(target.paneId, cols = 90, rows = 30)
                } catch (e2: Exception) {
                    messageFlow.value = "Cannot attach: ${e2.message}"
                    return@launch
                }
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
            val backend = ConnectionTerminalBackend(connection, viewModelScope)
            backendFlow.value = backend
            flushPending(backend)
        }
    }

    fun openTab(workspaceId: String, tabId: String) {
        val snapshot = client.state.value.snapshot ?: return
        val tab = snapshot.tab(tabId) ?: return
        val pane = tab.activePane ?: return
        openTarget(TerminalTarget(workspaceId, tabId, pane.id))
    }

    /**
     * Re-attach the current target after a failure/disconnect. Hands the dead
     * backend to openTarget (which releases it) — nulling backendFlow first
     * would orphan the live controller, since openTarget releases whatever it
     * finds in backendFlow.
     */
    fun retryAttach() {
        val target = targetFlow.value ?: return
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
     *
     * Waits for the client link first: after minutes in background the tunnel is
     * dead and endpoint=null, so an immediate openTerminal throws "no endpoint
     * is open". Waiting for CONNECTED (kick → first snapshot) makes resume
     * self-healing instead of an error the user must clear via Home.
     */
    fun resumeAfterBackground() {
        val target = targetFlow.value
        val hasBackend = backendFlow.value != null
        if (target == null || hasBackend) return
        attachJob?.cancel()
        messageFlow.value = null
        attachJob = viewModelScope.launch {
            client.kick()
            val connected = try {
                withTimeout(30_000) {
                    client.state.first { it.connection == ConnectionState.CONNECTED }
                }
                true
            } catch (_: Exception) {
                false
            }
            ensureActive()
            if (!connected) {
                messageFlow.value = "Reconnect timed out — tap Retry"
                return@launch
            }
            openTarget(target)
        }
    }

    /**
     * Input typed while no backend is attached (attaching window): queued and
     * flushed on the next backend, instead of silently dropped (the CJK panel
     * already cleared its field, so a drop loses user text with no trace).
     * Bounded at 64 entries; failures surface as a status message.
     */
    private fun enqueueOrSend(op: suspend (ConnectionTerminalBackend) -> Unit) {
        tickInput()
        val backend = backendFlow.value
        if (backend != null) {
            viewModelScope.launch {
                runCatching { op(backend) }
                    .onFailure { messageFlow.value = "Send failed: ${it.message}" }
            }
            return
        }
        synchronized(pendingInput) {
            if (pendingInput.size >= 64) pendingInput.removeFirst()
            pendingInput.addLast(op)
        }
    }

    private fun flushPending(backend: ConnectionTerminalBackend) {
        val ops = synchronized(pendingInput) {
            val list = pendingInput.toList()
            pendingInput.clear()
            list
        }
        if (ops.isEmpty()) return
        viewModelScope.launch {
            for (op in ops) {
                runCatching { op(backend) }
                    .onFailure { messageFlow.value = "Send failed: ${it.message}" }
            }
        }
    }

    fun sendText(text: String) {
        // Sticky CTRL/ALT from Extra Keys applies here too: one soft-keyboard
        // char becomes the chord (Ctrl+C from keyboard, no letter row needed).
        // Unencodable text (paste, CJK, multi-char) goes verbatim and the
        // latch survives — half-modifying an atomic paste corrupts user data.
        val sticky = stickyFlow.value
        val chord = if (sticky.isEmpty()) {
            null
        } else {
            TerminalKeyEncoder.withModifiers(sticky, text)
        }
        if (chord != null) {
            stickyFlow.value = emptySet()
            enqueueOrSend({ backend -> backend.send(chord) })
        } else {
            enqueueOrSend({ backend -> backend.sendText(text) })
        }
    }

    fun sendBytes(data: ByteArray) {
        enqueueOrSend({ backend -> backend.send(data) })
    }

    /**
     * Extra-key tap with sticky modifiers applied: modifier keys toggle the
     * latch (multi-select), any other key consumes it. Single-char keys stack
     * both modifiers; escape sequences ignore them (same rule as the encoder:
     * raw sequences are sent as-is, latch still consumed).
     */
    fun sendExtraKey(key: TerminalKeyEncoder.Key) {
        val modifier = key.modifier
        if (modifier != null) {
            val current = stickyFlow.value
            stickyFlow.value = if (modifier in current) current - modifier else current + modifier
            return
        }
        val sticky = stickyFlow.value
        val raw = key.bytes
        val bytes = if (sticky.isEmpty()) {
            raw
        } else if (raw != null && raw.size == 1) {
            var out: ByteArray? = raw
            if (TerminalKeyEncoder.Modifier.CTRL in sticky) {
                out = TerminalKeyEncoder.withModifier(
                    TerminalKeyEncoder.Modifier.CTRL,
                    TerminalKeyEncoder.Key("", bytes = out),
                ) ?: return // meaningless combo: latch survives, user picks another key
            }
            if (TerminalKeyEncoder.Modifier.ALT in sticky) {
                out = byteArrayOf(TerminalKeyEncoder.ESC, out!![0])
            }
            out
        } else {
            raw
        }
        if (bytes == null) return // herdrKeys-only path: no consumer yet
        stickyFlow.value = emptySet()
        enqueueOrSend({ backend -> backend.send(bytes) })
    }

    fun interrupt() {
        tickInput()
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
                .onSuccess { tabId ->
                    // The snapshot lacks the new tab until the structural event
                    // (or refetch) lands: wait for it, or openTab's lookup
                    // silently returns and the new tab never opens.
                    client.refresh()
                    val deadline = System.currentTimeMillis() + 5_000
                    while (System.currentTimeMillis() < deadline) {
                        if (client.state.value.snapshot?.tab(tabId) != null) break
                        kotlinx.coroutines.delay(150)
                    }
                    openTab(workspaceId, tabId)
                }
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
        private val paneId: String? = null,
        private val settings: StateFlow<AppSettings>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TerminalViewModel(client, workspaceId, tabId, paneId, settings) as T
    }
}
