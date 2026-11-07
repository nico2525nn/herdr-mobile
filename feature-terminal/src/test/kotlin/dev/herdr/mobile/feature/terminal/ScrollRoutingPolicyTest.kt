package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.model.Pane
import dev.herdr.mobile.core.model.PaneScroll
import dev.herdr.mobile.core.model.ScrollMode
import io.github.sagernet.libghostty.GhosttyTerminalView.ScrollRouting
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollRoutingPolicyTest {

    private fun pane(
        agent: String? = null,
        scroll: PaneScroll? = null,
    ) = Pane(
        id = "w1:p1",
        tabId = "w1:t1",
        workspaceId = "w1",
        agent = agent,
        scroll = scroll,
    )

    private fun scrollback(rows: Long = 200) =
        PaneScroll(offsetFromBottom = 0, maxOffsetFromBottom = rows, viewportRows = 40)

    private fun fullScreen() = PaneScroll(0, 0, 40)

    @Test
    fun `a pane with host scrollback scrolls rows on the server`() {
        assertEquals(
            ScrollRouting.REMOTE_HOST,
            scrollRoutingFor(ScrollMode.AUTO, pane(agent = "codex", scroll = scrollback())),
        )
    }

    @Test
    fun `a full-screen wheel agent sends wheel reports`() {
        assertEquals(
            ScrollRouting.REMOTE_WHEEL,
            scrollRoutingFor(ScrollMode.AUTO, pane(agent = "codex", scroll = fullScreen())),
        )
    }

    @Test
    fun `a full-screen unknown app pages`() {
        assertEquals(
            ScrollRouting.REMOTE_PAGE,
            scrollRoutingFor(ScrollMode.AUTO, pane(scroll = fullScreen())),
        )
    }

    @Test
    fun `missing scroll state leaves routing to the engine`() {
        assertEquals(ScrollRouting.AUTO, scrollRoutingFor(ScrollMode.AUTO, pane()))
        assertEquals(ScrollRouting.AUTO, scrollRoutingFor(ScrollMode.AUTO, null))
    }

    @Test
    fun `the user's explicit mode wins`() {
        val sb = pane(agent = "codex", scroll = scrollback())
        assertEquals(ScrollRouting.LOCAL, scrollRoutingFor(ScrollMode.LOCAL, sb))
        assertEquals(ScrollRouting.REMOTE_WHEEL, scrollRoutingFor(ScrollMode.WHEEL, sb))
        assertEquals(ScrollRouting.REMOTE_PAGE, scrollRoutingFor(ScrollMode.PAGE, sb))
    }
}
