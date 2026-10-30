package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.BackendMode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class DaemonApiUnavailableTest {
    @Test
    fun unavailableIsPreservedAsTypedUserVisibleError() = runBlocking {
        // Regression for bug class #8: core-only operations must not become a
        // successful-looking no-op or an unclassified transport failure.
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(501)
                .message("Not Implemented")
                .body(
                    """{"error":{"code":"unavailable","message":"tab.move is unavailable for herdr-core"}}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                .build()
        }.build()
        val api = DaemonApi(
            http,
            DaemonEndpoint.direct("http://127.0.0.1:8765", null, "core", BackendMode.CORE),
        )

        val error = runCatching { api.moveTab("tab-1", 1) }.exceptionOrNull() as DaemonException
        assertEquals("unavailable", error.code)
        assertEquals(501, error.httpStatus)
        assertEquals("tab.move is unavailable for herdr-core", error.message)
    }
}
