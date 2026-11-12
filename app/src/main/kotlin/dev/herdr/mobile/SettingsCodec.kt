package dev.herdr.mobile

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.BackendMode
import dev.herdr.mobile.core.model.CursorStyle
import dev.herdr.mobile.core.model.HostProfile
import dev.herdr.mobile.core.model.SwipeBehavior
import dev.herdr.mobile.core.model.TerminalColorScheme
import dev.herdr.mobile.core.model.ScrollMode
import dev.herdr.mobile.core.model.TerminalFont
import dev.herdr.mobile.core.model.ThemeMode
import dev.herdr.mobile.core.model.TransportMode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Preference keys of the settings DataStore. These strings are on-disk names:
 * renaming one silently drops the stored value.
 */
internal object SettingsKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val TERMINAL_SCHEME = stringPreferencesKey("terminal_scheme")
    val TERMINAL_FONT = stringPreferencesKey("terminal_font")
    val FONT_SIZE = intPreferencesKey("font_size")
    val LINE_HEIGHT = floatPreferencesKey("line_height")
    val CURSOR_STYLE = stringPreferencesKey("cursor_style")
    val BOLD_BRIGHT = booleanPreferencesKey("bold_bright")
    val TRANSPORT_MODE = stringPreferencesKey("transport_mode")
    val DIRECT_URL = stringPreferencesKey("direct_url")
    val DIRECT_BACKEND = stringPreferencesKey("direct_backend")
    val ACTIVE_PROFILE = stringPreferencesKey("active_profile")
    val PROFILES = stringPreferencesKey("profiles")
    val EXTRA_KEYS = booleanPreferencesKey("extra_keys")
    val CJK_INPUT = booleanPreferencesKey("cjk_input")
    val SWIPE = stringPreferencesKey("swipe")
    val HAPTIC = booleanPreferencesKey("haptic")
    val BELL = booleanPreferencesKey("bell")
    val SCROLLBACK = intPreferencesKey("scrollback")
    val NOTIFY_DONE = booleanPreferencesKey("notify_done")
    val NOTIFY_BLOCKED = booleanPreferencesKey("notify_blocked")
    val NOTIFIED_MAP = stringPreferencesKey("notified_map")
    val SHOW_SCROLL_DIAG = booleanPreferencesKey("show_scroll_diagnostics")
    val SCROLL_MODE = stringPreferencesKey("scroll_mode")
    val BG_MONITOR = booleanPreferencesKey("bg_monitor")
}

private val codecJson = Json { ignoreUnknownKeys = true }

/**
 * Decoded profile list plus, when the stored JSON did not parse, the exact
 * bytes that must be written back so one corrupt read does not wipe hosts.
 */
internal data class StoredProfiles(
    val profiles: List<HostProfile>,
    val corruptRaw: String?,
)

internal fun decodeStoredProfiles(raw: String?): StoredProfiles {
    if (raw.isNullOrBlank()) return StoredProfiles(emptyList(), null)
    return runCatching {
        val profiles = codecJson
            .decodeFromString(ListSerializer(StoredProfile.serializer()), raw)
            .map { it.toModel() }
        StoredProfiles(profiles, null)
    }.getOrElse {
        StoredProfiles(emptyList(), raw)
    }
}

internal fun encodeProfiles(profiles: List<HostProfile>): String =
    codecJson.encodeToString(
        ListSerializer(StoredProfile.serializer()),
        profiles.map { StoredProfile.from(it) },
    )

