package dev.herdr.mobile.core.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Double-release must be safe: DisposableEffect and onCleared race on the same
 * socket when the terminal screen goes away. Only the first release() may touch
 * the socket; the rest no-op instead of sending a second close frame.
 */
class TerminalSocketReleaseTest {

    @Test
    fun doubleReleaseSendsOnce() {
        val socket = TerminalSocket(
            http = OkHttpClient(),
            endpoint = DaemonEndpoint.direct("http://127.0.0.1:1", null, "test"),
            paneId = "w1:pX",
            cols = 80,
            rows = 24,
            takeover = false,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        val done = CountDownLatch(2)
        var first = false
        var second = false
        val t1 = Thread {
            kotlinx.coroutines.runBlocking {
                socket.release()
                first = true
                done.countDown()
            }
        }
        val t2 = Thread {
            kotlinx.coroutines.runBlocking {
                socket.release()
                second = true
                done.countDown()
            }
        }
        t1.start()
        t2.start()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(first && second)
    }
}
