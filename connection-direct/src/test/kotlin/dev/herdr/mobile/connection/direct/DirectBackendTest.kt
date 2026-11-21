package dev.herdr.mobile.connection.direct

import dev.herdr.mobile.core.model.BackendMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectBackendTest {
    @Test
    fun selectedBackendIsCarriedToEndpoint() = runBlocking {
        val provider = DirectProvider(
            baseUrl = "http://127.0.0.1:8765",
            bearerToken = { null },
            backendMode = BackendMode.CORE,
        )

        assertEquals(BackendMode.CORE, provider.open().backendMode)
    }

    @Test
    fun cleartextBearerIsRefusedOffDevice() { runBlocking {
        // 10.0.2.2 is the emulator's host gateway, not a loopback exemption
        // for a real phone; a token must never leave the device in cleartext.
        val provider = DirectProvider(
            baseUrl = "http://10.0.2.2:8765",
            bearerToken = { "secret" },
        )
        assertThrows(IllegalArgumentException::class.java) { runBlocking { provider.open() } }
    } }

    @Test
    fun loopbackBearerIsAllowed() { runBlocking {
        val provider = DirectProvider(
            baseUrl = "http://127.0.0.1:8765",
            bearerToken = { "secret" },
        )
        assertEquals("secret", provider.open().token)
    } }

    @Test
    fun savedNonOriginUrlIsRejectedAtConnectTime() {
        // A URL saved by an older build can still be in DataStore even though
        // Settings now rejects new ones. REST and WebSocket resolve a base
        // path differently, so fail before either transport is built.
        for (legacy in listOf(
            "https://daemon.example:8765/api",
            "https://daemon.example:8765?legacy=1",
            "https://daemon.example:8765/#fragment",
        )) {
            val provider = DirectProvider(baseUrl = legacy, bearerToken = { null })
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { provider.open() }
            }
        }
    }
}
