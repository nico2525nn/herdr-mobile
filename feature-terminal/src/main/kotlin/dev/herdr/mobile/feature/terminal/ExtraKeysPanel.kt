package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextOverflow
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
        val bytes = TerminalKeyEncoder.withModifier(latched, key)
        if (bytes != null) {
            onSend(bytes)
            latched = null
        }
        // Null = meaningless combination or unimplemented key path: keep the latch
        // so the user can pick a working key instead of losing the modifier.
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (TerminalKeyEncoder.DEFAULT_LAYOUT + listOf(TerminalKeyEncoder.LETTER_ROW)).forEach { row ->
            // The letter row only helps with a latched modifier; without one it
            // duplicates nothing (letters are typed on the keyboard instead).
            if (row === TerminalKeyEncoder.LETTER_ROW && latched == null) return@forEach
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
                        shape = ButtonDefaults.filledTonalShape,
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
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
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
