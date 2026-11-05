package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.designsystem.text
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.Pane
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.model.Tab
import dev.herdr.mobile.core.model.Workspace
import dev.herdr.mobile.core.network.HerdrClientState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import dev.herdr.mobile.core.network.testing.FakeHerdrSession
import dev.herdr.mobile.core.network.testing.FakeTerminalConnection

/**
 * Behaviour of the terminal screen's state machine, described from the user's
 * side: which pane ends up on screen, and what happens to the session when the
 * world moves underneath it (workspace closed elsewhere, daemon restarted,
 * link redialled, typing during attach).
 *
 * These are the rules that used to live only in the code and in my head; the
 * test names are the rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelBehaviourTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- fixtures ------------------------------------------------------

    private fun pane(workspace: String, tab: String, id: String = "$workspace:$tab") = Pane(
        id = id,
        tabId = tab,
        workspaceId = workspace,
    )

    private fun tab(id: String, workspace: String, panes: List<Pane>) =
        Tab(id = id, workspaceId = workspace, panes = panes)

    private fun workspace(id: String, tabs: List<Tab>, activeTabId: String? = tabs.first().id) =
        Workspace(id = id, label = id, activeTabId = activeTabId, tabs = tabs)

    private fun snapshot(vararg workspaces: Workspace) =
        SessionSnapshot(revision = 1, seq = 1, workspaces = workspaces.toList())

    private fun oneWorkspace(id: String = "w1"): Workspace =
        workspace(id, listOf(tab("$id:t1", id, listOf(pane(id, "$id:t1")))))

    private fun settings() = MutableStateFlow(AppSettings())

    /** uiState is WhileSubscribed: keep a collector alive to read it. */
    private fun kotlinx.coroutines.test.TestScope.observe(vm: TerminalViewModel) =
        backgroundScope.launch { vm.uiState.collect {} }

    // ---- what the user sees -------------------------------------------

    @Test
    fun `opens the active pane of the active tab`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val vm = TerminalViewModel(session, null, null, null, settings())
        observe(vm)
        advanceUntilIdle()

        assertEquals(listOf("w1:w1:t1"), session.openedPanes)
        assertNotNull(vm.uiState.value.target)
    }

    @Test
    fun `a workspace closed elsewhere retargets to a survivor and says so`() = runTest(dispatcher) {
        val first = oneWorkspace("w1")
        val second = oneWorkspace("w2")
        val session = FakeHerdrSession(snapshot(first, second))
        val vm = TerminalViewModel(session, null, null, null, settings())
        observe(vm)
        advanceUntilIdle()
        assertEquals(listOf("w1:w1:t1"), session.openedPanes)

        // Another client (desktop TUI, herdr CLI) closes w1.
        session.setSnapshot(snapshot(second))
        advanceUntilIdle()

        assertEquals("w2:w2:t1", session.openedPanes.last())
        val target = vm.uiState.value.target
        assertEquals("w2", target?.workspaceId)
        val message = vm.uiState.value.statusMessage?.text()
        assertTrue(
            "the user must be told why the pane changed, got: $message",
            message?.contains("w2") == true,
        )
    }

    @Test
    fun `an empty session drops the target instead of showing a dead attach`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val vm = TerminalViewModel(session, null, null, null, settings())
        observe(vm)
        advanceUntilIdle()

        session.setSnapshot(snapshot())
        advanceUntilIdle()

        assertEquals(null, vm.uiState.value.target)
        assertEquals(null, vm.uiState.value.backend)
    }

    @Test
    fun `a redialled daemon reopens the same pane`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val vm = TerminalViewModel(session, null, null, null, settings())
        observe(vm)
        advanceUntilIdle()
        assertEquals(1, session.openedPanes.size)

        // The daemon restarted: the target (host/pane routing) generation moves.
        session.targetGeneration = 1
        // StateFlow conflates equal values: a real redial carries a new revision.
        val afterRestart = snapshot(oneWorkspace()).copy(revision = 2, seq = 2)
        session.publish(HerdrClientState(ConnectionState.CONNECTED, afterRestart))
        advanceUntilIdle()

        assertEquals(
            "the same pane must come back after a daemon restart",
            listOf("w1:w1:t1", "w1:w1:t1"),
            session.openedPanes,
        )
        assertEquals("w1", vm.uiState.value.target?.workspaceId)
    }

    @Test
    fun `a dropped terminal stream is re-attached without user action`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val vm = TerminalViewModel(session, null, null, null, settings())
        advanceUntilIdle()
        val first = session.connections.single()

        // Tunnel blip: the link drops, the attach stream dies, the link returns.
        first.detach("closed")
        session.setConnection(ConnectionState.RECONNECTING)
        advanceUntilIdle()
        session.setConnection(ConnectionState.CONNECTED)
        advanceUntilIdle()

        assertEquals(
            "a transport blip must re-attach the pane the user was looking at",
            2,
            session.openedPanes.size,
        )
        assertEquals("w1:w1:t1", session.openedPanes.last())
    }

    @Test
    fun `switching to another pane of the same tab attaches that pane`() = runTest(dispatcher) {
        // A tab can hold several panes (Herdr splits); the pane rail offers
        // them, and choosing one must re-attach the stream, not just repaint.
        val p1 = pane("w1", "w1:t1", id = "w1:p1")
        val p2 = pane("w1", "w1:t1", id = "w1:p2")
        val snapshot = snapshot(
            workspace("w1", listOf(tab("w1:t1", "w1", listOf(p1, p2)))),
        )
        val session = FakeHerdrSession(snapshot)
        val vm = TerminalViewModel(session, null, null, null, settings())
        observe(vm)
        advanceUntilIdle()
        assertEquals(listOf("w1:p1"), session.openedPanes)

        vm.openTarget(TerminalTarget("w1", "w1:t1", "w1:p2"))
        advanceUntilIdle()

        assertEquals("w1:p2", vm.uiState.value.target?.paneId)
        assertEquals(listOf("w1:p1", "w1:p2"), session.openedPanes)
    }

    @Test
    fun `typing while the stream is being re-attached is delivered`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val vm = TerminalViewModel(session, null, null, null, settings())
        advanceUntilIdle()
        val first = session.connections.single()

        // Background/foreground: the visible stream is released before the
        // re-attach completes, which is exactly the window where typing used
        // to be dropped on the floor.
        vm.releaseForBackground()
        advanceUntilIdle()
        vm.sendText("ls\n")
        vm.resumeAfterBackground()
        advanceUntilIdle()

        val connection = session.connections.last()
        assertTrue(connection !== first)
        assertEquals(
            "input typed during re-attach must not be dropped",
            listOf("ls\r"),
            connection.sentText,
        )
    }

    @Test
    fun `a second send goes to the live stream, not the queue`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val vm = TerminalViewModel(session, null, null, null, settings())
        advanceUntilIdle()

        vm.sendText("a")
        advanceUntilIdle()

        assertEquals(listOf("a"), session.connections.single().sentText)
    }
}
