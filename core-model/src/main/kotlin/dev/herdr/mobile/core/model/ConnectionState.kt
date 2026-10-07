package dev.herdr.mobile.core.model

/** Lifecycle of the client's link to the daemon, surfaced verbatim in the UI. */
enum class ConnectionState {
    /** No host profile configured yet. */
    IDLE,

    /** First connection attempt in flight. */
    CONNECTING,

    /** Snapshot loaded and the semantic event stream is live. */
    CONNECTED,

    /** Lost the stream; retrying with backoff. Cached state is still shown but not asserted. */
    RECONNECTING,

    /**
     * A sequence gap or an explicit `snapshot.required` invalidated the cache and a refetch
     * is in flight. Cached state may be stale.
     */
    STALE,

    /** Retries exhausted or the daemon rejected us. Needs user action. */
    FAILED;

    val isUsable: Boolean get() = this == CONNECTED
}

/** Result of a user-triggered connection test from Settings. */
sealed interface ConnectionTestResult {
    data class Success(
        val latencyMs: Long,
        val herdr: HerdrInfo,
        val revision: Long,
        val workspaceCount: Int,
    ) : ConnectionTestResult

    data class Failure(val kind: FailureKind, val detail: String) : ConnectionTestResult

    /**
     * The server presented a host key with no stored entry. The detail is human text;
     * [pending] carries the TOFU approval payload (fingerprint + known_hosts line).
     */
    data class UnknownHostKey(
        val detail: String,
        val profileId: String,
        val host: String,
        val fingerprint: String,
        val knownHostsLine: String,
    ) : ConnectionTestResult
}

/**
 * The server presented a host key with no stored entry to check against. Carries what
 * the user needs for TOFU approval: a fingerprint to verify out of band and the full
 * known_hosts line to persist on approval. Lives in core-model so both the SSH provider
 * (thrower) and the client classifier (catcher) share it without a module cycle.
 */
class UnknownHostKeyException(
    val profileId: String,
    val host: String,
    val fingerprint: String,
    val knownHostsLine: String,
) : Exception("Unknown host key for $host ($fingerprint)")

enum class FailureKind {
    /** The host name did not resolve. */
    DNS,

    /** Socket connected but nothing answered, or the tunnel never came up. */
    UNREACHABLE,

    /** SSH key was rejected, or the daemon bearer token was rejected. */
    AUTH,

    /** The stored host key does not match the one presented by the server. */
    HOST_KEY_MISMATCH,

    /**
     * The server presented a host key we have never seen. Unlike [HOST_KEY_MISMATCH]
     * (stored key disagrees — possible attack, never auto-accept), this asks the user
     * to verify the fingerprint out of band and approve it (TOFU).
     */
    HOST_KEY_UNKNOWN,

    /** The daemon answered but the Herdr socket behind it is not usable. */
    DAEMON_UNAVAILABLE,

    /** The daemon speaks a protocol generation this client does not implement. */
    PROTOCOL_MISMATCH,

    /** Anything else. */
    UNKNOWN,
}