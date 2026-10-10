package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.core.designsystem.StatusChip
import dev.herdr.mobile.core.designsystem.StatusDot
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.InputPanelPage
import com.termux.view.TerminalView
import dev.herdr.mobile.terminal.view.BackendState
import dev.herdr.mobile.terminal.view.RemoteTermuxSession
import dev.herdr.mobile.terminal.view.TermuxSchemes
import dev.herdr.mobile.terminal.view.TermuxTerminalHost
import dev.herdr.mobile.terminal.view.TermuxViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Deep-interaction screen: compact workspace/tab rails, the terminal surface, and the
 * two-page bottom input panel. The rails never grow under selection; overflow scrolls
 * horizontally.
 */
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onNavigateHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tabMenu by remember { mutableStateOf<TabMenuTarget?>(null) }
    var renameTarget by remember { mutableStateOf<TabMenuTarget?>(null) }
    // Manual keyboard summon (CJK panel button) / hide (leaving the CJK page
    // must not strand the IME on a hidden field). Counters, not booleans: two
    // consecutive requests must both fire. Nothing else shows the IME — no
    // tap, no swipe, no focus change (Termux behavior).
    var keyboardShowRequest by remember { mutableStateOf(0) }
    var keyboardHideRequest by remember { mutableStateOf(0) }

    // App backgrounded (Home button, task switch) does NOT dispose this screen,
    // so neither DisposableEffect nor onCleared runs. Release the controller or
    // its resize lock leaks until the process dies.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                viewModel.releaseForBackground()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                viewModel.resumeAfterBackground()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // navigationBarsPadding keeps the panel above the gesture bar when the
    // keyboard is hidden; imePadding lifts it above the keyboard when shown.
    // The terminal Box above shrinks accordingly, so its bottom edge always
    // sits exactly on top of the panel — never behind the keyboard.
    Column(modifier = modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        WorkspaceRail(
            state = state,
            onNavigateHome = onNavigateHome,
            onSelectWorkspace = { workspaceId ->
                val snapshot = state.snapshot ?: return@WorkspaceRail
                val workspace = snapshot.workspace(workspaceId) ?: return@WorkspaceRail
                val tab = workspace.tabs.firstOrNull { it.id == workspace.activeTabId }
                    ?: workspace.tabs.firstOrNull()
                    ?: return@WorkspaceRail
                val pane = tab.activePane ?: return@WorkspaceRail
                viewModel.openTarget(TerminalTarget(workspaceId, tab.id, pane.id))
            },
        )
        TabRail(
            state = state,
            onSelectTab = { workspaceId, tabId -> viewModel.openTab(workspaceId, tabId) },
            onAddTab = {
                val workspaceId = state.target?.workspaceId ?: return@TabRail
                viewModel.createTab(workspaceId, label = null)
            },
            onTabLongPress = { workspaceId, tabId, label, anchor ->
                tabMenu = TabMenuTarget(workspaceId, tabId, label, anchor)
            },
        )
        state.statusMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val backend = state.backend
            if (backend == null) {
                TerminalPlaceholder(
                    connection = state.connection,
                    hasTarget = state.target != null,
                )
            } else {
                TerminalSurface(
                    state = state,
                    onSendBytes = { viewModel.sendBytes(it) },
                    onSendText = { viewModel.sendText(it) },
                    onRetry = { viewModel.retryAttach() },
                    keyboardShowRequest = keyboardShowRequest,
                    keyboardHideRequest = keyboardHideRequest,
                    inputTick = viewModel.inputTick.collectAsStateWithLifecycle().value,
                    onGridChanged = { cols, rows -> viewModel.setAttachGrid(cols, rows) },
                )
            }
        }
        BottomInputPanel(
            viewModel = viewModel,
            state = state,
            onSendBytes = { viewModel.sendBytes(it) },
            onSendText = { viewModel.sendText(it) },
            onExtraKey = { viewModel.sendExtraKey(it) },
            onPageChange = { viewModel.setInputPage(it) },
            onSummonKeyboard = { keyboardShowRequest++ },
            onHideKeyboard = { keyboardHideRequest++ },
        )
    }

    tabMenu?.let { target ->
        TabActionsMenu(
            target = target,
            onDismiss = { tabMenu = null },
            onRename = {
                renameTarget = target
                tabMenu = null
            },
            onClose = {
                viewModel.closeTab(target.workspaceId, target.tabId)
                tabMenu = null
            },
        )
    }
    renameTarget?.let { target ->
        TabRenameDialog(
            target = target,
            onDismiss = { renameTarget = null },
            onConfirm = { label ->
                viewModel.renameTab(target.tabId, label)
                renameTarget = null
            },
        )
    }
}

