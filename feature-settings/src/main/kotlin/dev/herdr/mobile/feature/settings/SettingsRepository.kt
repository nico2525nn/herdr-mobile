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

    /** Suspends until the persisted values have landed (pre-load reads are defaults). */
    suspend fun awaitLoaded()

    suspend fun update(transform: (AppSettings) -> AppSettings)

    /**
     * Serialize a profile read-modify-write with the matching secret I/O.
     * Saves, removals, and TOFU host-key approval must share one boundary:
     * otherwise a removal that lands while a save awaits the Keystore lets the
     * save re-create the deleted profile (or resurrect a deleted alias).
     */
    suspend fun <T> withProfileLock(block: suspend () -> T): T = block()

    /** Store a PEM private key under an alias. Returns the alias to reference from a profile. */
    suspend fun storePrivateKey(alias: String?, pem: String, label: String): String

    suspend fun deletePrivateKey(alias: String)

    /** Store an SSH password under an alias. Blank input clears the stored password. */
    suspend fun storeSshPassword(alias: String?, password: String): String?

    suspend fun deleteSshPassword(alias: String)

    /** Store the daemon bearer token. Returns the alias. */
    suspend fun storeBearerToken(alias: String?, token: String): String

    suspend fun deleteBearerToken(alias: String)

    suspend fun storeKnownHosts(alias: String?, knownHosts: String): String

    suspend fun addProfile(profile: HostProfile)
    suspend fun updateProfile(profile: HostProfile)
    suspend fun removeProfile(profileId: String)
    suspend fun setActiveProfile(profileId: String?)

    suspend fun testConnection(): ConnectionTestResult

    /**
     * TOFU accept: stores [knownHostsLine] (a single `host type base64` line) under the
     * profile's host-key alias so the next connect verifies against it. Returns the alias.
     */
    suspend fun acceptHostKey(profileId: String, knownHostsLine: String): String

    /**
     * Accept only if the profile still points at the probed endpoint. A test
     * connection can finish after the user edits host/port; storing A's key on
     * B's profile makes B fail strict verification forever.
     */
    suspend fun acceptHostKeyFor(
        profileId: String,
        knownHostsLine: String,
        expectedHost: String,
        expectedPort: Int,
    ): String = acceptHostKey(profileId, knownHostsLine)
}
