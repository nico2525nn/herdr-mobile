package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.terminal.view.TerminalKeyEncoder

/**
 * Page A of the bottom input panel: the Termux default two-row extra-keys layout.
 *
 * CTRL/ALT latch for exactly one following key; the latched key is visually marked while
 * armed. Everything sends raw bytes — the daemon forwards them verbatim.
 */
@Composable
fun ExtraKeysPanel(
    onSend: (ByteArray) -> Unit,
    haptic: Boolean,
    modifier: Modifier = Modifier,
) {
    var latched by remember { mutableStateOf<TerminalKeyEncoder.Modifier?>(null) }
    val haptics = LocalHapticFeedback.current

    fun press(key: TerminalKeyEncoder.Key) {
        if (haptic) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (key.modifier != null) {
            latched = if (latched == key.modifier) null else key.modifier
            return
        }
        onSend(TerminalKeyEncoder.withModifier(latched, key))
        latched = null
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TerminalKeyEncoder.DEFAULT_LAYOUT.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    val armed = key.modifier != null && latched == key.modifier
                    FilledTonalButton(
                        onClick = { press(key) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = if (armed) {
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            ButtonDefaults.filledTonalButtonColors()
                        },
                    ) {
                        Text(
                            text = key.label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
