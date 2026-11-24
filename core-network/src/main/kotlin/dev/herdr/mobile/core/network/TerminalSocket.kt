package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.BackendMode
import dev.herdr.mobile.core.model.TerminalAttachmentState
import dev.herdr.mobile.core.model.TerminalProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface OutboundFrame {
    data class Binary(val data: ByteArray) : OutboundFrame

    /** Typed text: encoded once per burst, not once per character. */
    data class TextInput(val text: String) : OutboundFrame
    data class Text(val text: String) : OutboundFrame

    /**
     * The detach record. Distinct from [Text] so the pump can prove it reached
     * OkHttp before the socket closes: input queued before the release must not
     * be dropped just because the screen went away.
     */
    data class Release(val text: String) : OutboundFrame
}

/**
 * Collapse a turn of queued outbound frames. Bytes concatenate (PTY order),
 * typed text accumulates into one `input.text`, and any control record flushes
 * both first so resize/scroll never jump ahead of input.
 */
internal fun mergeOutbound(values: List<OutboundFrame>): List<OutboundFrame> {
    val out = ArrayList<OutboundFrame>(values.size)
    var bytes: java.io.ByteArrayOutputStream? = null
    var text: StringBuilder? = null
    fun flush() {
        bytes?.let { out += OutboundFrame.Binary(it.toByteArray()) }
        bytes = null
        text?.let { out += OutboundFrame.TextInput(it.toString()) }
        text = null
    }
    for (value in values) {
        when (value) {
            is OutboundFrame.Binary -> {
                if (text != null) flush()
                // `plus` copied the whole accumulator for every frame; a
                // fling of 100 binary frames was O(n²). Append once here and
                // materialize on flush.
                (bytes ?: java.io.ByteArrayOutputStream(value.data.size * 2).also { bytes = it })
                    .write(value.data)
            }
            is OutboundFrame.TextInput -> {
                if (bytes != null) flush()
                text = text?.append(value.text) ?: StringBuilder(value.text)
            }
            is OutboundFrame.Text, is OutboundFrame.Release -> {
                flush()
                out += value
            }
        }
    }
    flush()
    return out
}

/**
 * Everything the terminal screen observes or drives for one attached pane.
 *
 * Byte traffic never touches the JSON layer: binary frames are fed to the emulator as they
 * arrive and keystrokes leave as binary frames. Control records ([resize], [scroll], [mouse])
 * leave as JSON text frames; [TerminalProtocol.Ready] / [Closed] arrive as JSON text frames.
 */
interface TerminalConnection {
    val backendMode: BackendMode get() = BackendMode.TUI
    val state: StateFlow<TerminalAttachmentState>
    val inbound: Flow<TerminalInbound>

    suspend fun send(data: ByteArray)
    suspend fun sendText(text: String)
    suspend fun resize(cols: Int, rows: Int)
    suspend fun scrollRemote(lines: Int, allowPageKey: Boolean, unit: String)

    /** TUI host scrollbar drag: set the absolute offset from the live bottom. */
    suspend fun scrollHostTo(offsetFromBottom: Long)
    suspend fun mouse(action: String, button: String, column: Int, row: Int)
    suspend fun release()

    /**
     * Drop the replay cache once the live consumer has drained it. The cache
     * exists only for the attach race (prelude before the pump subscribes);
     * afterwards it would pin history frames for the session's lifetime.
     */
    fun clearReplayCache() {}

    val isOpen: Boolean
}

sealed interface TerminalInbound {
    data class Ready(
        val paneId: String,
        val cols: Int,
        val rows: Int,
        val resumed: Boolean,
        val historyRows: Int = 0,
        val historyTruncated: Boolean = false,
        val historyError: String? = null,
        val visibleOk: Boolean = true,
        /** History prelude (cols=0) is not proof that the controller attached. */
        val prelude: Boolean = false,
    ) : TerminalInbound
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
    override val backendMode: BackendMode get() = endpoint.backendMode

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
    /** Orders enqueues against release so no frame can land after the detach record. */
    private val outboundLock = Mutex()
    private val releaseClaimed = AtomicBoolean(false)
    private val releaseFrameSent = AtomicBoolean(false)
    private val releaseDrained = CompletableDeferred<Unit>()
    private val sendBroken = AtomicBoolean(false)
    /**
     * Do not publish inbound frames before the first collector exists.
     * SharedFlow replay keeps the LAST N emissions; an initial prelude
     * followed by a 256-frame output burst evicted the prelude before the
     * Compose bridge subscribed, and the view started mid-stream.
     */
    private val firstSubscriber = CompletableDeferred<Unit>()
    private var subscriptionWatcher: Job? = null

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
            .header("X-Herdr-Backend", endpoint.backendMode.wire)
            .apply { endpoint.token?.let { header("Authorization", "Bearer $it") } }
            .build()

