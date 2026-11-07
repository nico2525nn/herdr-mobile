package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.model.TerminalAttachmentState
import dev.herdr.mobile.core.network.TerminalConnection
import dev.herdr.mobile.core.network.TerminalInbound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One live pane stream, with the attachment lifecycle the screen gates on.
 *
 * The screen never sees network types; this adapter is the only place that
 * knows both sides.
 */
class ConnectionTerminalBackend(
    connection: TerminalConnection,
    scope: CoroutineScope,
) {

    private val inner: TerminalConnection = connection

    val state: StateFlow<BackendState> = connection.state
        .map { s ->
            when (s) {
                is TerminalAttachmentState.Idle -> BackendState.Idle
                is TerminalAttachmentState.Attaching -> BackendState.Attaching(s.paneId)
                is TerminalAttachmentState.Attached ->
                    BackendState.Attached(
                        s.paneId, s.cols, s.rows,
                        s.historyRows, s.historyTruncated, s.historyError,
                        s.visibleOk,
                    )

                is TerminalAttachmentState.Detached -> BackendState.Detached(s.reason)
                is TerminalAttachmentState.Failed -> BackendState.Failed(s.code, s.message)
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), BackendState.Idle)

    val bytes: Flow<ByteArray> =
        inner.inbound.filterIsInstance<TerminalInbound.Bytes>().map { it.data }

    fun clearReplayCache() = inner.clearReplayCache()

    suspend fun send(data: ByteArray) = inner.send(data)

    suspend fun sendText(text: String) = inner.sendText(text)

    suspend fun resize(cols: Int, rows: Int) = inner.resize(cols, rows)

    suspend fun scrollRemote(lines: Int, unit: String) =
        inner.scrollRemote(lines, allowPageKey = false, unit = unit)
    suspend fun release() = inner.release()
}
