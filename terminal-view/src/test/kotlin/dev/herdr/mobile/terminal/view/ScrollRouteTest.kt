package dev.herdr.mobile.terminal.view

import dev.herdr.mobile.core.model.AgentMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll-routing matrix. Every row here is a real pane shape seen on the
 * device; the "stale agent label" rows are the regression that made shell
 * scrolling dead.
 */
class ScrollRouteTest {

    @Test
    fun `plain shell with history scrolls locally`() {
        assertEquals(
            ScrollOwner.LOCAL,
            scrollOwner(altScreen = false, mouseTracking = false, hostBackedAgent = false, localHistoryRows = 120),
        )
    }

    @Test
    fun `stale agent label on a shell still scrolls locally`() {
        // Herdr reports agent=codex for a pane that already exited to a prompt.
        assertEquals(
            ScrollOwner.LOCAL,
            scrollOwner(altScreen = false, mouseTracking = false, hostBackedAgent = true, localHistoryRows = 18),
        )
    }

    @Test
    fun `alt screen always routes remote`() {
        assertEquals(
            ScrollOwner.REMOTE,
            scrollOwner(altScreen = true, mouseTracking = false, hostBackedAgent = false, localHistoryRows = 500),
        )
    }

    @Test
    fun `mouse tracking routes remote even without alt screen`() {
        assertEquals(
            ScrollOwner.REMOTE,
            scrollOwner(altScreen = false, mouseTracking = true, hostBackedAgent = false, localHistoryRows = 500),
        )
    }

    @Test
    fun `main screen agent without local history uses host scrollback`() {
        // omp: frames are replayed, the transcript lives on the host.
        assertEquals(
            ScrollOwner.REMOTE,
            scrollOwner(altScreen = false, mouseTracking = false, hostBackedAgent = true, localHistoryRows = 0),
        )
    }

    @Test
    fun `wheel agents get wheel notches only while mouse tracking is on`() {
        for (agent in listOf("codex", "pi", "opencode", "qwen")) {
            assertTrue("$agent should be wheel-based", AgentMatrix.useWheelScroll(agent))
            assertEquals(RemoteScrollKind.WHEEL, remoteScrollKind(agent, mouseTracking = true))
            // Without mouse tracking the SGR bytes are inert input.
            assertEquals(
                RemoteScrollKind.PAGE_KEY,
                remoteScrollKind(agent, mouseTracking = false),
            )
        }
    }

    @Test
    fun `page key agents never use wheel`() {
        assertEquals(RemoteScrollKind.PAGE_KEY, remoteScrollKind("omp", mouseTracking = true))
        assertEquals(RemoteScrollKind.PAGE_KEY, remoteScrollKind(null, mouseTracking = true))
        assertEquals(RemoteScrollKind.PAGE_KEY, remoteScrollKind("", mouseTracking = false))
    }
}
