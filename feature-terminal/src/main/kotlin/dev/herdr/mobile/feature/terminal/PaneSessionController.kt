package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.designsystem.UiMessage
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.network.HerdrSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Owns "which pane is on screen and how traffic reaches it".
 *
 * This is the part of the terminal screen that used to be a dozen fields and
 * five coroutines inside the view-model: target resolution, attach/retry, the
 * input queue that survives an attach window, and the recovery rules
 * (daemon restart, dropped stream, closed workspace). It is separated so those
 * rules can be exercised against a scripted [HerdrSession] without a
 * ViewModel, and so the view-model is left with user intents and state mapping.
 *
 * Threading: every mutation happens on the scope's dispatcher (the view-model's
 * main scope in production). [send] may be called from any coroutine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaneSessionController(
    private val session: HerdrSession,
    private val scope: CoroutineScope,
    private val onMessage: (UiMessage?) -> Unit,
    private val initialWorkspaceId: String?,
    private val initialTabId: String?,
    private val initialPaneId: String?,
) {

    private val targetFlow = MutableStateFlow<TerminalTarget?>(null)
    val target: StateFlow<TerminalTarget?> = targetFlow.asStateFlow()

    private val backendFlow = MutableStateFlow<ConnectionTerminalBackend?>(null)
    val backend: StateFlow<ConnectionTerminalBackend?> = backendFlow.asStateFlow()

    private val gridFlow = MutableStateFlow(90 to 30)

    private val _inputTick = MutableStateFlow(0)

    /** Increments on every user input send; the surface snaps to live on it. */
    val inputTick: StateFlow<Int> = _inputTick.asStateFlow()

    /**
     * Input typed while no backend is attached (attach/reconnect window):
     * queued and flushed on the next backend instead of silently dropped.
     * Bounded at 64 entries.
     */
    private val pendingInput = PendingInput()
    private var pendingFlushJob: Job? = null

    private var attachJob: Job? = null

    /** Pane the live backend belongs to (release ordering needs it). */
    private var backendPaneId: String? = null
    private var observedTargetGeneration = session.targetGeneration
    private var targetOwnerGeneration: Long? = null

    /** Start the collectors: target resolution + recovery rules. */
    fun start() {
        scope.launch {
            // Deep links (notification taps) refresh first: the cached snapshot
            // may predate the pane (reconnect gap) or outlive it (closed).
            // Resolving on stale state silently opens the WRONG agent.
            if (initialPaneId != null) {
                session.refresh()
                runCatching {
                    withTimeout(10_000) {
                        session.state.first { st ->
                            st.snapshot?.pane(initialPaneId) != null &&
                                st.connection == ConnectionState.CONNECTED
                        }
                    }
                }
            }
            session.state.collect { state -> onSessionState(state) }
        }
        scope.launch { watchReconnect() }
    }

    // ---- target resolution -------------------------------------------

    private fun onSessionState(state: dev.herdr.mobile.core.network.HerdrClientState) {
        val generation = session.targetGeneration
        if (generation != observedTargetGeneration) {
            observedTargetGeneration = generation
            onTargetGenerationChanged(state.snapshot)
            return
        }
        val snapshot = state.snapshot ?: return
        val current = targetFlow.value
        if (current == null) {
            val resolved = resolveTarget(snapshot, initialWorkspaceId, initialTabId, initialPaneId)
            if (resolved != null) {
                onMessage(null)
                fallbackNotice(
                    initialWorkspaceId,
                    initialTabId,
                    initialPaneId,
                    snapshot,
                    resolved,
                )?.let(onMessage)
                openTarget(resolved, preserveMessage = true)
            }
        } else {
            reconcileVanishedTarget(snapshot, current)
        }
    }

    /**
     * The daemon restarted or the tunnel re-dialled. Pane ids are stable, so
     * the same pane is re-opened instead of dropping the user on "choose a
     * session".
     */
    private fun onTargetGenerationChanged(snapshot: SessionSnapshot?) {
        targetOwnerGeneration = null
        attachJob?.cancel()
        pendingFlushJob?.cancel()
        pendingFlushJob = null
        val keep = targetFlow.value
        val old = backendFlow.value
        backendFlow.value = null
        pendingInput.clear()
        if (old != null) releaseInBackground(old)

        val reopen = keep?.takeIf { snapshot?.pane(it.paneId) != null }
        if (reopen != null) {
            openTarget(reopen, preserveMessage = true)
            return
        }
        targetFlow.value = null
        onMessage(UiMessage.TargetChanged)
    }

    private fun reconcileVanishedTarget(snapshot: SessionSnapshot, target: TerminalTarget) {
        when (val decision = reconcileTarget(snapshot, target)) {
            TargetReconcile.Keep -> Unit
            is TargetReconcile.Retarget -> {
                val label = snapshot.workspace(decision.next.workspaceId)
                    ?.label?.takeIf { it.isNotBlank() }
                    ?: decision.next.workspaceId
                onMessage(UiMessage.WorkspaceClosed(label))
                openTarget(decision.next, preserveMessage = true)
            }
            TargetReconcile.Clear -> {
                backendFlow.value = null
                targetFlow.value = null
                onMessage(UiMessage.SessionClosed)
            }
        }
    }

    /**
     * A transport blip kills the attach stream, not the pane: once the link is
     * back and the terminal still reports a detach, re-attach. Bounded retries
     * per (pane, reason) so a pane that genuinely cannot attach does not spin.
     */
    private suspend fun watchReconnect() {
        var attempts = 0
        var lastKey: Pair<String, String>? = null
        session.state.map { it.connection }
            .distinctUntilChanged()
            .combine(
                backendFlow.flatMapLatest { it?.state ?: flowOf(null) },
            ) { connection, backendState -> connection to backendState }
            .collect { (connection, backendState) ->
                if (backendState is BackendState.Attached) {
                    attempts = 0
                    lastKey = null
                    return@collect
                }
                if (connection != ConnectionState.CONNECTED) {
                    attempts = 0
                    lastKey = null
                    return@collect
                }
                val detached = backendState as? BackendState.Detached
                    ?: return@collect
                val key = (targetFlow.value?.paneId ?: "?") to detached.reason
                if (key != lastKey) {
                    lastKey = key
                    attempts = 0
                }
                if (attempts >= AUTO_REATTACH_ATTEMPTS) return@collect
                val delayMs = AUTO_REATTACH_DELAY_MS * (1L shl attempts)
                attempts++
                scope.launch {
                    delay(delayMs)
                    retryAttach()
                }
            }
    }

    // ---- attach lifecycle ---------------------------------------------

    fun setAttachGrid(cols: Int, rows: Int) {
        gridFlow.value = cols to rows
    }

    /**
     * Host-owned scrollback: forward row deltas without counting as input.
     *
     * A drag produces one delta per cell crossing; the server needs rows, not
     * a frame per row. Coalesce for one scroll tick and send the sum.
     */
    fun scrollRemote(rows: Int) {
        if (rows == 0) return
        pendingScrollRows += rows
        if (scrollFlushJob?.isActive == true) return
        val backend = backendFlow.value ?: return
        val generation = targetOwnerGeneration ?: return
        scrollFlushJob = scope.launch {
            delay(SCROLL_COALESCE_MS)
            val delta = pendingScrollRows
            pendingScrollRows = 0
            if (delta == 0 || !isCurrent(backend, generation)) return@launch
            runCatching { backend.scrollRemote(delta, "rows") }
        }
    }

    private var pendingScrollRows = 0
    private var scrollFlushJob: Job? = null

    /**
     * The view's settled grid: forward to the live backend without counting as
     * user input (no inputTick, no pending queue).
     */
    fun resizeBackend(cols: Int, rows: Int) {
        gridFlow.value = cols to rows
        val backend = backendFlow.value ?: return
        val generation = targetOwnerGeneration ?: return
        scope.launch {
            if (isCurrent(backend, generation)) runCatching { backend.resize(cols, rows) }
        }
    }

    fun openTarget(target: TerminalTarget) {
        openTarget(target, preserveMessage = false)
    }

    fun openTab(workspaceId: String, tabId: String) {
        val snapshot = session.state.value.snapshot ?: return
        val tab = snapshot.tab(tabId) ?: return
        val pane = tab.activePane ?: return
        openTarget(TerminalTarget(workspaceId, tabId, pane.id))
    }

    /**
     * Re-attach the current target after a failure. Hands the dead backend to
     * [openTarget] (which releases it) — nulling the flow first would orphan
     * the live controller.
     */
    fun retryAttach() {
        if (targetOwnerGeneration != session.targetGeneration) return
        val target = targetFlow.value ?: return
        openTarget(target)
    }

    private fun openTarget(target: TerminalTarget, preserveMessage: Boolean) {
        if (!preserveMessage) onMessage(null)
        val generation = session.targetGeneration
        val snapshot = session.state.value.snapshot ?: return
        val pane = snapshot.pane(target.paneId) ?: return
        if (pane.workspaceId != target.workspaceId || pane.tabId != target.tabId) return
        if (targetFlow.value == target && targetOwnerGeneration != null &&
            targetOwnerGeneration != generation
        ) return

        // Read-ack: opening a DONE pane focuses its tab server-side, which
        // transitions done -> idle everywhere. Never steal focus for
        // working/idle opens, and never let a stale ack overwrite a message.
        if (pane.status == dev.herdr.mobile.core.model.AgentStatus.DONE) {
            scope.launch {
                focusTabForReadAck(session, target.tabId) { detail ->
                    if (session.targetGeneration == generation) {
                        onMessage(UiMessage.CommandFailed(UiMessage.CommandFailed.Command.READ_ACK, detail))
                    }
                }
            }
        }

        if (targetFlow.value != target) pendingInput.clear()
        attachJob?.cancel()
        pendingFlushJob?.cancel()
        pendingFlushJob = null
        targetFlow.value = target
        targetOwnerGeneration = generation
        attachJob = scope.launch { attach(target, generation) }
    }

    private suspend fun attach(target: TerminalTarget, generation: Long) {
        // Release the old backend UNCANCELLABLY: release() suspends and a rapid
        // tab switch would otherwise leave released=true with the socket open.
        val old = backendFlow.value
        backendFlow.value = null
        val oldPaneId = backendPaneId
        backendPaneId = null
        if (old != null) {
            if (oldPaneId == target.paneId) {
                // Same pane: the release restores its grid, so the new attach
                // must not race it. Everything else releases in the background
                // — waiting cost a fixed 150ms on every tab/pane switch.
                try {
                    withContext(NonCancellable) { old.release() }
                } catch (_: Exception) {
                }
            } else {
                releaseInBackground(old)
            }
        }
        currentCoroutineContext().ensureActive()
        if (session.targetGeneration != generation) return

        val connection = openTerminalConnection(
            session = session,
            target = target,
            generation = generation,
            grid = gridFlow.value,
            onMessage = onMessage,
        ) ?: return
        if (!currentCoroutineContext().isActive || targetFlow.value != target ||
            session.targetGeneration != generation
        ) {
            releaseQuietly(connection)
            return
        }
        val backend = ConnectionTerminalBackend(connection, scope)
        backendPaneId = target.paneId
        backendFlow.value = backend
        if (!currentCoroutineContext().isActive || targetFlow.value != target ||
            session.targetGeneration != generation
        ) {
            if (backendFlow.value === backend) backendFlow.value = null
            releaseInBackground(backend)
            return
        }
        flushPending(backend)
    }

    /** Release a detached socket without depending on the caller's lifetime. */
    private fun releaseInBackground(backend: ConnectionTerminalBackend) {
        scope.launch {
            try {
                withTimeout(5_000) { backend.release() }
            } catch (_: Exception) {
            }
        }
    }

    // ---- input ---------------------------------------------------------

    /**
     * Send to the live backend, or queue it while the stream is being
     * (re)attached. Failures surface as [UiMessage.SendFailed] and never throw
     * into the UI.
     */
    fun send(op: suspend (ConnectionTerminalBackend) -> Unit) {
        _inputTick.value += 1
        val backend = backendFlow.value
        if (backend != null) {
            val generation = targetOwnerGeneration ?: return
            if (generation != session.targetGeneration) return
            scope.launch {
                if (!isCurrent(backend, generation)) return@launch
                runCatching { op(backend) }.onFailure {
                    if (isCurrent(backend, generation)) {
                        onMessage(UiMessage.SendFailed(it.message))
                    }
                }
            }
            return
        }
        pendingInput.enqueue(op)
    }

    private fun flushPending(backend: ConnectionTerminalBackend) {
        val generation = targetOwnerGeneration ?: return
        if (!isCurrent(backend, generation)) return
        val ops = pendingInput.drain()
        if (ops.isEmpty()) return
        pendingFlushJob = scope.launch {
            for (op in ops) {
                if (!isCurrent(backend, generation)) return@launch
                runCatching { op(backend) }.onFailure {
                    if (isCurrent(backend, generation)) {
                        onMessage(UiMessage.SendFailed(it.message))
                    }
                }
            }
        }
    }

    /**
     * A backend may only be used while it still owns the screen: it is the
     * current flow value, the target was opened for this owner generation, and
     * the daemon has not restarted since.
     */
    private fun isCurrent(backend: ConnectionTerminalBackend, generation: Long): Boolean =
        backendFlow.value === backend &&
            targetOwnerGeneration == generation &&
            session.targetGeneration == generation

    // ---- lifecycle -----------------------------------------------------

    /** Activity ON_STOP keeps the Compose tree alive: release the stream. */
    fun releaseForBackground() {
        attachJob?.cancel()
        val backend = backendFlow.value ?: return
        backendFlow.value = null
        backendPaneId = null
        releaseInBackground(backend)
    }

    /**
     * Activity ON_START after [releaseForBackground]: re-attach the kept target.
     * Waits for the link first — after minutes in background the tunnel is dead.
     */
    fun resumeAfterBackground() {
        val target = targetFlow.value ?: return
        if (backendFlow.value != null) return
        if (targetOwnerGeneration != session.targetGeneration) return
        attachJob?.cancel()
        onMessage(null)
        attachJob = scope.launch {
            session.kick()
            if (!awaitConnected(session)) {
                onMessage(UiMessage.ReconnectTimedOut)
                return@launch
            }
            openTarget(target)
        }
    }

    fun shutdown() {
        attachJob?.cancel()
        pendingFlushJob?.cancel()
        val backend = backendFlow.value
        backendFlow.value = null
        backendPaneId = null
        backend?.let { releaseInBackground(it) }
    }
}

/** Reconnect settles (snapshot + stream) before the attach is retried. */
private const val AUTO_REATTACH_DELAY_MS = 600L

/** 0.6s, 1.2s, 2.4s, then the Retry button takes over. */
private const val AUTO_REATTACH_ATTEMPTS = 4

/** One scroll tick: the view reports per-row deltas during a drag. */
private const val SCROLL_COALESCE_MS = 40L
