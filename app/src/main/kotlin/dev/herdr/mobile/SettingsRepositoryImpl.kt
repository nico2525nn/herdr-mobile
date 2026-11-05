package dev.herdr.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dev.herdr.mobile.connection.direct.DirectProvider
import dev.herdr.mobile.connection.ssh.SshTunnelProvider
import dev.herdr.mobile.core.model.UnknownHostKeyException
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.ConnectionTestResult
import dev.herdr.mobile.core.model.FailureKind
import dev.herdr.mobile.core.model.HostProfile
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
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "herdr_settings")

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

    override suspend fun awaitLoaded() {
        loaded.await()
    }

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
        val storedProfiles = decodeStoredProfiles(prefs[SettingsKeys.PROFILES])
        preservedProfilesRaw = storedProfiles.corruptRaw
        _settings.value = decodeSettings(prefs, storedProfiles)
        loaded.complete(Unit)
    }

    suspend fun loadNotified(): Map<String, String> {
        loaded.await()
        return decodeNotifiedMap(context.dataStore.data.first()[SettingsKeys.NOTIFIED_MAP])
    }

    suspend fun saveNotified(map: Map<String, String>) {
        loaded.await()
        val raw = encodeNotifiedMap(map)
        context.dataStore.edit { prefs -> prefs[SettingsKeys.NOTIFIED_MAP] = raw }
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
        context.dataStore.edit { prefs -> prefs.writeSettings(next, preservedProfilesRaw) }
    }

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
                    backendMode = profile.backendMode,
                )
            }

            TransportMode.DIRECT -> {
                // The direct URL lives in DataStore as plain text (it is not a secret).
                DirectProvider(
                    baseUrl = settings.directUrl.ifBlank { BuildConfig.DEFAULT_DIRECT_URL },
                    bearerToken = {
                        val profile = settings.hostProfiles.firstOrNull { it.id == settings.activeProfileId }
                        profile?.bearerTokenAlias?.let { secrets.get(it) }
                    },
                    backendMode = settings.directBackendMode,
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

