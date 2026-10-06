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
        val initial = settingsRepository.settings.value
        val first = settingsRepository.providerFor(initial)
        providerFlow.value = first
        client = HerdrClient(
            endpointProvider = SwitchingProvider(providerFlow),
            externalScope = scope,
        )
        // Rebuild the provider whenever transport-relevant settings change; the client
        // picks the new one up on its next (re)connect.
        scope.launch {
            settingsRepository.settings
                .map { s -> settingsRepository.providerFor(s)?.id to s.transportMode }
                .distinctUntilChanged()
                .collect { (id, _) ->
                    val current = settingsRepository.settings.value
                    val rebuilt = settingsRepository.providerFor(current)
                    if (rebuilt?.id != providerFlow.value?.id && rebuilt != null ||
                        providerFlow.value == null && rebuilt != null
                    ) {
                        providerFlow.value?.let { old ->
                            runCatching { old.close() }
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
