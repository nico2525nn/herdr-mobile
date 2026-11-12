package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.network.testing.FakeHerdrSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalAttachTest {

    @Test
    fun `awaitConnected propagates caller cancellation instead of reporting a timeout`() = runTest {
        val session = FakeHerdrSession(connection = ConnectionState.RECONNECTING)

        // The old catch swallowed TimeoutCancellationException and returned
        // false, so callers continued as if the link had failed normally.
        val result = withTimeoutOrNull(50) { awaitConnected(session) }

        assertNull(result)
    }
}
