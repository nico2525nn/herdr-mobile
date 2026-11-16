package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.network.DaemonEndpoint
import dev.herdr.mobile.core.network.DaemonEndpointProvider
import dev.herdr.mobile.core.network.HerdrClient
import dev.herdr.mobile.core.network.testing.FakeHerdrSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalViewModelErrorTest {
    @Test
    fun readAcknowledgementFocusesThePaneAndRefreshes() = runBlocking {
        val session = FakeHerdrSession()
        val ok = focusPaneForReadAck(session, "w1:p1") { error("should not fail") }
        org.junit.Assert.assertTrue(ok)
        org.junit.Assert.assertEquals(listOf("w1:p1"), session.paneFocusCalls)
        org.junit.Assert.assertEquals(1, session.refreshCalls)
    }

    @Test
    fun unavailableReadAcknowledgementIsShownWithoutFailingAttach() = runBlocking {
        // Bug class #8: an unavailable read-ack must be visible, not swallowed or crash attach.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = HerdrClient(NoEndpointProvider, scope)
        var statusMessage: String? = null
        try {
            focusPaneForReadAck(client, "pane") { statusMessage = it }
            assertEquals("No endpoint is open", statusMessage)
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
