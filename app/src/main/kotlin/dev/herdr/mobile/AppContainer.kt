package dev.herdr.mobile

import android.content.Context
import dev.herdr.mobile.core.network.DaemonEndpointProvider
import dev.herdr.mobile.core.network.HerdrClient
import dev.herdr.mobile.core.network.networkAvailable
import dev.herdr.mobile.notifications.HerdrNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Manual service locator. No DI framework: the graph is small (settings → provider →
 * client → viewmodels) and explicit construction keeps every dependency visible.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val secrets = KeystoreSecrets(appContext)
    val settingsRepository = SettingsRepositoryImpl(appContext, secrets, scope)

    private val providerFlow = MutableStateFlow<DaemonEndpointProvider?>(null)
    val provider: StateFlow<DaemonEndpointProvider?> = providerFlow.asStateFlow()

    val client: HerdrClient

    init {
        HerdrNotifications.ensureChannels(appContext)
        client = HerdrClient(
            endpointProvider = SwitchingProvider(providerFlow),
            externalScope = scope,
        )
        // The first provider is built by the settings collector below once the
        // DataStore load lands — never synchronously here. Reading .value in
        // init races load() and builds from default AppSettings (zero profiles).
        // Rebuild the provider whenever transport-relevant settings change; the client
        // picks the new one up on its next (re)connect. The key covers transport
        // mode + direct URL + full active-profile content: the provider id alone
        // never changes on field edits ("direct" is constant, profile ids are
        // stable), so gating on id drops every edit until process restart.
        scope.launch {
            settingsRepository.settings
                .map { s ->
                    val active = s.hostProfiles.firstOrNull { it.id == s.activeProfileId }
                        ?: s.hostProfiles.firstOrNull()
                    Triple(s.transportMode, s.directUrl, active)
                }
                .distinctUntilChanged()
                // Debounce: URL/host keystrokes emit a distinct key per character.
                // Without this every keystroke tears down and redials the tunnel.
                .collectLatest {
                    kotlinx.coroutines.delay(500)
                    val current = settingsRepository.settings.value
                    val rebuilt = settingsRepository.providerFor(current)
                    val old = providerFlow.value
                    if (rebuilt == null) {
                        // Last profile deleted (or transport unusable): close the
                        // stale tunnel instead of staying connected to a deleted
                        // host. The client surfaces no-profile state on kick.
                        if (old != null) {
                            try {
                                old.close()
                            } catch (_: Exception) {
                            }
                            providerFlow.value = null
                            client.kick()
                        }
                    } else {
                        if (old != null) {
                            try {
                                old.close()
                            } catch (_: Exception) {
                            }
                        }
                        providerFlow.value = rebuilt
                        client.kick()
                    }
                }
        }
        // Network regain retries immediately instead of sleeping through the backoff.
        scope.launch {
            networkAvailable(appContext).collect { available ->
                if (available) client.kick()
            }
        }
        client.start()
    }

    val notificationRelay = NotificationRelay(appContext, settingsRepository, client, scope)

    /** Forwards the current provider; the client never caches a stale tunnel. */
    private class SwitchingProvider(
        private val current: StateFlow<DaemonEndpointProvider?>,
    ) : DaemonEndpointProvider {
        override val id: String get() = current.value?.id ?: "none"
        override val description: String get() = current.value?.description ?: "no provider"

        override suspend fun open() =
            current.value?.open()
                ?: throw IllegalStateException("No host profile configured")

        override suspend fun close() {
            current.value?.close()
        }
    }
}
