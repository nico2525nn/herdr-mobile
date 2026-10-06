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
}

enum class FailureKind {
    /** The host name did not resolve. */
    DNS,

    /** Socket connected but nothing answered, or the tunnel never came up. */
    UNREACHABLE,

    /** SSH key was rejected, or the daemon bearer token was rejected. */
    AUTH,

    /** The stored host key does not match the one presented by the server. */
    HOST_KEY_MISMATCH,

    /** The daemon answered but the Herdr socket behind it is not usable. */
    DAEMON_UNAVAILABLE,

    /** The daemon speaks a protocol generation this client does not implement. */
    PROTOCOL_MISMATCH,

    /** Anything else. */
    UNKNOWN,
}