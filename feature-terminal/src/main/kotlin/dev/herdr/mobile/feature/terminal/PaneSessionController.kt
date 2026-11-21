package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.designsystem.UiMessage
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.Pane
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.network.HerdrSession
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeoutOrNull

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
    private var ackJob: Job? = null
    private var readAckPaneId: String? = null
    private var lastReadAckAttemptAt = 0L

    /** Pane the live backend belongs to (release ordering needs it). */
    private var backendPaneId: String? = null
    private var observedTargetGeneration = session.targetGeneration
    private var targetOwnerGeneration: Long? = null
    /**
     * A screen left open across a host switch must not fall back to the new
     * host's focused/first workspace using the old route's ids. A fresh screen
     * (initialResolutionAllowed) may still do that.
     */
    private var initialResolutionAllowed = true

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
        // The screen is backgrounded: keep the cached target/snapshot but do
        // not attach, retarget, or acknowledge DONE off-screen. The resume
        // path re-evaluates the latest snapshot.
        if (backgrounded) return
        val generation = session.targetGeneration
        if (generation != observedTargetGeneration) {
            observedTargetGeneration = generation
            onTargetGenerationChanged(state.snapshot)
            return
        }
        val snapshot = state.snapshot ?: return
        val current = targetFlow.value
        if (current == null) {
            // After a host switch an old route must not re-resolve by ID.
            if (!initialResolutionAllowed) return
            val resolved = resolveTarget(
                snapshot,
                initialWorkspaceId,
                initialTabId,
                initialPaneId,
                allowFallback = initialResolutionAllowed,
            )
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
                snapshot.pane(resolved.paneId)?.let { pane ->
                    maybeAckDone(pane, resolved, session.targetGeneration)
                }
            }
        } else {
            reconcileVanishedTarget(snapshot, current)
            snapshot.pane(current.paneId)?.let { pane ->
                maybeAckDone(pane, current, session.targetGeneration)
            }
        }
    }

    /**
     * The daemon restarted or the tunnel re-dialled. Pane ids are stable, so
     * the same pane is re-opened instead of dropping the user on "choose a
     * session".
     */
    private fun onTargetGenerationChanged(snapshot: SessionSnapshot?) {
        targetOwnerGeneration = null
        cancelAttachmentWork(clearInput = true)
        val old = backendFlow.value
        backendFlow.value = null
        if (old != null) releaseInBackground(old)

        // targetGeneration changes only on a profile/transport switch, not on
        // a daemon redial (that keeps the generation). Do not reopen a pane
        // whose id merely happens to exist on the new host.
        targetFlow.value = null
        initialResolutionAllowed = false
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
                clearTargetAndRelease()
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
        var attachedAt = 0L
        session.state.map { it.connection }
            .distinctUntilChanged()
            .combine(
                backendFlow.flatMapLatest { it?.state ?: flowOf(null) },
            ) { connection, backendState -> connection to backendState }
            .collect { (connection, backendState) ->
                if (backendState is BackendState.Attached) {
                    // Do NOT reset the retry budget here. A pane that attaches
                    // and immediately drops (missing terminal, daemon/backend
                    // race) otherwise loops forever: every short attach reset
                    // the counter that was meant to stop it.
                    if (attachedAt == 0L) attachedAt = System.currentTimeMillis()
                    // A slow attach may have outlived the 5s read-ack wait;
                    // re-evaluate the visible DONE now that it is really up.
                    val target = targetFlow.value
                    val generation = targetOwnerGeneration
                    val pane = target?.let { session.state.value.snapshot?.pane(it.paneId) }
                    if (target != null && generation != null && pane != null) {
                        maybeAckDone(pane, target, generation)
                    }
                    return@collect
                }
                // The combine pair can carry a stale CONNECTED next to a
                // freshly detached backend (the link and terminal sockets
                // close independently). Retrying on that pair cleared the
                // attached backend mid-drop and replaced the frozen terminal
                // with a placeholder. Read the live link instead.
                if (session.state.value.connection != ConnectionState.CONNECTED) {
                    attempts = 0
                    lastKey = null
                    attachedAt = 0
                    return@collect
                }
                val detached = backendState as? BackendState.Detached
                    ?: return@collect
                val now = System.currentTimeMillis()
                if (attachedAt != 0L && now - attachedAt >= STABLE_ATTACH_MS) {
                    // A real, long-lived attach earns a fresh retry budget.
                    attempts = 0
                    lastKey = null
                }
                attachedAt = 0
                val key = (targetFlow.value?.paneId ?: "?") to detached.reason
                if (key != lastKey) {
                    lastKey = key
                    attempts = 0
                }
                // A missing pane is permanent: reconnecting cannot create it.
                val permanent = detached.reason.contains("not found", ignoreCase = true) ||
                    detached.reason.contains("pane_not_found", ignoreCase = true)
                if (permanent || attempts >= AUTO_REATTACH_ATTEMPTS) {
                    onMessage(UiMessage.AttachFailed(detached.reason))
                    return@collect
                }
                // lagged_resync is transient but still goes through this
                // one retry path, so the budget and link guard apply.
                val delayMs = if (detached.reason == "lagged_resync") {
                    0L
                } else {
                    AUTO_REATTACH_DELAY_MS * (1L shl attempts)
                }
                attempts++
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(delayMs)
                    // The link can drop again during the backoff; do not
                    // clear a still-frozen backend for a retry that cannot
                    // open. The next CONNECTED emission re-schedules.
                    if (session.state.value.connection == ConnectionState.CONNECTED) {
                        retryAttach()
                    }
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
    fun scrollRemote(rows: Int, allowPageKey: Boolean = false) {
        if (rows == 0) return
        val backend = backendFlow.value
        if (backend == null) {
            // No stream: a queued delta would be replayed on the next gesture
            // after attach and jump far more rows than the finger moved.
            pendingScrollRows = 0
            return
        }
        // Routing follows the pane report (AGENTS.md): do not issue a full
        // snapshot GET every 2s mid-drag just to guess that scrollback appeared.
        pendingScrollRows += rows
        if (scrollFlushJob?.isActive == true) return
        targetOwnerGeneration ?: return
        scrollFlushJob = scope.launch {
            delay(SCROLL_COALESCE_MS)
            val delta = pendingScrollRows
            pendingScrollRows = 0
            val current = backendFlow.value ?: return@launch
            val currentGeneration = targetOwnerGeneration ?: return@launch
            if (delta == 0 || !isCurrent(current, currentGeneration)) return@launch
            runCatching { current.scrollRemote(delta, allowPageKey, "rows") }
        }
    }

    /**
     * Absolute host scrollbar drag. Coalesce to the latest requested offset so
     * a 60 Hz drag does not queue a daemon RPC per pixel, while preserving the
     * final position.
     */
    fun scrollHostTo(offsetFromBottom: Long) {
        val backend = backendFlow.value ?: return
        pendingHostOffset = offsetFromBottom
        if (hostScrollJob?.isActive == true) return
        val generation = targetOwnerGeneration ?: return
        hostScrollJob = scope.launch {
            delay(SCROLL_COALESCE_MS)
            val offset = pendingHostOffset ?: return@launch
            pendingHostOffset = null
            val current = backendFlow.value ?: return@launch
            val currentGeneration = targetOwnerGeneration ?: return@launch
            if (!isCurrent(current, currentGeneration)) return@launch
            runCatching { current.scrollHostTo(offset) }
        }
    }

    private var pendingScrollRows = 0
    private var scrollFlushJob: Job? = null
    private var pendingHostOffset: Long? = null
    private var hostScrollJob: Job? = null
    private var retryJob: Job? = null
    private var backgrounded = false

    /**
     * The view's settled grid: forward to the live backend without counting as
     * user input (no inputTick, no pending queue).
     */
    fun resizeBackend(cols: Int, rows: Int) {
        gridFlow.value = cols to rows
        val backend = backendFlow.value ?: return
        val generation = targetOwnerGeneration ?: return
        // Opening a tab lays out the view several times (insets, IME, panel),
        // and every frame resized the core PTY again. OMP reflows its whole
        // transcript on resize, so the repeated sizes caused seconds of
        // thrashing while the user scrolled. Send only the settled geometry.
        pendingResize = cols to rows
        resizeJob?.cancel()
        resizeJob = scope.launch {
            delay(RESIZE_SETTLE_MS)
            val target = pendingResize ?: return@launch
            pendingResize = null
            if (isCurrent(backend, generation)) {
                runCatching { backend.resize(target.first, target.second) }
            }
        }
    }

    private var pendingResize: Pair<Int, Int>? = null
    private var resizeJob: Job? = null

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
        if (backgrounded) return
        if (targetOwnerGeneration != session.targetGeneration) return
        val target = targetFlow.value ?: return
        openTarget(target)
    }

    private fun openTarget(target: TerminalTarget, preserveMessage: Boolean) {
        backgrounded = false
        if (!preserveMessage) onMessage(null)
        val generation = session.targetGeneration
        val snapshot = session.state.value.snapshot ?: return
        val pane = snapshot.pane(target.paneId) ?: return
        if (pane.workspaceId != target.workspaceId || pane.tabId != target.tabId) return
        if (targetFlow.value == target && targetOwnerGeneration != null &&
            targetOwnerGeneration != generation
        ) return

        val targetChanged = targetFlow.value != target
        // A coalescing job from the old pane must not steal the new pane's
        // first gesture: it captures the old backend and drops the merged sum.
        cancelAttachmentWork(clearInput = targetChanged)
        targetFlow.value = target
        targetOwnerGeneration = generation
        attachJob = scope.launch { attach(target, generation) }
        maybeAckDone(pane, target, generation)
    }

    /**
     * Mark a DONE pane read exactly once per done episode, including when the
     * user is already looking at it. The old open-time-only ack required
     * leaving and reopening the terminal after the agent finished.
     */
    private fun maybeAckDone(pane: Pane, target: TerminalTarget, generation: Long) {
        if (backgrounded) return
        if (pane.status != dev.herdr.mobile.core.model.AgentStatus.DONE) {
            if (readAckPaneId == pane.id) readAckPaneId = null
            return
        }
        if (readAckPaneId == pane.id || ackJob?.isActive == true) return
        if (targetFlow.value != target || targetOwnerGeneration != generation) return
        val now = System.currentTimeMillis()
        if (now - lastReadAckAttemptAt < 1_000) return
        lastReadAckAttemptAt = now
        readAckPaneId = pane.id
        ackJob = scope.launch {
            // Do not mark a DONE read before the pane stream actually
            // attached. A failed attach must leave the completion unread.
            val backend = backendFlow.value ?: run {
                readAckPaneId = null
                return@launch
            }
            withTimeoutOrNull(5_000) {
                backend.state.first { it is BackendState.Attached }
            } ?: run {
                readAckPaneId = null
                return@launch
            }
            if (targetFlow.value != target || targetOwnerGeneration != generation) {
                readAckPaneId = null
                return@launch
            }
            val ok = focusPaneForReadAck(session, pane.id, generation) { detail ->
                if (session.targetGeneration == generation) {
                    onMessage(UiMessage.CommandFailed(UiMessage.CommandFailed.Command.READ_ACK, detail))
                }
            }
            if (!ok) readAckPaneId = null
        }
    }

    private suspend fun attach(target: TerminalTarget, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (session.targetGeneration != generation) return

        val previous = backendFlow.value
        val previousPaneId = backendPaneId
        val samePane = previous != null && previousPaneId == target.paneId
        if (!samePane) {
            // A pane switch must not leave the old pane as the routing or
            // display target: input would go to the pane the user left. Clear
            // it before opening the replacement; the placeholder owns the
            // transition.
            backendFlow.value = null
            backendPaneId = null
            previous?.let { releaseInBackground(it) }
        }

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
        if (samePane) {
            // Same pane: the old controller already paints the frozen frame.
            // Do not swap to the new (still Attaching) controller until it can
            // paint, or the last output disappears for the attach window.
            val ready = try {
                withTimeoutOrNull(ATTACH_READY_TIMEOUT_MS) {
                    backend.state.first {
                        it is BackendState.Attached || it is BackendState.Failed
                    }
                }
            } catch (e: CancellationException) {
                releaseQuietly(connection)
                throw e
            }
            if (ready !is BackendState.Attached) {
                releaseQuietly(connection)
                return
            }
        }
        backendPaneId = target.paneId
        backendFlow.value = backend
        if (!currentCoroutineContext().isActive || targetFlow.value != target ||
            session.targetGeneration != generation
        ) {
            if (backendFlow.value === backend) backendFlow.value = null
            releaseInBackground(backend)
            if (samePane && previous != null) releaseInBackground(previous)
            return
        }
        if (samePane && previous != null && previous !== backend) {
            // The new bridge is attached; release the old controller only now.
            try {
                withContext(NonCancellable) { previous.release() }
            } catch (_: Exception) {
            }
        }
        flushPending(backend)
        session.state.value.snapshot?.pane(target.paneId)?.let { pane ->
            maybeAckDone(pane, target, generation)
        }
    }

    /** Release a detached socket without depending on the caller's lifetime. */
    private fun releaseInBackground(backend: ConnectionTerminalBackend) {
        // NonCancellable: onCleared runs after viewModelScope is cancelled, so
        // a plain launch was a no-op and closed screens leaked their socket.
        // The coroutine still rides the caller's dispatcher (tests drive it).
        scope.launch(NonCancellable) {
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
        dispatch(op)
    }

    /** Engine-originated input: same path, no input tick. */
    fun sendRaw(op: suspend (ConnectionTerminalBackend) -> Unit) = dispatch(op)

    private fun dispatch(op: suspend (ConnectionTerminalBackend) -> Unit) {
        val backend = backendFlow.value
        val generation = targetOwnerGeneration
        if (backend == null || generation == null || !isCurrent(backend, generation)) {
            // The selected pane has no live stream yet (switch/reattach).
            // Queue instead of typing into the pane the user left.
            pendingInput.enqueue(op)
            return
        }
        if (backend != null) {
            // A detached stream keeps the backend in the flow so the last
            // frame stays visible, but its socket is already closed. Queue
            // input instead of silently discarding it in ws.send().
            when (backend.state.value) {
                is BackendState.Detached, is BackendState.Failed -> {
                    pendingInput.enqueue(op)
                    return
                }
                else -> Unit
            }
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
            backendPaneId == targetFlow.value?.paneId &&
            targetOwnerGeneration == generation &&
            session.targetGeneration == generation

    // ---- lifecycle -----------------------------------------------------

    /**
     * One place that cancels every attachment-scoped job. These jobs form the
     * controller's real state machine (target + generation + backend + queued
     * input); scheduling them independently is how a stale ack, scroll or
     * resize used to outlive its pane.
     */
    private fun cancelAttachmentWork(clearInput: Boolean) {
        attachJob?.cancel()
        attachJob = null
        ackJob?.cancel()
        ackJob = null
        readAckPaneId = null
        lastReadAckAttemptAt = 0
        pendingFlushJob?.cancel()
        pendingFlushJob = null
        retryJob?.cancel()
        retryJob = null
        scrollFlushJob?.cancel()
        scrollFlushJob = null
        pendingScrollRows = 0
        hostScrollJob?.cancel()
        hostScrollJob = null
        pendingHostOffset = null
        resizeJob?.cancel()
        resizeJob = null
        pendingResize = null
        if (clearInput) pendingInput.clear()
    }

    /** Activity ON_STOP keeps the Compose tree alive: release the stream. */
    fun releaseForBackground() {
        backgrounded = true
        cancelAttachmentWork(clearInput = false)
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
        backgrounded = false
        val state = session.state.value
        if (observedTargetGeneration != session.targetGeneration) {
            observedTargetGeneration = session.targetGeneration
            onTargetGenerationChanged(state.snapshot)
            // A kept target was reopened above. If there was none (the first
            // snapshot arrived while stopped), fall through and resolve it
            // from the current snapshot instead of waiting for another emit.
            if (targetFlow.value != null) return
        }
        // The first snapshot may have arrived while stopped (onSessionState
        // returns early when backgrounded), leaving no target at all. Resolve
        // it now instead of waiting for an unrelated later state change.
        val target = targetFlow.value ?: run {
            if (!initialResolutionAllowed) return
            val snapshot = state.snapshot ?: return
            val resolved = resolveTarget(
                snapshot, initialWorkspaceId, initialTabId, initialPaneId,
                allowFallback = initialResolutionAllowed,
            ) ?: return
            onMessage(null)
            fallbackNotice(
                initialWorkspaceId,
                initialTabId,
                initialPaneId,
                snapshot,
                resolved,
            )?.let(onMessage)
            openTarget(resolved, preserveMessage = true)
            snapshot.pane(resolved.paneId)?.let { pane ->
                maybeAckDone(pane, resolved, session.targetGeneration)
            }
            return
        }
        if (backendFlow.value != null) return
        if (targetOwnerGeneration != session.targetGeneration) {
            onTargetGenerationChanged(state.snapshot)
            return
        }
        // The snapshot may have moved while the stream was released. Resolve
        // the kept target before re-attaching instead of attaching a closed
        // pane, and acknowledge a DONE that arrived while away.
        val snapshot = state.snapshot
        if (snapshot != null) {
            when (val decision = reconcileTarget(snapshot, target)) {
                TargetReconcile.Keep -> Unit
                is TargetReconcile.Retarget -> {
                    val label = snapshot.workspace(decision.next.workspaceId)
                        ?.label?.takeIf { it.isNotBlank() }
                        ?: decision.next.workspaceId
                    onMessage(UiMessage.WorkspaceClosed(label))
                    openTarget(decision.next, preserveMessage = true)
                    return
                }
                TargetReconcile.Clear -> {
                    clearTargetAndRelease()
                    onMessage(UiMessage.SessionClosed)
                    return
                }
            }
            snapshot.pane(target.paneId)?.let { pane ->
                maybeAckDone(pane, target, session.targetGeneration)
            }
        }
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

    /** Clear the visible target and release the socket before the flow drops it. */
    private fun clearTargetAndRelease() {
        cancelAttachmentWork(clearInput = true)
        val old = backendFlow.value
        backendFlow.value = null
        backendPaneId = null
        targetFlow.value = null
        old?.let { releaseInBackground(it) }
    }

    fun shutdown() {
        backgrounded = true
        cancelAttachmentWork(clearInput = false)
        val backend = backendFlow.value
        backendFlow.value = null
        backendPaneId = null
        backend?.let { releaseInBackground(it) }
    }
}

/** Coalesce layout churn before resizing the remote PTY. */
private const val RESIZE_SETTLE_MS = 180L

/** Reconnect settles (snapshot + stream) before the attach is retried. */
private const val AUTO_REATTACH_DELAY_MS = 600L

/** 0.6s, 1.2s, 2.4s, then the Retry button takes over. */
private const val AUTO_REATTACH_ATTEMPTS = 4

/** How long a same-pane replacement may spend Attaching before the frozen
 *  controller is kept instead. */
private const val ATTACH_READY_TIMEOUT_MS = 10_000L

/** Attach must last this long before a detach earns a fresh retry budget. */
private const val STABLE_ATTACH_MS = 5_000L

/** One scroll tick: the view reports per-row deltas during a drag. */
private const val SCROLL_COALESCE_MS = 40L
