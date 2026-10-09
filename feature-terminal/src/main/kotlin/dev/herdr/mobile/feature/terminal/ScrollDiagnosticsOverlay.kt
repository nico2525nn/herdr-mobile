package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Scroll-state diagnostics. Visible only when [visible] (debug builds toggle
 * it from Settings > Terminal > Diagnostics; release builds never show it).
 *
 * State only, never content: history size, alt flag, viewport offset,
 * gesture/scroll counters, prelude outcome. No terminal text is ever logged
 * or displayed here.
 */
@Composable
fun ScrollDiagnosticsOverlay(
    text: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible || text.isEmpty()) return
    Box(
        modifier = modifier
            .padding(8.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
        )
    }
}
