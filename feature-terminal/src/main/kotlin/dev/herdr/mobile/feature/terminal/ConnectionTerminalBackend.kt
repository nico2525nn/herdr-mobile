package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.model.TerminalAttachmentState
import dev.herdr.mobile.core.network.TerminalConnection
import dev.herdr.mobile.core.network.TerminalInbound
import dev.herdr.mobile.terminal.view.BackendState
import dev.herdr.mobile.terminal.view.TerminalBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Adapts the OkHttp-backed [TerminalConnection] to the view's [TerminalBackend].
 *
 * The view never sees network types; this adapter is the only place that knows both.
 */
class ConnectionTerminalBackend(
    connection: TerminalConnection,
    scope: CoroutineScope,
) : TerminalBackend {

    private val inner: TerminalConnection = connection

    override val state: StateFlow<BackendState> = connection.state
        .map { s ->
            when (s) {
                is TerminalAttachmentState.Idle -> BackendState.Idle
                is TerminalAttachmentState.Attaching -> BackendState.Attaching(s.paneId)
                is TerminalAttachmentState.Attached ->
                    BackendState.Attached(s.paneId, s.cols, s.rows)

                is TerminalAttachmentState.Detached -> BackendState.Detached(s.reason)
                is TerminalAttachmentState.Failed -> BackendState.Failed(s.code, s.message)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, BackendState.Idle)

    override val bytes: Flow<ByteArray> =
        inner.inbound.filterIsInstance<TerminalInbound.Bytes>().map { it.data }

    override suspend fun send(data: ByteArray) = inner.send(data)

    override suspend fun sendText(text: String) = inner.sendText(text)

    override suspend fun resize(cols: Int, rows: Int) = inner.resize(cols, rows)

    override suspend fun scrollUp(lines: Int) = inner.scrollUp(lines)

    override suspend fun scrollDown(lines: Int) = inner.scrollDown(lines)

    override suspend fun mouse(action: String, button: String, column: Int, row: Int) =
        inner.mouse(action, button, column, row)

    override suspend fun release() = inner.release()
}
