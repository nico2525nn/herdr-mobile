package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.SemanticEvent
import dev.herdr.mobile.core.model.SnapshotReducer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keeps the snapshot cache honest: consumes `/v1/events`, folds contiguous events, and
 * refetches authoritatively the moment the cache can no longer be trusted.
 *
 * Every public entry point carries the link generation it was started with. Anything that
 * arrives after a redial is dropped before it can touch the cache or the notification bus.
 */
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
     */
    private val _events = MutableSharedFlow<SemanticEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SemanticEvent> = _events

    /**
     * Collect the event stream for [generation] until it dies (throws) or the link is
     * replaced. `stream.ready` carries the daemon cursor with no replay, so a cursor that
     * moved past our snapshot forces a refetch before consuming.
     */
    suspend fun consume(generation: Long) {
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

                is EventSocket.Signal.Event -> applyEvent(signal.event, generation)

                is EventSocket.Signal.Closed ->
                    throw DaemonException("stream_closed", signal.reason.ifBlank { "Event stream closed" })
            }
        }
    }

    suspend fun applyEvent(event: SemanticEvent, generation: Long) {
        if (!accept(event, generation)) return
        val current = state.state.value.snapshot ?: run {
            refetch("event without snapshot", generation)
            return
        }
        when (val outcome = SnapshotReducer.apply(current, event)) {
            is SnapshotReducer.Outcome.Applied -> {
                state.update(generation) {
                    it.copy(
                        snapshot = outcome.snapshot,
                        stale = false,
                        lastSyncAt = clock.now(),
                    )
                }
                // Consistency tick: the reducer already advanced the cursor (ticks fold as
                // no-ops), so a hash mismatch means the fold diverged despite contiguous
                // seqs — refetch authoritatively. Match = proof, zero bytes beyond the tick.
                if (event.type == SemanticEvent.TYPE_SNAPSHOT_TICK) {
                    state.markTick(generation)
                    val want = (event.detail?.get("hash") as? JsonPrimitive)?.content
                    val have = outcome.snapshot.statusHash()
                    if (want != null && want != have) {
                        refetch("tick hash drift (want $want, have $have)", generation)
                    }
                }
            }

            is SnapshotReducer.Outcome.Gap -> {
                state.update(generation) {
                    it.copy(connection = ConnectionState.STALE, stale = true)
                }
                refetch("seq gap: expected ${outcome.expectedSeq}, got ${outcome.receivedSeq}", generation)
            }

            SnapshotReducer.Outcome.Refetch -> {
                refetch("daemon requested refetch (${event.type})", generation)
            }
        }
    }

    /** Force an authoritative snapshot (presence check, cursor drift, gap, manual refresh). */
    suspend fun refetch(reason: String, generation: Long) {
        if (!state.isCurrent(generation)) return
        try {
            val snapshot = fetchSnapshot()
            state.update(generation) {
                it.copy(
                    connection = ConnectionState.CONNECTED,
                    snapshot = snapshot,
                    stale = false,
                    error = null,
                    lastSyncAt = clock.now(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update(generation) {
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

    /**
     * Emit the event and record stream activity, atomically with the generation check.
     * Returns false when the event belongs to a link that has already been replaced.
     */
    private fun accept(event: SemanticEvent, generation: Long): Boolean =
        state.acceptActivity(generation) { _events.tryEmit(event) }

    private suspend fun fetchSnapshot() =
        transport.snapshot(
            state.endpoint ?: throw DaemonException("not_connected", "No endpoint is open"),
        )
}
