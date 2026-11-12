package dev.herdr.mobile.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.core.model.HostProfile

@Composable
internal fun ProfileEditorDialog(
    profile: HostProfile,
    onDismiss: () -> Unit,
    onSave: (HostProfile, password: String?, token: String?, keyPem: String?, keyLabel: String?) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf(profile.label) }
    var host by rememberSaveable { mutableStateOf(profile.host) }
    var port by rememberSaveable { mutableStateOf(profile.port.toString()) }
    var username by rememberSaveable { mutableStateOf(profile.username) }
    var daemonPort by rememberSaveable { mutableStateOf(profile.daemonPort.toString()) }
    var password by remember { mutableStateOf("") }
    var passwordTouched by remember { mutableStateOf(false) }
    val hasPassword = profile.passwordAlias != null
    var token by remember { mutableStateOf("") }
    var keyPem by remember { mutableStateOf("") }
    val hasKey = profile.privateKeyAlias != null
    val portValue = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val daemonPortValue = daemonPort.toIntOrNull()?.takeIf { it in 1..65535 }
    val canSave = host.isNotBlank() && username.isNotBlank() &&
        portValue != null && daemonPortValue != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Host profile") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "label") {
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") })
                }
                item(key = "host") {
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Hostname") })
                }
                item(key = "port") {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("SSH port") },
                        isError = portValue == null,
                        supportingText = if (portValue == null) {
                            { Text("1–65535") }
                        } else {
                            null
                        },
                    )
                }
                item(key = "user") {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") })
                }
                item(key = "dport") {
                    OutlinedTextField(
                        value = daemonPort,
                        onValueChange = { daemonPort = it },
                        label = { Text("Daemon port on host") },
                        isError = daemonPortValue == null,
                        supportingText = if (daemonPortValue == null) {
                            { Text("1–65535") }
                        } else {
                            null
                        },
                    )
                }
                item(key = "password") {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; passwordTouched = true },
                        label = { Text("SSH password") },
                        placeholder = {
                            Text(if (hasPassword) "Saved — type to replace" else "Required for password auth")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        singleLine = true,
                    )
                }
                item(key = "token") {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Daemon bearer token (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        singleLine = true,
                    )
                }
                item(key = "key") {
                    // Key-only sshd accounts cannot be provisioned without this:
                    // paste the PEM here, it lands in the keystore on save.
                    // Untouched = keep the stored key (if any).
                    OutlinedTextField(
                        value = keyPem,
                        onValueChange = { keyPem = it },
                        label = { Text("SSH private key PEM (optional)") },
                        placeholder = {
                            Text(if (hasKey) "Key ${profile.privateKeyLabel ?: "saved"} — paste to replace" else "Paste PEM for key auth")
                        },
                        singleLine = false,
                        maxLines = 3,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    // Untouched password field = leave the stored secret alone.
                    // Editing label/host/ports must never wipe it.
                    val pw = if (passwordTouched) password else null
                    val tok = token.ifBlank { null }
                    val pem = keyPem.ifBlank { null }
                    onSave(
                        profile.copy(
                            label = label.ifBlank { profile.label },
                            host = host.trim(),
                            port = requireNotNull(portValue),
                            username = username.trim(),
                            daemonPort = requireNotNull(daemonPortValue),
                        ),
                        pw,
                        tok,
                        pem,
                        label.ifBlank { profile.label },
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
