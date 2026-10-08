package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Page B of the bottom input panel: CJK pre-composition.
 *
 * The Android IME composes inside this ordinary TextField; nothing is sent until the text is
 * committed (IME action or the send button), at which point the whole string goes to the
 * terminal as UTF-8. Composing text never trickles into the remote PTY mid-conversion.
 *
 * Commit sends text verbatim with NO appended newline: the field is single-line so the
 * keyboard's action key fires commit (Enter = send), and a bare commit with empty text
 * sends a lone newline (= pressing Enter on an empty prompt). Appending "\n" to every
 * commit made Enter-after-typing insert a stray blank line — the reported bug.
 *
 * Paste comes from the IME/system long-press menu, not a dedicated button: a paste
 * button would either discard the in-progress composition or need merge semantics.
 */
@Composable
fun CjkInputPanel(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }

    fun commit() {
        // Empty commit = bare Enter (newline). Non-empty = verbatim text, no
        // terminator: the user presses action again (or native Enter) to run it.
        onSend(text.ifEmpty { "\n" })
        text = ""
        // Deliberately NOT clearing focus: continuous CJK input must keep the
        // keyboard up. The user dismisses it with system back when done.
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type, convert, then send") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Send,
                autoCorrectEnabled = true,
            ),
            keyboardActions = KeyboardActions(onSend = { commit() }),
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear input")
                    }
                }
            },
        )
        IconButton(onClick = { commit() }) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to terminal")
        }
    }
}
