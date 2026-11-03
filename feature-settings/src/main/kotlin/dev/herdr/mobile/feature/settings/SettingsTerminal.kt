package dev.herdr.mobile.feature.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Switch
import dev.herdr.mobile.core.designsystem.SettingsRow
import dev.herdr.mobile.core.designsystem.SettingsSection
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.SwipeBehavior

internal fun LazyListScope.settingsTerminalSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
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
                showDivider = true,
                trailing = {
                    Switch(
                        checked = settings.bellVibration,
                        onCheckedChange = { viewModel.setBellVibration(it) },
                    )
                },
            )
            SettingsRow(
                headline = "Scroll diagnostics",
                supporting = "Overlay scroll state (debug, no content)",
                showDivider = false,
                trailing = {
                    Switch(
                        checked = settings.showScrollDiagnostics,
                        onCheckedChange = { viewModel.setShowScrollDiagnostics(it) },
                    )
                },
            )
        }
    }
}
