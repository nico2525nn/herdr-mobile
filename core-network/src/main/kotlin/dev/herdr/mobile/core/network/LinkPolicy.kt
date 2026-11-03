package dev.herdr.mobile.core.network

import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Time source for the link, injectable so retry backoff and the stream watchdog can be
 * exercised on virtual time instead of real sleeps.
 */
internal interface LinkClock {
    fun now(): Long

    suspend fun sleep(ms: Long)
}

/** Wall clock for production; identical to calling `delay`/`System.currentTimeMillis`. */
internal object SystemLinkClock : LinkClock {
    override fun now(): Long = System.currentTimeMillis()

    override suspend fun sleep(ms: Long) = delay(ms)
}

/**
 * Every timing constant the link policy uses, in one inspectable place.
 *
 * Keeping them out of the loop is what makes "how long before the next retry" a testable
 * question instead of a comment.
 */
internal data class LinkPolicy(
    /** First redial wait; doubles per consecutive failure. */
    val initialBackoffMs: Long = 500,
    /** Ceiling for the exponential redial wait. */
    val maxBackoffMs: Long = 15_000,
    /** Uniform spread added to each redial wait so clients never retry in lockstep. */
    val jitterMs: Long = 250,
    /**
     * Auth/protocol failures need a human (token rotation, daemon upgrade), but parking
     * forever leaves a live process silently dead: retry slowly so monitoring can revive.
     */
    val authRetryMs: Long = 300_000,
    /** How often the stream watchdog looks for a wedged stream. */
    val watchdogTickMs: Long = 60_000,
    /** Silence after which a CONNECTED link is considered wedged. */
    val watchdogSilenceMs: Long = 90_000,
) {
    fun grow(backoffMs: Long): Long = min(backoffMs * 2, maxBackoffMs)
}

/**
 * Delay sequence for consecutive failed redials: exponential from
 * [LinkPolicy.initialBackoffMs] to [LinkPolicy.maxBackoffMs], each draw spread by up to
 * [LinkPolicy.jitterMs]. [reset] is called after a successful bootstrap so the next outage
 * starts fast again.
 */
internal class BackoffSequence(
    private val policy: LinkPolicy,
    private val jitter: (Long) -> Long = { (0..it).random() },
) {
    var currentMs: Long = policy.initialBackoffMs
        private set

    fun reset() {
        currentMs = policy.initialBackoffMs
    }

    /** Next wait in ms; always grows the following draw, even for the capped value. */
    fun nextDelayMs(): Long {
        val spread = jitter(policy.jitterMs).coerceIn(0, policy.jitterMs)
        val delayMs = currentMs + spread
        currentMs = policy.grow(currentMs)
        return delayMs
    }
}