/** Which tab a long-press menu refers to. [anchor] is the press point in the TabRail's coordinates. */
private data class TabMenuTarget(
    val workspaceId: String,
    val tabId: String,
    val label: String,
    val anchor: androidx.compose.ui.unit.IntOffset,
)

@Composable
private fun TabActionsMenu(
    target: TabMenuTarget,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
) {
    // Anchored at the pressed chip: DropdownMenu unanchored renders at the
    // window origin (the reported "wrong position"). Offset = press point.
    Box(
        modifier = Modifier
            .offset { target.anchor }
            .size(1.dp),
    ) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text("Rename \"${target.label}\"") },
                onClick = onRename,
            )
            DropdownMenuItem(
                text = { Text("Close \"${target.label}\"") },
                onClick = onClose,
            )
        }
    }
}

@Composable
private fun TabRenameDialog(
    target: TabMenuTarget,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var label by remember(target.tabId) { mutableStateOf(target.label) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename tab") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Tab name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label) },
                enabled = label.trim().isNotEmpty(),
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun WorkspaceRail(
    state: TerminalUiState,
    onNavigateHome: () -> Unit,
    onSelectWorkspace: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot ?: return
    // Home stays pinned left while the workspace chips scroll under it.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onNavigateHome, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Home, contentDescription = "Back to Home")
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            snapshot.workspaces.forEach { workspace ->
                StatusChip(
                    label = workspace.label,
                    status = workspace.status,
                    selected = workspace.id == state.target?.workspaceId,
                    onClick = { onSelectWorkspace(workspace.id) },
                )
            }
        }
    }
}

@Composable
private fun TabRail(
    state: TerminalUiState,
    onSelectTab: (workspaceId: String, tabId: String) -> Unit,
    onAddTab: () -> Unit,
    onTabLongPress: (workspaceId: String, tabId: String, label: String, androidx.compose.ui.unit.IntOffset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = state.workspace ?: return
    // Rail origin in window coordinates: chip-local press offsets are summed
    // with it so the menu anchor lands on the pressed chip.
    var railOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // + stays pinned right while the tab chips scroll under it. The anchor
    // math uses window coords, so the extra nesting changes nothing.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { railOrigin = it.positionInWindow() }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            workspace.tabs.forEach { tab ->
                val harness = tab.harnessNames.joinToString(",").takeIf { it.isNotEmpty() }
                var chipOrigin by remember(tab.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                StatusChip(
                    label = if (harness != null) "${tab.displayLabel} · $harness" else tab.displayLabel,
                    status = tab.status,
                    selected = tab.id == state.target?.tabId,
                    modifier = Modifier.onGloballyPositioned { chipOrigin = it.positionInWindow() },
                    onClick = { onSelectTab(workspace.id, tab.id) },
                    onLongClick = { press ->
                        // Menu renders at the Column top (below rails): window
                        // coords minus the rails' height. Rail origin ≈ menu origin.
                        val anchor = androidx.compose.ui.unit.IntOffset(
                            (chipOrigin.x + press.x).toInt(),
                            (chipOrigin.y + press.y - railOrigin.y).toInt(),
                        )
                        onTabLongPress(workspace.id, tab.id, tab.displayLabel, anchor)
                    },
                )
            }
        }
        IconButton(onClick = onAddTab, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "New tab in ${workspace.label}")
        }
    }
}

