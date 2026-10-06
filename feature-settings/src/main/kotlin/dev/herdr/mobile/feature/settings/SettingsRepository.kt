package dev.herdr.mobile.feature.settings

import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.ConnectionTestResult
import dev.herdr.mobile.core.model.HostProfile
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings storage. Implemented by `:app` (DataStore for values, Keystore for secrets);
 * this feature only renders and edits.
 */
interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)

    /** Store a PEM private key under an alias. Returns the alias to reference from a profile. */
    suspend fun storePrivateKey(alias: String?, pem: String, label: String): String

    suspend fun deletePrivateKey(alias: String)

    /** Store the daemon bearer token. Returns the alias. */
    suspend fun storeBearerToken(alias: String?, token: String): String

    suspend fun deleteBearerToken(alias: String)

    suspend fun storeKnownHosts(alias: String?, knownHosts: String): String

    suspend fun addProfile(profile: HostProfile)
    suspend fun updateProfile(profile: HostProfile)
    suspend fun removeProfile(profileId: String)
    suspend fun setActiveProfile(profileId: String?)

    suspend fun testConnection(): ConnectionTestResult
}
