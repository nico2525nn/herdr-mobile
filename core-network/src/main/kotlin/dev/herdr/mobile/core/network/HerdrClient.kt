package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.AgentReport
import dev.herdr.mobile.core.model.AgentStatus
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.ConnectionTestResult
import dev.herdr.mobile.core.model.FailureKind
import dev.herdr.mobile.core.model.HealthReport
import dev.herdr.mobile.core.model.SemanticEvent
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.model.SnapshotReducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/** Everything Home and Terminal observe about the daemon link. */
data class HerdrClientState(
    val connection: ConnectionState = ConnectionState.IDLE,
    val snapshot: SessionSnapshot? = null,
    /** True when the cache is shown but known stale, or when events are being reconciled. */
    val stale: Boolean = false,
    val error: String? = null,
    val endpointDescription: String? = null,
    /** Last successful snapshot fetch, epoch millis. 0 when never. */
    val lastSyncAt: Long = 0,
)

/**
 * The single owner of the daemon link for the whole app.
 *
 * Policy, in one place:
 * - one REST bootstrap (`/v1/health` + `/v1/snapshot`), then one `/v1/events` stream;
 * - events fold into the cached snapshot only while [SemanticEvent.seq] advances by exactly
 *   one; any gap, any `snapshot.required`, or any structural event the reducer cannot fold
 *   triggers an authoritative refetch;
 * - the stream dying for any reason moves to [ConnectionState.RECONNECTING] with exponential
 *   backoff (0.5s → 15s cap), jittered; connectivity regain is signalled with [kick] so the
 *   loop retries immediately instead of sleeping through the outage;
 * - nothing here opens a terminal stream; Terminal owns those sockets and closes them when
 *   it leaves.
 */