@Composable
private fun TerminalPlaceholder(connection: ConnectionState, hasTarget: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = when {
                !hasTarget && connection == ConnectionState.CONNECTED -> "No pane to attach"
                connection == ConnectionState.CONNECTED -> "Attaching…"
                connection == ConnectionState.FAILED -> "Connection failed"
                else -> "Connecting…"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TerminalErrorBody(message: String, onRetry: (() -> Unit)? = null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(20.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun TerminalSurface(
    state: TerminalUiState,
    onSendBytes: (ByteArray) -> Unit,
    onSendText: (String) -> Unit,
    onRetry: () -> Unit,
    keyboardShowRequest: Int,
    keyboardHideRequest: Int,
    inputTick: Int,
    onGridChanged: (cols: Int, rows: Int) -> Unit,
) {
    val backend = state.backend ?: return
    val backendState by backend.state.collectAsStateWithLifecycle(initialValue = BackendState.Idle)
    val clipboard = LocalClipboardManager.current
    // Attach failure / disconnect must never render as a blank terminal: show the
    // reason with a retry instead, and stop feeding a dead backend to the view.
    // lagged_resync is transient (daemon dropped burst frames): retry at once.
    val detached = backendState as? BackendState.Detached
    if (detached?.reason == "lagged_resync") {
        LaunchedEffect(backend) { onRetry() }
    }
    when (val bs = backendState) {
        is BackendState.Failed -> {
            TerminalErrorBody("Attach failed (${bs.code}): ${bs.message}", onRetry)
            return
        }
        is BackendState.Detached -> {
            TerminalErrorBody("Detached (${bs.reason})", onRetry)
            return
        }
        else -> {}
    }
    val settings = state.settings
    var bridge by remember(backend) { mutableStateOf<RemoteTermuxSession?>(null) }
    // DisposableEffect(backend) re-runs on backend change — but by onDispose
    // time, remember(backend) has ALREADY reset `bridge` to null, so reading
    // it there releases nothing (leak) or the wrong bridge. Capture the live
    // bridge in a ref that survives the reset.
    val bridgeRef = remember { mutableStateOf<RemoteTermuxSession?>(null) }
    var viewRef by remember { mutableStateOf<TerminalView?>(null) }
    var hostRef by remember { mutableStateOf<TermuxTerminalHost?>(null) }
    var viewClientRef by remember { mutableStateOf<TermuxViewClient?>(null) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Manual summon: the CJK keyboard button. Guard 0 so composition
    // doesn't steal focus on first mount.
    LaunchedEffect(keyboardShowRequest) {
        if (keyboardShowRequest > 0) {
            hostRef?.showKeyboard()
        }
    }
    // Leaving the CJK page hides the IME (it was bound to the hidden field)
    // and returns focus to the terminal without showing anything.
    LaunchedEffect(keyboardHideRequest) {
        if (keyboardHideRequest > 0) {
            hostRef?.hideKeyboard()
            viewRef?.requestFocus()
        }
    }
    // User input snaps a scrolled viewport home (back at the prompt). Guard 0:
    // the initial value must not yank on mount.
    LaunchedEffect(inputTick) {
        if (inputTick > 0) {
            hostRef?.snapToBottom()
        }
    }

    val scheme = remember(state.settings.terminalColorScheme) { state.terminalScheme() }

    // View size → grid size → backend resize, debounced by the bridge/daemon coalescing.
    var viewSizePx by remember { mutableStateOf(0 to 0) }
    // First traversal complete (view.post in the factory): creating the
    // session before layout yields a transient grid.
    var viewLaidOut by remember { mutableStateOf(false) }

    // The session belongs to the backend, not to the view size: create it
    // eagerly once layout is known (viewSizePx persists across tab switches,
    // so identical-layout switches attach immediately). NEVER start at the
    // 80x24 fallback: Termux growth-resize (24→30) consumes transcript rows
    // into the screen, which would eat the prelude history the daemon just
    // banked. Waiting for the first layout costs one frame and sizes exactly.
    LaunchedEffect(backend, viewRef, viewSizePx, viewLaidOut) {
        val view = viewRef
        val host = hostRef
        val (w, h) = viewSizePx
        if (bridge == null && view != null && host != null && w > 0 && h > 0 && viewLaidOut) {
            TermuxSchemes.apply(scheme)
            val created = RemoteTermuxSession(
                backend = backend,
                view = view,
                host = host,
                scrollbackLimit = settings.scrollbackLimit,
                onCopyText = { text ->
                    if (text.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(text))
                    }
                },
            )
            created.sessionClient.cursorStyle = settings.cursorStyle
            created.emulator.mColors.reset()
            view.attachSession(created.termuxSession)
            // The view computed the grid synchronously in attach (updateSize):
            // start the pump with ITS numbers, never our own metrics (a
            // disagreeing size resizes mid-prelude through the column-change
            // reflow, which drops banked transcript rows).
            val cols = created.emulator.mColumns
            val rows = created.emulator.mRows
            bridge = created
            bridgeRef.value = created
            created.start(cols, rows)
        }
    }

    // Rebind every composition in update(): the factory closure runs once and
    // captured `bridge` State goes stale after a tab switch (remember yields a
    // NEW State object). Stale callbacks tap/scroll the released backend,
    // silently dropped by runCatching.
    fun bindTermuxCallbacks(viewClient: TermuxViewClient, host: TermuxTerminalHost) {
        viewClient.onDirectInput = { text -> onSendText(text) }
        viewClient.onDirectDelete = { onSendBytes(byteArrayOf(0x7F)) }
        viewClient.onTapCell = { col, row ->
            // Mouse-mode apps (vim, less, tmux): forward the tap as a
            // left-button press. Non-mouse apps ignore it server-side.
            val b = bridgeRef.value
            if (b != null) {
                scope.launch { runCatching { b.mouse("down", "left", col, row) } }
            }
        }
        viewClient.onZoomFont = { _ ->
            // Font zoom is applied through settings; the host clamps it.
        }
        host.onRemoteScroll = { lines ->
            bridgeRef.value?.scrollRemote(lines)
        }
    }

    AndroidView(
        factory = { context ->
            // Init order matters (Termux NPEs otherwise): text size first
            // (updateSize derefs the renderer), then the view client (updateSize
            // calls onEmulatorSet), then attach (in the session effect above).
            val viewClient = TermuxViewClient()
            val view = TerminalView(context, null)
            // Termux sets these in layout XML; programmatically-created views
            // are NOT focusable by default, and without focus the IME never
            // binds (manual keyboard summon silently no-ops) and hardware keys
            // never arrive.
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            val px = (settings.terminalFontSizeSp * density.density).toInt()
            view.setTextSize(px)
            view.setTypeface(android.graphics.Typeface.create(settings.terminalFont.familyName, android.graphics.Typeface.NORMAL))
            view.setTerminalViewClient(viewClient)
            viewClient.view = view
            view.setBackgroundColor(TermuxSchemes.backgroundAndroid(scheme))
            val host = TermuxTerminalHost(view)
            viewClient.host = host
            host.textSizePx = px
            viewRef = view
            hostRef = host
            viewClientRef = viewClient
            bindTermuxCallbacks(viewClient, host)
            view.post { viewLaidOut = true }
            view
        },
        update = { view ->
            // Scheme/font/cursor follow settings live (Termux has no line-height
            // multiplier: terminalLineHeight is accepted as unsupported).
            TermuxSchemes.apply(scheme)
            bridge?.emulator?.mColors?.reset()
            view.setBackgroundColor(TermuxSchemes.backgroundAndroid(scheme))
            view.invalidate()
            val px = (settings.terminalFontSizeSp * density.density).toInt()
            view.setTextSize(px)
            view.setTypeface(android.graphics.Typeface.create(settings.terminalFont.familyName, android.graphics.Typeface.NORMAL))
            hostRef?.textSizePx = px
            bridge?.sessionClient?.cursorStyle = settings.cursorStyle
            // Sticky CTRL/ALT feed the view client (Termux applies them to the
            // next hardware key when set).
            viewClientRef?.ctrlDown = dev.herdr.mobile.terminal.view.TerminalKeyEncoder.Modifier.CTRL in state.stickyModifiers
            viewClientRef?.altDown = dev.herdr.mobile.terminal.view.TerminalKeyEncoder.Modifier.ALT in state.stickyModifiers
            // Remote scroll routing (host part): agent panes (main-screen
            // TUIs like codex whose transcript lives in HOST scrollback, not
            // local history). Alt-screen is OR-ed live by the pump.
            // Plain shells keep instant local scroll.
            hostRef?.remoteScrollHost = state.pane?.agent?.isNotBlank() == true
            val vc = viewClientRef
            val h = hostRef
            if (vc != null && h != null) bindTermuxCallbacks(vc, h)
        },
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                viewSizePx = size.width to size.height
                // Publish the live grid for the NEXT attach (daemon prelude
                // fill must match the real grid or history under-banks).
                val px = hostRef?.textSizePx ?: 0
                if (px > 0) {
                    val (gc, gr) = gridFor(px, size.width, size.height, settings.terminalFont.familyName)
                    onGridChanged(gc, gr)
                }
                // After layout (view.updateSize ran first): forward the
                // view-computed grid. Never resize the emulator from here.
                viewRef?.post { bridgeRef.value?.syncBackendSize() }
            },
    )

    // Scroll diagnostics overlay (debug toggle; state only, never content).
    // Ticks on every frame + scroll so the numbers stay live while dragging.
    if (state.settings.showScrollDiagnostics) {
        var diagTick by remember { mutableStateOf(0) }
        LaunchedEffect(bridge, backendState) {
            // Recompose the overlay whenever frames flow: poll the view at
            // 4Hz while visible (cheap getters, no allocation).
            while (true) {
                kotlinx.coroutines.delay(250)
                diagTick++
            }
        }
        @Suppress("UNUSED_EXPRESSION")
        diagTick
        val attached = backendState as? BackendState.Attached
        val diagText = remember(diagTick, backendState, hostRef) {
            val v = hostRef
            buildString {
                append("hist=")
                append(v?.snapshotHistorySize ?: -1)
                append(" alt=")
                append(v?.snapshotUsingAlt ?: false)
                append(" mse=")
                append(if (v?.mouseTracking == true) 1 else 0)
                append(" top=")
                append(v?.currentTopRow ?: 0)
                append(" ev=")
                append(v?.scrollEvents ?: 0)
                append(" rows=")
                append(v?.scrolledRows ?: 0)
                append(" rem=")
                append(v?.remoteScrolls ?: 0)
                if (v?.remoteScrollHost == true) append("(R)")
                if (attached != null) {
                    append(" pre=")
                    append(attached.historyRows)
                    if (attached.historyTruncated) append("+")
                    attached.historyError?.let { append(" ERR:").append(it.take(24)) }
                } else {
                    append(" pre=?")
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            ScrollDiagnosticsOverlay(text = diagText, visible = true)
        }
    }

    DisposableEffect(backend) {
        onDispose {
            // The bridge release suspends until the release frame reaches the
            // backend; run it on a fresh scope with a timeout, never on a scope
            // that is being torn down with us. Read from bridgeRef, NOT bridge:
            // remember(backend) has already reset by onDispose time.
            val toRelease = bridgeRef.value
            bridgeRef.value = null
            if (toRelease != null) {
                val releaser = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                releaser.launch {
                    try {
                        withTimeout(5_000) { toRelease.release() }
                    } catch (_: Exception) {
                    } finally {
                        releaser.cancel()
                    }
                }
            }
        }
    }

    // BEL -> haptic/audio feedback. Gated on the bellVibration setting; without
    // a collector the bridge counter increments into the void.
    if (settings.bellVibration || settings.hapticFeedback) {
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        LaunchedEffect(bridge) {
            val b = bridge ?: return@LaunchedEffect
            var last = 0L
            b.bell.collect { count ->
                if (last != 0L && count != last) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                }
                last = count
            }
        }
    }

    // Terminal bottom edge: the Box weight above shrinks with imePadding, so
    // the grid's last row always sits on the input panel, never behind it.
}

@Composable
private fun BottomInputPanel(
    viewModel: TerminalViewModel,
    state: TerminalUiState,
    onSendBytes: (ByteArray) -> Unit,
    onSendText: (String) -> Unit,
    onExtraKey: (dev.herdr.mobile.terminal.view.TerminalKeyEncoder.Key) -> Unit,
    onPageChange: (InputPanelPage) -> Unit,
    onSummonKeyboard: () -> Unit,
    onHideKeyboard: () -> Unit,
) {
    val settings = state.settings
    val pages = buildList {
        if (settings.extraKeysEnabled) add(InputPanelPage.EXTRA_KEYS)
        if (settings.cjkInputEnabled) add(InputPanelPage.CJK_INPUT)
    }
    if (pages.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        return
    }
    val pagerState = rememberPagerState(
        initialPage = pages.indexOf(state.inputPage).coerceAtLeast(0),
        pageCount = { pages.size },
    )
    // When a panel is disabled in Settings, pages shrinks: clamp the pager or
    // pages[currentPage] throws IndexOutOfBounds on a stale page 1.
    LaunchedEffect(pages.size) {
        if (pagerState.currentPage >= pages.size) {
            pagerState.scrollToPage(0)
        }
    }
    LaunchedEffect(pagerState.currentPage, pages.size) {
        pages.getOrNull(pagerState.currentPage)?.let { onPageChange(it) }
    }
    // Leaving the CJK page hides the IME (it was bound to the hidden field)
    // and returns focus to the terminal. Never shows anything: all IME
    // appearances are explicit user taps.
    var previousPage by remember { mutableStateOf(pagerState.currentPage) }
    LaunchedEffect(pagerState.currentPage) {
        val prev = pages.getOrNull(previousPage)
        val cur = pages.getOrNull(pagerState.currentPage)
        previousPage = pagerState.currentPage
        if (prev == InputPanelPage.CJK_INPUT && cur == InputPanelPage.EXTRA_KEYS) {
            onHideKeyboard()
        }
    }
    Surface(
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (pages.size > 1) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { index ->
                    when (pages[index]) {
                        InputPanelPage.EXTRA_KEYS -> ExtraKeysPanel(
                            onKey = onExtraKey,
                            armed = state.stickyModifiers,
                            haptic = settings.hapticFeedback,
                        )

                        InputPanelPage.CJK_INPUT -> {
                            val tabId = state.target?.tabId
                            val draft by viewModel.cjkDraft(tabId).collectAsStateWithLifecycle()
                            CjkInputPanel(
                                text = draft,
                                onTextChange = { viewModel.setCjkDraft(tabId, it) },
                                onSend = {
                                    onSendText(it)
                                    viewModel.clearCjkDraft(tabId)
                                },
                                onBackspace = { onSendBytes(byteArrayOf(0x7F)) },
                                onSummonKeyboard = onSummonKeyboard,
                            )
                        }
                    }
                }
                // Dots double as page buttons: with the keyboard up, the CJK
                // field eats horizontal drags, so swiping back can be
                // impossible — tapping a dot always pages.
                val dotScope = rememberCoroutineScope()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pages.forEachIndexed { index, _ ->
                        // 32dp hit box around the 5–7dp dot (finger target).
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .clickable {
                                    dotScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                },
                        ) {
                            StatusDot(
                                status = state.tab?.status
                                    ?: state.workspace?.status
                                    ?: dev.herdr.mobile.core.model.AgentStatus.UNKNOWN,
                                diameter = if (index == pagerState.currentPage) 7.dp else 5.dp,
                                describe = false,
                            )
                        }
                    }
                }
            } else {
                when (pages.first()) {
                    InputPanelPage.EXTRA_KEYS -> ExtraKeysPanel(
                        onKey = onExtraKey,
                        armed = state.stickyModifiers,
                        haptic = settings.hapticFeedback,
                    )

                    InputPanelPage.CJK_INPUT -> {
                        val tabId = state.target?.tabId
                        val draft by viewModel.cjkDraft(tabId).collectAsStateWithLifecycle()
                        CjkInputPanel(
                            text = draft,
                            onTextChange = { viewModel.setCjkDraft(tabId, it) },
                            onSend = {
                                onSendText(it)
                                viewModel.clearCjkDraft(tabId)
                            },
                            onBackspace = { onSendBytes(byteArrayOf(0x7F)) },
                            onSummonKeyboard = onSummonKeyboard,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Grid for a view size + text size, computed EXACTLY the way Termux's own
 * updateSize does (monospace "X" advance, ceiled font spacing): the attach
 * geometry (and the daemon's prelude blank-fill) must match the view's grid
 * or history banking falls short. Same 20x5 clamp as the old view.
 */
private fun gridFor(
    textSizePx: Int,
    widthPx: Int,
    heightPx: Int,
    familyName: String = "monospace",
): Pair<Int, Int> {
    if (textSizePx <= 0 || widthPx <= 0 || heightPx <= 0) return 80 to 24
    val paint = android.graphics.Paint().apply {
        textSize = textSizePx.toFloat()
        typeface = android.graphics.Typeface.create(familyName, android.graphics.Typeface.NORMAL)
    }
    val cellWidth = paint.measureText("X").toInt().coerceAtLeast(1)
    val cellHeight = kotlin.math.ceil(paint.fontSpacing).toInt().coerceAtLeast(1)
    return maxOf(20, widthPx / cellWidth) to
        maxOf(5, heightPx / cellHeight)
}
