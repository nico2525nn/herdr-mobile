package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Page B of the bottom input panel: CJK pre-composition.
 *
 * The Android IME composes inside this ordinary TextField; nothing is sent until the text is
 * committed (IME action or the send button), at which point the whole string goes to the
 * terminal as UTF-8. Composing text never trickles into the remote PTY mid-conversion.
 */
@Composable
fun CjkInputPanel(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current

    fun commit() {
        if (text.isEmpty()) return
        onSend(text)
        text = ""
        focus.clearFocus(force = true)
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
        IconButton(
            onClick = {
                val pasted = clipboard.getText()?.text
                if (!pasted.isNullOrEmpty()) {
                    onSend(pasted)
                    text = ""
                    focus.clearFocus(force = true)
                }
            },
        ) {
            Icon(Icons.Filled.ContentPaste, contentDescription = "Send clipboard to terminal")
        }
        IconButton(onClick = { commit() }, enabled = text.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to terminal")
        }
    }
}
