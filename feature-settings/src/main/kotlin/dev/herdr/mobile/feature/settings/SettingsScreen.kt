package dev.herdr.mobile.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.core.designsystem.SettingsRow
import dev.herdr.mobile.core.designsystem.SettingsSection
import dev.herdr.mobile.core.model.ConnectionTestResult
import dev.herdr.mobile.core.model.CursorStyle
import dev.herdr.mobile.core.model.FailureKind
import dev.herdr.mobile.core.model.HostProfile
import dev.herdr.mobile.core.model.SwipeBehavior
import dev.herdr.mobile.core.model.TerminalColorScheme
import dev.herdr.mobile.core.model.TerminalFont
import dev.herdr.mobile.core.model.ThemeMode
import dev.herdr.mobile.core.model.TransportMode

/**
 * M3 grouped-list Settings: Appearance, Connection, Terminal, Notifications.
 *
 * Secrets never appear here: private keys and tokens are referenced by label only and
 * edited through import dialogs that write straight to the keystore-backed store.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = ui.settings
    var editingProfile by remember { mutableStateOf<HostProfile?>(null) }
    var showAddProfile by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "title") {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        item(key = "appearance") {
            SettingsSection(title = "Appearance") {
                EnumRow(
                    headline = "Theme",
                    supporting = "App theme",
                    value = settings.themeMode.displayName(),
                    options = ThemeMode.entries.map { it to it.displayName() },
                    onSelect = { viewModel.setThemeMode(it) },
                    showDivider = true,
                )
                SettingsRow(
                    headline = "Dynamic color",
                    supporting = "Follow the system palette",
                    showDivider = true,
                    trailing = {
                        Switch(
                            checked = settings.dynamicColor,
                            onCheckedChange = { viewModel.setDynamicColor(it) },
                        )
                    },
                )
                EnumRow(
                    headline = "Terminal theme",
                    supporting = "ANSI color scheme",
                    value = settings.terminalColorScheme.displayName,
                    options = TerminalColorScheme.entries.map { it to it.displayName },
                    onSelect = { viewModel.setTerminalScheme(it) },
                    showDivider = true,
                )
                EnumRow(
                    headline = "Terminal font",
                    supporting = "Typeface and text size (${settings.terminalFontSizeSp}sp)",
                    value = settings.terminalFont.displayName,
                    options = TerminalFont.entries.map { it to it.displayName },
                    onSelect = { viewModel.setTerminalFont(it) },
                    showDivider = true,
                )
                SettingsRow(
                    headline = "Font size",
                    supporting = "${settings.terminalFontSizeSp} sp · tap to cycle 10 → 12 → 14 → 16",
                    showDivider = true,
                    trailing = {
                        TextButton(onClick = {
                            val next = when (settings.terminalFontSizeSp) {
                                in Int.MIN_VALUE..10 -> 12
                                in 11..12 -> 14
                                in 13..14 -> 16
                                else -> 10
                            }
                            viewModel.setFontSize(next)
                        }) {
                            Text("${settings.terminalFontSizeSp}sp")
                        }
                    },
                )
                EnumRow(
                    headline = "Cursor style",
                    supporting = "Terminal cursor shape",
                    value = settings.cursorStyle.displayName,
                    options = CursorStyle.entries.map { it to it.displayName },
                    onSelect = { viewModel.setCursorStyle(it) },
                    showDivider = false,
                )
            }
        }
        item(key = "connection") {
            SettingsSection(title = "Connection") {
                EnumRow(
                    headline = "Transport",
                    supporting = if (settings.transportMode == TransportMode.SSH) {
                        "SSH tunnel to the daemon"
                    } else {
                        "Direct tailnet connection"
                    },
                    value = settings.transportMode.displayName,
                    options = TransportMode.entries.map { it to it.displayName },
                    onSelect = { viewModel.setTransportMode(it) },
                    showDivider = true,
                )
                if (settings.transportMode == TransportMode.DIRECT) {
                    var editingUrl by remember(settings.directUrl) {
                        mutableStateOf(settings.directUrl)
                    }
                    SettingsRow(
                        headline = "Daemon URL",
                        supporting = "Direct origin, e.g. http://100.x.y.z:8765",
                        showDivider = true,
                        trailing = {
                            TextButton(onClick = { viewModel.setDirectUrl(editingUrl) }) {
                                Text("Apply")
                            }
                        },
                    )
                    OutlinedTextField(
                        value = editingUrl,
                        onValueChange = { editingUrl = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        label = { Text("Daemon URL") },
                        singleLine = true,
                    )
                }
                settings.hostProfiles.forEach { profile ->
                    val active = profile.id == settings.activeProfileId
                    SettingsRow(
                        headline = "${if (active) "● " else ""}${profile.label}",
                        supporting = buildString {
                            append(profile.userAtHost)
                            append(" · auth: ")
                            append(
                                when {
                                    profile.passwordAlias != null -> "password saved"
                                    profile.privateKeyLabel != null -> "key ${profile.privateKeyLabel}"
                                    else -> "no credentials"
                                },
                            )
                        },
                        showDivider = true,
                        trailing = {
                            Row {
                                RadioButton(
                                    selected = active,
                                    onClick = { viewModel.setActiveProfile(profile.id) },
                                )
                                IconButton(onClick = { editingProfile = profile }) {
                                    Text("Edit")
                                }
                                IconButton(onClick = { viewModel.removeProfile(profile.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove ${profile.label}")
                                }
                            }
                        },
                    )
                }
                SettingsRow(
                    headline = "Add host profile",
                    supporting = "SSH host, key, daemon endpoint",
                    showDivider = true,
                    trailing = {
                        IconButton(onClick = { showAddProfile = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add host profile")
                        }
                    },
                )
                SettingsRow(
                    headline = "Test connection",
                    supporting = testSummary(ui.testResult),
                    showDivider = false,
                    trailing = {
                        if (ui.testing) {
                            CircularProgressIndicator()
                        } else {
                            TextButton(onClick = { viewModel.testConnection() }) {
                                Text("Test")
                            }
                        }
                    },
                )
            }
        }
        item(key = "terminal") {
            SettingsSection(title = "Terminal") {
                SettingsRow(
                    headline = "Extra keys",
                    supporting = "Two-row key bar above the panel",
                    showDivider = true,
                    trailing = {
                        Switch(
                            checked = settings.extraKeysEnabled,
                            onCheckedChange = { viewModel.setExtraKeysEnabled(it) },
                        )
                    },
                )
                SettingsRow(
                    headline = "CJK pre-input",
                    supporting = "Compose in a text field, send on commit",
                    showDivider = true,
                    trailing = {
                        Switch(
                            checked = settings.cjkInputEnabled,
                            onCheckedChange = { viewModel.setCjkInputEnabled(it) },
                        )
                    },
                )
                EnumRow(
                    headline = "Panel swipe",
                    supporting = "Horizontal swipe inside the panel",
                    value = settings.swipeBehavior.displayName,
                    options = SwipeBehavior.entries.map { it to it.displayName },
                    onSelect = { viewModel.setSwipeBehavior(it) },
                    showDivider = true,
                )
                SettingsRow(
                    headline = "Haptic feedback",
                    supporting = "Vibrate on extra-key taps",
                    showDivider = true,
                    trailing = {
                        Switch(
                            checked = settings.hapticFeedback,
                            onCheckedChange = { viewModel.setHaptic(it) },
                        )
                    },
                )
                SettingsRow(
                    headline = "Bell vibration",
                    supporting = "Vibrate on terminal bell",
                    showDivider = false,
                    trailing = {
                        Switch(
                            checked = settings.bellVibration,
                            onCheckedChange = { viewModel.setBellVibration(it) },
                        )
                    },
                )
            }
        }
        item(key = "notifications") {
            SettingsSection(title = "Notifications") {
                SettingsRow(
                    headline = "Done",
                    supporting = "Agent finished with new results",
                    showDivider = true,
                    trailing = {
                        Switch(
                            checked = settings.notifyDone,
                            onCheckedChange = { viewModel.setNotifyDone(it) },
                        )
                    },
                )
                SettingsRow(
                    headline = "Blocked",
                    supporting = "Agent waits for input or permission",
                    showDivider = true,
                    trailing = {
                        Switch(
                            checked = settings.notifyBlocked,
                            onCheckedChange = { viewModel.setNotifyBlocked(it) },
                        )
                    },
                )
                SettingsRow(
                    headline = "Failed",
                    supporting = "Agent exited or errored",
                    showDivider = false,
                    trailing = {
                        Switch(
                            checked = settings.notifyFailed,
                            onCheckedChange = { viewModel.setNotifyFailed(it) },
                        )
                    },
                )
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(16.dp)) }
    }

    editingProfile?.let { profile ->
        ProfileEditorDialog(
            profile = profile,
            onDismiss = { editingProfile = null },
            onSave = { updated, password, token, keyPem, keyLabel ->
                viewModel.saveProfileWithSecrets(updated, password, token, keyPem, keyLabel)
                editingProfile = null
            },
        )
    }
    if (showAddProfile) {
        ProfileEditorDialog(
            profile = HostProfile(
                id = viewModel.newProfileId(),
                label = "New host",
                host = "",
                username = "",
            ),
            onDismiss = { showAddProfile = false },
            onSave = { created, password, token, keyPem, keyLabel ->
                viewModel.saveProfileWithSecrets(created, password, token, keyPem, keyLabel)
                showAddProfile = false
            },
        )
    }
    ui.pendingHostKey?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissHostKey() },
            title = { Text("Unknown host key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pending.host} presented a key this phone has never seen.")
                    Text(
                        pending.fingerprint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Compare it with the server's key (ssh-keyscan, Termux, or your admin), then approve. A changed key on a known host is rejected outright and never shows this dialog.")
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.approveHostKey() }) {
                    Text("Approve & retry")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissHostKey() }) {
                    Text("Reject")
                }
            },
        )
    }
}

private fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun testSummary(result: ConnectionTestResult?): String = when (result) {
    null -> "DNS, auth, host key, daemon and protocol failures are distinguished"
    is ConnectionTestResult.Success ->
        "OK in ${result.latencyMs} ms · herdr ${result.herdr.version ?: "?"} · ${result.workspaceCount} workspaces"

    is ConnectionTestResult.Failure -> when (result.kind) {
        FailureKind.DNS -> "DNS failure: ${result.detail}"
        FailureKind.AUTH -> "Auth failure: ${result.detail}"
        FailureKind.HOST_KEY_MISMATCH -> "Host key mismatch: ${result.detail}"
        FailureKind.HOST_KEY_UNKNOWN -> "Unknown host key: ${result.detail}"
        FailureKind.UNREACHABLE -> "Unreachable: ${result.detail}"
        FailureKind.DAEMON_UNAVAILABLE -> "Daemon unavailable: ${result.detail}"
        FailureKind.PROTOCOL_MISMATCH -> "Protocol mismatch: ${result.detail}"
        FailureKind.UNKNOWN -> "Failed: ${result.detail}"
    }

    is ConnectionTestResult.UnknownHostKey -> "Unknown host key: ${result.detail}"
}

@Composable
private fun <T> EnumRow(
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

@Composable
private fun ProfileEditorDialog(
    profile: HostProfile,
    onDismiss: () -> Unit,
    onSave: (HostProfile, password: String?, token: String?, keyPem: String?, keyLabel: String?) -> Unit,
) {
    var label by remember { mutableStateOf(profile.label) }
    var host by remember { mutableStateOf(profile.host) }
    var port by remember { mutableStateOf(profile.port.toString()) }
    var username by remember { mutableStateOf(profile.username) }
    var daemonPort by remember { mutableStateOf(profile.daemonPort.toString()) }
    var password by remember { mutableStateOf("") }
    var passwordTouched by remember { mutableStateOf(false) }
    val hasPassword = profile.passwordAlias != null
    var token by remember { mutableStateOf("") }
    var keyPem by remember { mutableStateOf("") }
    val hasKey = profile.privateKeyAlias != null

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
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("SSH port") })
                }
                item(key = "user") {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") })
                }
                item(key = "dport") {
                    OutlinedTextField(
                        value = daemonPort,
                        onValueChange = { daemonPort = it },
                        label = { Text("Daemon port on host") },
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
            TextButton(onClick = {
                // Untouched password field = leave the stored secret alone.
                // Editing label/host/ports must never wipe it.
                val pw = if (passwordTouched) password else null
                val tok = token.ifBlank { null }
                val pem = keyPem.ifBlank { null }
                onSave(
                    profile.copy(
                        label = label.ifBlank { profile.label },
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 22,
                        username = username.trim(),
                        daemonPort = daemonPort.toIntOrNull() ?: 8765,
                    ),
                    pw,
                    tok,
                    pem,
                    label.ifBlank { profile.label },
                )
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
