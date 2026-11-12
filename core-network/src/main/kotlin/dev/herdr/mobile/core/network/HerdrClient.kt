package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.AgentStatus
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.ConnectionTestResult
import dev.herdr.mobile.core.model.FailureKind
import dev.herdr.mobile.core.model.HealthReport
import dev.herdr.mobile.core.model.SemanticEvent
import dev.herdr.mobile.core.model.SessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

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
 * This class is only the composition root plus the command surface. The moving parts live
 * behind narrow types so each can be reasoned about (and tested) on its own:
 * - [DaemonTransport] — provider dialling, REST, event stream and terminal sockets;
 * - [LinkState] — published state, both generation counters, installed endpoint, activity;
 * - [SnapshotSync] — event folding, gap/`snapshot.required` handling, authoritative refetch;
 * - [LinkLifecycle] — bootstrap, redial with exponential backoff, watchdog, kick/start/stop.
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
    externalScope: CoroutineScope,
    http: OkHttpClient? = null,
) : HerdrSession {

    private val scope = CoroutineScope(externalScope.coroutineContext)
    private val transport: DaemonTransport = HttpDaemonTransport.create(endpointProvider, http)
    private val clock: LinkClock = SystemLinkClock
    private val linkState = LinkState(clock)
    private val sync = SnapshotSync(linkState, transport, clock)
    private val lifecycle = LinkLifecycle(linkState, transport, sync, scope, clock)
    private val commands = SessionCommands(linkState, transport)

    override val state: StateFlow<HerdrClientState> = linkState.state

    override val linkGeneration: Long get() = linkState.linkGeneration
    override val targetGeneration: Long get() = linkState.targetGeneration

    /**
     * Every semantic event as it arrives for NotificationRelay. Notifications need exact
     * transitions; the snapshot remains the UI source.
     */
    val events: SharedFlow<SemanticEvent> = sync.events

    /** Start (or restart) the link. Safe to call repeatedly; replaces any running loop. */
    fun start() = lifecycle.start()

    /** Stop retrying and close the transport. State keeps the last snapshot for offline reading. */
    fun stop() = lifecycle.stop()

    /**
     * Drop data and event subscriptions for a changed profile/transport before provider
     * construction or teardown can suspend. The caller starts the new link with
     * [restartLink] after installing its provider.
     */
    fun invalidateTarget() = lifecycle.invalidateTarget()

    /** Forced full redial, even when connected (transport or backend settings changed). */
    fun restartLink(clearCachedSnapshot: Boolean = false) =
        lifecycle.restart(clearCachedSnapshot)

    override fun kick() = lifecycle.kick()

    override fun refresh() = lifecycle.refresh()

    /** Open a terminal stream. Caller owns it and must [TerminalConnection.release] it. */
    override fun openTerminal(
        paneId: String,
        cols: Int,
        rows: Int,
        takeover: Boolean,
    ): TerminalConnection {
        val endpoint = linkState.endpoint
            ?: throw DaemonException("not_connected", "No endpoint is open")
        return transport.terminal(endpoint, paneId, cols, rows, takeover, scope)
    }

    // -- Actions ---------------------------------------------------------------------------

    suspend fun sendInput(paneId: String, text: String) = commands.sendInput(paneId, text)

    override suspend fun interrupt(paneId: String) = commands.interrupt(paneId)

    suspend fun reportAgent(
        paneId: String,
        status: AgentStatus,
        message: String?,
        agent: String? = null,
    ) = commands.reportAgent(paneId, status, message, agent)

    override suspend fun createTab(workspaceId: String, label: String?, focus: Boolean): String =
        commands.createTab(workspaceId, label, focus)

    override suspend fun closeTab(tabId: String) = commands.closeTab(tabId)

    override suspend fun focusTab(tabId: String) = commands.focusTab(tabId)

    override suspend fun renameTab(tabId: String, label: String) = commands.renameTab(tabId, label)

    /** [focus] = false creates a workspace without moving the desktop's focus. */
    override suspend fun createWorkspace(label: String?, cwd: String?, focus: Boolean): String =
        commands.createWorkspace(label, cwd, focus)

    override suspend fun renameWorkspace(workspaceId: String, label: String) =
        commands.renameWorkspace(workspaceId, label)

    override suspend fun closeWorkspace(workspaceId: String) =
        commands.closeWorkspace(workspaceId)

    override suspend fun moveTab(tabId: String, insertIndex: Long) =
        commands.moveTab(tabId, insertIndex)

    override suspend fun moveWorkspace(workspaceId: String, insertIndex: Long) =
        commands.moveWorkspace(workspaceId, insertIndex)

    suspend fun resizePane(paneId: String, cols: Int, rows: Int) =
        commands.resizePane(paneId, cols, rows)

    /** Fresh update check (network hit). Snapshot block stays the source of truth. */
    override suspend fun herdrUpdateStatus() = commands.herdrUpdateStatus()

    /** Run the install; returns the updater's own log on success. */
    override suspend fun runHerdrUpdate(): String = commands.runHerdrUpdate()

    // -- One-shot connection test for Settings ---------------------------------------------

    suspend fun testConnection(provider: DaemonEndpointProvider): ConnectionTestResult =
        withContext(Dispatchers.IO) { testConnectionBlocking(provider) }

    private suspend fun testConnectionBlocking(provider: DaemonEndpointProvider): ConnectionTestResult {
        val started = System.currentTimeMillis()
        suspend fun closeProbe() {
            if (provider !== endpointProvider) runCatching { provider.close() }
        }
        val ep: DaemonEndpoint
        try {
            ep = provider.open()
        } catch (e: Exception) {
            // TOFU payload must survive as typed data, not a classified string:
            // walk the cause chain for UnknownHostKeyException specifically.
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is dev.herdr.mobile.core.model.UnknownHostKeyException) {
                    closeProbe()
                    return ConnectionTestResult.UnknownHostKey(
                        detail = "${cause.host} presented an unknown key (${cause.fingerprint}). " +
                            "Verify it out of band, then approve below.",
                        profileId = cause.profileId,
                        host = cause.host,
                        fingerprint = cause.fingerprint,
                        knownHostsLine = cause.knownHostsLine,
                    )
                }
                cause = cause.cause
            }
            closeProbe()
            return ConnectionTestResult.Failure(classifyFailure(e), userMessage(e))
        }
        return try {
            val health = transport.health(ep)
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
            val snapshot = transport.snapshot(ep)
            ConnectionTestResult.Success(
                latencyMs = System.currentTimeMillis() - started,
                herdr = snapshot.herdr,
                revision = snapshot.revision,
                workspaceCount = snapshot.workspaces.size,
            )
        } catch (e: Exception) {
            ConnectionTestResult.Failure(classifyFailure(e), userMessage(e))
        } finally {
            if (provider !== endpointProvider) {
                try {
                    provider.close()
                } catch (_: Exception) {
                }
            }
        }
    }
}
