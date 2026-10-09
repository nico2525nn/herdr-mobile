package dev.herdr.mobile.core.model

/** App theme mode, as offered in Settings > Appearance. */
enum class ThemeMode(val wire: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromWire(value: String?): ThemeMode = entries.firstOrNull { it.wire == value } ?: SYSTEM
    }
}

/** Built-in terminal colour schemes. Terminal content is the only place these are used. */
enum class TerminalColorScheme(val wire: String, val displayName: String) {
    DARK("dark", "Dark"),
    LIGHT("light", "Light"),
    SOLARIZED_DARK("solarized_dark", "Solarized Dark"),
    SOLARIZED_LIGHT("solarized_light", "Solarized Light"),
    TOKYO_NIGHT("tokyo_night", "Tokyo Night"),
    DRACULA("dracula", "Dracula"),
    GRUVBOX_DARK("gruvbox_dark", "Gruvbox Dark"),
    CAMPBELL("campbell", "Campbell");

    companion object {
        fun fromWire(value: String?): TerminalColorScheme =
            entries.firstOrNull { it.wire == value } ?: TOKYO_NIGHT
    }
}

/**
 * Monospace fonts offered for terminal content only. UI text always uses Material typography.
 *
 * Each entry names a system font family that Android ships, so no font binaries are bundled.
 */
enum class TerminalFont(val wire: String, val displayName: String, val familyName: String) {
    MONOSPACE("monospace", "Monospace", "monospace"),
    DROID_SANS_MONO("droid_sans_mono", "Droid Sans Mono", "Droid Sans Mono"),
    NOTO_SANS_MONO("noto_sans_mono", "Noto Sans Mono", "Noto Sans Mono"),
    SOURCE_CODE_PRO("source_code_pro", "Source Code Pro", "monospace"),
    JETBRAINS_MONO("jetbrains_mono", "JetBrains Mono", "monospace");

    companion object {
        fun fromWire(value: String?): TerminalFont =
            entries.firstOrNull { it.wire == value } ?: MONOSPACE
    }
}

enum class CursorStyle(val wire: String, val displayName: String) {
    BLOCK("block", "Block"),
    UNDERLINE("underline", "Underline"),
    BAR("bar", "Bar");

    companion object {
        fun fromWire(value: String?): CursorStyle = entries.firstOrNull { it.wire == value } ?: BLOCK
    }
}

/** The two pages of the bottom terminal input panel. */
enum class InputPanelPage { EXTRA_KEYS, CJK_INPUT }

/** How a horizontal swipe inside the bottom panel changes pages. */
enum class SwipeBehavior(val wire: String, val displayName: String) {
    PAGE("page", "Switch page"),
    NONE("none", "Do nothing"),
    SCROLLBACK("scrollback", "Terminal scrollback");

    companion object {
        fun fromWire(value: String?): SwipeBehavior =
            entries.firstOrNull { it.wire == value } ?: PAGE
    }
}

/** Transport used to reach the daemon. */
enum class TransportMode(val wire: String, val displayName: String) {
    /** Recommended default: SSH tunnel to a daemon bound to the loopback interface. */
    SSH("ssh", "SSH tunnel"),

    /** Tailscale / LAN: talk straight to a TLS-protected daemon endpoint. */
    DIRECT("direct", "Direct (tailnet)");

    companion object {
        fun fromWire(value: String?): TransportMode = entries.firstOrNull { it.wire == value } ?: SSH
    }
}

/**
 * One SSH host profile. Secrets (private key material, password, bearer token) are never
 * part of this value; they live in the keystore-backed store and are referenced by alias.
 * Password auth is only attempted when [passwordAlias] resolves; otherwise key auth alone.
 */
data class HostProfile(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val daemonPort: Int = 8765,
    val daemonHost: String = "127.0.0.1",
    val privateKeyAlias: String? = null,
    val privateKeyLabel: String? = null,
    val passwordAlias: String? = null,
    val hostKeyAlias: String? = null,
    val useTls: Boolean = false,
    val bearerTokenAlias: String? = null,
) {
    val userAtHost: String get() = "$username@$host"
}

/** Everything the user can change from Settings, with no secrets inside. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val terminalColorScheme: TerminalColorScheme = TerminalColorScheme.TOKYO_NIGHT,
    val terminalFont: TerminalFont = TerminalFont.MONOSPACE,
    val terminalFontSizeSp: Int = 12,
    val terminalLineHeight: Float = 1.15f,
    val cursorStyle: CursorStyle = CursorStyle.BLOCK,
    val boldIsBright: Boolean = true,

    val transportMode: TransportMode = TransportMode.SSH,
    val activeProfileId: String? = null,
    val hostProfiles: List<HostProfile> = emptyList(),
    /** Direct-transport daemon origin, e.g. `http://100.x.y.z:8765`. Not a secret. */
    val directUrl: String = "http://10.0.2.2:8765",

    val extraKeysEnabled: Boolean = true,
    val extraKeysLayoutId: String = DEFAULT_EXTRA_KEYS_LAYOUT,
    val cjkInputEnabled: Boolean = true,
    val swipeBehavior: SwipeBehavior = SwipeBehavior.PAGE,
    val hapticFeedback: Boolean = true,
    val bellVibration: Boolean = true,
    val scrollbackLimit: Int = 2000,

    val notifyDone: Boolean = true,
    val notifyBlocked: Boolean = true,
    /** Debug-only scroll diagnostics overlay (hist/alt/offset/gestures). Never content. */
    val showScrollDiagnostics: Boolean = false,
) {
    companion object {
        const val DEFAULT_EXTRA_KEYS_LAYOUT = "termux_default"
    }
}