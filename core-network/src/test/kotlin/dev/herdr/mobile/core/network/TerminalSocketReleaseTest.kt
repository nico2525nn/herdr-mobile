package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.BackendMode
import dev.herdr.mobile.core.model.TerminalAttachmentState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.runningFold

/**
 * Double-release must be safe: DisposableEffect and onCleared race on the same
 * socket when the terminal screen goes away. Only the first release() may send;
 * the rest no-op instead of sending a second release frame + close.
 *
 * This test drives the PRODUCTION release() path through a fake WebSocket and
 * counts actual send() calls — a return-without-send regression goes red.
 */
class TerminalSocketReleaseTest {

    private class FakeWebSocket : WebSocket {
        val textSends = AtomicInteger(0)
        val sentText = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sentBinary = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val closes = AtomicInteger(0)
        /** Simulates OkHttp refusing a frame on a closing/full socket. */
        @Volatile var failSends = false
        /** Blocks every send until [unblockSends], to stall the pump. */
        @Volatile var blockSends = false
        val sendEntered = CountDownLatch(1)
        val unblockSends = CountDownLatch(1)
        @Volatile var isClosed = false

        override fun request(): Request =
            Request.Builder().url("http://127.0.0.1:1/").build()

        override fun queueSize(): Long = 0

        private fun waitForGate() {
            if (!blockSends) return
            sendEntered.countDown()
            unblockSends.await(5, TimeUnit.SECONDS)
        }

        override fun send(text: String): Boolean {
            waitForGate()
            if (failSends || isClosed) return false
            sentText += text
            textSends.incrementAndGet()
            return true
        }

        override fun send(bytes: ByteString): Boolean {
            waitForGate()
            if (failSends || isClosed) return false
            sentBinary += bytes.toByteArray()
            return true
        }

        override fun close(code: Int, reason: String?): Boolean {
            isClosed = true
            closes.incrementAndGet()
            return true
        }

        override fun cancel() {}
    }

