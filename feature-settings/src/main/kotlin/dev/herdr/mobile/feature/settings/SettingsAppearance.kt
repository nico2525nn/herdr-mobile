package dev.herdr.mobile.feature.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.herdr.mobile.core.designsystem.SettingsRow
import dev.herdr.mobile.core.designsystem.SettingsSection
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.CursorStyle
import dev.herdr.mobile.core.model.TerminalColorScheme
import dev.herdr.mobile.core.model.TerminalFont
import dev.herdr.mobile.core.model.ThemeMode

internal fun LazyListScope.settingsAppearanceSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
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
                options = listOf(TerminalFont.MONOSPACE to TerminalFont.MONOSPACE.displayName),
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
}
