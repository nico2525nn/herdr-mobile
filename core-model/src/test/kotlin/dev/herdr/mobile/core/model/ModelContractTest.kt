package dev.herdr.mobile.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
        assertEquals(AgentStatus.FAILED, AgentStatus.fromWire("failed"))
        assertEquals(AgentStatus.UNKNOWN, AgentStatus.fromWire("unknown"))
        assertEquals(AgentStatus.UNKNOWN, AgentStatus.fromWire(null))
    }

    @Test
    fun `roll-up prefers failed over blocked over working over done over idle over unknown`() {
        assertEquals(
            AgentStatus.FAILED,
            AgentStatus.rollUp(listOf(AgentStatus.BLOCKED, AgentStatus.FAILED, AgentStatus.WORKING)),
        )
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
        // Priorities are spaced, and the order is a strict total order matching
        // the plan: failed outranks blocked (a failure anywhere dominates).
        val ordered = AgentStatus.entries.sortedBy { it.priority }
        assertEquals(
            listOf(AgentStatus.UNKNOWN, AgentStatus.IDLE, AgentStatus.DONE, AgentStatus.WORKING, AgentStatus.BLOCKED, AgentStatus.FAILED),
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
    fun `bug class 2 live core app identity updates agent routing`() {
        val pane = Pane(id = "w1:p1", tabId = "w1:t1", workspaceId = "w1", agent = null)
        val tab = Tab(id = "w1:t1", workspaceId = "w1", panes = listOf(pane))
        val workspace = Workspace(id = "w1", label = "w", tabs = listOf(tab))
        val snapshot = SessionSnapshot(revision = 1, seq = 10, workspaces = listOf(workspace))
        val update = SemanticEvent(
            seq = 11,
            type = SemanticEvent.TYPE_PANE_STATUS_CHANGED,
            workspaceId = "w1",
            tabId = "w1:t1",
            paneId = "w1:p1",
            status = AgentStatus.WORKING,
            detail = json.parseToJsonElement("""{"status":{"state":"working","app":"pi"}}""").jsonObject,
        )
        val updated = (SnapshotReducer.apply(snapshot, update) as SnapshotReducer.Outcome.Applied).snapshot
        assertEquals("pi", updated.pane("w1:p1")?.agent)

        val cleared = update.copy(
            seq = 12,
            detail = json.parseToJsonElement("""{"status":null}""").jsonObject,
        )
        val afterStop = (SnapshotReducer.apply(updated, cleared) as SnapshotReducer.Outcome.Applied).snapshot
        assertNull(afterStop.pane("w1:p1")?.agent)
    }

    @Test
    fun `reducer folds pane title updates`() {
        val pane = Pane(id = "w1:p1", tabId = "w1:t1", workspaceId = "w1", title = "old")
        val tab = Tab(id = "w1:t1", workspaceId = "w1", panes = listOf(pane))
        val workspace = Workspace(id = "w1", label = "w", tabs = listOf(tab))
        val snapshot = SessionSnapshot(revision = 1, seq = 10, workspaces = listOf(workspace))
        val outcome = SnapshotReducer.apply(snapshot, SemanticEvent(
            seq = 11,
            type = SemanticEvent.TYPE_PANE_TITLE_CHANGED,
            workspaceId = "w1",
            paneId = "w1:p1",
            label = "new",
        ))
        assertTrue(outcome is SnapshotReducer.Outcome.Applied)
        assertEquals("new", (outcome as SnapshotReducer.Outcome.Applied).snapshot.pane("w1:p1")?.title)
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
            SemanticEvent.TYPE_HERDR_UPDATE,
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

    @Test
    fun `fnv vectors match daemon twin`() {
        assertEquals("cbf29ce484222325", fnv1a64Hex(ByteArray(0)))
        assertEquals("af63dc4c8601ec8c", fnv1a64Hex("a".toByteArray()))
        assertEquals("d45c43e692645000", fnv1a64Hex("w1:p1=idle;".toByteArray()))
    }

    @Test
    fun `insertIndex moves up exactly`() {
        // [a,b,c] move c(2) to 0 -> request 0.
        assertEquals(0L, Reorder.insertIndex(2, 0, 3))
    }

    @Test
    fun `insertIndex compensates down shift`() {
        // Verified live: [w3,w1,w2] move w3(0) to 2 landed at 1; requesting
        // 3 reaches the end. to is the VISUAL position, not the wire index.
        assertEquals(3L, Reorder.insertIndex(0, 2, 3))
        assertEquals(2L, Reorder.insertIndex(0, 1, 3))
    }

    @Test
    fun `insertIndex clamps and no-ops`() {
        assertEquals(1L, Reorder.insertIndex(1, 1, 3))
        assertEquals(0L, Reorder.insertIndex(1, -5, 3))
        assertEquals(3L, Reorder.insertIndex(0, 99, 3))
    }

    @Test
    fun `moved events are structural`() {
        val tick = SemanticEvent(seq = 1, type = SemanticEvent.TYPE_TAB_MOVED)
        assertTrue(tick.isStructural)
        val wtick = SemanticEvent(seq = 1, type = SemanticEvent.TYPE_WORKSPACE_MOVED)
        assertTrue(wtick.isStructural)
    }

    @Test
    fun `status hash is order and status sensitive`() {
        fun snap(panes: List<Pair<String, AgentStatus>>) = SessionSnapshot(
            revision = 1,
            seq = 1,
            workspaces = listOf(
                Workspace(
                    id = "w1",
                    label = "w",
                    tabs = listOf(
                        Tab(
                            id = "w1:t1",
                            workspaceId = "w1",
                            panes = panes.map { (id, st) ->
                                Pane(id = id, tabId = "w1:t1", workspaceId = "w1", status = st)
                            },
                        ),
                    ),
                ),
            ),
        )
        val a = snap(listOf("w1:p1" to AgentStatus.IDLE, "w1:p2" to AgentStatus.WORKING))
        val b = snap(listOf("w1:p2" to AgentStatus.WORKING, "w1:p1" to AgentStatus.IDLE))
        assertTrue(a.statusHash() != b.statusHash())
        val c = snap(listOf("w1:p1" to AgentStatus.DONE, "w1:p2" to AgentStatus.WORKING))
        assertTrue(a.statusHash() != c.statusHash())
    }

    @Test
    fun `versions match daemon twin vectors`() {
        assertTrue(Versions.isNewer("0.9.3", "0.9.5"))
        assertTrue(Versions.isNewer("0.9.9", "0.10.0"))
        assertTrue(Versions.isNewer("v0.9.3", "0.9.5"))
        assertTrue(!Versions.isNewer("0.9.5", "0.9.5"))
        assertTrue(!Versions.isNewer("0.9.5", "0.9.3"))
        assertTrue(!Versions.isNewer("0.9.5", "garbage"))
    }

    @Test
    fun `bug classes 2 and 7 require app identity or alt screen for page-key scroll`() {
        // Regression: a foreground command such as `sleep` is not an app identity;
        // the core adapter represents missing status.app as null, so it cannot
        // route PageUp into an ordinary shell. Active alt-screen remains eligible.
        assertTrue(!AgentMatrix.allowPageKey(appIdentity = null, altActive = false))
        assertTrue(AgentMatrix.allowPageKey(appIdentity = null, altActive = true))
        assertTrue(AgentMatrix.allowPageKey(appIdentity = "codex", altActive = false))

        // Unknown explicit apps log in the screen and safely fall back to the
        // conservative page-key/emulator-bit routes instead of a guessed wheel.
        assertTrue(!AgentMatrix.isKnownApp("future-app"))
        assertTrue(AgentMatrix.allowPageKey(appIdentity = "future-app", altActive = false))
        assertTrue(!AgentMatrix.useWheelScroll("future-app"))
        assertTrue(!AgentMatrix.alwaysBracket("future-app"))
        assertTrue(AgentMatrix.isKnownApp("CODEX"))
    }

    @Test
    fun `agent matrix routes scroll by kind`() {
        // Live/source-backed wheel transports (1 row/event unless noted).
        listOf("codex", "pi", "opencode", "qwen").forEach { kind ->
            assertTrue("$kind should route wheel", AgentMatrix.useWheelScroll(kind))
            assertTrue("$kind should be case-insensitive", AgentMatrix.useWheelScroll(kind.uppercase()))
        }
        // Every other supported kind uses page_key; null/unknown retain default.
        // copilot/hermes: wheel mentioned upstream but stride unestablished —
        // PAGE_KEY is conservative (PgUp proven / degrades to host offset).
        listOf(
            "omp", "claude", "gemini", "cursor", "devin", "agy", "cline",
            "mastracode", "copilot", "kimi", "kiro", "droid", "amp", "grok",
            "hermes", "kilo", "qodercli", "letta", "maki", "muse",
        ).forEach { kind ->
            assertTrue("$kind should route page keys", !AgentMatrix.useWheelScroll(kind))
            assertTrue("$kind should be case-insensitive", !AgentMatrix.useWheelScroll(kind.uppercase()))
        }
        assertTrue(!AgentMatrix.useWheelScroll("unknown-agent"))
        assertTrue(!AgentMatrix.useWheelScroll(null))
        assertTrue(!AgentMatrix.useWheelScroll(""))
    }

    @Test
    fun `agent matrix preserves documented wheel stride`() {
        listOf(
            "codex", "pi", "opencode",
        ).forEach { kind ->
            assertEquals(1, AgentMatrix.wheelRowsPerEvent(kind))
            assertEquals(1, AgentMatrix.wheelRowsPerEvent(kind.uppercase()))
        }
        assertEquals(3, AgentMatrix.wheelRowsPerEvent("qwen"))
        assertEquals(3, AgentMatrix.wheelRowsPerEvent("QWEN"))
        assertEquals(1, AgentMatrix.wheelRowsPerEvent("unknown-agent"))
        assertEquals(1, AgentMatrix.wheelRowsPerEvent(null))
        assertEquals(1, AgentMatrix.wheelRowsPerEvent(""))
    }

    @Test
    fun `agent matrix brackets paste by kind`() {
        // Existing server-side-rendered TUIs whose local emulator cannot
        // observe the app's bracketed-paste mode.
        listOf("pi", "opencode").forEach { kind ->
            assertTrue("$kind should always bracket", AgentMatrix.alwaysBracket(kind))
            assertTrue("$kind should be case-insensitive", AgentMatrix.alwaysBracket(kind.uppercase()))
        }
        // All remaining supported kinds use the emulator-bit gate.
        listOf(
            "omp", "codex", "claude", "gemini", "cursor", "devin", "agy",
            "cline", "mastracode", "copilot", "kimi", "kiro", "droid", "amp",
            "grok", "hermes", "kilo", "qodercli", "qwen", "letta", "maki", "muse",
        ).forEach { kind ->
            assertTrue("$kind should use emulator mode", !AgentMatrix.alwaysBracket(kind))
            assertTrue("$kind should be case-insensitive", !AgentMatrix.alwaysBracket(kind.uppercase()))
        }
        assertTrue(!AgentMatrix.alwaysBracket(null))
        assertTrue(!AgentMatrix.alwaysBracket("unknown-agent"))
        assertTrue(!AgentMatrix.alwaysBracket(""))
    }
}
