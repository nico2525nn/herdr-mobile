package dev.herdr.mobile.feature.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.core.designsystem.SettingsRow
import dev.herdr.mobile.core.designsystem.SettingsSection
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.BackendMode
import dev.herdr.mobile.core.model.ConnectionTestResult
import dev.herdr.mobile.core.model.HostProfile
import dev.herdr.mobile.core.model.TransportMode

internal fun LazyListScope.settingsConnectionSection(
    settings: AppSettings,
    testResult: ConnectionTestResult?,
    testing: Boolean,
    onEditProfile: (HostProfile) -> Unit,
    onAddProfile: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val activeProfile = settings.hostProfiles.firstOrNull { it.id == settings.activeProfileId }
        ?: settings.hostProfiles.firstOrNull()
    val backendMode = if (settings.transportMode == TransportMode.SSH) {
        activeProfile?.backendMode ?: BackendMode.TUI
    } else {
        settings.directBackendMode
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
            EnumRow(
                headline = "Backend",
                supporting = "Choose Herdr TUI or Herdr app (core) for this connection",
                value = backendMode.displayName,
                options = BackendMode.entries.map { it to it.displayName },
                onSelect = { viewModel.setBackendMode(it) },
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
                    // Uri disables autocorrect: without it Gboard rewrites
                    // "http://127.0.0.1:8765" mid-edit (duplicated port,
                    // emoji, phantom chars — verified on emulator).
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
            settings.hostProfiles.forEach { profile ->
                val active = profile.id == settings.activeProfileId
                SettingsRow(
                    headline = "${if (active) "● " else ""}${profile.label}",
                    supporting = buildString {
                        append(profile.userAtHost)
                        append(" · ${profile.backendMode.displayName}")
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
                            IconButton(onClick = { onEditProfile(profile) }) {
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
                    IconButton(onClick = onAddProfile) {
                        Icon(Icons.Filled.Add, contentDescription = "Add host profile")
                    }
                },
            )
            SettingsRow(
                headline = "Test connection",
                supporting = testSummary(testResult),
                showDivider = false,
                trailing = {
                    if (testing) {
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
}
