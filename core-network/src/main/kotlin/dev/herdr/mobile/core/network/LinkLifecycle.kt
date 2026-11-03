package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.HealthReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * The reconnect policy: one bootstrap + event stream per generation, redial with exponential
 * backoff when it dies, and act on external start/stop/restart/kick/refresh requests.
 *
 * This type owns no sockets and no clocks: [DaemonTransport] is the network, [LinkClock] the
 * time source, [LinkState] the guarded state. That split is what lets the whole retry policy
 * run in a unit test with a scripted transport and virtual time.
 */
internal class LinkLifecycle(
    private val state: LinkState,
    private val transport: DaemonTransport,
    private val sync: SnapshotSync,
    private val scope: CoroutineScope,
    private val clock: LinkClock,
    private val policy: LinkPolicy = LinkPolicy(),
    private val jitter: (Long) -> Long = { (0..it).random() },
) {
    /**
     * Single kick actor: start/stop/kick funnel through here so overlapping calls serialize
     * instead of spawning duplicate reconnect loops.
     */
    private sealed interface LoopCommand {
        data object Restart : LoopCommand
        data object Stop : LoopCommand
    }

    private val commands = Channel<LoopCommand>(capacity = Channel.CONFLATED)

    private var loop: Job? = null

    init {
        // Stream watchdog: ticks arrive every 60s on a healthy link, so silence past the
        // policy threshold means a wedged stream (half-open TCP that neither side detects —
        // infinite readTimeout + NAT-only pings never fire) and the ONLY cure is a full
        // redial: a REST refetch would heal the data but leave the dead SSE in place.
        // Against tickless old daemons a quiet link is normal — refetch data only.
        scope.launch {
            while (true) {
                clock.sleep(policy.watchdogTickMs)
                if (state.state.value.connection == ConnectionState.CONNECTED &&
                    clock.now() - state.lastActivityAt > policy.watchdogSilenceMs
                ) {
                    if (state.tickSeen) {
                        restart()
                    } else {
                        runCatching { refresh() }
                    }
                }
            }
        }
        scope.launch {
            for (command in commands) {
                when (command) {
                    LoopCommand.Restart -> {
                        loop?.cancelAndJoin()
                        loop = scope.launch { run(state.linkGeneration) }
                    }

                    LoopCommand.Stop -> {
                        loop?.cancelAndJoin()
                        loop = null
                        try {
                            transport.close()
                        } catch (_: Exception) {
                        }
                        state.publishStopped()
                    }
                }
            }
        }
    }

    /** Start (or restart) the link. Safe to call repeatedly; replaces any running loop. */
    fun start() = restart()

    /** Stop retrying and close the transport. State keeps the last snapshot for offline reading. */
    fun stop() {
        state.beginStop()
        commands.trySend(LoopCommand.Stop)
    }

    /**
     * Retry now instead of waiting out the backoff (network regained, sticky restart).
     * No-op when already CONNECTED: every foregrounding used to tear down a healthy SSE
     * (transient STALE, resubscribe gap, extra SSH dials).
     */
    fun kick() {
        if (state.targetInvalidated || state.state.value.connection == ConnectionState.CONNECTED) return
        restart()
    }

    /** Drop data and event subscriptions for a changed profile/transport. */
    fun invalidateTarget() = state.invalidateTarget()

    /** Forced full redial, even when connected (transport or backend settings changed). */
    fun restart(clearCachedSnapshot: Boolean = false) {
        state.beginRedial(clearCachedSnapshot)
        commands.trySend(LoopCommand.Restart)
    }

    /**
     * Force an authoritative refetch, folding nothing. Redials only when the link is down;
     * on a healthy link it re-GETs the snapshot without tearing down the SSE (deep-link
     * freshness, post-createTab waits).
     */
    fun refresh() {
        val generation = state.linkGeneration
        if (state.state.value.connection != ConnectionState.CONNECTED) {
            kick()
            return
        }
        scope.launch {
            runCatching { sync.refetch("manual", generation) }
        }
    }

    private suspend fun run(generation: Long) {
        val backoff = BackoffSequence(policy, jitter)
        while (coroutineContext.isActive && state.isCurrent(generation) && !state.targetInvalidated) {
            try {
                state.update(generation) {
                    it.copy(
                        connection = if (it.snapshot == null) {
                            ConnectionState.CONNECTING
                        } else {
                            ConnectionState.RECONNECTING
                        },
                        error = null,
                    )
                }
                val endpoint = transport.open()
                if (!state.installEndpoint(generation, endpoint)) return
                state.update(generation) { it.copy(endpointDescription = endpoint.description) }

                val health = transport.health(endpoint)
                if (!state.isCurrent(generation)) return
                checkProtocol(health)
                if (!health.ok || !health.herdrConnected) {
                    throw DaemonException(
                        "daemon_unavailable",
                        health.error ?: "Daemon is up but Herdr is not reachable",
                    )
                }

                val snapshot = transport.snapshot(endpoint)
                if (!state.isCurrent(generation)) return
                state.update(generation) {
                    it.copy(
                        connection = ConnectionState.CONNECTED,
                        snapshot = snapshot,
                        stale = false,
                        error = null,
                        lastSyncAt = clock.now(),
                    )
                }
                backoff.reset()

                sync.consume(generation)

                // Stream ended without an exception: reconnect, the daemon went quiet.
                throw DaemonException("stream_closed", "Event stream closed")
            } catch (e: DaemonException) {
                if (coroutineContext.isActive && state.isCurrent(generation)) {
                    // Transport failure (not auth/protocol): invalidate the tunnel so the
                    // next retry dials fresh. JSch isConnected stays true on half-open TCP
                    // (keepalive needs up to 45s), and reusing the dead session would loop
                    // forever without this close.
                    if (e.code != "unauthorized" && e.code != "protocol_mismatch") {
                        runCatching { transport.close() }
                        state.clearEndpoint(generation)
                    }
                    state.update(generation) {
                        it.copy(
                            connection = if (e.code == "unauthorized" || e.code == "protocol_mismatch") {
                                ConnectionState.FAILED
                            } else if (it.snapshot != null) {
                                ConnectionState.RECONNECTING
                            } else {
                                ConnectionState.CONNECTING
                            },
                            stale = it.snapshot != null,
                            error = userMessage(e),
                        )
                    }
                    if (e.code == "unauthorized" || e.code == "protocol_mismatch") {
                        // One cheap dial per policy window; any settings edit/provider
                        // rebuild kicks immediately.
                        clock.sleep(policy.authRetryMs)
                        continue
                    }
                    clock.sleep(backoff.nextDelayMs())
                }
            } catch (e: Exception) {
                if (coroutineContext.isActive && state.isCurrent(generation)) {
                    // Same invalidation for non-daemon transport errors.
                    runCatching { transport.close() }
                    state.clearEndpoint(generation)
                    state.update(generation) {
                        it.copy(
                            connection = if (it.snapshot != null) {
                                ConnectionState.RECONNECTING
                            } else {
                                ConnectionState.CONNECTING
                            },
                            stale = it.snapshot != null,
                            error = userMessage(e),
                        )
                    }
                    clock.sleep(backoff.nextDelayMs())
                }
            }
        }
    }

    private fun checkProtocol(health: HealthReport) {
        if (health.protocol != HealthReport.PROTOCOL) {
            throw DaemonException(
                "protocol_mismatch",
                "Daemon speaks protocol ${health.protocol}, this client implements ${HealthReport.PROTOCOL}",
            )
        }
    }
}
