package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.BackendMode
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daemon's HTTP contract as the client observes it: which request the
 * client builds, and how every non-2xx answer is classified. No sockets: a
 * recording interceptor plays the server.
 */
class DaemonApiContractTest {

    private val recorded = mutableListOf<Request>()

    private fun api(
        status: Int = 200,
        body: String = "{}",
        token: String? = null,
        backend: BackendMode = BackendMode.TUI,
    ): DaemonApi {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            recorded += request
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("scripted")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return DaemonApi(
            http,
            DaemonEndpoint.direct("http://127.0.0.1:8765", token, "local", backend),
        )
    }

    @Test
    fun `requests carry the backend mode and the bearer token`() = runBlocking {
        api(token = "s3cret", backend = BackendMode.CORE).health()
        val request = recorded.single()
        assertEquals("core", request.header("X-Herdr-Backend"))
        assertEquals("Bearer s3cret", request.header("Authorization"))
        assertEquals("http://127.0.0.1:8765/v1/health", request.url.toString())
    }

    @Test
    fun `an anonymous endpoint sends no authorization header`() = runBlocking {
        api().health()
        assertNull(recorded.single().header("Authorization"))
    }

    @Test
    fun `pane ids are url-encoded into the path`() = runBlocking {
        api().sendInput("w1:p 1", "hi")
        assertEquals(
            "http://127.0.0.1:8765/v1/pane/w1%3Ap+1/input",
            recorded.single().url.toString(),
        )
    }

    @Test
    fun `utf-8 is the default encoding and base64 is explicit`() = runBlocking {
        api().sendInput("w1:p1", "こんにちは")
        api().sendInput("w1:p1", "aGk=", base64 = true)
        // encodeDefaults=false: the daemon treats an absent field as utf-8.
        assertTrue(recorded[0].bodyText().contains("\"text\":\"こんにちは\""))
        assertTrue(!recorded[0].bodyText().contains("encoding"))
        assertTrue(recorded[1].bodyText().contains("\"encoding\":\"base64\""))
        assertTrue(recorded[1].bodyText().contains("aGk="))
    }

    @Test
    fun `401 is classified as an authentication failure`() = runBlocking {
        val error = runCatching {
            api(status = 401, body = """{"error":{"code":"x"}}""").snapshot()
        }.exceptionOrNull() as DaemonException
        assertEquals("unauthorized", error.code)
        assertEquals(401, error.httpStatus)
        assertTrue(error.message.contains("token"))
    }

    @Test
    fun `a typed server error keeps its code and message`() = runBlocking {
        val error = runCatching {
            api(status = 409, body = """{"error":{"code":"conflict","message":"tab moved"}}""")
                .closeTab("t1")
        }.exceptionOrNull() as DaemonException
        assertEquals("conflict", error.code)
        assertEquals("tab moved", error.message)
        assertEquals(409, error.httpStatus)
    }

    @Test
    fun `an untyped error falls back to the status code`() = runBlocking {
        val error = runCatching {
            api(status = 500, body = "<html>oops</html>").closeTab("t1")
        }.exceptionOrNull() as DaemonException
        assertEquals("http_500", error.code)
        assertEquals("<html>oops</html>", error.message)
    }

    @Test
    fun `an empty error body still yields a usable message`() = runBlocking {
        val error = runCatching { api(status = 503, body = "").closeTab("t1") }
            .exceptionOrNull() as DaemonException
        assertEquals("http_503", error.code)
        assertEquals("HTTP 503", error.message)
    }

    private fun Request.bodyText(): String {
        val buffer = okio.Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }
}
