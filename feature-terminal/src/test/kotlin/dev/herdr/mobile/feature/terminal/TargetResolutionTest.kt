package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.designsystem.UiMessage
import dev.herdr.mobile.core.model.Pane
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.model.Tab
import dev.herdr.mobile.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetResolutionTest {

    private fun pane(id: String, tab: String, workspace: String) =
        Pane(id = id, tabId = tab, workspaceId = workspace)

    private fun tab(id: String, workspace: String, panes: List<Pane>) =
        Tab(id = id, workspaceId = workspace, panes = panes)

    private fun workspace(
        id: String,
        tabs: List<Tab>,
        activeTabId: String? = tabs.firstOrNull()?.id,
    ) = Workspace(id = id, label = id, activeTabId = activeTabId, tabs = tabs)

    private fun snapshot(vararg workspaces: Workspace) =
        SessionSnapshot(revision = 1, seq = 1, workspaces = workspaces.toList())

    private val p1 = pane("w1:p1", "t1", "w1")
    private val p2 = pane("w1:p2", "t1", "w1")
    private val snap = snapshot(
        workspace("w1", listOf(tab("t1", "w1", listOf(p1, p2)))),
        workspace("w2", listOf(tab("t2", "w2", listOf(pane("w2:p1", "t2", "w2"))))),
    )

    @Test
    fun `exact ids resolve to the named pane`() {
        assertEquals(TerminalTarget("w1", "t1", "w1:p2"), resolveTarget(snap, "w1", "t1", "w1:p2"))
    }

    @Test
    fun `a missing workspace opens the first one`() {
        assertEquals(TerminalTarget("w1", "t1", "w1:p1"), resolveTarget(snap, "gone", null, null))
    }

    @Test
    fun `no ids at all resolve to the first workspace's active tab`() {
        assertEquals(TerminalTarget("w1", "t1", "w1:p1"), resolveTarget(snap, null, null, null))
    }

    @Test
    fun `a tab from another workspace is ignored`() {
        assertEquals(TerminalTarget("w1", "t1", "w1:p1"), resolveTarget(snap, "w1", "t2", null))
    }

    @Test
    fun `a pane from another tab is ignored`() {
        assertEquals(TerminalTarget("w2", "t2", "w2:p1"), resolveTarget(snap, "w2", null, "w1:p2"))
    }

    @Test
    fun `an empty snapshot resolves to nothing`() {
        assertNull(resolveTarget(snapshot(), null, null, null))
    }

    @Test
    fun `in-app navigation never warns`() {
        assertNull(
            fallbackNotice(null, null, null, snap, TerminalTarget("w1", "t1", "w1:p1")),
        )
    }

    @Test
    fun `a deep link that landed on the named pane is silent`() {
        assertNull(
            fallbackNotice("w1", "t1", "w1:p2", snap, TerminalTarget("w1", "t1", "w1:p2")),
        )
    }

    @Test
    fun `a deep link that lost its pane names the substitute`() {
        val notice = fallbackNotice("w1", "t1", "w1:p9", snap, TerminalTarget("w1", "t1", "w1:p1"))
        assertEquals(
            UiMessage.OpenedInstead(UiMessage.OpenedInstead.TargetKind.PANE, "w1:p1"),
            notice,
        )
    }

    @Test
    fun `a deep link whose tab vanished reports the tab`() {
        assertEquals(
            UiMessage.OpenedInstead(UiMessage.OpenedInstead.TargetKind.TAB, null),
            fallbackNotice("w1", "t9", "w1:p1", snap, TerminalTarget("w1", "t1", "w1:p1")),
        )
    }

    @Test
    fun `a deep link whose workspace changed reports the workspace`() {
        assertEquals(
            UiMessage.OpenedInstead(UiMessage.OpenedInstead.TargetKind.WORKSPACE, null),
            fallbackNotice("w9", "t1", "w1:p1", snap, TerminalTarget("w1", "t1", "w1:p1")),
        )
    }
}