        socket = http.newWebSocket(request, Listener())
        subscriptionWatcher = scope.launch {
            _inbound.subscriptionCount.first { it > 0 }
            firstSubscriber.complete(Unit)
        }
        scope.launch { pump() }
    }

    private suspend fun pump() {
        try {
            while (true) {
                val first = outbound.receiveCatching().getOrNull() ?: break
                // Drain what is already queued, then send one frame per kind: key
                // rolls and IME bursts become a single socket frame. No timer, so
                // latency is unchanged; control records still flush and keep order.
                val turn = ArrayList<OutboundFrame>(8)
                turn += first
                while (true) {
                    turn += outbound.tryReceive().getOrNull() ?: break
                }
                val ws = socket ?: break
                var failed = false
                for (frame in mergeOutbound(turn)) {
                    val sent = when (frame) {
                        is OutboundFrame.Binary -> ws.send(ByteString.of(*frame.data))
                        is OutboundFrame.TextInput -> ws.send(encodeInputText(frame.text))
                        is OutboundFrame.Text -> ws.send(frame.text)
                        is OutboundFrame.Release ->
                            if (releaseClaimed.compareAndSet(false, true)) {
                                ws.send(frame.text).also { releaseFrameSent.set(it) }
                            } else {
                                // A stalled pump timed out and sent the detach
                                // record directly; do not duplicate it.
                                true
                            }
                    }
                    if (!sent) {
                        failed = true
                        break
                    }
                }
                if (failed) {
                    failOutbound()
                    break
                }
            }
        } finally {
            // Unblocks release() even when the pump ended before the detach
            // record (closed socket, send failure, or no connect at all).
            releaseDrained.complete(Unit)
        }
    }

    /**
     * OkHttp refused a frame: the socket is closing or its queue is full. The
     * emulator is now missing bytes, so surface the failure and let the normal
     * reattach path replay the visible screen instead of pretending the input
     * was delivered.
     */
    private fun failOutbound() {
        if (released.get() || !sendBroken.compareAndSet(false, true)) return
        open.set(false)
        val reason = "send_failed"
        _state.value = TerminalAttachmentState.Detached(reason)
        emit(TerminalInbound.Closed(reason))
        outbound.close()
        try {
            socket?.close(1011, reason)
        } catch (_: Exception) {
        }
    }

    private fun encodeInputText(text: String): String = HerdrJson.encodeToString(
        TerminalProtocol.InputText.serializer(),
        TerminalProtocol.InputText(text),
    )

    /**
     * Single ordered inbound lane: every frame (fast or slow path) goes through
     * this channel in arrival order, and ONE collector forwards to _inbound.
     * tryEmit-then-mutex lets a later fast-path frame overtake an earlier
     * queued suspend-emit; only a single ordered queue preserves PTY order.
     */
    // Bounded: the SharedFlow suspends the forwarder, but OkHttp's callback
    // cannot, so an unbounded lane pinned every byte when the UI stalled.
    // Overflow means the socket is unrecoverably behind; close and let the
    // normal reattach path replay the visible screen instead of growing RAM.
    private val inboundLane = Channel<TerminalInbound>(capacity = 1024)

    init {
        scope.launch {
            // Burst-merge: live output + scroll repaints arrive as many small
            // socket messages; forwarding each separately redraws the view N
            // times per frame (the judder under OMP output). One turn is the
            // received value plus everything already queued behind it, up to
            // and including the first control record; mergeBurst concatenates
            // consecutive Bytes (lossless, PTY order preserves) and lets a
            // control record flush pending bytes first. One emit per record
            // produced by the turn, not per socket message.
            for (value in inboundLane) {
                val turn = ArrayList<TerminalInbound>(4)
                turn += value
                if (value is TerminalInbound.Bytes) {
                    while (true) {
                        val next = inboundLane.tryReceive().getOrNull() ?: break
                        turn += next
                        // A control record ends the burst: bytes flush ahead
                        // of it inside mergeBurst, order intact.
                        if (next !is TerminalInbound.Bytes) break
                    }
                }
                firstSubscriber.await()
                if (released.get()) break
                for (merged in mergeBurst(turn)) _inbound.emit(merged)
            }
        }
    }

    private fun closeInboundLane() {
        // Stops the forwarder: without this each attach leaks one coroutine +
        // channel + socket refs on the app-lifetime scope, parked on receive
        // forever after release.
        subscriptionWatcher?.cancel()
        subscriptionWatcher = null
        // Release must not leave the forwarder parked waiting for a collector
        // that will never arrive.
        firstSubscriber.complete(Unit)
        inboundLane.close()
    }

    private fun emit(value: TerminalInbound) {
        // Never suspend the OkHttp callback thread. Order is preserved by the
        // single consumer; if the bounded lane is full the stream is too far
        // behind to heal in place, so close and resync instead of dropping
        // bytes (which would silently desync the emulator).
        if (released.get()) return
        if (!inboundLane.trySend(value).isSuccess) {
            socket?.close(1011, "lagged_resync")
        }
    }

    private suspend fun enqueue(frame: OutboundFrame) {
        outboundLock.withLock {
            // release() holds the same lock when it appends the detach record,
            // so a frame accepted here is always ahead of the release.
            if (released.get() || sendBroken.get()) {
                throw IllegalStateException("terminal stream is closed")
            }
            while (true) {
                val result = outbound.trySend(frame)
                if (result.isSuccess) return@withLock
                if (result.isClosed) {
                    throw IllegalStateException("terminal stream is closed")
                }
                // Do NOT suspend inside send(): a cancellation delivered
                // after the channel accepted the frame would make the caller
                // keep a pending input that was already queued (duplicate on
                // resume). trySend either accepts synchronously or not at all.
                currentCoroutineContext().ensureActive()
                delay(1)
            }
        }
    }

    override suspend fun send(data: ByteArray) {
        enqueue(OutboundFrame.Binary(data))
    }

    override suspend fun sendText(text: String) {
        enqueue(OutboundFrame.TextInput(text))
    }

    override suspend fun resize(cols: Int, rows: Int) {
        val record = HerdrJson.encodeToString(
            TerminalProtocol.Resize.serializer(),
            TerminalProtocol.Resize(cols, rows),
        )
        enqueue(OutboundFrame.Text(record))
    }

    override suspend fun scrollRemote(lines: Int, allowPageKey: Boolean, unit: String) {
        if (lines == 0) return
        // Sign matches view scrollRows: positive = finger up = toward live =
        // direction down; negative = toward older = direction up. Core cannot
        // distinguish shell input from host scrollback, so only send its
        // destructive PgUp/PgDn fallback when the view explicitly observed an
        // agent or alternate-screen app. TUI ignores the extra intent bit.
        val record = HerdrJson.encodeToString(
            TerminalProtocol.Scroll.serializer(),
            TerminalProtocol.Scroll(
                direction = if (lines > 0) TerminalProtocol.Scroll.DOWN else TerminalProtocol.Scroll.UP,
                lines = kotlin.math.abs(lines),
                allowPageKey = endpoint.backendMode == BackendMode.CORE && allowPageKey,
                unit = unit,
            ),
        )
        enqueue(OutboundFrame.Text(record))
    }

    override suspend fun scrollHostTo(offsetFromBottom: Long) {
        if (endpoint.backendMode != BackendMode.TUI) return
        val record = HerdrJson.encodeToString(
            TerminalProtocol.HostScroll.serializer(),
            TerminalProtocol.HostScroll(offsetFromBottom),
        )
        enqueue(OutboundFrame.Text(record))
    }

    override suspend fun mouse(action: String, button: String, column: Int, row: Int) {
        val record = HerdrJson.encodeToString(
            TerminalProtocol.Mouse.serializer(),
            TerminalProtocol.Mouse(action, button, column, row),
        )
        enqueue(OutboundFrame.Text(record))
    }

    private val released = AtomicBoolean(false)

    override fun clearReplayCache() {
        _inbound.resetReplayCache()
    }

    override suspend fun release() {
        // Idempotent: DisposableEffect and onCleared race on the same socket when
        // the screen goes away. Only the first call sends/closes; the rest no-op.
        if (!released.compareAndSet(false, true)) return
        val record = HerdrJson.encodeToString(
            dev.herdr.mobile.core.model.TerminalRelease.serializer(),
            dev.herdr.mobile.core.model.TerminalRelease(),
        )
        try {
            val ws = socket
            if (ws != null) {
                // Append the detach record BEHIND every frame already accepted,
                // then close the channel so the pump drains exactly that order.
                // The old path sent release directly and dropped queued keystrokes
                // when the screen was left immediately after typing.
                outboundLock.withLock {
                    outbound.trySend(OutboundFrame.Release(record))
                    outbound.close()
                }
                // Wait for the pump even when the release could not be queued
                // (a full channel): closing the channel still lets it drain the
                // accepted input, and only then does the socket close. Closing
                // immediately was what discarded all 128 queued keystrokes.
                withTimeoutOrNull(2_000) { releaseDrained.await() }
                // The pump may have ended (or the queue was full). Claim the
                // release exactly once so a healthy pump can never duplicate it.
                if (!releaseFrameSent.get() && releaseClaimed.compareAndSet(false, true)) {
                    runCatching { ws.send(record) }
                }
            } else {
                outbound.close()
            }
        } finally {
            // A caller timeout can cancel the drain wait. The WebSocket must
            // still close: the old code left it open, and every later
            // release() no-op'd because `released` was already set.
            closeInboundLane()
            try {
                socket?.close(1000, "release")
            } catch (_: Exception) {
            }
            socket = null
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            open.set(true)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            emit(TerminalInbound.Bytes(bytes.toByteArray()))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Pure parse: malformed / unknown records are ignored (stay attached).
            val transition = parseTerminalControl(
                text = text,
                previous = _state.value as? TerminalAttachmentState.Attached,
                fallbackPaneId = paneId,
                previousAttaching = _state.value as? TerminalAttachmentState.Attaching,
            ) ?: return
            _state.value = transition.state
            emit(transition.inbound)
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
