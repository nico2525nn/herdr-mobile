package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.SemanticEvent
import dev.herdr.mobile.core.model.SessionSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Principle-level tests for the reconnect policy: what the link does over time, without
 * sockets or wall-clock sleeps. Scripted transport + virtual clock only.
 */
class LinkLifecycleTest {

    @Test
    fun failedRedialsDoubleFromTheInitialBackoffToTheCap() = runBlocking {
        val link = Harness()
        try {
            link.lifecycle.start()

            val delays = List(7) { link.clock.nextBackoff() }

            // 0.5s, 1s, 2s, 4s, 8s, then pinned at the 15s cap.
            assertEquals(
                listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L),
                delays,
            )
            assertEquals(ConnectionState.CONNECTING, link.state.state.value.connection)
        } finally {
            link.scope.cancel()
        }
    }

    @Test
    fun aSuccessfulBootstrapResetsBackoffToTheInitialDelay() = runBlocking {
        val transport = FakeDaemonTransport()
        // First dial fails; the next one bootstraps and then the event stream dies.
        transport.openFailure = { attempt -> if (attempt == 1) IOException("dial failed") else null }
        val link = Harness(transport = transport)
        try {
            link.lifecycle.start()
            assertEquals(500L, link.clock.nextBackoff())

            // Second dial succeeded (health + snapshot), then the stream closed at seq 1.
            link.clock.awaitSleep(500)
            assertEquals(500L, link.clock.nextBackoff())
            // The third dial may already be in flight after the stream drop;
            // the assertion that matters is the reset backoff above.
            assertTrue(transport.opens.get() >= 2)
            assertTrue(transport.healthChecks.get() >= 1)
            assertTrue(transport.snapshots.get() >= 1)
            assertEquals(ConnectionState.RECONNECTING, link.state.state.value.connection)
        } finally {
            link.scope.cancel()
        }
    }

    @Test
    fun targetChangeBumpsGenerationsAndDropsTheCachedSnapshot() {
        val link = Harness()
        try {
            val oldGeneration = link.state.linkGeneration
            assertTrue(
                link.state.update(oldGeneration) {
                    it.copy(
                        connection = ConnectionState.CONNECTED,
                        snapshot = SessionSnapshot(revision = 1, seq = 5),
                    )
                },
            )
            assertTrue(link.state.installEndpoint(oldGeneration, link.transport.endpoint))

            link.lifecycle.invalidateTarget()

            assertEquals(oldGeneration + 1, link.state.linkGeneration)
            assertEquals(1L, link.state.targetGeneration)
            assertEquals(ConnectionState.CONNECTING, link.state.state.value.connection)
            assertNull(link.state.state.value.snapshot)
            assertNull(link.state.endpoint)
            assertTrue(link.state.targetInvalidated)

            // Anything still in flight for the old target is now refused.
            assertFalse(link.state.update(oldGeneration) { it.copy(error = "late") })
            assertFalse(link.state.installEndpoint(oldGeneration, link.transport.endpoint))

            // A kick during the debounce must not redial through the outgoing provider.
            link.lifecycle.kick()
            assertEquals(oldGeneration + 1, link.state.linkGeneration)
            assertEquals(0, link.transport.opens.get())
        } finally {
            link.scope.cancel()
        }
    }

    @Test
    fun snapshotRequiredRefetchesAuthoritativelyAndStillEmitsTheEvent() = runBlocking {
        val clock = ManualClock()
        val transport = FakeDaemonTransport()
        val state = LinkState(clock)
        val sync = SnapshotSync(state, transport, clock)
        val generation = state.linkGeneration
        transport.snapshot = SessionSnapshot(revision = 9, seq = 43)

        assertTrue(state.installEndpoint(generation, transport.endpoint))
        assertTrue(
            state.update(generation) {
                it.copy(
                    connection = ConnectionState.CONNECTED,
                    snapshot = SessionSnapshot(revision = 1, seq = 41),
                )
            },
        )

        val seen = CompletableDeferred<SemanticEvent>()
        val collector = launch(Dispatchers.Unconfined) { seen.complete(sync.events.first()) }
        try {
            sync.applyEvent(
                SemanticEvent(seq = 42, type = SemanticEvent.TYPE_SNAPSHOT_REQUIRED),
                generation,
            )

            assertEquals(SemanticEvent.TYPE_SNAPSHOT_REQUIRED, seen.await().type)
            assertEquals(43L, state.state.value.snapshot?.seq)
            assertEquals(ConnectionState.CONNECTED, state.state.value.connection)
            assertFalse(state.state.value.stale)
            assertEquals(1, transport.snapshots.get())
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun aSequenceGapMarksTheCacheStaleBeforeRefetching() = runBlocking {
        val clock = ManualClock()
        val transport = FakeDaemonTransport()
        val state = LinkState(clock)
        val sync = SnapshotSync(state, transport, clock)
        val generation = state.linkGeneration
        val fetchStarted = CompletableDeferred<Unit>()
        val allowFetch = CompletableDeferred<Unit>()
        transport.onSnapshot = {
            fetchStarted.complete(Unit)
            allowFetch.await()
        }
        transport.snapshot = SessionSnapshot(revision = 2, seq = 20)

        assertTrue(state.installEndpoint(generation, transport.endpoint))
        assertTrue(
            state.update(generation) {
                it.copy(
                    connection = ConnectionState.CONNECTED,
                    snapshot = SessionSnapshot(revision = 1, seq = 10),
                )
            },
        )

        val job = launch(Dispatchers.Default) {
            sync.applyEvent(
                SemanticEvent(seq = 15, type = SemanticEvent.TYPE_PANE_STATUS_CHANGED),
                generation,
            )
        }
        fetchStarted.await()

        // The gap is published immediately; the refetch then heals it.
        assertEquals(ConnectionState.STALE, state.state.value.connection)
        assertTrue(state.state.value.stale)

        allowFetch.complete(Unit)
        job.join()
        assertEquals(20L, state.state.value.snapshot?.seq)
        assertEquals(ConnectionState.CONNECTED, state.state.value.connection)
    }

    @Test
    fun aStoppedLinkKeepsItsCacheButStopsRetrying() = runBlocking {
        val link = Harness()
        try {
            assertTrue(
                link.state.update(link.state.linkGeneration) {
                    it.copy(
                        connection = ConnectionState.CONNECTED,
                        snapshot = SessionSnapshot(revision = 1, seq = 1),
                    )
                },
            )
            link.lifecycle.start()
            assertEquals(500L, link.clock.nextBackoff())
            assertEquals(1_000L, link.clock.nextBackoff())
            link.clock.awaitSleep(2_000)
            val attempts = link.transport.opens.get()
            val closes = link.transport.closes.get()

            link.lifecycle.stop()
            withTimeout(5_000) {
                while (link.transport.closes.get() <= closes) delay(1)
            }
            // Let the cancelled sleeper's gate fire; a live loop would dial again.
            link.clock.release(2_000)
            delay(100)

            assertEquals(attempts, link.transport.opens.get())
            assertEquals(ConnectionState.IDLE, link.state.state.value.connection)
            assertTrue(link.state.state.value.stale)
            assertEquals(1L, link.state.state.value.snapshot?.seq)
        } finally {
            link.scope.cancel()
        }
    }

    @Test
    fun aCancelledScopeStopsRetrying() = runBlocking {
        val link = Harness()
        try {
            link.lifecycle.start()
            assertEquals(500L, link.clock.nextBackoff())
            // Let the second dial happen and park in the next backoff before cancelling.
            withTimeout(5_000) {
                while (link.transport.opens.get() < 2) delay(1)
            }
            link.clock.awaitSleep(1_000)
            val attempts = link.transport.opens.get()

            link.scope.cancel()
            link.clock.release(1_000)
            delay(100)

            assertEquals(attempts, link.transport.opens.get())
        } finally {
            link.scope.cancel()
        }
    }

    private class Harness(
        val clock: ManualClock = ManualClock(),
        val transport: FakeDaemonTransport = FakeDaemonTransport(),
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) {
        val state = LinkState(clock)
        val sync = SnapshotSync(state, transport, clock)
        val lifecycle = LinkLifecycle(
            state = state,
            transport = transport,
            sync = sync,
            scope = scope,
            clock = clock,
            policy = LinkPolicy(jitterMs = 0),
            jitter = { 0L },
        )
    }
}
