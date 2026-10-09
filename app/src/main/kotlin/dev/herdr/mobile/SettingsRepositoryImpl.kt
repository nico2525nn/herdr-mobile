package dev.herdr.mobile

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.herdr.mobile.connection.direct.DirectProvider
import dev.herdr.mobile.connection.ssh.SshTunnelProvider
import dev.herdr.mobile.core.model.UnknownHostKeyException
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.ConnectionTestResult
import dev.herdr.mobile.core.model.CursorStyle
import dev.herdr.mobile.core.model.FailureKind
import dev.herdr.mobile.core.model.HostProfile
import dev.herdr.mobile.core.model.SwipeBehavior
import dev.herdr.mobile.core.model.TerminalColorScheme
import dev.herdr.mobile.core.model.TerminalFont
import dev.herdr.mobile.core.model.ThemeMode
import dev.herdr.mobile.core.model.TransportMode
import dev.herdr.mobile.core.network.DaemonEndpointProvider
import dev.herdr.mobile.core.network.HerdrClient
import dev.herdr.mobile.feature.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "herdr_settings")

private object Keys {
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
    val SHOW_SCROLL_DIAG = booleanPreferencesKey("show_scroll_diagnostics")
}

private val storeJson = Json { ignoreUnknownKeys = true }

/**
 * DataStore for values, [KeystoreSecrets] for secrets. Profiles hold aliases, never material.
 */
