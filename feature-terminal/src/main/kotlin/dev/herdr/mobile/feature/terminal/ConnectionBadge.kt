package dev.herdr.mobile.feature.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.core.model.ConnectionState
import kotlinx.coroutines.delay

/** How long the link must stay down before it is worth interrupting the user. */
private const val BADGE_DELAY_MS = 1_000L

/**
 * Small floating status pill for a degraded link.
 *
 * A sub-second drop is normal (socket swap on pane switch, daemon hiccup) and
 * must not flash UI, so the badge waits [BADGE_DELAY_MS] and disappears the
 * instant the connection is usable again. It carries no layout weight: the
 * terminal keeps its full height and the pill floats over the top rows.
 */
@Composable
fun ConnectionBadge(
    connection: ConnectionState,
    modifier: Modifier = Modifier,
) {
    val label = connection.badgeLabel()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(connection) {
        if (label == null) {
            visible = false
            return@LaunchedEffect
        }
        delay(BADGE_DELAY_MS)
        visible = true
    }
    AnimatedVisibility(
        visible = visible && label != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val busy = connection != ConnectionState.FAILED
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(text = "!", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    text = label.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Null means "nothing to report": the badge stays hidden. */
private fun ConnectionState.badgeLabel(): String? = when (this) {
    ConnectionState.CONNECTED -> null
    ConnectionState.IDLE -> null
    ConnectionState.CONNECTING -> "Connecting…"
    ConnectionState.RECONNECTING -> "Reconnecting…"
    ConnectionState.STALE -> "Refreshing…"
    ConnectionState.FAILED -> "Connection lost"
}
