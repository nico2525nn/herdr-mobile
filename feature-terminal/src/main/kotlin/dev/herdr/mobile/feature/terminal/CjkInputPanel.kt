package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Page B of the bottom input panel: CJK pre-composition.
 *
 * The Android IME composes inside this ordinary TextField; nothing is sent until the text is
 * committed (keyboard's Send action), at which point the whole string goes to the
 * terminal as UTF-8. Composing text never trickles into the remote PTY mid-conversion.
 *
 * The trailing button summons the terminal's direct-typing keyboard instead of
 * sending: this panel never auto-shows anything, and neither do taps or page
 * swipes — every IME appearance is an explicit user tap (Termux behavior).
 *
 * [text]/[onTextChange] are hoisted to the ViewModel's per-tab draft store: swiping pages
 * or switching tabs keeps half-composed input. Commit clears the draft.
 *
 * Commit sends text verbatim with NO appended terminator: the field is single-line so the
 * keyboard's action key fires commit (Enter = send). A bare commit with empty text sends
 * CR ("\r", the Enter key) — NOT LF: raw-mode apps (codex, vim) read LF as linefeed
 * (cursor down a row), while CR is Enter everywhere, matching the direct-IME path.
 *
 * Backspace deletes one local char when the draft is non-empty, else sends DEL to the
 * remote line (erase a char the terminal already echoes). Paste comes from the IME/system
 * long-press menu, not a dedicated button: a paste button would either discard the
 * in-progress composition or need merge semantics.
 */
@Composable
fun CjkInputPanel(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onBackspace: () -> Unit,
    onSummonKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun commit() {
        // Empty commit = bare Enter key (CR). Non-empty = verbatim text, no
        // terminator: the user presses action again to run it.
        onSend(text.ifEmpty { "\r" })
        onTextChange("")
        // Deliberately NOT clearing focus: continuous CJK input must keep the
        // keyboard up. The user dismisses it with system back when done.
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                if (text.isNotEmpty()) {
                    // Delete the last code point (surrogate-pair safe).
                    val cut = text.offsetByCodePoints(text.length, -1)
                    onTextChange(text.substring(0, cut))
                } else {
                    onBackspace()
                }
            },
        ) {
            Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Backspace")
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type, convert, then send") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Send,
                autoCorrectEnabled = true,
            ),
            keyboardActions = KeyboardActions(onSend = { commit() }),
            // Quiet focus ring: the default primary-colored outline flashes
            // bright on every tap (the reported annoyance). Unfocused and
            // focused borders stay the same subdued outlineVariant; the cursor
            // + keyboard appearance already signal focus.
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { onTextChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear input")
                    }
                }
            },
        )
        IconButton(onClick = onSummonKeyboard) {
            Icon(Icons.Filled.Keyboard, contentDescription = "Terminal keyboard")
        }
    }
}
