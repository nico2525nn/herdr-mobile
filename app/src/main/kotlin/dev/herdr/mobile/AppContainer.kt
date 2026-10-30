package dev.herdr.mobile

import android.content.Context
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.BackendMode
import dev.herdr.mobile.core.model.HostProfile
import dev.herdr.mobile.core.model.TransportMode
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

/** Settings that identify the daemon/session stream whose data is safe to cache. */
internal data class ConnectionTargetKey(
    val transportMode: TransportMode,
    val directUrl: String?,
    val activeProfile: HostProfile?,
    val backendMode: BackendMode,
)

internal fun connectionTargetKey(settings: AppSettings): ConnectionTargetKey {
    val activeProfile = settings.hostProfiles.firstOrNull { it.id == settings.activeProfileId }
        ?: settings.hostProfiles.firstOrNull()
    return ConnectionTargetKey(
        transportMode = settings.transportMode,
        directUrl = settings.directUrl.takeIf { settings.transportMode == TransportMode.DIRECT },
        activeProfile = activeProfile,
        backendMode = if (settings.transportMode == TransportMode.SSH) {
            activeProfile?.backendMode ?: BackendMode.TUI
        } else {
            settings.directBackendMode
        },
    )
}

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
        // mode + direct URL + full active-profile content, and its cache is invalidated
        // immediately before debouncing provider rebuilds. The provider id alone
        // never changes on field edits ("direct" is constant, profile ids are
        // stable), so gating on id drops every edit until process restart.
        scope.launch {
            var previousTarget: ConnectionTargetKey? = null
            var targetInvalidated = false
            settingsRepository.settings
                .map(::connectionTargetKey)
                .distinctUntilChanged()
                // Debounce: URL/host keystrokes emit a distinct key per character.
                // Without this every keystroke tears down and redials the tunnel.
                .collectLatest { target ->
                    val changedTarget = previousTarget != null && previousTarget != target
                    previousTarget = target
                    if (changedTarget && !targetInvalidated) {
                        client.invalidateTarget()
                        targetInvalidated = true
                    }
                    kotlinx.coroutines.delay(500)
                    val current = settingsRepository.settings.value
                    val rebuilt = settingsRepository.providerFor(current)
                    val old = providerFlow.value
                    if (rebuilt == null) {
                        // Last profile deleted (or transport unusable): close the
                        // stale tunnel instead of staying connected to a deleted
                        // host. The client surfaces no-profile state on restart.
                        if (old != null) {
                            try {
                                old.close()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            }
                            providerFlow.value = null
                        }
                        if (old != null || targetInvalidated) client.restartLink()
                    } else {
                        if (old != null) {
                            try {
                                old.close()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            }
                        }
                        providerFlow.value = rebuilt
                        client.restartLink()
                    }
                    targetInvalidated = false
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
        override val backendMode: BackendMode get() = current.value?.backendMode ?: BackendMode.TUI

        override suspend fun open() =
            current.value?.open()
                ?: throw IllegalStateException("No host profile configured")

        override suspend fun close() {
            current.value?.close()
        }
    }
}
