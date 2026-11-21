package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.mobile.core.designsystem.UiMessage
import dev.herdr.mobile.core.model.ConnectionState

/**
 * The region between the rails and the input panel when no terminal is
 * attached. First load is a single left-aligned mono line in the text column,
 * not a spinner or an illustrated centrepiece.
 */
@Composable
internal fun TerminalPlaceholder(
    connection: ConnectionState,
    hasTarget: Boolean,
    label: String?,
    error: String?,
    failure: UiMessage?,
    onRetry: (() -> Unit)?,
    onBack: () -> Unit,
) {
    val failureCopy = failureCopy(failure)
    when {
        failureCopy != null -> TerminalFailure(
            title = failureCopy.first,
            detail = failureCopy.second,
            onRetry = onRetry,
            onBack = onBack,
        )
        connection == ConnectionState.FAILED -> TerminalFailure(
            title = "Can't connect",
            detail = error,
            onRetry = onRetry,
            onBack = onBack,
        )
        !hasTarget -> MonoLine("No pane selected")
        else -> MonoLine(
            when (connection) {
                ConnectionState.CONNECTING ->
                    if (label != null) "Connecting to $label…" else "Connecting…"
                ConnectionState.RECONNECTING -> "Reconnecting…"
                ConnectionState.STALE -> "Refreshing…"
                else -> if (label != null) "Opening $label…" else "Opening terminal…"
            },
        )
    }
}

@Composable
internal fun TerminalErrorBody(
    message: String,
    onRetry: (() -> Unit)?,
    onBack: (() -> Unit)?,
    title: String = "Can't attach",
) {
    TerminalFailure(
        title = title,
        detail = message,
        onRetry = onRetry,
        onBack = onBack,
    )
}

private fun failureCopy(failure: UiMessage?): Pair<String, String?>? = when (failure) {
    is UiMessage.AttachFailed -> "Can't attach" to failure.detail
    is UiMessage.ReconnectTimedOut -> "Reconnect timed out" to "The daemon did not answer."
    is UiMessage.StreamChanged -> "Connection changed" to "The terminal stream was replaced."
    else -> null
}

@Composable
private fun MonoLine(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        Text(
            text = text,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Attach failure is the only state allowed to replace terminal content.
 * Left-aligned and narrow; cause in one clause; actions side by side.
 */
@Composable
private fun TerminalFailure(
    title: String,
    detail: String?,
    onRetry: (() -> Unit)?,
    onBack: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp, top = 28.dp, end = 24.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onRetry != null) {
                    Button(onClick = onRetry) {
                        Text("Retry")
                    }
                }
                if (onBack != null) {
                    TextButton(onClick = onBack) {
                        Text("Back to tabs")
                    }
                }
            }
        }
    }
}