/** AppSettings from DataStore, with the same per-field defaults as a first run. */
internal fun decodeSettings(
    prefs: Preferences,
    profiles: StoredProfiles = decodeStoredProfiles(prefs[SettingsKeys.PROFILES]),
): AppSettings = AppSettings(
    themeMode = ThemeMode.fromWire(prefs[SettingsKeys.THEME_MODE]),
    dynamicColor = prefs[SettingsKeys.DYNAMIC_COLOR] ?: true,
    terminalColorScheme = TerminalColorScheme.fromWire(prefs[SettingsKeys.TERMINAL_SCHEME]),
    terminalFont = TerminalFont.fromWire(prefs[SettingsKeys.TERMINAL_FONT]).let {
            // Only the platform monospace family is guaranteed on every device;
            // the other enum values always rendered with it, so normalise.
            if (it == TerminalFont.MONOSPACE) it else TerminalFont.MONOSPACE
        },
    terminalFontSizeSp = prefs[SettingsKeys.FONT_SIZE] ?: 12,
    terminalLineHeight = prefs[SettingsKeys.LINE_HEIGHT] ?: 1.15f,
    scrollMode = ScrollMode.fromWire(prefs[SettingsKeys.SCROLL_MODE]),
    cursorStyle = CursorStyle.fromWire(prefs[SettingsKeys.CURSOR_STYLE]),
    boldIsBright = prefs[SettingsKeys.BOLD_BRIGHT] ?: true,
    transportMode = TransportMode.fromWire(prefs[SettingsKeys.TRANSPORT_MODE]),
    directUrl = prefs[SettingsKeys.DIRECT_URL] ?: BuildConfig.DEFAULT_DIRECT_URL,
    directBackendMode = BackendMode.fromWire(prefs[SettingsKeys.DIRECT_BACKEND]),
    activeProfileId = prefs[SettingsKeys.ACTIVE_PROFILE],
    hostProfiles = profiles.profiles,
    extraKeysEnabled = prefs[SettingsKeys.EXTRA_KEYS] ?: true,
    cjkInputEnabled = prefs[SettingsKeys.CJK_INPUT] ?: true,
    swipeBehavior = SwipeBehavior.fromWire(prefs[SettingsKeys.SWIPE]),
    hapticFeedback = prefs[SettingsKeys.HAPTIC] ?: true,
    bellVibration = prefs[SettingsKeys.BELL] ?: true,
    scrollbackLimit = prefs[SettingsKeys.SCROLLBACK] ?: 2000,
    notifyDone = prefs[SettingsKeys.NOTIFY_DONE] ?: true,
    notifyBlocked = prefs[SettingsKeys.NOTIFY_BLOCKED] ?: true,
    showScrollDiagnostics = prefs[SettingsKeys.SHOW_SCROLL_DIAG] ?: false,
    backgroundMonitoring = prefs[SettingsKeys.BG_MONITOR] ?: true,
)

/**
 * Writes every persisted field. When [preservedProfilesRaw] is set the stored
 * profiles JSON is written back verbatim instead of re-encoded from
 * [next], whose host list is only the empty decode fallback.
 *
 * [AppSettings.extraKeysLayoutId] has no key: it is transient and is never
 * persisted.
 */
internal fun MutablePreferences.writeSettings(
    next: AppSettings,
    preservedProfilesRaw: String? = null,
) {
    this[SettingsKeys.THEME_MODE] = next.themeMode.wire
    this[SettingsKeys.DYNAMIC_COLOR] = next.dynamicColor
    this[SettingsKeys.TERMINAL_SCHEME] = next.terminalColorScheme.wire
    this[SettingsKeys.TERMINAL_FONT] = next.terminalFont.wire
    this[SettingsKeys.FONT_SIZE] = next.terminalFontSizeSp
    this[SettingsKeys.LINE_HEIGHT] = next.terminalLineHeight
    this[SettingsKeys.SCROLL_MODE] = next.scrollMode.wire
    this[SettingsKeys.CURSOR_STYLE] = next.cursorStyle.wire
    this[SettingsKeys.BOLD_BRIGHT] = next.boldIsBright
    this[SettingsKeys.TRANSPORT_MODE] = next.transportMode.wire
    this[SettingsKeys.DIRECT_URL] = next.directUrl
    this[SettingsKeys.DIRECT_BACKEND] = next.directBackendMode.wire
    val activeProfileId = next.activeProfileId
    if (activeProfileId != null) {
        this[SettingsKeys.ACTIVE_PROFILE] = activeProfileId
    } else {
        remove(SettingsKeys.ACTIVE_PROFILE)
    }
    this[SettingsKeys.PROFILES] = preservedProfilesRaw ?: encodeProfiles(next.hostProfiles)
    this[SettingsKeys.EXTRA_KEYS] = next.extraKeysEnabled
    this[SettingsKeys.CJK_INPUT] = next.cjkInputEnabled
    this[SettingsKeys.SWIPE] = next.swipeBehavior.wire
    this[SettingsKeys.HAPTIC] = next.hapticFeedback
    this[SettingsKeys.BELL] = next.bellVibration
    this[SettingsKeys.SCROLLBACK] = next.scrollbackLimit
    this[SettingsKeys.NOTIFY_DONE] = next.notifyDone
    this[SettingsKeys.NOTIFY_BLOCKED] = next.notifyBlocked
    this[SettingsKeys.SHOW_SCROLL_DIAG] = next.showScrollDiagnostics
    this[SettingsKeys.BG_MONITOR] = next.backgroundMonitoring
}

/**
 * Last notified status per pane (M9), persisted as `id=wire;id=wire`. Pane ids
 * and wire names contain neither separator, so a split is unambiguous; corrupt
 * entries are dropped (worst case: re-alert once).
 */
internal fun decodeNotifiedMap(raw: String?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return raw.split(';')
        .mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.take(eq) to part.substring(eq + 1)
        }
        .toMap()
}

internal fun encodeNotifiedMap(map: Map<String, String>): String =
    map.entries.joinToString(";") { "${it.key}=${it.value}" }