class HerdrClient(
    private val endpointProvider: DaemonEndpointProvider,
    private val externalScope: CoroutineScope,
    http: OkHttpClient? = null,
) {
    private val scope = CoroutineScope(externalScope.coroutineContext + SupervisorJob())

    private val http: OkHttpClient = http ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _state = MutableStateFlow(HerdrClientState())
    val state: StateFlow<HerdrClientState> = _state.asStateFlow()

    private var loop: Job? = null
    private var kickSignal: Job? = null

    @Volatile
    private var endpoint: DaemonEndpoint? = null

    private fun api(): DaemonApi {
        val ep = endpoint ?: throw DaemonException("not_connected", "No endpoint is open")
        return DaemonApi(http, ep)
    }

    /** Start (or restart) the link. Safe to call repeatedly; replaces any running loop. */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    /** Stop retrying and close the transport. State keeps the last snapshot for offline reading. */
    fun stop() {
        loop?.cancel()
        loop = null
        scope.launch {
            try {
                endpointProvider.close()
            } catch (_: Exception) {
            }
        }
        endpoint = null
        _state.update {
            if (it.connection == ConnectionState.CONNECTED ||
                it.connection == ConnectionState.RECONNECTING ||
                it.connection == ConnectionState.STALE
            ) {
                it.copy(connection = ConnectionState.IDLE, stale = it.snapshot != null)
            } else {
                it
            }
        }
    }

    /** Retry now instead of waiting out the backoff (network regained, user pulled to refresh). */
    fun kick() {
        kickSignal?.cancel()
        kickSignal = scope.launch {
            loop?.cancelAndJoin()
            loop = scope.launch { run() }
        }
    }

    /** Force an authoritative refetch, folding nothing. */
    fun refresh() {
        kick()
    }

    private suspend fun run() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (coroutineContext.isActive) {
            try {
                _state.update {
                    it.copy(
                        connection = if (it.snapshot == null) {
                            ConnectionState.CONNECTING
                        } else {
                            ConnectionState.RECONNECTING
                        },
                        error = null,
                    )
                }
                val ep = endpointProvider.open()
                endpoint = ep
                _state.update { it.copy(endpointDescription = ep.description) }

                val health = api().health()
                checkProtocol(health)
                if (!health.ok || !health.herdrConnected) {
                    throw DaemonException(
                        "daemon_unavailable",
                        health.error ?: "Daemon is up but Herdr is not reachable",
                    )
                }

                val snapshot = api().snapshot()
                _state.update {
                    it.copy(
                        connection = ConnectionState.CONNECTED,
                        snapshot = snapshot,
                        stale = false,
                        error = null,
                        lastSyncAt = System.currentTimeMillis(),
                    )
                }
                backoffMs = INITIAL_BACKOFF_MS

                consumeEvents()

                // Stream ended without an exception: reconnect, the daemon went quiet.
                throw DaemonException("stream_closed", "Event stream closed")
            } catch (e: DaemonException) {
                if (coroutineContext.isActive) {
                    _state.update {
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
                    if (e.code == "unauthorized" || e.code == "protocol_mismatch") return
                    delay(backoffMs + (0..250).random())
                    backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
                }
            } catch (e: Exception) {
                if (coroutineContext.isActive) {
                    _state.update {
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
                    delay(backoffMs + (0..250).random())
                    backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
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

    private suspend fun consumeEvents() {
        val socket = EventSocket(http) {
            endpoint ?: throw DaemonException("not_connected", "No endpoint is open")
        }
        socket.stream().collect { signal ->
            when (signal) {
                is EventSocket.Signal.Ready -> {
                    // Cursor noted; the snapshot we hold was taken after it, so any event with
                    // seq <= snapshot.seq is history we already have.
                }

                is EventSocket.Signal.Event -> applyEvent(signal.event)

                is EventSocket.Signal.Closed ->
                    throw DaemonException("stream_closed", signal.reason.ifBlank { "Event stream closed" })
            }
        }
    }

    private suspend fun applyEvent(event: SemanticEvent) {
        val current = _state.value.snapshot ?: run {
            refetch("event without snapshot")
            return
        }
        when (val outcome = SnapshotReducer.apply(current, event)) {
            is SnapshotReducer.Outcome.Applied -> {
                _state.update {
                    it.copy(
                        snapshot = outcome.snapshot,
                        stale = false,
                        lastSyncAt = System.currentTimeMillis(),
                    )
                }
            }

            is SnapshotReducer.Outcome.Gap -> {
                _state.update { it.copy(connection = ConnectionState.STALE, stale = true) }
                refetch("seq gap: expected ${outcome.expectedSeq}, got ${outcome.receivedSeq}")
            }

            SnapshotReducer.Outcome.Refetch -> refetch("daemon requested refetch (${event.type})")
        }
    }

    private suspend fun refetch(reason: String) {
        try {
            val snapshot = api().snapshot()
            _state.update {
                it.copy(
                    connection = ConnectionState.CONNECTED,
                    snapshot = snapshot,
                    stale = false,
                    error = null,
                    lastSyncAt = System.currentTimeMillis(),
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    connection = if (it.snapshot != null) ConnectionState.RECONNECTING else ConnectionState.CONNECTING,
                    stale = it.snapshot != null,
                    error = "Refetch failed ($reason): ${userMessage(e)}",
                )
            }
            throw e
        }
    }

    // -- Actions ---------------------------------------------------------------------------

    /** Open a terminal stream. Caller owns it and must [TerminalConnection.release] it. */
    fun openTerminal(
        paneId: String,
        cols: Int,
        rows: Int,
        takeover: Boolean = false,
    ): TerminalConnection {
        val ep = endpoint ?: throw DaemonException("not_connected", "No endpoint is open")
        return TerminalSocket(http, ep, paneId, cols, rows, takeover, scope).also { it.connect() }
    }

    suspend fun sendInput(paneId: String, text: String) = api().sendInput(paneId, text)

    suspend fun interrupt(paneId: String) = api().interrupt(paneId)

    suspend fun reportAgent(paneId: String, status: AgentStatus, message: String?, agent: String? = null) {
        api().reportAgent(AgentReport(paneId = paneId, status = status, message = message, agent = agent))
    }

    suspend fun createTab(workspaceId: String, label: String?): String =
        api().createTab(workspaceId, label)

    suspend fun resizePane(paneId: String, cols: Int, rows: Int) =
        api().resizePane(paneId, cols, rows)

    // -- One-shot connection test for Settings ---------------------------------------------

    suspend fun testConnection(provider: DaemonEndpointProvider): ConnectionTestResult {
        val started = System.currentTimeMillis()
        val ep: DaemonEndpoint
        try {
            ep = provider.open()
        } catch (e: Exception) {
            return ConnectionTestResult.Failure(classify(e), userMessage(e))
        }
        return try {
            val api = DaemonApi(http, ep)
            val health = api.health()
            if (health.protocol != HealthReport.PROTOCOL) {
                return ConnectionTestResult.Failure(
                    FailureKind.PROTOCOL_MISMATCH,
                    "Daemon speaks protocol ${health.protocol}, this client implements ${HealthReport.PROTOCOL}",
                )
            }
            if (!health.ok || !health.herdrConnected) {
                return ConnectionTestResult.Failure(
                    FailureKind.DAEMON_UNAVAILABLE,
                    health.error ?: "Daemon is up but Herdr is not reachable",
                )
            }
            val snapshot = api.snapshot()
            ConnectionTestResult.Success(
                latencyMs = System.currentTimeMillis() - started,
                herdr = snapshot.herdr,
                revision = snapshot.revision,
                workspaceCount = snapshot.workspaces.size,
            )
        } catch (e: Exception) {
            ConnectionTestResult.Failure(classify(e), userMessage(e))
        } finally {
            if (provider !== endpointProvider) {
                try {
                    provider.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun classify(e: Throwable): FailureKind {
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is UnknownHostException -> return FailureKind.DNS
                is SocketTimeoutException -> return FailureKind.UNREACHABLE
                is java.net.ConnectException ->
                    return if ((cause.message ?: "").contains("refused", ignoreCase = true)) {
                        FailureKind.UNREACHABLE
                    } else {
                        FailureKind.UNKNOWN
                    }
                is DaemonException -> return when (cause.code) {
                    "unauthorized" -> FailureKind.AUTH
                    "host_key_mismatch" -> FailureKind.HOST_KEY_MISMATCH
                    "daemon_unavailable" -> FailureKind.DAEMON_UNAVAILABLE
                    "protocol_mismatch" -> FailureKind.PROTOCOL_MISMATCH
                    else -> FailureKind.UNKNOWN
                }
            }
            if (cause.message?.contains("HostKey", ignoreCase = true) == true ||
                cause.message?.contains("host key", ignoreCase = true) == true
            ) {
                return FailureKind.HOST_KEY_MISMATCH
            }
            if (cause.message?.contains("Auth fail", ignoreCase = true) == true ||
                cause.message?.contains("authentication", ignoreCase = true) == true
            ) {
                return FailureKind.AUTH
            }
            cause = cause.cause
        }
        return FailureKind.UNKNOWN
    }

    private fun userMessage(e: Throwable): String {
        if (e is DaemonException) return e.message
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is UnknownHostException -> return "Cannot resolve ${cause.message}"
                is SocketTimeoutException -> return "Timed out reaching the daemon"
                is java.net.ConnectException -> return "Connection refused: ${cause.message}"
            }
            cause = cause.cause
        }
        return e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val INITIAL_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 15_000L
    }
}