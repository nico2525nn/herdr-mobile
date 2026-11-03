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
    fun `a labelled pane starts remote even when a few local rows exist`() {
        // Codex/Pi bank prelude rows; treating those as a scrollback made the
        // pane "local" and the app's viewport never moved.
        assertEquals(
            ScrollOwner.REMOTE,
            scrollOwner(altScreen = false, mouseTracking = false, hostBackedAgent = true, localHistoryRows = 18),
        )
        // A stale label is handled at runtime (probe fails -> local fallback),
        // not by guessing here.
        assertEquals(
            ScrollOwner.REMOTE,
            scrollOwner(altScreen = false, mouseTracking = false, hostBackedAgent = true, localHistoryRows = 1),
        )
    }

    @Test
    fun `an unlabelled shell with history scrolls locally`() {
        assertEquals(
            ScrollOwner.LOCAL,
            scrollOwner(altScreen = false, mouseTracking = false, hostBackedAgent = false, localHistoryRows = 40),
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
    fun `any pane without local history scrolls remotely`() {
        // Mirrored full-screen TUIs (less, vim, top, agent panes) never
        // accumulate local scrollback, so a local scroll would be a no-op.
        assertEquals(
            ScrollOwner.REMOTE,
            scrollOwner(altScreen = false, mouseTracking = false, hostBackedAgent = true, localHistoryRows = 0),
        )
        assertEquals(
            ScrollOwner.REMOTE,
            scrollOwner(altScreen = false, mouseTracking = false, hostBackedAgent = false, localHistoryRows = 0),
        )
    }

    @Test
    fun `wheel agents get wheel notches only while mouse tracking is on`() {
        for (agent in listOf("codex", "pi", "opencode", "qwen")) {
            assertTrue("$agent should be wheel-based", AgentMatrix.useWheelScroll(agent))
            assertEquals(RemoteScrollKind.WHEEL, remoteScrollKind(agent, mouseTracking = true))
            // Without mouse tracking the SGR bytes are inert input, but the
            // *mechanism list* still offers wheel first: the mirror cannot see
            // the app's DECSET state, so the probe decides on the device.
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