class SettingsRepositoryImpl(
    private val context: Context,
    private val secrets: KeystoreSecrets,
    private val scope: CoroutineScope,
) : SettingsRepository {

    private val _settings = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /**
     * Serializes update(): two concurrent read-modify-writes interleave on a
     * stale in-memory read and the second clobbers the first, in memory and
     * in DataStore.
     */
    private val updateMutex = kotlinx.coroutines.sync.Mutex()

    init {
        scope.launch { load() }
    }

    /**
     * Completes when the initial DataStore load lands. update() waits on it:
     * without the gate, a startup write persists default empty hostProfiles
     * over the stored values.
     */
    private val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()

    /**
     * Raw profiles JSON when decode fails: the next update() must rewrite this
     * verbatim instead of persisting the empty fallback, or one corrupt read
     * wipes every host. Cleared once a successful decode or explicit edit lands.
     */
    @Volatile
    private var preservedProfilesRaw: String? = null

    private suspend fun load() {
        val prefs = try {
            context.dataStore.data.first()
        } catch (e: Exception) {
            // Corrupt/unreadable prefs: keep defaults but still complete the
            // gate — otherwise every update() hangs on await() forever and the
            // app is bricked until reinstall. Defaults are safe; the next
            // successful edit rewrites clean prefs.
            android.util.Log.w("Settings", "DataStore load failed, using defaults: " + e.message)
            loaded.complete(Unit)
            return
        }
        _settings.value = AppSettings(
            themeMode = ThemeMode.fromWire(prefs[Keys.THEME_MODE]),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            terminalColorScheme = TerminalColorScheme.fromWire(prefs[Keys.TERMINAL_SCHEME]),
            terminalFont = TerminalFont.fromWire(prefs[Keys.TERMINAL_FONT]),
            terminalFontSizeSp = prefs[Keys.FONT_SIZE] ?: 12,
            terminalLineHeight = prefs[Keys.LINE_HEIGHT] ?: 1.15f,
            cursorStyle = CursorStyle.fromWire(prefs[Keys.CURSOR_STYLE]),
            boldIsBright = prefs[Keys.BOLD_BRIGHT] ?: true,
            transportMode = TransportMode.fromWire(prefs[Keys.TRANSPORT_MODE]),
            directUrl = prefs[Keys.DIRECT_URL] ?: "http://10.0.2.2:8765",
            activeProfileId = prefs[Keys.ACTIVE_PROFILE],
            hostProfiles = decodeProfiles(prefs[Keys.PROFILES]),
            extraKeysEnabled = prefs[Keys.EXTRA_KEYS] ?: true,
            cjkInputEnabled = prefs[Keys.CJK_INPUT] ?: true,
            swipeBehavior = SwipeBehavior.fromWire(prefs[Keys.SWIPE]),
            hapticFeedback = prefs[Keys.HAPTIC] ?: true,
            bellVibration = prefs[Keys.BELL] ?: true,
            scrollbackLimit = prefs[Keys.SCROLLBACK] ?: 2000,
            notifyDone = prefs[Keys.NOTIFY_DONE] ?: true,
            notifyBlocked = prefs[Keys.NOTIFY_BLOCKED] ?: true,
            showScrollDiagnostics = prefs[Keys.SHOW_SCROLL_DIAG] ?: false,
        )
        loaded.complete(Unit)
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        loaded.await()
        updateMutex.withLock {
            val next = transform(_settings.value)
            _settings.value = next
            persist(next)
        }
    }

    private suspend fun persist(next: AppSettings) {
        context.dataStore.edit { prefs -> writePrefs(prefs, next) }
    }

    private fun writePrefs(prefs: androidx.datastore.preferences.core.MutablePreferences, next: AppSettings) {
        prefs[Keys.THEME_MODE] = next.themeMode.wire
        prefs[Keys.DYNAMIC_COLOR] = next.dynamicColor
        prefs[Keys.TERMINAL_SCHEME] = next.terminalColorScheme.wire
        prefs[Keys.TERMINAL_FONT] = next.terminalFont.wire
        prefs[Keys.FONT_SIZE] = next.terminalFontSizeSp
        prefs[Keys.LINE_HEIGHT] = next.terminalLineHeight
        prefs[Keys.CURSOR_STYLE] = next.cursorStyle.wire
        prefs[Keys.BOLD_BRIGHT] = next.boldIsBright
        prefs[Keys.TRANSPORT_MODE] = next.transportMode.wire
        prefs[Keys.DIRECT_URL] = next.directUrl
        val activeProfileId = next.activeProfileId
        if (activeProfileId != null) {
            prefs[Keys.ACTIVE_PROFILE] = activeProfileId
        } else {
            prefs.remove(Keys.ACTIVE_PROFILE)
        }
        prefs[Keys.PROFILES] = preservedProfilesRaw ?: encodeProfiles(next.hostProfiles)
        prefs[Keys.EXTRA_KEYS] = next.extraKeysEnabled
        prefs[Keys.CJK_INPUT] = next.cjkInputEnabled
        prefs[Keys.SWIPE] = next.swipeBehavior.wire
        prefs[Keys.HAPTIC] = next.hapticFeedback
        prefs[Keys.BELL] = next.bellVibration
        prefs[Keys.SCROLLBACK] = next.scrollbackLimit
        prefs[Keys.NOTIFY_DONE] = next.notifyDone
        prefs[Keys.NOTIFY_BLOCKED] = next.notifyBlocked
        prefs[Keys.SHOW_SCROLL_DIAG] = next.showScrollDiagnostics
    }

    private fun decodeProfiles(raw: String?): List<HostProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            storeJson.decodeFromString(ListSerializer(StoredProfile.serializer()), raw)
                .map { it.toModel() }
        }.getOrElse {
            // Corrupt JSON: show zero hosts but keep the raw bytes so the next
            // update() rewrites them verbatim instead of wiping every host.
            preservedProfilesRaw = raw
            emptyList()
        }
    }

    private fun encodeProfiles(profiles: List<HostProfile>): String =
        storeJson.encodeToString(
            ListSerializer(StoredProfile.serializer()),
            profiles.map { StoredProfile.from(it) },
        )

    override suspend fun storePrivateKey(alias: String?, pem: String, label: String): String =
        withContext(Dispatchers.IO) {
            val resolved = alias ?: "ssh-key-${UUID.randomUUID()}"
            secrets.put(resolved, pem)
            resolved
        }

    override suspend fun deletePrivateKey(alias: String) = withContext(Dispatchers.IO) {
        secrets.delete(alias)
    }

    override suspend fun storeSshPassword(alias: String?, password: String): String? =
        withContext(Dispatchers.IO) {
            if (password.isBlank()) {
                if (alias != null) secrets.delete(alias)
                return@withContext null
            }
            val resolved = alias ?: "ssh-password-${UUID.randomUUID()}"
            secrets.put(resolved, password)
            resolved
        }

    override suspend fun deleteSshPassword(alias: String) = withContext(Dispatchers.IO) {
        secrets.delete(alias)
    }

    override suspend fun storeBearerToken(alias: String?, token: String): String =
        withContext(Dispatchers.IO) {
            val resolved = alias ?: "token-${UUID.randomUUID()}"
            secrets.put(resolved, token)
            resolved
        }

    override suspend fun deleteBearerToken(alias: String) = withContext(Dispatchers.IO) {
        secrets.delete(alias)
    }

    override suspend fun storeKnownHosts(alias: String?, knownHosts: String): String =
        withContext(Dispatchers.IO) {
            val resolved = alias ?: "known-hosts-${UUID.randomUUID()}"
            secrets.put(resolved, knownHosts)
            resolved
        }

    override suspend fun acceptHostKey(profileId: String, knownHostsLine: String): String {
        val current = _settings.value.hostProfiles.firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("No such profile: $profileId")
        val alias = storeKnownHosts(current.hostKeyAlias, knownHostsLine.trim())
        updateProfile(current.copy(hostKeyAlias = alias))
        return alias
    }

    override suspend fun addProfile(profile: HostProfile) {
        preservedProfilesRaw = null
        update { it.copy(hostProfiles = it.hostProfiles + profile) }
    }

    override suspend fun updateProfile(profile: HostProfile) {
        preservedProfilesRaw = null
        update { s ->
            val profiles = s.hostProfiles.map { if (it.id == profile.id) profile else it }
            val withAdded = if (profiles.any { it.id == profile.id }) profiles else profiles + profile
            s.copy(hostProfiles = withAdded)
        }
    }

    override suspend fun removeProfile(profileId: String) {
        val current = _settings.value.hostProfiles.firstOrNull { it.id == profileId }
        val keyAlias = current?.privateKeyAlias
        if (keyAlias != null) deletePrivateKey(keyAlias)
        val tokenAlias = current?.bearerTokenAlias
        if (tokenAlias != null) deleteBearerToken(tokenAlias)
        val hostAlias = current?.hostKeyAlias
        if (hostAlias != null) secrets.delete(hostAlias)
        val passwordSecretAlias = current?.passwordAlias
        if (passwordSecretAlias != null) deleteSshPassword(passwordSecretAlias)
        update { s ->
            val active = s.activeProfileId
            s.copy(
                hostProfiles = s.hostProfiles.filterNot { it.id == profileId },
                activeProfileId = if (active == profileId) null else active,
            )
        }
    }

    override suspend fun setActiveProfile(profileId: String?) {
        update { it.copy(activeProfileId = profileId) }
    }

    /** Build the provider for the current settings. Used by [AppContainer]. */
    fun providerFor(settings: AppSettings): DaemonEndpointProvider? {
        return when (settings.transportMode) {
            TransportMode.SSH -> {
                val profile = settings.hostProfiles.firstOrNull { it.id == settings.activeProfileId }
                    ?: settings.hostProfiles.firstOrNull()
                    ?: return null
                SshTunnelProvider(
                    profile = profile,
                    privateKeyPem = { profile.privateKeyAlias?.let { secrets.get(it) } },
                    password = { profile.passwordAlias?.let { secrets.get(it) } },
                    knownHosts = { profile.hostKeyAlias?.let { secrets.get(it) } },
                    onUnknownHostKey = { _, _ -> false },
                    bearerToken = { profile.bearerTokenAlias?.let { secrets.get(it) } },
                )
            }

            TransportMode.DIRECT -> {
                // The direct URL lives in DataStore as plain text (it is not a secret).
                DirectProvider(
                    baseUrl = settings.directUrl.ifBlank { "http://10.0.2.2:8765" },
                    bearerToken = {
                        val profile = settings.hostProfiles.firstOrNull { it.id == settings.activeProfileId }
                        profile?.bearerTokenAlias?.let { secrets.get(it) }
                    },
                )
            }
        }
    }

    override suspend fun testConnection(): ConnectionTestResult {
        val settings = _settings.value
        val provider = providerFor(settings)
            ?: return ConnectionTestResult.Failure(FailureKind.UNKNOWN, "No host profile configured")
        // A throwaway client so the test never disturbs the live link.
        // Scope AND provider are both closed afterwards: probe.testConnection
        // skips closing when provider === endpointProvider, so without this
        // every Test tap leaks an SSH session + local forward + scope.
        val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val probe = HerdrClient(
            endpointProvider = provider,
            externalScope = probeScope,
        )
        return try {
            withTimeout(30_000) {
                try {
                    probe.testConnection(provider)
                } catch (e: UnknownHostKeyException) {
                    ConnectionTestResult.UnknownHostKey(
                        detail = "${e.host} presented an unknown key (${e.fingerprint}). " +
                            "Verify it out of band, then approve below.",
                        profileId = e.profileId,
                        host = e.host,
                        fingerprint = e.fingerprint,
                        knownHostsLine = e.knownHostsLine,
                    )
                }
            }
        } finally {
            probeScope.launch {
                runCatching { provider.close() }
            }.join()
            probeScope.cancel()
        }
    }

    companion object {
        fun create(
            context: Context,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): SettingsRepositoryImpl {
            return SettingsRepositoryImpl(context.applicationContext, KeystoreSecrets(context), scope)
        }
    }
}

