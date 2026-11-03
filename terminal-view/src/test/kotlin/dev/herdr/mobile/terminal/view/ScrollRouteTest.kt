package dev.herdr.mobile.terminal.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Scroll routing, stated as protocol facts rather than app names.
 *
 * The audit that grounds these rules: codex, omp, opencode, grok and pi all
 * emit `?1049h` (alternate screen) and `?1000h`/`?1002h`/`?1003h` + `?1006h`
 * (SGR mouse reporting). So a full-screen pane can always be scrolled by input,
 * and only a pane the host keeps no scrollback for needs that path.
 */
class ScrollRouteTest {

    @Test
    fun `host scrollback always wins because it is line exact`() {
        assertEquals(
            ScrollSource.HOST,
            scrollSource(
                hostScrollKnown = true,
                hostScrollable = true,
                agentPane = true,
                altScreen = true,
                mouseTracking = true,
                localHistoryRows = 0,
            ),
        )
        // Plain shell with host history: still the host, not the mirror.
        assertEquals(
            ScrollSource.HOST,
            scrollSource(
                hostScrollKnown = true,
                hostScrollable = true,
                agentPane = false,
                altScreen = false,
                mouseTracking = false,
                localHistoryRows = 40,
            ),
        )
    }

    @Test
    fun `full-screen panes scroll by input`() {
        assertEquals(
            ScrollSource.APP_INPUT,
            scrollSource(
                hostScrollKnown = true,
                hostScrollable = false,
                agentPane = true,
                altScreen = true,
                mouseTracking = false,
                localHistoryRows = 5,
            ),
        )
        assertEquals(
            ScrollSource.APP_INPUT,
            scrollSource(
                hostScrollKnown = true,
                hostScrollable = false,
                agentPane = false,
                altScreen = false,
                mouseTracking = true,
                localHistoryRows = 5,
            ),
        )
    }

    @Test
    fun `an agent pane without host scrollback is still full-screen`() {
        // The TUI mirror cannot see DECSET (frames, not bytes), so the agent
        // label is the evidence that this pane paints its own viewport.
        assertEquals(
            ScrollSource.APP_INPUT,
            scrollSource(
                hostScrollKnown = true,
                hostScrollable = false,
                agentPane = true,
                altScreen = false,
                mouseTracking = false,
                localHistoryRows = 5,
            ),
        )
    }

    @Test
    fun `an unlabelled shell with a transcript scrolls locally`() {
        assertEquals(
            ScrollSource.LOCAL,
            scrollSource(
                hostScrollKnown = false,
                hostScrollable = false,
                agentPane = false,
                altScreen = false,
                mouseTracking = false,
                localHistoryRows = 40,
            ),
        )
    }

    @Test
    fun `a reported empty scrollback means full screen, whatever the mirror thinks`() {
        // This is the case that broke `less` and the agents: the mirror banked
        // a little history, but the host says there is no scrollback, so the
        // pane is a full-screen program and its input is the only lever.
        assertEquals(
            ScrollSource.APP_INPUT,
            scrollSource(
                hostScrollKnown = true,
                hostScrollable = false,
                agentPane = false,
                altScreen = false,
                mouseTracking = false,
                localHistoryRows = 40,
            ),
        )
    }

    @Test
    fun `unknown host state falls back to mirror evidence`() {
        // Older daemon: no scroll info. A plain shell still scrolls locally.
        assertEquals(
            ScrollSource.LOCAL,
            scrollSource(
                hostScrollKnown = false,
                hostScrollable = false,
                agentPane = false,
                altScreen = false,
                mouseTracking = false,
                localHistoryRows = 40,
            ),
        )
        assertEquals(
            ScrollSource.APP_INPUT,
            scrollSource(
                hostScrollKnown = false,
                hostScrollable = false,
                agentPane = false,
                altScreen = false,
                mouseTracking = false,
                localHistoryRows = 0,
            ),
        )
    }

    @Test
    fun `mechanisms are host then pages`() {
        assertEquals(
            listOf(ScrollMechanism.HOST, ScrollMechanism.PAGE_KEY),
            scrollMechanisms(hostScrollable = true, agentPane = true),
        )
        assertEquals(
            listOf(ScrollMechanism.PAGE_KEY),
            scrollMechanisms(hostScrollable = false, agentPane = true),
        )
        assertNull(
            nextMechanism(
                scrollMechanisms(hostScrollable = false, agentPane = true),
                failed = setOf(ScrollMechanism.PAGE_KEY),
            ),
        )
    }
}
