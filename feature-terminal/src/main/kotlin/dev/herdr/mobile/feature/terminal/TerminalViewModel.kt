package dev.herdr.mobile.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.herdr.mobile.core.designsystem.UiMessage
import dev.herdr.mobile.core.designsystem.text
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.InputPanelPage
import dev.herdr.mobile.core.model.Pane
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.model.Tab
import dev.herdr.mobile.core.model.TerminalColorScheme
import dev.herdr.mobile.core.model.Workspace
import dev.herdr.mobile.feature.terminal.theme.TerminalThemes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which pane Terminal shows right now, and how it got there. */

/** Everything the Terminal screen renders. */
data class TerminalUiState(
    val snapshot: SessionSnapshot? = null,
    val connection: ConnectionState = ConnectionState.IDLE,
    val target: TerminalTarget? = null,
    val backend: ConnectionTerminalBackend? = null,
    val settings: AppSettings = AppSettings(),
    val inputPage: InputPanelPage = InputPanelPage.EXTRA_KEYS,
    val statusMessage: UiMessage? = null,
    /** Sticky CTRL/ALT from Extra Keys: applies to the next extra key AND the next soft-keyboard char. */
    val stickyModifiers: Set<TerminalKeyEncoder.Modifier> = emptySet(),
) {
    val workspace: Workspace? get() = snapshot?.workspace(target?.workspaceId)
    val tab: Tab? get() = snapshot?.tab(target?.tabId)
    val pane: Pane? get() = snapshot?.pane(target?.paneId)

    fun terminalScheme(): dev.herdr.mobile.feature.terminal.theme.TerminalColorScheme =
        when (settings.terminalColorScheme) {
            TerminalColorScheme.DARK -> TerminalThemes.dark()
            TerminalColorScheme.LIGHT -> TerminalThemes.light()
            TerminalColorScheme.SOLARIZED_DARK -> TerminalThemes.solarizedDark()
            TerminalColorScheme.SOLARIZED_LIGHT -> TerminalThemes.solarizedLight()
            TerminalColorScheme.TOKYO_NIGHT -> TerminalThemes.tokyoNight()
            TerminalColorScheme.DRACULA -> TerminalThemes.dracula()
            TerminalColorScheme.GRUVBOX_DARK -> TerminalThemes.gruvboxDark()
            TerminalColorScheme.CAMPBELL -> TerminalThemes.campbell()
            TerminalColorScheme.MIDNIGHT -> TerminalThemes.midnight()
        }
}

/**
 * Read-ack side effect: opening a DONE pane focuses its tab server-side, which
 * transitions done -> idle everywhere. [onFailure] receives the *reason* only;
 * the UI decides how to present it ([UiMessage.CommandFailed]).
 */
