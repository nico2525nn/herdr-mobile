package dev.herdr.mobile.connection.direct

import dev.herdr.mobile.core.model.BackendMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
