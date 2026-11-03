package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The link's published state plus the generation counters and endpoint that guard it.
 *
 * Single writer rule: every mutation that is tied to a redial carries its link generation
 * and is dropped once a newer redial started. That is what stops a late snapshot, a late
 * event or an endpoint installed by an old transport from republishing state for a target
 * the app has already left.
 */
internal class LinkState(private val clock: LinkClock) {

    private val lock = Any()

    private val _state = MutableStateFlow(HerdrClientState())
    val state: StateFlow<HerdrClientState> = _state.asStateFlow()

    /** Invalidates work from a previous redial, without discarding its cache. */
    @Volatile
    var linkGeneration: Long = 0
        private set

    /** Invalidates pane targets when the app switches to a different profile/transport. */
    @Volatile
    var targetGeneration: Long = 0
        private set

    /** Prevents an external kick from reconnecting through the outgoing provider during debounce. */
    @Volatile
    var targetInvalidated: Boolean = false
        private set

    /** Endpoint installed by the current generation; null between redials. */
    @Volatile
    var endpoint: DaemonEndpoint? = null
        private set

    /** Last tick or event observed: the stream watchdog keys off it. */
    @Volatile
    var lastActivityAt: Long = 0
        private set

    /** True once any tick arrived: distinguishes dead streams from tickless old daemons. */
    @Volatile
    var tickSeen: Boolean = false
        private set

    fun isCurrent(generation: Long): Boolean = linkGeneration == generation

    /** Publish a state transform only while [generation] is still the live link. */
    fun update(generation: Long, transform: (HerdrClientState) -> HerdrClientState): Boolean =
        synchronized(lock) {
            if (linkGeneration != generation) {
                false
            } else {
                _state.update(transform)
                true
            }
        }

    /**
     * Install the endpoint opened by [generation]. Fails when the link was replaced or the
     * target was invalidated while the provider was still dialling.
     */
    fun installEndpoint(generation: Long, value: DaemonEndpoint): Boolean =
        synchronized(lock) {
            if (linkGeneration != generation || targetInvalidated) {
                false
            } else {
                endpoint = value
                true
            }
        }

    /** Drop the endpoint when it belongs to [generation] (the transport it came from failed). */
    fun clearEndpoint(generation: Long) {
        synchronized(lock) {
            if (linkGeneration == generation) endpoint = null
        }
    }

    /**
     * Run [block] only while [generation] is live, and record that the stream just spoke.
     *
     * Atomic with generation bumps on purpose: an event from a replaced target must never
     * reach the event bus, or notifications fire for a profile the app already left.
     */
    fun acceptActivity(generation: Long, block: () -> Unit): Boolean =
        synchronized(lock) {
            if (linkGeneration != generation) {
                false
            } else {
                block()
                lastActivityAt = clock.now()
                true
            }
        }

    fun markTick(generation: Long) {
        synchronized(lock) {
            if (linkGeneration == generation) tickSeen = true
        }
    }

    /**
     * Drop data and event subscriptions for a changed profile/transport before provider
     * construction or teardown can suspend.
     */
    fun invalidateTarget() {
        synchronized(lock) {
            linkGeneration++
            invalidateTargetLocked()
        }
    }

    /**
     * Bump the generation for a redial. The cache survives unless [clearCachedSnapshot]
     * (a target change) forces the pristine reset.
     */
    fun beginRedial(clearCachedSnapshot: Boolean) {
        synchronized(lock) {
            linkGeneration++
            if (clearCachedSnapshot) {
                invalidateTargetLocked()
            } else {
                clearSecondaryState()
                _state.update {
                    it.copy(
                        connection = if (it.snapshot == null) {
                            ConnectionState.CONNECTING
                        } else {
                            ConnectionState.RECONNECTING
                        },
                        stale = it.snapshot != null,
                        error = null,
                    )
                }
            }
            targetInvalidated = false
        }
    }

    /** Bump the generation and drop the endpoint; [publishStopped] lands once the loop is gone. */
    fun beginStop() {
        synchronized(lock) {
            linkGeneration++
            endpoint = null
        }
    }

    /** Final state after [stop]: keep the last snapshot for offline reading. */
    fun publishStopped() {
        _state.update {
            if (it.connection == ConnectionState.CONNECTED ||
                it.connection == ConnectionState.RECONNECTING ||
                it.connection == ConnectionState.STALE ||
                it.connection == ConnectionState.FAILED
            ) {
                it.copy(connection = ConnectionState.IDLE, stale = it.snapshot != null)
            } else {
                it
            }
        }
    }

    private fun invalidateTargetLocked() {
        targetGeneration++
        targetInvalidated = true
        clearSecondaryState()
        _state.update {
            it.copy(
                connection = ConnectionState.CONNECTING,
                snapshot = null,
                stale = false,
                error = null,
                endpointDescription = null,
                lastSyncAt = 0,
            )
        }
    }

    private fun clearSecondaryState() {
        endpoint = null
        tickSeen = false
        lastActivityAt = 0
    }
}
