package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf

/**
 * Debug overlay (settings toggle): the engine's viewport facts, never content.
 * Ticks at 4Hz so the numbers stay live during a drag.
 */
@Composable
internal fun TerminalDiagnostics(
    controller: GhosttyTerminalController?,
    backendState: BackendState,
    visible: Boolean,
) {
    if (!visible) return
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            if (event == Lifecycle.Event.ON_STOP) resumed = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(backendState, resumed) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(250)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick
    val text = remember(tick, backendState, controller) {
        val flags = controller?.viewportState() ?: 0
        val bar = controller?.scrollbar()
        buildString {
            append("alt=")
            append(flags and GhosttyTerminalController.VIEWPORT_ALTERNATE_SCREEN != 0)
            append(" mse=")
            append(flags and GhosttyTerminalController.VIEWPORT_MOUSE_TRACKING != 0)
            if (bar != null && bar.size >= 3) {
                append(" sb=")
                append(bar[1])
                append('/')
                append(bar[0])
                append(" vp=")
                append(bar[2])
            }
            val attached = backendState as? BackendState.Attached
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
        ScrollDiagnosticsOverlay(text = text, visible = true)
    }
}
