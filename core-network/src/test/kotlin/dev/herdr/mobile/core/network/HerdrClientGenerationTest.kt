package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.SessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HerdrClientGenerationTest {
    @Test
    fun oldInFlightSnapshotCannotRestoreCacheAfterTargetSwitch() = runBlocking {
        // Bug class #3: an old API response arriving after a profile switch cannot republish cache.
        val requestStarted = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        val body = HerdrJson.encodeToString(SessionSnapshot.serializer(), SessionSnapshot(2, 8))
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requestStarted.countDown()
            check(allowResponse.await(5, TimeUnit.SECONDS))
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HerdrClient(NoEndpointProvider, scope, http)
        val oldSnapshot = SessionSnapshot(revision = 1, seq = 7)
        val oldGeneration = client.linkGeneration
        try {
            client.updateForGeneration(oldGeneration) {
                it.copy(connection = ConnectionState.CONNECTED, snapshot = oldSnapshot)
            }
            assertTrue(
                client.installEndpoint(
                    oldGeneration,
                    DaemonEndpoint.direct("http://127.0.0.1:8765", null, "old target"),
                ),
            )
            val refresh = scope.launch { client.refetch("manual", oldGeneration) }
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))

            // AppContainer invalidates immediately, then redials after provider rebuild.
            client.invalidateTarget()
            client.restartLink()
            allowResponse.countDown()
            refresh.join()

            assertNull(client.state.value.snapshot)
            assertEquals(ConnectionState.CONNECTING, client.state.value.connection)
            assertEquals(oldGeneration + 1, client.targetGeneration)
        } finally {
            allowResponse.countDown()
            scope.cancel()
        }
    }

    private object NoEndpointProvider : DaemonEndpointProvider {
        override val id: String = "none"
        override val description: String = "no endpoint"

        override suspend fun open(): DaemonEndpoint = throw kotlinx.coroutines.CancellationException("test")
        override suspend fun close() = Unit
    }
}

