package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.model.Pane
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.model.Tab
import dev.herdr.mobile.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetReconcilerTest {

    private fun pane(workspaceId: String, tabId: String, id: String) = Pane(
        id = id,
        tabId = tabId,
        workspaceId = workspaceId,
    )

    private fun workspace(
        id: String,
        tabs: List<Tab>,
        activeTabId: String? = tabs.firstOrNull()?.id,
    ) = Workspace(id = id, label = id, activeTabId = activeTabId, tabs = tabs)

    private fun tab(id: String, workspaceId: String, panes: List<Pane>) =
        Tab(id = id, workspaceId = workspaceId, panes = panes)

    private fun snapshot(vararg workspaces: Workspace) =
        SessionSnapshot(revision = 1, seq = 1, workspaces = workspaces.toList())

    @Test
    fun `live target is kept`() {
        val p = pane("w1", "t1", "w1:p1")
        val snap = snapshot(workspace("w1", listOf(tab("t1", "w1", listOf(p)))))
        assertEquals(
            TargetReconcile.Keep,
            reconcileTarget(snap, TerminalTarget("w1", "t1", "w1:p1")),
        )
    }

    @Test
    fun `closed workspace retargets the first surviving workspace`() {
        val p2 = pane("w2", "t9", "w2:p9")
        val snap = snapshot(
            workspace("w2", listOf(tab("t9", "w2", listOf(p2)))),
            workspace("w3", listOf(tab("t3", "w3", listOf(pane("w3", "t3", "w3:p3"))))),
        )
        assertEquals(
            TargetReconcile.Retarget(TerminalTarget("w2", "t9", "w2:p9")),
            reconcileTarget(snap, TerminalTarget("w1", "t1", "w1:p1")),
        )
    }

    @Test
    fun `pane id reused in another workspace is not the same target`() {
        val reused = pane("w2", "t2", "w1:p1")
        val snap = snapshot(workspace("w2", listOf(tab("t2", "w2", listOf(reused)))))
        assertEquals(
            TargetReconcile.Retarget(TerminalTarget("w2", "t2", "w1:p1")),
            reconcileTarget(snap, TerminalTarget("w1", "t1", "w1:p1")),
        )
    }

    @Test
    fun `empty session clears the target`() {
        assertEquals(
            TargetReconcile.Clear,
            reconcileTarget(snapshot(), TerminalTarget("w1", "t1", "w1:p1")),
        )
    }
}
