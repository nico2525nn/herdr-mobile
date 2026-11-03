package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.HealthReport
import dev.herdr.mobile.core.model.SessionSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Virtual [LinkClock]: sleeps suspend until the test releases them by duration, and `now`
 * only advances by the duration actually released. Lets the retry policy be asserted
 * without any real waiting.
 */
internal class ManualClock(start: Long = 1_000_000_000_000L) : LinkClock {
    private val lock = Any()
    private var current = start
    private val pending = LinkedHashMap<Long, MutableList<CompletableDeferred<Unit>>>()

    override fun now(): Long = synchronized(lock) { current }

    override suspend fun sleep(ms: Long) {
        val gate = CompletableDeferred<Unit>()
        synchronized(lock) {
            pending.getOrPut(ms) { mutableListOf() } += gate
        }
        gate.await()
        synchronized(lock) { current += ms }
    }

    /** Release the next non-watchdog sleep and return the duration that was requested. */
    suspend fun nextBackoff(lessThan: Long = 30_000): Long =
        withTimeout(WAIT_MS) { awaitNextBackoff(lessThan) }

    private suspend fun awaitNextBackoff(lessThan: Long): Long {
        while (true) {
            val ms = synchronized(lock) {
                pending.entries.firstOrNull { it.key < lessThan && it.value.isNotEmpty() }?.key
            }
            if (ms != null) {
                release(ms)
                return ms
            }
            delay(1)
        }
    }

    suspend fun awaitSleep(ms: Long) {
        withTimeout(WAIT_MS) {
            while (synchronized(lock) { pending[ms]?.isNotEmpty() != true }) delay(1)
        }
    }

    /** Complete a sleeper for [ms] (if one exists) and let it run. */
    suspend fun release(ms: Long): Boolean {
        val gate = withTimeout(WAIT_MS) { awaitGate(ms) }
        gate.complete(Unit)
        return true
    }

    private suspend fun awaitGate(ms: Long): CompletableDeferred<Unit> {
        while (true) {
            val gate = synchronized(lock) { pending[ms]?.removeFirstOrNull() }
            if (gate != null) return gate
            delay(1)
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}

/**
 * Scriptable [DaemonTransport] for link-policy tests. No sockets: every dial, health check,
 * snapshot fetch and event stream is a recorded call the test controls.
 */
internal class FakeDaemonTransport : DaemonTransport {
    val opens = AtomicInteger()
    val closes = AtomicInteger()
    val healthChecks = AtomicInteger()
    val snapshots = AtomicInteger()

    var endpoint: DaemonEndpoint =
        DaemonEndpoint.direct("http://127.0.0.1:8765", null, "fake target")

    var health: HealthReport = HealthReport(ok = true, herdrConnected = true)
    var snapshot: SessionSnapshot = SessionSnapshot(revision = 1, seq = 1)

    /** Throw to make dial number [attempt] (1-based) fail; null lets it open. */
    var openFailure: (Int) -> Throwable? = { IOException("dial failed") }

    /** Runs inside [snapshot]; lets a test hold a fetch in flight. */
    var onSnapshot: suspend () -> Unit = {}

    /** Event stream per successful bootstrap. Default: stream closes immediately. */
    var events: () -> Flow<EventSocket.Signal> =
        { flowOf(EventSocket.Signal.Closed(1000, "closed")) }

    override suspend fun open(): DaemonEndpoint {
        val attempt = opens.incrementAndGet()
        openFailure(attempt)?.let { throw it }
        return endpoint
    }

    override suspend fun close() {
        closes.incrementAndGet()
    }

    override suspend fun health(endpoint: DaemonEndpoint): HealthReport {
        healthChecks.incrementAndGet()
        return health
    }

    override suspend fun snapshot(endpoint: DaemonEndpoint): SessionSnapshot {
        snapshots.incrementAndGet()
        onSnapshot()
        return snapshot
    }

    override fun api(endpoint: DaemonEndpoint): DaemonApi =
        error("REST commands are not used by link-policy tests")

    override fun events(endpoint: DaemonEndpoint, stillWanted: () -> Boolean): Flow<EventSocket.Signal> =
        events()

    override fun terminal(
        endpoint: DaemonEndpoint,
        paneId: String,
        cols: Int,
        rows: Int,
        takeover: Boolean,
        scope: CoroutineScope,
    ): TerminalConnection = error("terminals are not opened by link-policy tests")
}
