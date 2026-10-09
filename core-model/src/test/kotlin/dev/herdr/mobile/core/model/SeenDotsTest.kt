package dev.herdr.mobile.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SeenDotsTest {

    @Before
    fun clear() {
        SeenDots.clear()
    }

    private fun pane(id: String, status: AgentStatus) = Pane(
        id = id,
        tabId = "t1",
        workspaceId = "w1",
        status = status,
    )

    @Test
    fun `unmarked done is not seen`() {
        assertFalse(SeenDots.isSeenDone("p1", AgentStatus.DONE))
    }

    @Test
    fun `mark only counts while still done`() {
        SeenDots.mark("p1")
        assertTrue(SeenDots.isSeenDone("p1", AgentStatus.DONE))
        // Next run started: the mark must not mute the new working dot.
        assertFalse(SeenDots.isSeenDone("p1", AgentStatus.WORKING))
    }

    @Test
    fun `tab needs every done pane seen`() {
        SeenDots.mark("p1")
        val partial = Tab(id = "t1", workspaceId = "w1", status = AgentStatus.DONE, panes = listOf(
            pane("p1", AgentStatus.DONE),
            pane("p2", AgentStatus.DONE),
        ))
        assertFalse(partial.isSeenDone())
        SeenDots.mark("p2")
        assertTrue(partial.isSeenDone())
    }

    @Test
    fun `non-done roll-up is never seen done`() {
        SeenDots.mark("p1")
        val working = Tab(id = "t1", workspaceId = "w1", status = AgentStatus.WORKING, panes = listOf(
            pane("p1", AgentStatus.DONE),
        ))
        assertFalse(working.isSeenDone())
    }

    @Test
    fun `workspace needs every done pane seen`() {
        SeenDots.mark("p1")
        val ws = Workspace(id = "w1", label = "w", status = AgentStatus.DONE, tabs = listOf(
            Tab(id = "t1", workspaceId = "w1", status = AgentStatus.DONE, panes = listOf(
                pane("p1", AgentStatus.DONE),
            )),
            Tab(id = "t2", workspaceId = "w1", status = AgentStatus.DONE, panes = listOf(
                pane("p2", AgentStatus.DONE),
            )),
        ))
        assertFalse(ws.isSeenDone())
        SeenDots.mark("p2")
        assertTrue(ws.isSeenDone())
    }
}
