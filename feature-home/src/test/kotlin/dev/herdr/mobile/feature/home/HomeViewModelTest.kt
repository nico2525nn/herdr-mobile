package dev.herdr.mobile.feature.home

import dev.herdr.mobile.core.designsystem.UiMessage
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.HerdrInfo
import dev.herdr.mobile.core.model.HerdrUpdate
import dev.herdr.mobile.core.model.Pane
import dev.herdr.mobile.core.model.Reorder
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.model.Tab
import dev.herdr.mobile.core.model.Workspace
import dev.herdr.mobile.core.network.HerdrClientState
import dev.herdr.mobile.core.network.testing.FakeHerdrSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun workspace(id: String): Workspace {
        val pane = Pane(id = "$id:p1", tabId = "$id:t1", workspaceId = id)
        return Workspace(
            id = id,
            label = id,
            activeTabId = "$id:t1",
            tabs = listOf(Tab(id = "$id:t1", workspaceId = id, panes = listOf(pane))),
        )
    }

    private fun snapshot(
        workspaces: List<Workspace> = emptyList(),
        herdrVersion: String? = null,
        latestVersion: String? = null,
    ) = SessionSnapshot(
        revision = 1,
        seq = 1,
        herdr = HerdrInfo(version = herdrVersion),
        workspaces = workspaces,
        update = HerdrUpdate(latestVersion = latestVersion),
    )

    /** A ViewModel with its state flow collected, as a real screen would. */
    private fun TestScope.start(
        session: FakeHerdrSession,
    ): HomeViewModel {
        val model = HomeViewModel(session)
        backgroundScope.launch { model.uiState.collect {} }
        return model
    }

    @Test
    fun `create trims input and turns blank fields into nulls`() = runTest(dispatcher) {
        val session = FakeHerdrSession()
        val model = start(session)

        model.createWorkspace(label = "  work  ", cwd = "   ")
        advanceUntilIdle()

        assertEquals(listOf<String?>("work"), session.createWorkspaceCalls)
    }

    @Test
    fun `a failed command surfaces the action and the reason`() = runTest(dispatcher) {
        val session = FakeHerdrSession().apply {
            commandFailure = IllegalStateException("daemon said no")
        }
        val model = start(session)

        model.closeTab("t1")
        advanceUntilIdle()

        assertEquals(
            UiMessage.CommandFailed(
                UiMessage.CommandFailed.Command.CLOSE_TAB,
                "daemon said no",
            ),
            model.message.value,
        )
    }

    @Test
    fun `moving a workspace sends the daemon's insertion index`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(listOf(workspace("a"), workspace("b"), workspace("c"))))
        val model = start(session)
        advanceUntilIdle()

        model.moveWorkspace("a", toIndex = 2)
        advanceUntilIdle()

        assertEquals(listOf("a" to Reorder.insertIndex(0, 2, 3)), session.moveWorkspaceCalls)
    }

    @Test
    fun `moving an unknown workspace or dropping it back is a no-op`() = runTest(dispatcher) {
        val session = FakeHerdrSession(snapshot(listOf(workspace("a"), workspace("b"))))
        val model = start(session)
        advanceUntilIdle()

        model.moveWorkspace("missing", toIndex = 0)
        model.moveWorkspace("a", toIndex = 0)
        advanceUntilIdle()

        assertTrue(session.moveWorkspaceCalls.isEmpty())
    }

    @Test
    fun `the update flag clears even when the update fails`() = runTest(dispatcher) {
        val session = FakeHerdrSession().apply {
            commandFailure = IllegalStateException("network down")
        }
        val model = start(session)

        model.runHerdrUpdate()
        advanceUntilIdle()

        assertEquals(false, model.updateRunning.value)
        assertEquals(
            UiMessage.CommandFailed(
                UiMessage.CommandFailed.Command.UPDATE_HERDR,
                "network down",
            ),
            model.message.value,
        )
    }

    @Test
    fun `an update pill appears only for a newer verified version`() = runTest(dispatcher) {
        val session = FakeHerdrSession()
        val model = start(session)
        advanceUntilIdle()

        session.publish(
            HerdrClientState(
                connection = ConnectionState.CONNECTED,
                snapshot = snapshot(herdrVersion = "0.9.3", latestVersion = "0.9.4"),
            ),
        )
        advanceUntilIdle()
        assertEquals("0.9.4", model.uiState.value.updateAvailable)

        session.publish(
            HerdrClientState(
                connection = ConnectionState.CONNECTED,
                snapshot = snapshot(herdrVersion = "0.9.4", latestVersion = "0.9.4"),
            ),
        )
        advanceUntilIdle()
        assertNull(model.uiState.value.updateAvailable)
    }

    @Test
    fun `the offline banner follows the link state and the cached snapshot`() = runTest(dispatcher) {
        val session = FakeHerdrSession()
        val model = start(session)
        advanceUntilIdle()

        session.publish(HerdrClientState(connection = ConnectionState.RECONNECTING))
        advanceUntilIdle()
        assertTrue(model.uiState.value.showOfflineBanner)

        // Failed WITH a snapshot keeps the app usable: no banner.
        session.publish(
            HerdrClientState(
                connection = ConnectionState.FAILED,
                snapshot = snapshot(listOf(workspace("a"))),
            ),
        )
        advanceUntilIdle()
        assertEquals(false, model.uiState.value.showOfflineBanner)
        assertEquals(1, model.uiState.value.workspaceCount)
        assertEquals(1, model.uiState.value.tabCount)
    }

    @Test
    fun `clearing a message leaves no notice behind`() = runTest(dispatcher) {
        val session = FakeHerdrSession().apply {
            commandFailure = IllegalStateException("boom")
        }
        val model = start(session)
        model.closeWorkspace("w1")
        advanceUntilIdle()
        assertEquals(
            UiMessage.CommandFailed(UiMessage.CommandFailed.Command.CLOSE_WORKSPACE, "boom"),
            model.message.value,
        )

        model.clearMessage()
        assertNull(model.message.value)
    }
}