    private class CapturingClient(val fake: FakeWebSocket) : OkHttpClient() {
        var capturedRequest: Request? = null
        var capturedListener: WebSocketListener? = null

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            capturedRequest = request
            capturedListener = listener
            listener.onOpen(fake, okhttp3.Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build())
            return fake
        }
    }

    @Test
    fun doubleReleaseSendsOnce() {
        val fake = FakeWebSocket()
        val socket = TerminalSocket(
            http = CapturingClient(fake),
            endpoint = DaemonEndpoint.direct("http://127.0.0.1:1", null, "test"),
            paneId = "w1:pX",
            cols = 80,
            rows = 24,
            takeover = false,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        socket.connect()
        val done = CountDownLatch(2)
        val t1 = Thread {
            kotlinx.coroutines.runBlocking {
                socket.release()
                done.countDown()
            }
        }
        val t2 = Thread {
            kotlinx.coroutines.runBlocking {
                socket.release()
                done.countDown()
            }
        }
        t1.start()
        t2.start()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals("exactly one release frame", 1, fake.textSends.get())
        assertEquals("exactly one close frame", 1, fake.closes.get())
    }

    @Test
    fun queuedInputIsFlushedBeforeTheReleaseFrame() = kotlinx.coroutines.runBlocking {
        // Regression: release() used to send the detach record directly and
        // close the pump channel, silently dropping a keystroke accepted just
        // before the screen went away.
        val fake = FakeWebSocket()
        val socket = tuiSocket(fake)
        socket.connect()
        socket.sendText("hello")
        socket.release()

        val inputAt = fake.sentText.indexOfFirst { it.contains("\"text\":\"hello\"") }
        val releaseAt = fake.sentText.indexOfFirst { it.contains("\"type\":\"release\"") }
        assertTrue("input frame missing: ${fake.sentText}", inputAt >= 0)
        assertTrue("release frame missing: ${fake.sentText}", releaseAt >= 0)
        assertTrue("release overtook queued input: ${fake.sentText}", inputAt < releaseAt)
        assertEquals(
            "exactly one release frame",
            1,
            fake.sentText.count { it.contains("\"type\":\"release\"") },
        )
        assertEquals("socket closed once", 1, fake.closes.get())
    }

    @Test
    fun refusedSendSurfacesClosedAndDetaches() = kotlinx.coroutines.runBlocking {
        // A false from ws.send() means the frame never left: do not pretend
        // the input was delivered, and let the reattach path resync.
        val fake = FakeWebSocket()
        val client = CapturingClient(fake)
        val socket = TerminalSocket(
            http = client,
            endpoint = DaemonEndpoint.direct("http://127.0.0.1:1", null, "test"),
            paneId = "w1:pX",
            cols = 80,
            rows = 24,
            takeover = false,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        socket.connect()
        requireNotNull(client.capturedListener).onMessage(
            fake,
            """{"type":"ready","paneId":"w1:pX","cols":80,"rows":24,"resumed":false}""",
        )
        assertTrue(socket.state.value is TerminalAttachmentState.Attached)

        fake.failSends = true
        socket.sendText("lost")
        val closed = kotlinx.coroutines.withTimeout(5_000) {
            socket.inbound.filterIsInstance<TerminalInbound.Closed>().first()
        }
        assertEquals("send_failed", closed.reason)
        assertTrue(socket.state.value is TerminalAttachmentState.Detached)
        assertTrue("socket must look closed", !socket.isOpen)
        assertTrue("socket must be closed", fake.closes.get() >= 1)
    }

    @Test
    fun sendAfterReleaseFailsInsteadOfSilentlyQueueing() = kotlinx.coroutines.runBlocking {
        val fake = FakeWebSocket()
        val socket = tuiSocket(fake)
        socket.connect()
        socket.release()

        val error = runCatching { socket.sendText("late") }.exceptionOrNull()
        assertTrue("late send must fail, got $error", error is IllegalStateException)
        assertTrue(
            "late frame must not reach the socket: ${fake.sentText}",
            fake.sentText.none { it.contains("\"text\":\"late\"") },
        )
    }

    @Test
    fun initialBytesSurviveABurstBeforeTheFirstSubscriber() = kotlinx.coroutines.runBlocking {
        // SharedFlow replay keeps the LAST 256 emissions. Before the Compose
        // bridge subscribed, a long output burst evicted the prelude and the
        // view started mid-stream. The gate holds emissions until a collector
        // exists, so the first byte run still starts with the prelude.
        val fake = FakeWebSocket()
        val client = CapturingClient(fake)
        val socket = TerminalSocket(
            http = client,
            endpoint = DaemonEndpoint.direct("http://127.0.0.1:1", null, "test"),
            paneId = "w1:pX",
            cols = 80,
            rows = 24,
            takeover = false,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        socket.connect()
        val listener = requireNotNull(client.capturedListener)
        val prelude = "PRELUDE".toByteArray()
        listener.onMessage(fake, ByteString.of(*prelude))
        kotlinx.coroutines.yield()
        repeat(300) { i ->
            listener.onMessage(fake, ByteString.of(('a'.code + i % 26).toByte()))
            kotlinx.coroutines.yield()
        }

        val first = kotlinx.coroutines.withTimeout(5_000) {
            socket.inbound.filterIsInstance<TerminalInbound.Bytes>().first()
        }
        assertArrayEquals(
            "the prelude must not be evicted by the live burst",
            prelude,
            first.data.copyOfRange(0, prelude.size),
        )
        socket.release()
    }

    @Test
    fun fullOutboundQueueIsDrainedBeforeTheSocketCloses() = kotlinx.coroutines.runBlocking {
        // A full 128-frame queue made trySend(release) fail; the old code then
        // sent release directly and closed the socket, discarding every
        // accepted frame. The pump must drain the queue first.
        val fake = FakeWebSocket().apply { blockSends = true }
        val socket = TerminalSocket(
            http = CapturingClient(fake),
            endpoint = DaemonEndpoint.direct("http://127.0.0.1:1", null, "test"),
            paneId = "w1:pX",
            cols = 80,
            rows = 24,
            takeover = false,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
        socket.connect()
        val total = 129 // one frame in the pump + 128 in the channel
        val producer = launch(kotlinx.coroutines.Dispatchers.Default) {
            repeat(total) { i -> socket.send(byteArrayOf(i.toByte())) }
        }
        assertTrue("pump never entered send", fake.sendEntered.await(5, TimeUnit.SECONDS))
        kotlinx.coroutines.delay(100)
        val release = launch(kotlinx.coroutines.Dispatchers.Default) {
            socket.release()
        }
        kotlinx.coroutines.delay(100)
        fake.unblockSends.countDown()
        kotlinx.coroutines.withTimeout(5_000) {
            producer.join()
            release.join()
        }

        // The pump merges adjacent binary frames, so count delivered BYTES,
        // not socket frames: all 129 accepted bytes must survive the release.
        assertEquals(
            "every accepted byte must reach the socket",
            (0 until total).map { it.toByte() },
            fake.sentBinary.flatMap { it.toList() },
        )
        assertTrue(
            "release must still be sent after the drain: ${fake.sentText}",
            fake.sentText.any { it.contains("\"type\":\"release\"") },
        )
        assertEquals("socket closed once", 1, fake.closes.get())
    }

    private fun tuiSocket(fake: FakeWebSocket): TerminalSocket = TerminalSocket(
        http = CapturingClient(fake),
        endpoint = DaemonEndpoint.direct("http://127.0.0.1:1", null, "test"),
        paneId = "w1:pX",
        cols = 80,
        rows = 24,
        takeover = false,
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
    )

    @Test
    fun hostScrollDragSendsAnAbsoluteControlFrame() {
        val fake = FakeWebSocket()
        val socket = TerminalSocket(
            http = CapturingClient(fake),
            endpoint = DaemonEndpoint.direct("http://127.0.0.1:1", null, "test"),
            paneId = "w1:pX",
            cols = 80,
            rows = 24,
            takeover = false,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        socket.connect()
        kotlinx.coroutines.runBlocking { socket.scrollHostTo(42) }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline &&
            fake.sentText.none { it.contains("\"type\":\"host_scroll\"") }
        ) {
            Thread.sleep(5)
        }
        assertTrue(
            "host scroll control was not sent: ${fake.sentText}",
            fake.sentText.any {
                it.contains("\"type\":\"host_scroll\"") &&
                    it.contains("\"offsetFromBottom\":42")
            },
        )
    }

    @Test
    fun backendModeIsSentOnTerminalSocketHandshake() {
        val fake = FakeWebSocket()
        val client = CapturingClient(fake)
        val socket = TerminalSocket(
            http = client,
            endpoint = DaemonEndpoint.direct(
                "http://127.0.0.1:1",
                null,
                "test",
                BackendMode.CORE,
            ),
            paneId = "core:t1",
            cols = 80,
            rows = 24,
            takeover = false,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )

        socket.connect()
        assertEquals(BackendMode.CORE, socket.backendMode)
        assertEquals("core", client.capturedRequest?.header("X-Herdr-Backend"))
    }

    @Test
    fun coreScrollPageKeyRequiresExplicitSafeIntent() = kotlinx.coroutines.runBlocking {
        // Regression for bug class #2: core scroll used to inject PgUp into shell
        // foregrounds (for example `sleep`) because the server had no route hint.
        val fake = FakeWebSocket()
        val socket = TerminalSocket(
            http = CapturingClient(fake),
            endpoint = DaemonEndpoint.direct("http://127.0.0.1:1", null, "core", BackendMode.CORE),
            paneId = "core:t1",
            cols = 80,
            rows = 24,
            takeover = false,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        socket.connect()
        socket.scrollRemote(5, allowPageKey = true, unit = "rows")
        assertTrue(fake.sentText.single().contains("\"allowPageKey\":true"))

        val tuiFake = FakeWebSocket()
        val tui = TerminalSocket(
            http = CapturingClient(tuiFake),
            endpoint = DaemonEndpoint.direct("http://127.0.0.1:1", null, "tui"),
            paneId = "w1:p1",
            cols = 80,
            rows = 24,
            takeover = false,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        tui.connect()
        tui.scrollRemote(5, allowPageKey = true, unit = "rows")
        assertTrue(tuiFake.sentText.single().contains("\"type\":\"scroll\""))
        assertTrue(!tuiFake.sentText.single().contains("allowPageKey"))
    }

    @Test
    fun bracketedPasteMarkersRemainRawBytesOnCoreSocket() = kotlinx.coroutines.runBlocking {
        // Regression for bug class #1: generated ESC markers must use binary DATA,
        // never `input.text` where paste normalization would strip them.
        val fake = FakeWebSocket()
        val socket = TerminalSocket(
            http = CapturingClient(fake),
            endpoint = DaemonEndpoint.direct("http://127.0.0.1:1", null, "core", BackendMode.CORE),
            paneId = "core:t1",
            cols = 80,
            rows = 24,
            takeover = false,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        socket.connect()
        val paste = "\u001b[200~hello\u001b[201~".toByteArray(Charsets.UTF_8)
        socket.send(paste)
        assertArrayEquals(paste, fake.sentBinary.single())
        assertTrue(fake.sentText.isEmpty())
    }

    @Test
    fun coreAttachBytesUseTheSameOrderedTerminalInboundLane() = kotlinx.coroutines.runBlocking {
        // Regression for bug class #7: core repaint frames must not bypass the
        // TerminalSocket byte lane used by TUI (ordering/burst coalescing path).
        val fake = FakeWebSocket()
        val client = CapturingClient(fake)
        val socket = TerminalSocket(
            http = client,
            endpoint = DaemonEndpoint.direct("http://127.0.0.1:1", null, "core", BackendMode.CORE),
            paneId = "core:t1",
            cols = 80,
            rows = 24,
            takeover = false,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        socket.connect()
        val first = byteArrayOf(0x1b, '['.code.toByte())
        val second = byteArrayOf('2'.code.toByte(), 'm'.code.toByte())
        val listener = requireNotNull(client.capturedListener)
        listener.onMessage(fake, ByteString.of(*first))
        listener.onMessage(fake, ByteString.of(*second))

        val received = kotlinx.coroutines.withTimeout(5_000) {
            socket.inbound.filterIsInstance<TerminalInbound.Bytes>()
                .runningFold(byteArrayOf()) { accumulated, frame -> accumulated + frame.data }
                .first { it.size >= first.size + second.size }
        }
        assertArrayEquals(first + second, received)
    }
}
