package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.BackendMode
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

        override fun request(): Request =
            Request.Builder().url("http://127.0.0.1:1/").build()

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean {
            sentText += text
            textSends.incrementAndGet()
            return true
        }

        override fun send(bytes: ByteString): Boolean {
            sentBinary += bytes.toByteArray()
            return true
        }

        override fun close(code: Int, reason: String?): Boolean {
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
        socket.scrollRemote(5, allowPageKey = true)
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
        tui.scrollRemote(5, allowPageKey = true)
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
