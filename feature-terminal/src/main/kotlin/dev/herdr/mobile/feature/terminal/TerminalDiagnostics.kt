package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.herdr.mobile.terminal.view.BackendState
import dev.herdr.mobile.terminal.view.TermuxTerminalHost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Everything the scroll overlay shows. A plain value so the formatting stays
 * testable without a view; the Compose wrapper only samples the host.
 */
internal data class ScrollDiagnosticsSnapshot(
    val historyRows: Int = -1,
    val altActive: Boolean = false,
    val mouseTracking: Boolean = false,
    val topRow: Int = 0,
    val scrollEvents: Long = 0,
    val scrolledRows: Long = 0,
    val remoteScrolls: Long = 0,
    val remoteEffective: Boolean = false,
    /** Null when the backend has not attached (prelude unknown). */
    val attachedHistoryRows: Int? = null,
    val attachedHistoryTruncated: Boolean = false,
    val attachedHistoryError: String? = null,
)

internal fun renderScrollDiagnostics(s: ScrollDiagnosticsSnapshot): String = buildString {
    append("hist=").append(s.historyRows)
    append(" alt=").append(s.altActive)
    append(" mse=").append(if (s.mouseTracking) 1 else 0)
    append(" top=").append(s.topRow)
    append(" ev=").append(s.scrollEvents)
    append(" rows=").append(s.scrolledRows)
    append(" rem=").append(s.remoteScrolls)
    if (s.remoteEffective) append("(R)")
    if (s.attachedHistoryRows != null) {
        append(" pre=").append(s.attachedHistoryRows)
        if (s.attachedHistoryTruncated) append("+")
        s.attachedHistoryError?.let { append(" ERR:").append(it.take(24)) }
    } else {
        append(" pre=?")
    }
}

/**
 * Debug overlay (settings toggle): routing counters only, never content.
 * Ticks at 4Hz while visible so the numbers stay live during a drag.
 */
@Composable
internal fun TerminalDiagnostics(
    host: TermuxTerminalHost?,
    backendState: BackendState,
    visible: Boolean,
) {
    if (!visible) return
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(backendState) {
        while (true) {
            kotlinx.coroutines.delay(250)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick
    val attached = backendState as? BackendState.Attached
    val text = remember(tick, backendState, host) {
        renderScrollDiagnostics(
            ScrollDiagnosticsSnapshot(
                historyRows = host?.snapshotHistorySize ?: -1,
                altActive = host?.snapshotUsingAlt ?: false,
                mouseTracking = host?.mouseTracking == true,
                topRow = host?.currentTopRow ?: 0,
                scrollEvents = host?.scrollEvents ?: 0,
                scrolledRows = host?.scrolledRows ?: 0,
                remoteScrolls = host?.remoteScrolls ?: 0,
                remoteEffective = host?.remoteEffective == true,
                attachedHistoryRows = attached?.historyRows,
                attachedHistoryTruncated = attached?.historyTruncated ?: false,
                attachedHistoryError = attached?.historyError,
            ),
        )
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        ScrollDiagnosticsOverlay(text = text, visible = true)
    }
}
