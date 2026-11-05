package dev.herdr.mobile

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.BackendMode
import dev.herdr.mobile.core.model.CursorStyle
import dev.herdr.mobile.core.model.HostProfile
import dev.herdr.mobile.core.model.SwipeBehavior
import dev.herdr.mobile.core.model.TerminalColorScheme
import dev.herdr.mobile.core.model.TerminalFont
import dev.herdr.mobile.core.model.ThemeMode
import dev.herdr.mobile.core.model.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure codec tests: plain [androidx.datastore.preferences.core.Preferences],
 * no Android runtime and no DataStore file I/O.
 *
 * [AppSettings.scrollMode] and [AppSettings.extraKeysLayoutId] have no
 * preference key, so they stay at their defaults here and still round-trip.
 */
class SettingsCodecTest {

    private val full = AppSettings(
        themeMode = ThemeMode.MIDNIGHT,
        dynamicColor = false,
        terminalColorScheme = TerminalColorScheme.DRACULA,
        terminalFont = TerminalFont.JETBRAINS_MONO,
        terminalFontSizeSp = 17,
        terminalLineHeight = 1.4f,
        cursorStyle = CursorStyle.BAR,
        boldIsBright = false,
        transportMode = TransportMode.DIRECT,
        directUrl = "https://daemon.example.test:9999",
        directBackendMode = BackendMode.CORE,
        activeProfileId = "profile-2",
        hostProfiles = listOf(
            HostProfile(
                id = "profile-2",
                label = "build box",
                host = "build.example.test",
                port = 2222,
                username = "nico",
                daemonPort = 8766,
                daemonHost = "10.0.0.7",
                privateKeyAlias = "ssh-key-1",
                privateKeyLabel = "work key",
                passwordAlias = "ssh-password-1",
                hostKeyAlias = "known-hosts-1",
                useTls = true,
                bearerTokenAlias = "token-1",
                backendMode = BackendMode.CORE,
            ),
        ),
        extraKeysEnabled = false,
        cjkInputEnabled = false,
        swipeBehavior = SwipeBehavior.SCROLLBACK,
        hapticFeedback = false,
        bellVibration = false,
        scrollbackLimit = 12_345,
        notifyDone = false,
        notifyBlocked = false,
        backgroundMonitoring = false,
        showScrollDiagnostics = true,
    )

    @Test
    fun emptyPreferencesDecodeToDefaults() {
        // The only field whose first-run value comes from the build type rather
        // than the model default: direct URL has a debug/release override.
        assertEquals(
            AppSettings().copy(directUrl = BuildConfig.DEFAULT_DIRECT_URL),
            decodeSettings(preferencesOf()),
        )
    }

    @Test
    fun fullSettingsRoundTrip() {
        val prefs = mutablePreferencesOf()
        prefs.writeSettings(full)

        assertEquals(full, decodeSettings(prefs))

        val stored = decodeStoredProfiles(prefs[SettingsKeys.PROFILES])
        assertNull(stored.corruptRaw)
        assertEquals(full.hostProfiles, stored.profiles)
        assertTrue(prefs.asMap().containsKey(SettingsKeys.ACTIVE_PROFILE))
    }

    @Test
    fun clearingActiveProfileRemovesTheKey() {
        val prefs = mutablePreferencesOf(SettingsKeys.ACTIVE_PROFILE to "profile-2")
        prefs.writeSettings(AppSettings())
        assertFalse(prefs.asMap().containsKey(SettingsKeys.ACTIVE_PROFILE))
    }

    @Test
    fun corruptProfilesDecodeEmptyAndPreserveRawForTheNextWrite() {
        val corrupt = """[{"id":"broken" """
        val prefs = preferencesOf(SettingsKeys.PROFILES to corrupt)

        val stored = decodeStoredProfiles(prefs[SettingsKeys.PROFILES])
        assertEquals(emptyList<HostProfile>(), stored.profiles)
        assertEquals(corrupt, stored.corruptRaw)
        assertEquals(emptyList<HostProfile>(), decodeSettings(prefs).hostProfiles)

        // The encode path must write the corrupt bytes back verbatim instead of
        // persisting the empty fallback, which would wipe every stored host.
        val rewritten = mutablePreferencesOf()
        rewritten.writeSettings(decodeSettings(prefs), stored.corruptRaw)
        assertEquals(corrupt, rewritten[SettingsKeys.PROFILES])
    }

    @Test
    fun blankProfilesDecodeEmptyWithoutPreservation() {
        val stored = decodeStoredProfiles("  ")
        assertEquals(emptyList<HostProfile>(), stored.profiles)
        assertNull(stored.corruptRaw)
        assertNull(decodeStoredProfiles(null).corruptRaw)
    }

    @Test
    fun notifiedMapRoundTrip() {
        val map = mapOf("pane-1" to "done", "pane-2" to "blocked", "pane-3" to "failed")
        assertEquals("pane-1=done;pane-2=blocked;pane-3=failed", encodeNotifiedMap(map))
        assertEquals(map, decodeNotifiedMap(encodeNotifiedMap(map)))
    }

    @Test
    fun emptyAndMalformedNotifiedEntriesAreDropped() {
        assertEquals(emptyMap<String, String>(), decodeNotifiedMap(null))
        assertEquals(emptyMap<String, String>(), decodeNotifiedMap(""))
        assertEquals(emptyMap<String, String>(), decodeNotifiedMap("garbage;=value;;"))

        assertEquals(
            mapOf("pane-1" to "done", "pane-2" to "", "pane" to "3=x"),
            decodeNotifiedMap("pane-1=done;garbage;=value;;pane-2=;pane=3=x"),
        )
    }

    @Test
    fun emptyNotifiedMapEncodesToEmptyString() {
        assertEquals("", encodeNotifiedMap(emptyMap()))
    }
}
