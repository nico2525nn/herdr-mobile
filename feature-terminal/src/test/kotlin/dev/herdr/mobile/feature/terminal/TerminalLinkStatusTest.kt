package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.designsystem.LinkTone
import dev.herdr.mobile.core.designsystem.UiMessage
import dev.herdr.mobile.core.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalLinkStatusTest {

    private fun status(
        connection: ConnectionState,
        stale: Boolean = false,
        failure: UiMessage? = null,
        detached: String? = null,
        elapsed: Long = 0,
        hasBackend: Boolean = true,
    ) = terminalLinkStatus(connection, stale, failure, detached, elapsed, hasBackend)

    @Test
    fun `no backend is the placeholder's state, not a row`() {
        assertNull(status(ConnectionState.RECONNECTING, hasBackend = false))
    }

    @Test
    fun `a healthy attached terminal has no row`() {
        assertNull(status(ConnectionState.CONNECTED))
    }

    @Test
    fun `a live link with an attach failure leaves the failure block alone`() {
        assertNull(
            status(
                ConnectionState.CONNECTED,
                failure = UiMessage.AttachFailed("pane_not_found"),
            ),
        )
    }

    @Test
    fun `reconnecting shows elapsed seconds`() {
        assertEquals(
            TerminalLinkStatus("Reconnecting… 7s", LinkTone.BUSY),
            status(ConnectionState.RECONNECTING, elapsed = 7),
        )
    }

    @Test
    fun `a failed link is a red row`() {
        assertEquals(
            TerminalLinkStatus("Link down", LinkTone.ERROR),
            status(ConnectionState.FAILED),
        )
    }

    @Test
    fun `a detached stream on a live link says reattaching`() {
        assertEquals(
            TerminalLinkStatus("Reattaching…", LinkTone.BUSY),
            status(ConnectionState.CONNECTED, detached = "released"),
        )
    }

    @Test
    fun `lagged resync and stale have distinct copy`() {
        assertEquals(
            TerminalLinkStatus("Resyncing…", LinkTone.BUSY),
            status(ConnectionState.CONNECTED, detached = "lagged_resync"),
        )
        assertEquals(
            TerminalLinkStatus("Refreshing…", LinkTone.WARNING),
            status(ConnectionState.CONNECTED, stale = true),
        )
    }
}
