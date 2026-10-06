package dev.herdr.mobile.connection.direct

import dev.herdr.mobile.core.network.DaemonEndpoint
import dev.herdr.mobile.core.network.DaemonEndpointProvider
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Direct tailnet/LAN transport: no tunnel, just an origin plus a bearer token.
 *
 * The security properties live outside this class and must hold before it is used: the
 * daemon binds a protected address, the URL is https (or plain http strictly inside a
 * trusted tailnet), and the token comes from secure storage — never from Settings text.
 */
class DirectProvider(
    private val baseUrl: String,
    private val bearerToken: () -> String?,
    private val label: String = baseUrl,
) : DaemonEndpointProvider {

    override val id: String get() = "direct"

    override val description: String get() = "direct $label"

    override suspend fun open(): DaemonEndpoint {
        val url = try {
            baseUrl.toHttpUrl()
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid daemon URL: $baseUrl", e)
        }
        return DaemonEndpoint(httpBase = url, token = bearerToken(), description = "direct $label")
    }

    override suspend fun close() {
        // Nothing to tear down.
    }
}
