package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.RawTerminalRecord
import dev.herdr.mobile.core.model.TerminalAttachmentState
import dev.herdr.mobile.core.model.TerminalProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything the terminal screen observes or drives for one attached pane.
 *
 * Byte traffic never touches the JSON layer: binary frames are fed to the emulator as they
 * arrive and keystrokes leave as binary frames. Control records ([resize], [scroll], [mouse])
 * leave as JSON text frames; [TerminalProtocol.Ready] / [Closed] arrive as JSON text frames.
 */
interface TerminalConnection {
    val state: StateFlow<TerminalAttachmentState>
    val inbound: Flow<TerminalInbound>

    suspend fun send(data: ByteArray)
    suspend fun sendText(text: String)
    suspend fun resize(cols: Int, rows: Int)
    suspend fun mouse(action: String, button: String, column: Int, row: Int)
    suspend fun release()

    val isOpen: Boolean
}

sealed interface TerminalInbound {
    data class Ready(val paneId: String, val cols: Int, val rows: Int, val resumed: Boolean) : TerminalInbound
    data class Bytes(val data: ByteArray) : TerminalInbound
    data class Closed(val reason: String) : TerminalInbound
    data class Failed(val code: String, val message: String) : TerminalInbound
}

/**
 * One live `/v1/terminal/{paneId}` socket. Create per attachment, [release] when the screen
 * leaves — holding a terminal stream open from Home would violate the lifecycle contract.
 */
