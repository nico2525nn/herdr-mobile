package dev.herdr.mobile.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.core.designsystem.LocalUiCopy
import dev.herdr.mobile.core.model.HostProfile

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
    val error = ui.error
    val settings = ui.settings
    // Rotation must not close the editor or discard its saveable fields.
    var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingProfile = editingProfileId?.let { id -> settings.hostProfiles.firstOrNull { it.id == id } }
    var showAddProfile by rememberSaveable { mutableStateOf(false) }

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
        error?.let { message ->
            item(key = "error") {
                Text(
                    text = message.let { LocalUiCopy.current.render(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
        settingsAppearanceSection(settings, viewModel)
        settingsConnectionSection(
            settings = settings,
            testResult = ui.testResult,
            testing = ui.testing,
            onEditProfile = { editingProfileId = it.id },
            onAddProfile = { showAddProfile = true },
            viewModel = viewModel,
        )
        settingsTerminalSection(settings, viewModel)
        settingsNotificationsSection(settings, viewModel)
        item(key = "bottom") { Spacer(Modifier.height(16.dp)) }
    }

    editingProfile?.let { profile ->
            ProfileEditorDialog(
            profile = profile,
            onDismiss = { editingProfileId = null },
            onSave = { updated, password, token, keyPem, keyLabel ->
                viewModel.saveProfileWithSecrets(updated, password, token, keyPem, keyLabel)
                editingProfileId = null
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
