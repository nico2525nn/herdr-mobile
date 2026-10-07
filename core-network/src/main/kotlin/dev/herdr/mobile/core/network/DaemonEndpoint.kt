package dev.herdr.mobile.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * A reachable daemon, in client terms.
 *
 * [httpBase] is the origin the REST API lives under, for example
 * `http://127.0.0.1:43121` when an SSH tunnel forwards a local port. WebSocket URLs
 * are derived from it by swapping the scheme to `ws`/`wss`.
 */
data class DaemonEndpoint(
    val httpBase: HttpUrl,
    val token: String?,
    val description: String,
) {
    val wsBase: String
        get() = httpBase.toString().let {
            when {
                it.startsWith("https://") -> "wss://" + it.removePrefix("https://")
                it.startsWith("http://") -> "ws://" + it.removePrefix("http://")
                else -> it
            }
        }.trimEnd('/')

    fun wsUrl(path: String, query: Map<String, String> = emptyMap()): String {
        val builder = StringBuilder(wsBase)
        if (!path.startsWith("/")) builder.append('/')
        builder.append(path)
        if (query.isNotEmpty()) {
            builder.append('?')
            // URL-encoded: tokens carry +/= which raw interpolation corrupts.
            builder.append(query.entries.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, "UTF-8") + "=" +
                    java.net.URLEncoder.encode(v, "UTF-8")
            })
        }
        return builder.toString()
    }

    companion object {
        fun direct(url: String, token: String?, description: String): DaemonEndpoint =
            DaemonEndpoint(url.toHttpUrl(), token, description)
    }
}

/**
 * Opens the transport that makes the daemon reachable, then hands back an endpoint.
 *
 * Implementations: SSH local-forward tunnel ([connection-ssh]) and direct tailnet/LAN
 * ([connection-direct]). The client treats both identically once [open] returns.
 */
interface DaemonEndpointProvider {
    /** Stable id of the profile this provider serves, or `"direct"`. */
    val id: String

    /** Human line for Settings and diagnostics, with no secrets in it. */
    val description: String

    /**
     * Establish the transport (tunnel, key exchange) and return the endpoint. Reuses an
     * already-open transport when one is healthy. Throws on failure.
     */
    suspend fun open(): DaemonEndpoint

    /** Tear the transport down. Safe to call when nothing is open. */
    suspend fun close()
}