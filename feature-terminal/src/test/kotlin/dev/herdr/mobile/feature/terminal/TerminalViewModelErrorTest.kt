package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.network.DaemonEndpoint
import dev.herdr.mobile.core.network.DaemonEndpointProvider
import dev.herdr.mobile.core.network.HerdrClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalViewModelErrorTest {
    @Test
    fun unavailableReadAcknowledgementIsShownWithoutFailingAttach() = runBlocking {
        // Bug class #8: an unavailable read-ack must be visible, not swallowed or crash attach.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = HerdrClient(NoEndpointProvider, scope)
        var statusMessage: String? = null
        try {
            focusTabForReadAck(client, "tab") { statusMessage = it }
            assertEquals("Read acknowledgement failed: No endpoint is open", statusMessage)
        } finally {
            scope.cancel()
        }
    }

    private object NoEndpointProvider : DaemonEndpointProvider {
        override val id: String = "none"
        override val description: String = "no endpoint"

        override suspend fun open(): DaemonEndpoint = error("Not expected to open")
        override suspend fun close() = Unit
    }
}
