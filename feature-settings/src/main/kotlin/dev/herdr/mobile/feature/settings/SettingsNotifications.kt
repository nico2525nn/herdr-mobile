package dev.herdr.mobile.feature.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Switch
import dev.herdr.mobile.core.designsystem.SettingsRow
import dev.herdr.mobile.core.designsystem.SettingsSection
import dev.herdr.mobile.core.model.AppSettings

internal fun LazyListScope.settingsNotificationsSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    item(key = "notifications") {
        SettingsSection(title = "Notifications") {
            NotificationGrantRow()
            BatteryExemptionRow()
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
                headline = "Background monitoring",
                supporting = "Keep watching when the app is closed (persistent status)",
                showDivider = false,
                trailing = {
                    Switch(
                        checked = settings.backgroundMonitoring,
                        onCheckedChange = { viewModel.setBackgroundMonitoring(it) },
                    )
                },
            )
        }
    }
}
