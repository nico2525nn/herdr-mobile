package dev.herdr.mobile.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        val closes = AtomicInteger(0)

        override fun request(): Request =
            Request.Builder().url("http://127.0.0.1:1/").build()

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean {
            textSends.incrementAndGet()
            return true
        }

        override fun send(bytes: ByteString): Boolean = true

        override fun close(code: Int, reason: String?): Boolean {
            closes.incrementAndGet()
            return true
        }

        override fun cancel() {}
    }

    private class CapturingClient(val fake: FakeWebSocket) : OkHttpClient() {
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
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
}