class TerminalSocket(
    private val http: OkHttpClient,
    private val endpoint: DaemonEndpoint,
    private val paneId: String,
    private val cols: Int,
    private val rows: Int,
    private val takeover: Boolean,
    private val scope: CoroutineScope,
    /**
     * Viewport to hand back to Herdr when this socket goes away. Null disables the
     * restore; the pane then keeps whatever grid the phone last set.
     */
    private val restore: Pair<Int, Int>? = null,
) : TerminalConnection {
    private val _state =
        MutableStateFlow<TerminalAttachmentState>(TerminalAttachmentState.Attaching(paneId))
    override val state: StateFlow<TerminalAttachmentState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<TerminalInbound>(
        // Lossless: DROP_OLDEST on PTY bytes silently desyncs the emulator
        // under burst output (and can drop Ready/Closed itself). The daemon
        // is the backpressure source; the client must not discard.
        //
        // replay covers the attach race: the daemon sends ready + history
        // prelude within milliseconds of connect, but the bridge pump
        // subscribes ~100ms later (Compose composition). With replay=0 those
        // first messages sit in the buffer invisible to the late subscriber —
        // the prelude (and its 1000 history rows) is silently lost. 256
        // dwarfs any attach burst (prelude + a few live frames).
        replay = 256,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val inbound: Flow<TerminalInbound> = _inbound.asSharedFlow()

    private val outbound = Channel<OutboundFrame>(capacity = 128)

    private val open = AtomicBoolean(false)
    override val isOpen: Boolean get() = open.get()

    private var socket: WebSocket? = null

    /** Must be called once; returns immediately after the socket is opened. */
    fun connect() {
        val query = buildMap {
            put("cols", cols.toString())
            put("rows", rows.toString())
            if (takeover) put("takeover", "true")
            // No restore_* params: the daemon snapshots the pane's tab-layout rect
            // at attach time and hands exactly that back on detach. Only an explicit
            // restore overrides it (restore_cols=0 disables).
            restore?.let { (restoreCols, restoreRows) ->
                put("restore_cols", restoreCols.toString())
                put("restore_rows", restoreRows.toString())
            }
            // No token in the query: Authorization header carries it. Query
            // strings land in proxies/logs/crash reports.
        }
        val request = Request.Builder()
            .url(endpoint.wsUrl("/v1/terminal/$paneId", query))
            .apply { endpoint.token?.let { header("Authorization", "Bearer $it") } }
            .build()

        socket = http.newWebSocket(request, Listener())
        scope.launch { pump() }
    }

    private sealed interface OutboundFrame {
        data class Binary(val data: ByteArray) : OutboundFrame
        data class Text(val text: String) : OutboundFrame
    }

    private suspend fun pump() {
        for (frame in outbound) {
            val ws = socket ?: break
            when (frame) {
                is OutboundFrame.Binary -> ws.send(ByteString.of(*frame.data))
                is OutboundFrame.Text -> ws.send(frame.text)
            }
        }
    }

    /**
     * Single ordered inbound lane: every frame (fast or slow path) goes through
     * this channel in arrival order, and ONE collector forwards to _inbound.
     * tryEmit-then-mutex lets a later fast-path frame overtake an earlier
     * queued suspend-emit; only a single ordered queue preserves PTY order.
     */
    private val inboundLane = Channel<TerminalInbound>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (value in inboundLane) {
                _inbound.emit(value)
            }
        }
    }

    private fun closeInboundLane() {
        // Stops the forwarder: without this each attach leaks one coroutine +
        // channel + socket refs on the app-lifetime scope, parked on receive
        // forever after release.
        inboundLane.close()
    }

    private fun emit(value: TerminalInbound) {
        // UNLIMITED channel never suspends the OkHttp callback thread; order
        // is preserved by the single consumer above. Backpressure lives at
        // the _inbound SharedFlow (SUSPEND), not here.
        inboundLane.trySend(value)
    }

    override suspend fun send(data: ByteArray) {
        outbound.send(OutboundFrame.Binary(data))
    }

    override suspend fun sendText(text: String) {
        val record = HerdrJson.encodeToString(
            TerminalProtocol.InputText.serializer(),
            TerminalProtocol.InputText(text),
        )
        outbound.send(OutboundFrame.Text(record))
    }

    override suspend fun resize(cols: Int, rows: Int) {
        val record = HerdrJson.encodeToString(
            TerminalProtocol.Resize.serializer(),
            TerminalProtocol.Resize(cols, rows),
        )
        outbound.send(OutboundFrame.Text(record))
    }

    override suspend fun mouse(action: String, button: String, column: Int, row: Int) {
        val record = HerdrJson.encodeToString(
            TerminalProtocol.Mouse.serializer(),
            TerminalProtocol.Mouse(action, button, column, row),
        )
        outbound.send(OutboundFrame.Text(record))
    }

    private val released = AtomicBoolean(false)

    override suspend fun release() {
        // Idempotent: DisposableEffect and onCleared race on the same socket when
        // the screen goes away. Only the first call sends/closes; the rest no-op.
        if (!released.compareAndSet(false, true)) return
        val record = HerdrJson.encodeToString(
            dev.herdr.mobile.core.model.TerminalRelease.serializer(),
            dev.herdr.mobile.core.model.TerminalRelease(),
        )
        try {
            // Send the release frame DIRECTLY on the socket, not through the pump
            // channel: the pump may not have drained yet when we close, and a
            // queued-but-unsent release leaks the direct-attach resize lock.
            val ws = socket
            if (ws != null) {
                // ws.send returns false when the socket is already closing: fall
                // back to the pump channel so the frame still goes out if the
                // socket recovers, instead of silently dropping the release.
                if (!ws.send(record)) {
                    runCatching { outbound.send(OutboundFrame.Text(record)) }
                }
                // Give OkHttp one flush cycle before the close frame.
                kotlinx.coroutines.delay(150)
            } else {
                outbound.send(OutboundFrame.Text(record))
            }
        } catch (_: Exception) {
            // Channel already closed; the socket teardown below finishes the job.
        }
        try {
            outbound.close()
        } catch (_: Exception) {
        }
        closeInboundLane()
        try {
            socket?.close(1000, "release")
        } catch (_: Exception) {
        }
        socket = null
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            open.set(true)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            emit(TerminalInbound.Bytes(bytes.toByteArray()))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val record = try {
                HerdrJson.decodeFromString(RawTerminalRecord.serializer(), text)
            } catch (_: Exception) {
                return
            }
            when (record.type) {
                TerminalProtocol.Ready.TYPE -> {
                    val obj = try {
                        HerdrJson.parseToJsonElement(text).jsonObject
                    } catch (_: Exception) {
                        return
                    }
                    fun int(key: String): Int =
                        obj[key]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val ready = TerminalInbound.Ready(
                        paneId = obj["paneId"]?.jsonPrimitive?.content ?: paneId,
                        cols = int("cols"),
                        rows = int("rows"),
                        resumed = obj["resumed"]?.jsonPrimitive?.content == "true",
                    )
                    _state.value = TerminalAttachmentState.Attached(
                        paneId = ready.paneId,
                        cols = ready.cols,
                        rows = ready.rows,
                        resumed = ready.resumed,
                    )
                    emit(ready)
                }

                TerminalProtocol.Closed.TYPE -> {
                    val reason = record.message ?: "closed"
                    _state.value = TerminalAttachmentState.Detached(reason)
                    emit(TerminalInbound.Closed(reason))
                }

                TerminalProtocol.Failure.TYPE -> {
                    val code = record.code ?: "unknown"
                    val message = record.message ?: "attach failed"
                    _state.value = TerminalAttachmentState.Failed(code, message)
                    emit(TerminalInbound.Failed(code, message))
                }

                else -> {
                    // Unknown control record from a newer daemon: ignore, stay attached.
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            open.set(false)
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            open.set(false)
            if (_state.value is TerminalAttachmentState.Attaching ||
                _state.value is TerminalAttachmentState.Attached
            ) {
                _state.value = TerminalAttachmentState.Detached(reason.ifBlank { "closed" })
                emit(TerminalInbound.Closed(reason.ifBlank { "closed" }))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            open.set(false)
            val message = t.message ?: response?.message.orEmpty()
            if (_state.value is TerminalAttachmentState.Attaching) {
                _state.value = TerminalAttachmentState.Failed("connect_failed", message)
                emit(TerminalInbound.Failed("connect_failed", message))
            } else if (_state.value is TerminalAttachmentState.Attached) {
                _state.value = TerminalAttachmentState.Detached(message.ifBlank { "failed" })
                emit(TerminalInbound.Closed(message.ifBlank { "failed" }))
            }
        }
    }
}