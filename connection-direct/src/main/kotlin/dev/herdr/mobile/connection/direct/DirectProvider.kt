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
        val token = bearerToken()
        // Never send a bearer over cleartext off-device: without TLS anyone on
        // the path reads it. Emulator loopback and https are fine. Tailnet
        // 100.x addresses are NOT exempt: the daemon has no TLS yet, so Direct
        // over tailnet must go through the SSH tunnel instead.
        if (token != null && url.scheme == "http" && !isLoopback(url.host)) {
            throw IllegalArgumentException(
                "Refusing cleartext bearer to ${url.host}: use the SSH tunnel " +
                    "or an https daemon URL",
            )
        }
        return DaemonEndpoint(httpBase = url, token = token, description = "direct $label")
    }

    private fun isLoopback(host: String): Boolean {
        return host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host == "10.0.2.2"
    }

    override suspend fun close() {
        // Nothing to tear down.
    }
}
