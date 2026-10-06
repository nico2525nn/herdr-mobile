package dev.herdr.mobile.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

class ModelContractTest {

    @Test
    fun `status wire names round trip`() {
        assertEquals(AgentStatus.BLOCKED, AgentStatus.fromWire("blocked"))
        assertEquals(AgentStatus.WORKING, AgentStatus.fromWire("working"))
        assertEquals(AgentStatus.DONE, AgentStatus.fromWire("done"))
        assertEquals(AgentStatus.IDLE, AgentStatus.fromWire("idle"))
        assertEquals(AgentStatus.UNKNOWN, AgentStatus.fromWire("unknown"))
        assertEquals(AgentStatus.UNKNOWN, AgentStatus.fromWire("failed"))
        assertEquals(AgentStatus.UNKNOWN, AgentStatus.fromWire(null))
    }

    @Test
    fun `roll-up prefers blocked over working over done over idle over unknown`() {
        assertEquals(
            AgentStatus.BLOCKED,
            AgentStatus.rollUp(listOf(AgentStatus.IDLE, AgentStatus.WORKING, AgentStatus.BLOCKED)),
        )
        assertEquals(
            AgentStatus.WORKING,
            AgentStatus.rollUp(listOf(AgentStatus.DONE, AgentStatus.WORKING, AgentStatus.IDLE)),
        )
        assertEquals(AgentStatus.DONE, AgentStatus.rollUp(listOf(AgentStatus.IDLE, AgentStatus.DONE)))
        assertEquals(AgentStatus.IDLE, AgentStatus.rollUp(listOf(AgentStatus.UNKNOWN, AgentStatus.IDLE)))
        assertEquals(AgentStatus.UNKNOWN, AgentStatus.rollUp(listOf(AgentStatus.UNKNOWN)))
        assertNull(AgentStatus.rollUp(emptyList()))
    }

    @Test
    fun `roll-up priority is explicit, not ordinal`() {
        // `failed` must be insertable above `blocked` without renumbering: priorities are
        // spaced, and the order is a strict total order matching the plan.
        val ordered = AgentStatus.entries.sortedBy { it.priority }
        assertEquals(
            listOf(AgentStatus.UNKNOWN, AgentStatus.IDLE, AgentStatus.DONE, AgentStatus.WORKING, AgentStatus.BLOCKED),
            ordered,
        )
    }

    @Test
    fun `snapshot decodes daemon camelCase with unknown fields ignored`() {
        val text = """
        {
          "revision": 1831, "seq": 90210, "capturedAt": "2026-10-06T18:00:00+09:00",
          "futureField": {"anything": true},
          "herdr": {"version": "0.9.1", "protocol": 22, "binary": "/bin/herdr", "extra": 1},
          "focus": {"workspaceId": "w1", "tabId": "w1:tR", "paneId": "w1:pT"},
          "workspaces": [{
            "id": "w1", "label": "cpp-fabricmc", "path": "/home/nico/school/app/cpp-fabricmc",
            "home": "/home/nico", "number": 1, "status": "working", "focused": false,
            "activeTabId": "w1:tR", "tokens": {},
            "tabs": [{
              "id": "w1:tR", "workspaceId": "w1", "label": "agents", "number": 24,
              "status": "idle", "focused": false,
              "panes": [{
                "id": "w1:pT", "tabId": "w1:tR", "workspaceId": "w1", "label": null,
                "title": "cpp-fabricmc 7th", "agent": "codex", "status": "idle",
                "cwd": "/home/nico/school/app/cpp-fabricmc", "terminalId": "term_x",
                "focused": false, "revision": 26, "tokens": {}, "mystery": [1,2]
              }]
            }]
          }]
        }
        """.trimIndent()
        val snapshot = json.decodeFromString(SessionSnapshot.serializer(), text)
        assertEquals(1831, snapshot.revision)
        assertEquals(90210, snapshot.seq)
        assertEquals(1, snapshot.workspaces.size)
        val workspace = snapshot.workspaces.first()
        assertEquals("~/school/app/cpp-fabricmc", workspace.displayPath)
        assertEquals(AgentStatus.WORKING, workspace.status)
        val tab = workspace.tabs.first()
        assertEquals("agents", tab.displayLabel)
        val pane = tab.panes.first()
        assertEquals("codex", pane.displayLabel)
        assertEquals(1, snapshot.tabCount)
        assertEquals(1, snapshot.paneCount)
        assertEquals(workspace, snapshot.workspace("w1"))
        assertEquals(tab, snapshot.tab("w1:tR"))
        assertEquals(pane, snapshot.pane("w1:pT"))
        assertNull(snapshot.pane("nope"))
    }

