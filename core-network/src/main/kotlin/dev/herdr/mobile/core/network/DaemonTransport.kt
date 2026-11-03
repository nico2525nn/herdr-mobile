package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.HealthReport
import dev.herdr.mobile.core.model.SessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Everything the link touches on the network: the provider transport, the REST calls the
 * bootstrap/refetch/commands need, the event stream, and terminal sockets.
 *
 * [LinkLifecycle] and [SnapshotSync] only talk to this interface, so reconnect, backoff and
 * generation rules can be exercised against a scripted transport — no sockets, no daemon.
 */
internal interface DaemonTransport {
    /** Establish the transport (tunnel, key exchange) and return the endpoint. */
    suspend fun open(): DaemonEndpoint

    /** Tear the transport down. Safe to call when nothing is open. */
    suspend fun close()

    suspend fun health(endpoint: DaemonEndpoint): HealthReport

    suspend fun snapshot(endpoint: DaemonEndpoint): SessionSnapshot

    /** REST surface for the session commands. */
    fun api(endpoint: DaemonEndpoint): DaemonApi

    /** Cold `/v1/events` stream for [endpoint]; [stillWanted] is checked when it opens. */
    fun events(endpoint: DaemonEndpoint, stillWanted: () -> Boolean): Flow<EventSocket.Signal>

    fun terminal(
        endpoint: DaemonEndpoint,
        paneId: String,
        cols: Int,
        rows: Int,
        takeover: Boolean,
        scope: CoroutineScope,
    ): TerminalConnection
}

/** Production transport: one socket client for streams, one bounded client for REST. */
internal class HttpDaemonTransport(
    private val provider: DaemonEndpointProvider,
    private val http: OkHttpClient,
    private val restHttp: OkHttpClient,
) : DaemonTransport {

    override suspend fun open(): DaemonEndpoint = provider.open()

    override suspend fun close() = provider.close()

    override suspend fun health(endpoint: DaemonEndpoint): HealthReport = api(endpoint).health()

    override suspend fun snapshot(endpoint: DaemonEndpoint): SessionSnapshot = api(endpoint).snapshot()

    override fun api(endpoint: DaemonEndpoint): DaemonApi = DaemonApi(restHttp, endpoint)

    override fun events(
        endpoint: DaemonEndpoint,
        stillWanted: () -> Boolean,
    ): Flow<EventSocket.Signal> =
        EventSocket(http) {
            if (!stillWanted()) throw DaemonException("not_connected", "Connection was replaced")
            endpoint
        }.stream()

    override fun terminal(
        endpoint: DaemonEndpoint,
        paneId: String,
        cols: Int,
        rows: Int,
        takeover: Boolean,
        scope: CoroutineScope,
    ): TerminalConnection =
        TerminalSocket(http, endpoint, paneId, cols, rows, takeover, scope).also { it.connect() }

    companion object {
        /**
         * Build the two OkHttp clients around an optional caller-supplied one.
         *
         * REST calls (health/snapshot/refetch) must never share the WebSocket client's
         * infinite readTimeout: a stalled daemon would hang them forever with no recovery.
         */
        fun create(provider: DaemonEndpointProvider, http: OkHttpClient? = null): HttpDaemonTransport {
            val socketHttp = http ?: OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            val restHttp = http?.newBuilder()
                ?.readTimeout(15, TimeUnit.SECONDS)
                ?.build()
                ?: OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()
            return HttpDaemonTransport(provider, socketHttp, restHttp)
        }
    }
}
