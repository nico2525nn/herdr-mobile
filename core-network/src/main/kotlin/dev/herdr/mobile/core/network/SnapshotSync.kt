package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.SemanticEvent
import dev.herdr.mobile.core.model.SnapshotReducer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keeps the snapshot cache honest: consumes `/v1/events`, folds contiguous events, and
 * refetches authoritatively the moment the cache can no longer be trusted.
 *
 * Every public entry point carries the link generation it was started with. Anything that
 * arrives after a redial is dropped before it can touch the cache or the notification bus.
 */
/** One semantic event plus the connection attempt that produced it. */
data class StampedEvent(val connectionEpoch: Long, val event: SemanticEvent)

internal class SnapshotSync(
    private val state: LinkState,
    private val transport: DaemonTransport,
    private val clock: LinkClock,
) {
    /**
     * Every semantic event as it arrives, BEFORE folding. Notifications consume this
     * (exact transitions, no coalescing loss); snapshots remain the UI source. Replay 0 +
     * conflated buffer: slow collectors drop interim events, and the snapshot-diff backstop
     * in NotificationRelay covers gaps.
     *
     * Single SharedFlow (never swapped): stale events are dropped at emit time by the
     * generation check in [accept], which is the only writer.
     *
     * Events are emitted AFTER the snapshot fold. Notifications therefore never see a
     * state older than the event that triggered them, even though they collect on a
     * different coroutine.
     */
    /** Concurrent manual refreshes must not race an older response over a newer one. */
    private val refetchMutex = Mutex()

    private val _events = MutableSharedFlow<SemanticEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SemanticEvent> = _events

    private val _stampedEvents = MutableSharedFlow<StampedEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val stampedEvents: SharedFlow<StampedEvent> = _stampedEvents

    /**
     * Collect the event stream for [generation] until it dies (throws) or the link is
     * replaced. `stream.ready` carries the daemon cursor with no replay, so a cursor that
     * moved past our snapshot forces a refetch before consuming.
     */
    suspend fun consume(
        generation: Long,
        connectionEpoch: Long = state.currentConnectionEpoch(),
        onStreamEvent: () -> Unit = {},
    ) {
        if (!state.isCurrent(generation)) {
            throw DaemonException("not_connected", "Connection was replaced")
        }
        val endpoint = state.endpoint
            ?: throw DaemonException("not_connected", "No endpoint is open")
        transport.events(endpoint) { state.isCurrent(generation) }.collect { signal ->
            if (!state.isCurrent(generation)) return@collect
            when (signal) {
                is EventSocket.Signal.Ready -> {
                    val held = state.state.value.snapshot?.seq ?: -1
                    if (signal.seq != held) {
                        refetch("cursor moved ${held} -> ${signal.seq} during subscribe", generation)
                    }
                }

                is EventSocket.Signal.Event -> {
                    // Proven data flow (not merely an accepted socket): used
                    // by the retry policy to reset backoff only once a stream
                    // actually delivers an event.
                    onStreamEvent()
                    applyEvent(signal.event, generation, connectionEpoch)
                }

                is EventSocket.Signal.Closed ->
                    throw DaemonException("stream_closed", signal.reason.ifBlank { "Event stream closed" })
            }
        }
    }

    suspend fun applyEvent(
        event: SemanticEvent,
        generation: Long,
        connectionEpoch: Long = state.currentConnectionEpoch(),
    ) {
        if (!accept(event, generation, connectionEpoch)) return
        var outcome: SnapshotReducer.Outcome? = null
        val updated = state.updateAt(generation, connectionEpoch) { current ->
            val snapshot = current.snapshot ?: return@updateAt current
            val result = SnapshotReducer.apply(snapshot, event)
            outcome = result
            when (result) {
                is SnapshotReducer.Outcome.Applied -> current.copy(
                    snapshot = result.snapshot,
                    stale = false,
                    lastSyncAt = clock.now(),
                )

                is SnapshotReducer.Outcome.Gap -> current.copy(
                    connection = ConnectionState.STALE,
                    stale = true,
                )

                SnapshotReducer.Outcome.Refetch -> current
            }
        }
        if (!updated) return
        // Publish only after the fold, and only while this link is still live.
        if (state.isCurrentEpoch(generation, connectionEpoch)) {
            _events.tryEmit(event)
            _stampedEvents.tryEmit(StampedEvent(connectionEpoch, event))
        }
        when (val result = outcome) {
            is SnapshotReducer.Outcome.Applied -> {
                // Consistency tick: the reducer already advanced the cursor (ticks fold as
                // no-ops), so a hash mismatch means the fold diverged despite contiguous
                // seqs — refetch authoritatively. Match = proof, zero bytes beyond the tick.
                if (event.type == SemanticEvent.TYPE_SNAPSHOT_TICK) {
                    state.markTick(generation)
                    val want = (event.detail?.get("hash") as? JsonPrimitive)?.content
                    val have = result.snapshot.statusHash()
                    if (want != null && want != have) {
                        refetch("tick hash drift (want $want, have $have)", generation)
                    }
                }
            }

            is SnapshotReducer.Outcome.Gap -> {
                refetch("seq gap: expected ${result.expectedSeq}, got ${result.receivedSeq}", generation)
            }

            SnapshotReducer.Outcome.Refetch -> {
                refetch("daemon requested refetch (${event.type})", generation)
            }

            null -> refetch("event without snapshot", generation)
        }
    }

    /** Force an authoritative snapshot (presence check, cursor drift, gap, manual refresh). */
    suspend fun refetch(reason: String, generation: Long) {
        // Capture the attempt with the endpoint. An automatic retry can start
        // (and advance the epoch) while this GET is in flight; a late response
        // from the old daemon must not overwrite the new snapshot.
        val endpoint = state.endpoint ?: return
        val connectionEpoch = state.currentConnectionEpoch()
        refetchMutex.withLock {
            if (!state.isCurrentEpoch(generation, connectionEpoch)) return
            try {
                val snapshot = transport.snapshot(endpoint)
                state.updateAt(generation, connectionEpoch) { current ->
                    // Events may have folded past a slow GET. Installing the
                    // older response would regress seq and force a gap refetch.
                    if (snapshot.seq < (current.snapshot?.seq ?: Long.MIN_VALUE)) {
                        current
                    } else {
                        current.copy(
                            connection = ConnectionState.CONNECTED,
                            snapshot = snapshot,
                            stale = false,
                            error = null,
                            lastSyncAt = clock.now(),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.updateAt(generation, connectionEpoch) {
                    it.copy(
                        connection = if (it.snapshot != null) {
                            ConnectionState.RECONNECTING
                        } else {
                            ConnectionState.CONNECTING
                        },
                        stale = it.snapshot != null,
                        error = "Refetch failed ($reason): ${userMessage(e)}",
                    )
                }
                throw e
            }
        }
    }

    /**
     * Emit the event and record stream activity, atomically with the generation check.
     * Returns false when the event belongs to a link that has already been replaced.
     */
    private fun accept(event: SemanticEvent, generation: Long, connectionEpoch: Long): Boolean =
        state.acceptActivity(generation, connectionEpoch) { }

}
