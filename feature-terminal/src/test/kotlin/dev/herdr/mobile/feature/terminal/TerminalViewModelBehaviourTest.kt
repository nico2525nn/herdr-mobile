package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.designsystem.text
import dev.herdr.mobile.core.model.AgentStatus
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.Pane
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.model.Tab
import dev.herdr.mobile.core.model.Workspace
import dev.herdr.mobile.core.network.DaemonException
import dev.herdr.mobile.core.network.HerdrClientState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import dev.herdr.mobile.core.network.testing.FakeHerdrSession
import dev.herdr.mobile.core.network.testing.FakeTerminalConnection
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent

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
    fun `a target switch does not reopen the same pane id on the new host`() = runTest(dispatcher) {
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
            "a same-id pane on the new host must not be auto-opened",
            listOf("w1:w1:t1"),
            session.openedPanes,
        )
        assertNull(vm.uiState.value.target)
    }

    @Test
    fun `a done transition while the pane is visible focuses exactly that pane`() = runTest(dispatcher) {
        val pane = pane("w1", "w1:t1", id = "w1:p1").copy(status = AgentStatus.IDLE)
        val ws = workspace("w1", listOf(tab("w1:t1", "w1", listOf(pane))))
        val session = FakeHerdrSession(snapshot(ws))
        val vm = TerminalViewModel(session, null, null, null, settings())
        advanceUntilIdle()
        session.connections.single().attach()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), session.paneFocusCalls)

        // The agent finishes while the user is already looking at the pane.
        session.publish(
            HerdrClientState(
                connection = ConnectionState.CONNECTED,
                snapshot = snapshot(ws.copy(tabs = listOf(tab("w1:t1", "w1", listOf(pane.copy(status = AgentStatus.DONE))))))
                    .copy(revision = 2, seq = 2),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            "read-ack must target the exact visible pane, not its tab",
            listOf("w1:p1"),
            session.paneFocusCalls,
        )
        assertEquals(emptyList<String>(), session.focusCalls)
        assertTrue(session.refreshCalls > 0)
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
    fun `typing while the visible stream is detached waits for the reattach`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val vm = TerminalViewModel(session, null, null, null, settings())
        advanceUntilIdle()
        val first = session.connections.single()
        first.detach("closed")
        runCurrent()

        vm.sendText("x")
        advanceUntilIdle()

        val second = session.connections.last()
        assertTrue(second !== first)
        assertEquals(emptyList<String>(), first.sentText)
        assertEquals(listOf("x"), second.sentText)
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

    @Test
    fun `a missing pane is not retried forever`() = runTest {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val controllerScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        var message: dev.herdr.mobile.core.designsystem.UiMessage? = null
        val controller = PaneSessionController(
            session = session,
            scope = controllerScope,
            onMessage = { message = it },
            initialWorkspaceId = null,
            initialTabId = null,
            initialPaneId = null,
        )
        controller.start()
        advanceUntilIdle()
        val opens = session.openedPanes.size

        session.connections.last().detach("terminal target w1:p1 not found")
        advanceTimeBy(30_000)
        runCurrent()

        org.junit.Assert.assertEquals(opens, session.openedPanes.size)
        org.junit.Assert.assertTrue(
            message is dev.herdr.mobile.core.designsystem.UiMessage.AttachFailed,
        )
        controller.shutdown()
        controllerScope.cancel()
    }

    @Test
    fun `a pane that attaches then immediately drops stops after bounded retries`() = runTest {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val controllerScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val controller = PaneSessionController(
            session = session,
            scope = controllerScope,
            onMessage = {},
            initialWorkspaceId = null,
            initialTabId = null,
            initialPaneId = null,
        )
        controller.start()
        advanceUntilIdle()
        val initialOpens = session.openedPanes.size

        // Each cycle looks healthy for one state emission, then drops. The old
        // `Attached -> attempts = 0` reset made this loop forever on a missing
        // pane (the reported w1:p1 attach storm).
        repeat(DesiredRetries + 2) {
            session.connections.last().attach()
            runCurrent()
            session.connections.last().detach("closed")
            advanceTimeBy(AutoReattachDelayCap)
            runCurrent()
        }

        org.junit.Assert.assertTrue(
            "opened ${session.openedPanes.size} times; retry budget did not bound",
            session.openedPanes.size <= initialOpens + DesiredRetries + 1,
        )
        controller.shutdown()
        controllerScope.cancel()
    }

    private companion object {
        const val DesiredRetries = 4
        const val AutoReattachDelayCap = 15_000L
    }

    @Test
    fun `switching panes does not wait for the previous release`() = runTest {
        val p1 = pane("w1", "t1", "w1:p1")
        val p2 = pane("w1", "t1", "w1:p2")
        val session = FakeHerdrSession(snapshot(workspace("w1", listOf(tab("t1", "w1", listOf(p1, p2))))))
        // Independent scope: the controller's collectors run until cancelled.
        val controllerScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val controller = PaneSessionController(
            session = session,
            scope = controllerScope,
            onMessage = {},
            initialWorkspaceId = null,
            initialTabId = null,
            initialPaneId = null,
        )
        controller.start()
        advanceUntilIdle()
        assertEquals(listOf("w1:p1"), session.openedPanes)

        // A real release blocks ~150ms; serialising it would delay the switch.
        val first = session.connections.single()
        first.releaseDelayMs = 100
        controller.openTarget(TerminalTarget("w1", "t1", "w1:p2"))
        runCurrent()

        // The new pane is attached while the old socket has not finished closing.
        assertEquals(listOf("w1:p1", "w1:p2"), session.openedPanes)
        assertEquals(0, first.released)

        advanceUntilIdle()
        assertEquals(1, first.released)
        controller.shutdown()
        controllerScope.cancel()
    }
    @Test
    fun `a done transition while backgrounded is not acknowledged`() = runTest(dispatcher) {
        val working = oneWorkspace()
        val session = FakeHerdrSession(snapshot(working))
        val vm = TerminalViewModel(session, null, null, null, settings())
        observe(vm)
        advanceUntilIdle()
        val initialFocus = session.paneFocusCalls.size

        vm.releaseForBackground()
        advanceUntilIdle()
        val done = working.copy(
            tabs = working.tabs.map { tab ->
                tab.copy(panes = tab.panes.map { it.copy(status = AgentStatus.DONE) })
            },
        )
        session.setSnapshot(snapshot(done))
        advanceUntilIdle()

        assertEquals(
            "a pane finishing off-screen must not be marked read",
            initialFocus,
            session.paneFocusCalls.size,
        )

        vm.resumeAfterBackground()
        runCurrent()
        session.connections.last().attach()
        advanceUntilIdle()
        assertTrue(
            "returning to the visible DONE pane should acknowledge it",
            session.paneFocusCalls.size > initialFocus,
        )
    }

    @Test
    fun `a snapshot that arrives while stopped is resolved on resume`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot = null, connection = ConnectionState.CONNECTING)
        val vm = TerminalViewModel(session, null, null, null, settings())
        observe(vm)
        advanceUntilIdle()
        assertNull(vm.uiState.value.target)

        vm.releaseForBackground()
        advanceUntilIdle()
        session.publish(
            HerdrClientState(
                connection = ConnectionState.CONNECTED,
                snapshot = snapshot(oneWorkspace()),
            ),
        )
        advanceUntilIdle()
        assertNull("the stopped screen must not attach", vm.uiState.value.target)

        vm.resumeAfterBackground()
        advanceUntilIdle()
        assertEquals(listOf("w1:w1:t1"), session.openedPanes)
        assertEquals("w1:w1:t1", vm.uiState.value.target?.paneId)
    }

    @Test
    fun `a reconnect wait that times out shows the retry message`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val vm = TerminalViewModel(session, null, null, null, settings())
        observe(vm)
        advanceUntilIdle()

        vm.releaseForBackground()
        advanceUntilIdle()
        session.setConnection(ConnectionState.CONNECTING)
        vm.resumeAfterBackground()
        advanceTimeBy(30_001)
        advanceUntilIdle()

        assertEquals(
            dev.herdr.mobile.core.designsystem.UiMessage.ReconnectTimedOut,
            vm.uiState.value.statusMessage,
        )
    }

    @Test
    fun `removing the last pane releases the terminal socket`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val vm = TerminalViewModel(session, null, null, null, settings())
        observe(vm)
        advanceUntilIdle()
        val connection = session.connections.single()

        session.setSnapshot(snapshot())
        advanceUntilIdle()

        assertNull(vm.uiState.value.target)
        assertEquals("the orphaned stream must be released", 1, connection.released)
    }


    @Test
    fun `a detach during a link drop keeps the frozen backend until the link returns`() =
        runTest(dispatcher) {
            val session = FakeHerdrSession(snapshot(oneWorkspace()))
            val vm = TerminalViewModel(session, null, null, null, settings())
            observe(vm)
            advanceUntilIdle()
            val first = session.connections.single()
            val frozen = vm.uiState.value.backend
            assertNotNull(frozen)

            // The terminal socket closes as the link drops. A stale
            // CONNECTED pair must not trigger an immediate reattach.
            session.setConnection(ConnectionState.RECONNECTING)
            first.detach("released")
            advanceUntilIdle()

            assertEquals(
                "must not re-open while the link is down",
                1,
                session.openedPanes.size,
            )
            assertEquals(
                "the backend behind the last frame must survive the drop",
                frozen,
                vm.uiState.value.backend,
            )
            assertEquals(0, first.released)

            session.setConnection(ConnectionState.CONNECTED)
            advanceUntilIdle()

            assertEquals(
                "the same pane is reattached once the link is usable",
                2,
                session.openedPanes.size,
            )
        }

    @Test
    fun `a reattach that cannot connect keeps the old backend`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val vm = TerminalViewModel(session, null, null, null, settings())
        observe(vm)
        advanceUntilIdle()
        val first = session.connections.single()

        session.setConnection(ConnectionState.RECONNECTING)
        first.detach("released")
        advanceUntilIdle()
        val frozen = vm.uiState.value.backend
        assertNotNull(frozen)

        session.openFailure = DaemonException("not_connected", "link down")
        vm.retryAttach()
        advanceTimeBy(30_001)
        advanceUntilIdle()

        assertEquals(
            "a failed reattach while disconnected must not clear the last frame",
            frozen,
            vm.uiState.value.backend,
        )
        assertEquals(0, first.released)
    }


    @Test
    fun `a pane switch never sends input to the pane the user left`() = runTest(dispatcher) {
        val p1 = pane("w1", "w1:t1", id = "w1:p1")
        val p2 = pane("w1", "w1:t1", id = "w1:p2")
        val session = FakeHerdrSession(
            snapshot(workspace("w1", listOf(tab("w1:t1", "w1", listOf(p1, p2))))),
        )
        val vm = TerminalViewModel(session, null, null, null, settings())
        observe(vm)
        advanceUntilIdle()
        val old = session.connections.single()
        val oldBackend = vm.uiState.value.backend
        assertNotNull(oldBackend)

        session.autoAttach = false
        vm.openTarget(TerminalTarget("w1", "w1:t1", "w1:p2"))
        runCurrent()
        assertEquals(
            "the opening placeholder must stay until the new stream is ready",
            null,
            vm.uiState.value.backend,
        )

        vm.sendText("x")
        runCurrent()
        assertEquals("input must never reach the pane the user left", emptyList<String>(), old.sentText)

        session.connections.last().attach()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.backend)
        assertEquals(listOf("x"), session.connections.last().sentText)
    }

    @Test
    fun `a same-pane reattach keeps the frame until the replacement can paint`() =
        runTest(dispatcher) {
            val session = FakeHerdrSession(snapshot(oneWorkspace()))
            val vm = TerminalViewModel(session, null, null, null, settings())
            observe(vm)
            advanceUntilIdle()
            val old = session.connections.single()
            val oldBackend = vm.uiState.value.backend
            assertNotNull(oldBackend)

            session.autoAttach = false
            old.detach("released")
            advanceTimeBy(700)
            runCurrent()

            assertEquals(
                "the old controller must paint until the new stream is Attached",
                oldBackend,
                vm.uiState.value.backend,
            )
            vm.sendText("x")
            runCurrent()
            assertEquals(emptyList<String>(), old.sentText)

            session.connections.last().attach()
            advanceUntilIdle()

            assertTrue(oldBackend !== vm.uiState.value.backend)
            assertEquals(1, old.released)
            assertEquals(listOf("x"), session.connections.last().sentText)
        }


    @Test
    fun `lagged resync still spends the bounded reattach budget`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val vm = TerminalViewModel(session, null, null, null, settings())
        observe(vm)
        advanceUntilIdle()

        repeat(5) {
            session.connections.lastOrNull()?.detach("lagged_resync")
            advanceTimeBy(20)
            runCurrent()
        }
        advanceUntilIdle()

        assertTrue(
            "lagged resync must not bypass the retry budget, opened=${session.openedPanes.size}",
            session.openedPanes.size <= 5,
        )
        assertTrue(
            "the budget must end in the explicit attach failure",
            vm.uiState.value.statusMessage is dev.herdr.mobile.core.designsystem.UiMessage.AttachFailed,
        )
    }


    @Test
    fun `a replacement that never paints ends in AttachFailed with Retry`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val vm = TerminalViewModel(session, null, null, null, settings())
        observe(vm)
        advanceUntilIdle()
        val old = session.connections.single()
        val oldBackend = vm.uiState.value.backend
        assertNotNull(oldBackend)

        session.autoAttach = false
        old.detach("released")
        advanceUntilIdle()

        assertEquals(
            "the frozen frame must stay while replacements fail",
            oldBackend,
            vm.uiState.value.backend,
        )
        assertTrue(
            "exhausting the retry budget must expose the failure block",
            vm.uiState.value.statusMessage is
                dev.herdr.mobile.core.designsystem.UiMessage.AttachFailed,
        )
        assertTrue(
            "the pre-ready retries must be bounded, opened=${session.openedPanes.size}",
            session.openedPanes.size <= 6,
        )

        session.autoAttach = true
        vm.retryAttach()
        advanceUntilIdle()

        assertNull(vm.uiState.value.statusMessage)
        assertTrue(oldBackend !== vm.uiState.value.backend)
    }


    @Test
    fun `DONE during a replacement attach is acknowledged when it lands`() =
        runTest(dispatcher) {
            val working = oneWorkspace()
            val session = FakeHerdrSession(snapshot(working))
            val vm = TerminalViewModel(session, null, null, null, settings())
            observe(vm)
            advanceUntilIdle()
            val first = session.connections.single()

            session.autoAttach = false
            first.detach("released")
            advanceTimeBy(700)
            runCurrent()
            val replacement = session.connections.last()
            assertTrue(replacement !== first)

            val done = working.copy(
                tabs = working.tabs.map { tab ->
                    tab.copy(panes = tab.panes.map { it.copy(status = AgentStatus.DONE) })
                },
            )
            session.setSnapshot(snapshot(done))
            runCurrent()
            assertEquals(
                "a detached frozen backend must not claim the DONE ack",
                emptyList<String>(),
                session.paneFocusCalls,
            )

            replacement.attach()
            advanceUntilIdle()

            assertEquals(listOf("w1:w1:t1"), session.paneFocusCalls)
        }

    @Test
    fun `a pending replacement retry is cancelled when the screen stops`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(oneWorkspace()))
        val vm = TerminalViewModel(session, null, null, null, settings())
        observe(vm)
        advanceUntilIdle()
        val first = session.connections.single()

        session.autoAttach = false
        first.detach("released")
        advanceTimeBy(700)
        runCurrent()
        assertTrue(session.connections.size >= 2)

        vm.releaseForBackground()
        advanceUntilIdle()
        val openedBefore = session.openedPanes.size

        // The link returns while the screen is stopped: the stale retry must
        // not reopen the pane behind the user's back.
        session.setConnection(ConnectionState.RECONNECTING)
        session.setConnection(ConnectionState.CONNECTED)
        advanceUntilIdle()
        assertEquals(openedBefore, session.openedPanes.size)

        session.autoAttach = true
        vm.resumeAfterBackground()
        advanceUntilIdle()
        assertEquals(openedBefore + 1, session.openedPanes.size)
    }

}
