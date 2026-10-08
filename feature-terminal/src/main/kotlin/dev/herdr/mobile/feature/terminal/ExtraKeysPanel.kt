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
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.terminal.view.TerminalKeyEncoder

/**
 * Page A of the bottom input panel: the Termux default two-row extra-keys layout.
 *
 * CTRL/ALT are sticky multi-select toggles owned by the ViewModel: armed modifiers
 * highlight here AND apply to the next soft-keyboard char (keyboard Ctrl+C works,
 * the latch clears on use). Everything sends raw bytes — the daemon forwards them
 * verbatim.
 */
@Composable
fun ExtraKeysPanel(
    onKey: (TerminalKeyEncoder.Key) -> Unit,
    armed: Set<TerminalKeyEncoder.Modifier>,
    haptic: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    fun press(key: TerminalKeyEncoder.Key) {
        if (haptic) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onKey(key)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (TerminalKeyEncoder.DEFAULT_LAYOUT + listOf(TerminalKeyEncoder.LETTER_ROW)).forEach { row ->
            // The letter row only helps with an armed modifier; without one it
            // duplicates nothing (letters are typed on the keyboard instead).
            if (row === TerminalKeyEncoder.LETTER_ROW && armed.isEmpty()) return@forEach
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    val isArmed = key.modifier != null && key.modifier in armed
                    FilledTonalButton(
                        onClick = { press(key) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = ButtonDefaults.filledTonalShape,
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                        colors = if (isArmed) {
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
