package dev.herdr.mobile

import dev.herdr.mobile.core.model.AgentStatus
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.Pane
import dev.herdr.mobile.core.model.SemanticEvent
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.model.Tab
import dev.herdr.mobile.core.model.Workspace
import dev.herdr.mobile.core.network.HerdrClientState
import dev.herdr.mobile.core.network.StampedEvent
import dev.herdr.mobile.core.network.testing.FakeHerdrSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the REAL NotificationRelay ordering machine with fake ports.
 * These are the orderings that repeatedly broke the release gate: initial
 * seed vs. live events, short DONE, and daemon restart with a lower seq.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationRelayOrderingTest {

    private sealed interface Action {
        data class Posted(val paneId: String?, val status: AgentStatus?) : Action
        data class DismissedStatus(
            val paneId: String?,
            val status: AgentStatus?,
        ) : Action
        data class DismissedPane(val paneId: String?) : Action
    }

    private class Records : AlertNotifier {
        val actions = mutableListOf<Action>()
        override fun notify(event: SemanticEvent, workspaceLabel: String?, tabLabel: String?) {
            actions += Action.Posted(event.paneId, event.status)
        }

        override fun dismissPane(workspaceId: String?, paneId: String?) {
            actions += Action.DismissedPane(paneId)
        }

        override fun dismissPaneStatus(
            workspaceId: String?,
            paneId: String?,
            status: AgentStatus,
        ) {
            actions += Action.DismissedStatus(paneId, status)
        }

        fun posted(status: AgentStatus) =
            actions.filterIsInstance<Action.Posted>().count { it.status == status }
    }

    private class Settings(
        persisted: Map<String, String> = emptyMap(),
        val gate: CompletableDeferred<Unit>? = null,
    ) : NotificationSettings {
        override val settings = kotlinx.coroutines.flow.MutableStateFlow(
            AppSettings(
                backgroundMonitoring = true,
                notifyDone = true,
                notifyBlocked = true,
            ),
        )
        override val storageHealthy = true
        var saved: Map<String, String> = persisted

        override suspend fun loadNotified(): Map<String, String> {
            gate?.await()
            return saved
        }

        override suspend fun saveNotified(map: Map<String, String>) {
            saved = map
        }
    }

    private fun pane(status: AgentStatus, id: String = "p1") = Pane(
        id = id,
        tabId = "t1",
        workspaceId = "w1",
        status = status,
    )

    private fun snapshot(seq: Long, status: AgentStatus): SessionSnapshot {
        val p = pane(status)
        return SessionSnapshot(
            revision = seq,
            seq = seq,
            workspaces = listOf(
                Workspace(
                    id = "w1",
                    label = "w1",
                    activeTabId = "t1",
                    tabs = listOf(Tab(id = "t1", workspaceId = "w1", panes = listOf(p))),
                ),
            ),
        )
    }

    private fun event(seq: Long, status: AgentStatus) = SemanticEvent(
        seq = seq,
        type = SemanticEvent.TYPE_PANE_STATUS_CHANGED,
        workspaceId = "w1",
        tabId = "t1",
        paneId = "p1",
        status = status,
    )

    private fun TestScope.relay(
        session: FakeHerdrSession,
        settings: Settings,
        records: Records,
        events: MutableSharedFlow<StampedEvent>,
    ) = NotificationRelay(
        notifier = records,
        settings = settings,
        client = session,
        scope = backgroundScope,
        events = events,
    )

    private fun FakeHerdrSession.publish(
        snapshot: SessionSnapshot,
        epoch: Long,
        connection: ConnectionState = ConnectionState.CONNECTED,
    ) {
        publish(
            HerdrClientState(
                connection = connection,
                snapshot = snapshot,
                connectionEpoch = epoch,
            ),
        )
    }

    @Test
    fun `daemon restart with lower seq still alerts the next DONE`() = runTest(UnconfinedTestDispatcher()) {
        val settings = Settings(persisted = mapOf("p1" to "done|w1"))
        val records = Records()
        val events = MutableSharedFlow<StampedEvent>(extraBufferCapacity = 8)
        val session = FakeHerdrSession(snapshot(100, AgentStatus.DONE))
        relay(session, settings, records, events)
        advanceUntilIdle()

        // Reconnect: epoch moves and the daemon's seq restarts at 0.
        session.publish(snapshot(0, AgentStatus.DONE), epoch = 2)
        advanceUntilIdle()
        events.emit(StampedEvent(2, event(1, AgentStatus.WORKING)))
        events.emit(StampedEvent(2, event(2, AgentStatus.DONE)))
        events.emit(StampedEvent(2, event(3, AgentStatus.WORKING)))
        runCurrent()

        assertEquals("the new completion must alert once: ${records.actions}", 1, records.posted(AgentStatus.DONE))
        assertTrue(
            "the alert must be retracted by the following WORKING: ${records.actions}",
            records.actions.any {
                (it is Action.DismissedPane && it.paneId == "p1") ||
                    (it is Action.DismissedStatus && it.status == AgentStatus.DONE)
            },
        )
    }

    @Test
    fun `a restart snapshot that overtakes events still alerts the short DONE`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = Settings(persisted = mapOf("p1" to "done|w1"))
            val records = Records()
            val events = MutableSharedFlow<StampedEvent>(extraBufferCapacity = 8)
            val session = FakeHerdrSession(snapshot(100, AgentStatus.DONE))
            relay(session, settings, records, events)
            advanceUntilIdle()

            // New epoch. The snapshot side sees DONE(0), then DONE(2)
            // without an intervening event, then WORKING(3). The late
            // DONE(2) event is the only chance to announce that completion.
            session.publish(snapshot(0, AgentStatus.DONE), epoch = 2)
            advanceUntilIdle()
            session.publish(snapshot(2, AgentStatus.DONE), epoch = 2)
            advanceUntilIdle()
            session.publish(snapshot(3, AgentStatus.WORKING), epoch = 2)
            advanceUntilIdle()

            events.emit(StampedEvent(2, event(2, AgentStatus.DONE)))
            advanceUntilIdle()

            assertEquals(
                "the late DONE must alert once: ${records.actions}",
                1,
                records.posted(AgentStatus.DONE),
            )
        }

    @Test
    fun `a short DONE alerts once and is retracted`() = runTest(UnconfinedTestDispatcher()) {
        val settings = Settings()
        val records = Records()
        val events = MutableSharedFlow<StampedEvent>(extraBufferCapacity = 8)
        val session = FakeHerdrSession(snapshot(0, AgentStatus.WORKING))
        relay(session, settings, records, events)
        advanceUntilIdle()

        events.emit(StampedEvent(0, event(1, AgentStatus.WORKING)))
        events.emit(StampedEvent(0, event(2, AgentStatus.DONE)))
        events.emit(StampedEvent(0, event(3, AgentStatus.WORKING)))
        advanceUntilIdle()

        assertEquals("short DONE: ${records.actions}", 1, records.posted(AgentStatus.DONE))
    }

    @Test
    fun `seed does not repost a DONE already announced by a live event`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val settings = Settings(persisted = mapOf("p1" to "blocked|w1"), gate = gate)
        val records = Records()
        val events = MutableSharedFlow<StampedEvent>(extraBufferCapacity = 8)
        val session = FakeHerdrSession(snapshot(11, AgentStatus.DONE))
        relay(session, settings, records, events)
        advanceUntilIdle()

        // Live DONE while the persisted map is still loading, then seed.
        events.emit(StampedEvent(0, event(11, AgentStatus.DONE)))
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, records.posted(AgentStatus.DONE))
        assertEquals(0, records.posted(AgentStatus.BLOCKED))
    }

    @Test
    fun `snapshot-first seed still alerts a later short DONE once`() = runTest(UnconfinedTestDispatcher()) {
        val settings = Settings(persisted = mapOf("p1" to "blocked|w1"))
        val records = Records()
        val events = MutableSharedFlow<StampedEvent>(extraBufferCapacity = 8)
        val session = FakeHerdrSession(snapshot(0, AgentStatus.WORKING))
        relay(session, settings, records, events)
        advanceUntilIdle()

        // A DONE event arrives after the seed's WORKING snapshot.
        events.emit(StampedEvent(0, event(1, AgentStatus.DONE)))
        advanceUntilIdle()
        events.emit(StampedEvent(0, event(2, AgentStatus.WORKING)))
        advanceUntilIdle()

        assertEquals("snapshot-first: ${records.actions}", 1, records.posted(AgentStatus.DONE))
    }
}
