package dev.herdr.mobile.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.core.designsystem.SettingsRow

@Composable
internal fun <T> EnumRow(
    headline: String,
    supporting: String?,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    showDivider: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    SettingsRow(
        headline = headline,
        supporting = supporting,
        showDivider = showDivider,
        trailing = {
            TextButton(onClick = { open = true }) {
                Text(value)
            }
        },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(headline) },
            text = {
                Column {
                    options.forEach { (option, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(option)
                                    open = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = label == value,
                                onClick = {
                                    onSelect(option)
                                    open = false
                                },
                            )
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Close") }
            },
        )
    }
}