internal suspend fun focusTabForReadAck(
    client: dev.herdr.mobile.core.network.HerdrSession,
    tabId: String,
    onFailure: (String) -> Unit,
) {
    try {
        client.focusTab(tabId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Detail only: the UI renderer owns the sentence and its category.
        onFailure(e.message ?: "unknown error")
    }
}

class TerminalViewModel(
    private val client: dev.herdr.mobile.core.network.HerdrSession,
    private val initialWorkspaceId: String?,
    private val initialTabId: String?,
    private val initialPaneId: String? = null,
    private val settingsFlow: StateFlow<AppSettings>,
    private val persistFontSize: (Int) -> Unit = {},
) : ViewModel() {

    private val pageFlow = MutableStateFlow(InputPanelPage.EXTRA_KEYS)
    private val messageFlow = MutableStateFlow<UiMessage?>(null)
    private val stickyFlow =
        MutableStateFlow<Set<TerminalKeyEncoder.Modifier>>(emptySet())

    /** Which pane is on screen, and how traffic reaches it. */
    private val panes = PaneSessionController(
        session = client,
        scope = viewModelScope,
        onMessage = { messageFlow.value = it },
        initialWorkspaceId = initialWorkspaceId,
        initialTabId = initialTabId,
        initialPaneId = initialPaneId,
    )

    val inputTick: StateFlow<Int> = panes.inputTick

    val uiState: StateFlow<TerminalUiState> = combine(
        client.state,
        panes.target,
        panes.backend,
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
        val message = flows[5] as UiMessage?
        val sticky = flows[6] as Set<TerminalKeyEncoder.Modifier>
        TerminalUiState(
            snapshot = clientState.snapshot,
            connection = clientState.connection,
            target = target.takeIf { clientState.snapshot != null },
            backend = backend.takeIf { clientState.snapshot != null },
            settings = settings,
            inputPage = page,
            statusMessage = message,
            stickyModifiers = sticky,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TerminalUiState())

    init {
        panes.start()
    }

    fun setAttachGrid(cols: Int, rows: Int) = panes.setAttachGrid(cols, rows)

    /** The view's settled grid changed: resize the live backend too. */
    fun resizeBackend(cols: Int, rows: Int) = panes.resizeBackend(cols, rows)

    /** Host-owned scrollback: pane.scroll rows. */
    fun scrollRemote(rows: Int) = panes.scrollRemote(rows)

    fun openTarget(target: TerminalTarget) = panes.openTarget(target)

    fun openTab(workspaceId: String, tabId: String) = panes.openTab(workspaceId, tabId)

    fun retryAttach() = panes.retryAttach()

    fun setInputPage(page: InputPanelPage) {
        pageFlow.value = page
    }

    // CJK drafts, keyed by tab id: swiping pages or switching tabs must not
    // eat half-composed Japanese. The panel writes through on every keystroke.
    private val draftFlow = MutableStateFlow<Map<String, String>>(emptyMap())

    fun cjkDraft(tabId: String?): StateFlow<String> =
        draftFlow.map { it[tabId].orEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, draftFlow.value[tabId].orEmpty())

    fun setCjkDraft(tabId: String?, text: String) {
        if (tabId == null) return
        val current = draftFlow.value[tabId].orEmpty()
        if (current == text) return
        draftFlow.value = draftFlow.value + (tabId to text)
    }

    fun clearCjkDraft(tabId: String?) {
        if (tabId == null) return
        if (!draftFlow.value.containsKey(tabId)) return
        draftFlow.value = draftFlow.value - tabId
    }

    fun releaseForBackground() = panes.releaseForBackground()

    fun resumeAfterBackground() = panes.resumeAfterBackground()

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
            panes.send({ backend -> backend.send(chord) })
        } else if (text.length > 1) {
            // Atomic multi-char commit (CJK panel): Termux paste normalization
            // (strip ESC/C1, newlines to CR). Single chars stay verbatim —
            // Termux typing parity (per-char commits are never normalized).
            panes.send({ backend -> backend.sendText(TerminalKeyEncoder.pasteNormalize(text)) })
        } else {
            panes.send({ backend -> backend.sendText(text) })
        }
    }

    /**
     * Clipboard/paste payload, already terminal-ready (escape-stripped and,
     * for bracketed pastes, wrapped in 2004 markers). Sent verbatim: the
     * normalizing [sendText] path would strip those markers and turn the
     * block's newlines into CR, which is exactly what broke multi-line paste
     * in TUI editors.
     */
    fun sendPaste(payload: String) {
        panes.send({ backend -> backend.sendText(payload) })
    }

    fun sendBytes(data: ByteArray) {
        panes.send({ backend -> backend.send(data) })
    }

    /**
     * Engine-originated input (typed keys, IME): the engine already snapped its
     * own viewport, so this must not count as a user action and trigger another
     * scroll-to-bottom.
     */
    fun sendEngineBytes(data: ByteArray) {
        panes.sendRaw { backend -> backend.send(data) }
    }

    /** Pinch-to-zoom: keep the setting in sync (debounced by the app). */
    fun onViewFontSizeChanged(sizeSp: Int) {
        persistFontSize(sizeSp)
    }

    fun clearStickyModifiers() {
        stickyFlow.value = emptySet()
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
        } else if (TerminalKeyEncoder.Modifier.SHIFT in sticky && raw != null && raw.size > 1) {
            // SHIFT is the only latch that transforms CSI sequences (arrows →
            // ;2 modified form); CTRL/ALT ignore multi-byte keys as before.
            var out: ByteArray? = TerminalKeyEncoder.withShift(raw)
            if (TerminalKeyEncoder.Modifier.ALT in sticky && out != null && out.size == 1) {
                out = byteArrayOf(TerminalKeyEncoder.ESC, out[0])
            }
            out
        } else if (raw != null && raw.size == 1) {
            var out: ByteArray? = raw
            if (TerminalKeyEncoder.Modifier.SHIFT in sticky) {
                out = TerminalKeyEncoder.withShift(out!!)
            }
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
        panes.send({ backend -> backend.send(bytes) })
    }

    fun interrupt() {
        val paneId = panes.target.value?.paneId ?: return
        // Fastest path first: the raw ETX byte through the live stream. The REST
        // fallback covers panes whose controller ignores it and attach windows.
        if (panes.backend.value == null) {
            viewModelScope.launch { runCatching { client.interrupt(paneId) } }
            return
        }
        panes.send { backend -> backend.send(byteArrayOf(0x03)) }
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
                .onFailure { messageFlow.value = UiMessage.CommandFailed(UiMessage.CommandFailed.Command.CREATE_TAB, it.message) }
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
                .onFailure { messageFlow.value = UiMessage.CommandFailed(UiMessage.CommandFailed.Command.CLOSE_TAB, it.message) }
        }
    }

    fun renameWorkspace(workspaceId: String, label: String) {
        viewModelScope.launch {
            runCatching { client.renameWorkspace(workspaceId, label.trim()) }
                .onFailure { messageFlow.value = UiMessage.CommandFailed(UiMessage.CommandFailed.Command.RENAME_WORKSPACE, it.message) }
        }
    }

    fun closeWorkspace(workspaceId: String) {
        viewModelScope.launch {
            runCatching { client.closeWorkspace(workspaceId) }
                .onSuccess {
                    // Move to a surviving workspace's active tab, like closeTab.
                    val snapshot = client.state.value.snapshot ?: return@onSuccess
                    val remaining = snapshot.workspaces.firstOrNull { it.id != workspaceId }
                        ?: return@onSuccess
                    val tab = remaining.tabs.firstOrNull { it.id == remaining.activeTabId }
                        ?: remaining.tabs.firstOrNull()
                        ?: return@onSuccess
                    val pane = tab.activePane ?: return@onSuccess
                    openTarget(TerminalTarget(remaining.id, tab.id, pane.id))
                }
                .onFailure { messageFlow.value = UiMessage.CommandFailed(UiMessage.CommandFailed.Command.CLOSE_WORKSPACE, it.message) }
        }
    }

    fun renameTab(tabId: String, label: String) {
        viewModelScope.launch {
            runCatching { client.renameTab(tabId, label.trim()) }
                .onFailure { messageFlow.value = UiMessage.CommandFailed(UiMessage.CommandFailed.Command.RENAME_TAB, it.message) }
        }
    }

    /**
     * Rail drag-reorder. Server-authoritative like everything else: no local
     * patch, the moved event refetches all clients (desktop included).
     */
    fun moveTab(workspaceId: String, tabId: String, toIndex: Int) {
        val tabs = client.state.value.snapshot?.workspace(workspaceId)?.tabs ?: return
        val from = tabs.indexOfFirst { it.id == tabId }
        if (from < 0 || from == toIndex) return
        viewModelScope.launch {
            runCatching {
                client.moveTab(
                    tabId,
                    dev.herdr.mobile.core.model.Reorder.insertIndex(from, toIndex, tabs.size),
                )
            }.onFailure { messageFlow.value = UiMessage.CommandFailed(UiMessage.CommandFailed.Command.MOVE_TAB, it.message) }
        }
    }

    fun moveWorkspace(workspaceId: String, toIndex: Int) {
        val workspaces = client.state.value.snapshot?.workspaces ?: return
        val from = workspaces.indexOfFirst { it.id == workspaceId }
        if (from < 0 || from == toIndex) return
        viewModelScope.launch {
            runCatching {
                client.moveWorkspace(
                    workspaceId,
                    dev.herdr.mobile.core.model.Reorder.insertIndex(from, toIndex, workspaces.size),
                )
            }.onFailure { messageFlow.value = UiMessage.CommandFailed(UiMessage.CommandFailed.Command.MOVE_WORKSPACE, it.message) }
        }
    }

    override fun onCleared() {
        panes.shutdown()
    }

    class Factory(
        private val client: dev.herdr.mobile.core.network.HerdrSession,
        private val workspaceId: String?,
        private val tabId: String?,
        private val paneId: String? = null,
        private val settings: StateFlow<AppSettings>,
        private val persistFontSize: (Int) -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TerminalViewModel(client, workspaceId, tabId, paneId, settings, persistFontSize) as T
    }
}

/** Reconnect settles (snapshot + stream) before the attach is retried. */
private const val AUTO_REATTACH_DELAY_MS = 600L

/** 0.6s, 1.2s, 2.4s, then the Retry button takes over. */
private const val AUTO_REATTACH_ATTEMPTS = 4
