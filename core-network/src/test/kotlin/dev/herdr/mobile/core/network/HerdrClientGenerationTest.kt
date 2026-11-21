package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.SessionSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrClientGenerationTest {
    @Test
    fun oldInFlightSnapshotCannotRestoreCacheAfterTargetSwitch() = runBlocking {
        // Bug class #3: an old API response arriving after a profile switch cannot republish cache.
        val clock = ManualClock()
        val transport = FakeDaemonTransport()
        val state = LinkState(clock)
        val sync = SnapshotSync(state, transport, clock)

        val requestStarted = CompletableDeferred<Unit>()
        val allowResponse = CompletableDeferred<Unit>()
        transport.onSnapshot = {
            requestStarted.complete(Unit)
            allowResponse.await()
        }
        transport.snapshot = SessionSnapshot(revision = 2, seq = 8)

        val oldGeneration = state.linkGeneration
        assertTrue(
            state.update(oldGeneration) {
                it.copy(
                    connection = ConnectionState.CONNECTED,
                    snapshot = SessionSnapshot(revision = 1, seq = 7),
                )
            },
        )
        assertTrue(state.installEndpoint(oldGeneration, transport.endpoint))

        val inFlight = launch(Dispatchers.Default) { sync.refetch("manual", oldGeneration) }
        requestStarted.await()

        // AppContainer invalidates immediately, then redials after the provider rebuild.
        state.invalidateTarget()
        state.beginRedial(clearCachedSnapshot = false)
        allowResponse.complete(Unit)
        inFlight.join()

        assertNull(state.state.value.snapshot)
        assertEquals(ConnectionState.CONNECTING, state.state.value.connection)
        assertEquals(1L, state.targetGeneration)
        assertEquals(oldGeneration + 2, state.linkGeneration)
    }

    @Test
    fun lateRestSnapshotFromThePreviousAttemptCannotOverwriteTheNewOne() = runBlocking {
        val clock = ManualClock()
        val transport = FakeDaemonTransport()
        val state = LinkState(clock)
        val sync = SnapshotSync(state, transport, clock)
        val generation = state.linkGeneration
        state.beginConnection(generation)
        assertTrue(
            state.update(generation) {
                it.copy(
                    connection = ConnectionState.CONNECTED,
                    snapshot = SessionSnapshot(revision = 1, seq = 0),
                )
            },
        )
        assertTrue(state.installEndpoint(generation, transport.endpoint))

        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.onSnapshot = {
            started.complete(Unit)
            release.await()
        }
        transport.snapshot = SessionSnapshot(revision = 2, seq = 100)
        val request = launch(Dispatchers.Default) { sync.refetch("race", generation) }
        started.await()

        // Automatic retry starts a new epoch while the old GET is in flight.
        state.beginConnection(generation)
        release.complete(Unit)
        request.join()

        assertEquals(0L, state.state.value.snapshot?.seq)
        assertEquals(2L, state.state.value.connectionEpoch)
    }

    @Test
    fun connectionEpochChangesPerAttemptAndRejectsStaleEvents() {
        val state = LinkState(ManualClock())
        val generation = state.linkGeneration

        assertEquals(1L, state.beginConnection(generation))
        assertEquals(1L, state.state.value.connectionEpoch)
        assertTrue(state.acceptActivity(generation, 1) {})

        // Automatic retry inside the same link generation.
        assertEquals(2L, state.beginConnection(generation))
        assertTrue(state.state.value.connectionEpoch == 2L)
        assertTrue(
            "an event stamped with the previous attempt must be rejected",
            !state.acceptActivity(generation, 1) {},
        )
        assertTrue(state.acceptActivity(generation, 2) {})
    }
}