    @Test
    fun `reducer folds pane status and rolls tab and workspace up`() {
        val pane = Pane(id = "w1:p1", tabId = "w1:t1", workspaceId = "w1", status = AgentStatus.IDLE)
        val other = Pane(id = "w1:p2", tabId = "w1:t1", workspaceId = "w1", status = AgentStatus.IDLE)
        val tab = Tab(id = "w1:t1", workspaceId = "w1", status = AgentStatus.IDLE, panes = listOf(pane, other))
        val workspace = Workspace(id = "w1", label = "w", status = AgentStatus.IDLE, tabs = listOf(tab))
        val snapshot = SessionSnapshot(revision = 1, seq = 10, workspaces = listOf(workspace))

        val event = SemanticEvent(seq = 11, type = SemanticEvent.TYPE_PANE_STATUS_CHANGED, workspaceId = "w1", tabId = "w1:t1", paneId = "w1:p1", status = AgentStatus.BLOCKED)
        val outcome = SnapshotReducer.apply(snapshot, event)
        assertTrue(outcome is SnapshotReducer.Outcome.Applied)
        val updated = (outcome as SnapshotReducer.Outcome.Applied).snapshot
        assertEquals(11, updated.seq)
        assertEquals(AgentStatus.BLOCKED, updated.pane("w1:p1")?.status)
        assertEquals(AgentStatus.BLOCKED, updated.tab("w1:t1")?.status)
        assertEquals(AgentStatus.BLOCKED, updated.workspace("w1")?.status)
    }

    @Test
    fun `reducer reports a gap instead of patching`() {
        val snapshot = SessionSnapshot(revision = 1, seq = 10)
        val outcome = SnapshotReducer.apply(
            snapshot,
            SemanticEvent(seq = 12, type = SemanticEvent.TYPE_PANE_STATUS_CHANGED),
        )
        assertTrue(outcome is SnapshotReducer.Outcome.Gap)
        val gap = outcome as SnapshotReducer.Outcome.Gap
        assertEquals(11, gap.expectedSeq)
        assertEquals(12, gap.receivedSeq)
    }

    @Test
    fun `reducer refetches on structural events and snapshot demands`() {
        val snapshot = SessionSnapshot(revision = 1, seq = 10)
        for (type in listOf(
            SemanticEvent.TYPE_TAB_CREATED,
            SemanticEvent.TYPE_PANE_CLOSED,
            SemanticEvent.TYPE_WORKSPACE_CREATED,
            SemanticEvent.TYPE_SNAPSHOT_REQUIRED,
            SemanticEvent.TYPE_STREAM_READY,
        )) {
            val outcome = SnapshotReducer.apply(snapshot, SemanticEvent(seq = 11, type = type))
            assertTrue("$type must refetch", outcome is SnapshotReducer.Outcome.Refetch)
        }
    }

    @Test
    fun `path display condenses home`() {
        assertEquals("~/a/b", PathDisplay.condense("/home/nico/a/b", "/home/nico"))
        assertEquals("~", PathDisplay.condense("/home/nico", "/home/nico"))
        assertEquals("/run/media/x", PathDisplay.condense("/run/media/x", "/home/nico"))
        assertNull(PathDisplay.condense(null, "/home/nico"))
        assertEquals("a/b", PathDisplay.fallback("/x/y/a/b"))
        assertNull(PathDisplay.fallback(null))
    }
}