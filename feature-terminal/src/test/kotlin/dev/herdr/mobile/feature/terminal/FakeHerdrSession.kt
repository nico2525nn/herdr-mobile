package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.TerminalAttachmentState
import dev.herdr.mobile.core.network.DaemonException
import dev.herdr.mobile.core.network.HerdrClientState
import dev.herdr.mobile.core.network.HerdrSession
import dev.herdr.mobile.core.network.TerminalConnection
import dev.herdr.mobile.core.network.TerminalInbound
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A session client that does exactly what a test tells it to.
 *
 * Written from the screen's point of view: the view-model only ever reads the
 * link state, the generations and the snapshot, and calls a handful of
 * commands. Everything else (websocket, cache, backoff) is the real client's
 * business, which is why the VM depends on the narrow `HerdrSession` contract.
 */
class FakeHerdrSession(
    snapshot: dev.herdr.mobile.core.model.SessionSnapshot? = null,
    connection: ConnectionState = ConnectionState.CONNECTED,
) : HerdrSession {

    private val _state = MutableStateFlow(HerdrClientState(connection = connection, snapshot = snapshot))
    override val state: StateFlow<HerdrClientState> = _state.asStateFlow()

    override var targetGeneration: Long = 0
    override var linkGeneration: Long = 0

    /** Snapshots of the arguments of each call, in order. */
    val openedPanes = mutableListOf<String>()
    val focusCalls = mutableListOf<String>()
    val interruptCalls = mutableListOf<String>()
    val closeTabCalls = mutableListOf<String>()
    val closeWorkspaceCalls = mutableListOf<String>()

    /** When set, [openTerminal] throws this instead of returning a connection. */
    var openFailure: Exception? = null

    /** Connections handed to the view-model, newest last. */
    val connections = mutableListOf<FakeTerminalConnection>()

    var refreshCalls = 0
    var kickCalls = 0

    fun publish(state: HerdrClientState) {
        _state.value = state
    }

    fun setSnapshot(snapshot: dev.herdr.mobile.core.model.SessionSnapshot?) {
        _state.value = _state.value.copy(snapshot = snapshot)
    }

    fun setConnection(connection: ConnectionState) {
        _state.value = _state.value.copy(connection = connection)
    }

    override fun refresh() {
        refreshCalls++
    }

    override fun kick() {
        kickCalls++
    }

    override fun openTerminal(paneId: String, cols: Int, rows: Int, takeover: Boolean): TerminalConnection {
        openFailure?.let { throw it }
        openedPanes += paneId
        return FakeTerminalConnection(paneId).also { connections += it }
    }

    override suspend fun focusTab(tabId: String) {
        focusCalls += tabId
    }

    override suspend fun createTab(workspaceId: String, label: String?): String = "new-tab"

    override suspend fun closeTab(tabId: String) {
        closeTabCalls += tabId
    }

    override suspend fun renameTab(tabId: String, label: String) = Unit

    override suspend fun closeWorkspace(workspaceId: String) {
        closeWorkspaceCalls += workspaceId
    }

    override suspend fun renameWorkspace(workspaceId: String, label: String) = Unit

    override suspend fun moveTab(tabId: String, insertIndex: Long) = Unit

    override suspend fun moveWorkspace(workspaceId: String, insertIndex: Long) = Unit

    override suspend fun interrupt(paneId: String) {
        interruptCalls += paneId
    }
}

/**
 * Terminal stream with a scripted attachment state. Records every byte/text
 * the view-model sends, so tests can assert on observable output instead of
 * internals.
 */
class FakeTerminalConnection(
    val paneId: String,
    initialState: TerminalAttachmentState = TerminalAttachmentState.Attaching(paneId),
) : TerminalConnection {

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<TerminalAttachmentState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<TerminalInbound>(extraBufferCapacity = 32)
    override val inbound: Flow<TerminalInbound> = _inbound

    val sentData = mutableListOf<ByteArray>()
    val sentText = mutableListOf<String>()
    val scrolls = mutableListOf<Triple<Int, Boolean, String>>()
    var released = 0
        private set

    override var isOpen: Boolean = true
        private set

    fun attach(cols: Int = 80, rows: Int = 24) {
        _state.value = TerminalAttachmentState.Attached(paneId, cols, rows, resumed = false)
    }

    fun detach(reason: String) {
        _state.value = TerminalAttachmentState.Detached(reason)
    }

    fun fail(code: String, message: String) {
        _state.value = TerminalAttachmentState.Failed(code, message)
    }

    override suspend fun send(data: ByteArray) {
        sentData += data
    }

    override suspend fun sendText(text: String) {
        sentText += text
    }

    override suspend fun resize(cols: Int, rows: Int) = Unit

    override suspend fun scrollRemote(lines: Int, allowPageKey: Boolean, unit: String) {
        scrolls += Triple(lines, allowPageKey, unit)
    }

    override suspend fun mouse(action: String, button: String, column: Int, row: Int) = Unit

    override suspend fun release() {
        released++
        isOpen = false
    }
}

/** A [DaemonException] for "no endpoint yet", the offline attach failure. */
fun offline(): Exception = DaemonException("not_connected", "No endpoint is open")
