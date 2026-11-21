package dev.herdr.mobile.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.herdr.mobile.core.designsystem.UiMessage
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.BackendMode
import dev.herdr.mobile.core.model.ConnectionTestResult
import dev.herdr.mobile.core.model.CursorStyle
import dev.herdr.mobile.core.model.HostProfile
import dev.herdr.mobile.core.model.SwipeBehavior
import dev.herdr.mobile.core.model.TerminalColorScheme
import dev.herdr.mobile.core.model.TerminalFont
import dev.herdr.mobile.core.model.ThemeMode
import dev.herdr.mobile.core.model.TransportMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Per-action message wrapper for the generic "could not save" paths. */
private fun saveFailed(error: Throwable): UiMessage =
    UiMessage.CommandFailed(UiMessage.CommandFailed.Command.SAVE_SETTINGS, error.message)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val testing: Boolean = false,
    val testResult: ConnectionTestResult? = null,
    val error: UiMessage? = null,
    /** TOFU prompt: server presented an unknown host key during the last test. */
    val pendingHostKey: PendingHostKey? = null,
)

/** Fingerprint + known_hosts line the user is asked to approve (TOFU). */
data class PendingHostKey(
    val profileId: String,
    val host: String,
    /** e.g. `SHA256:abc... (ssh-ed25519)` — whatever JSch reported. */
    val fingerprint: String,
    /** Full `host type base64` line to store on approval. */
    val knownHostsLine: String,
)

class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _ui.asStateFlow()

    private var testJob: Job? = null
    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _ui.value = _ui.value.copy(settings = settings)
            }
        }
    }

    private fun clearSaveError() {
        if (_ui.value.error != null) _ui.value = _ui.value.copy(error = null)
    }

    private fun edit(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            runCatching { repository.update(transform) }
                .onSuccess { clearSaveError() }
                .onFailure { _ui.value = _ui.value.copy(error = saveFailed(it)) }
        }
    }

    fun setThemeMode(mode: ThemeMode) = edit { it.copy(themeMode = mode) }
    fun setDynamicColor(enabled: Boolean) = edit { it.copy(dynamicColor = enabled) }
    fun setTerminalScheme(scheme: TerminalColorScheme) = edit { it.copy(terminalColorScheme = scheme) }
    fun setScrollMode(mode: dev.herdr.mobile.core.model.ScrollMode) = edit { it.copy(scrollMode = mode) }
    fun setTerminalFont(font: TerminalFont) = edit { it.copy(terminalFont = font) }
    fun setFontSize(sp: Int) = edit { it.copy(terminalFontSizeSp = sp.coerceIn(8, 24)) }
    fun setLineHeight(height: Float) = edit { it.copy(terminalLineHeight = height.coerceIn(1.0f, 2.0f)) }
    fun setCursorStyle(style: CursorStyle) = edit { it.copy(cursorStyle = style) }
    fun setBoldIsBright(enabled: Boolean) = edit { it.copy(boldIsBright = enabled) }

    fun setTransportMode(mode: TransportMode) = edit { it.copy(transportMode = mode) }
    fun setBackendMode(mode: BackendMode) = edit { settings ->
        if (settings.transportMode == TransportMode.DIRECT) {
            settings.copy(directBackendMode = mode)
        } else {
            val profile = settings.hostProfiles.firstOrNull { it.id == settings.activeProfileId }
                ?: settings.hostProfiles.firstOrNull()
                ?: return@edit settings
            settings.copy(hostProfiles = settings.hostProfiles.map {
                if (it.id == profile.id) it.copy(backendMode = mode) else it
            })
        }
    }
    fun setDirectUrl(url: String) {
        val trimmed = url.trim()
        val problem = directUrlProblem(trimmed)
        if (problem != null) {
            _ui.value = _ui.value.copy(
                error = UiMessage.CommandFailed(
                    UiMessage.CommandFailed.Command.SAVE_SETTINGS,
                    problem,
                ),
            )
            return
        }
        edit { it.copy(directUrl = trimmed) }
    }
    fun setActiveProfile(id: String?) {
        viewModelScope.launch {
            runCatching { repository.setActiveProfile(id) }
                .onSuccess { clearSaveError() }
                .onFailure { _ui.value = _ui.value.copy(error = saveFailed(it)) }
        }
    }

    fun saveProfile(profile: HostProfile) {
        viewModelScope.launch {
            repository.withProfileLock {
                runCatching {
                    if (repository.settings.value.hostProfiles.any { it.id == profile.id }) {
                        repository.updateProfile(profile)
                    } else {
                        repository.addProfile(profile)
                    }
                }.onSuccess { clearSaveError() }
                    .onFailure { _ui.value = _ui.value.copy(error = saveFailed(it)) }
            }
        }
    }

    fun removeProfile(id: String) {
        viewModelScope.launch {
            runCatching { repository.removeProfile(id) }
                .onSuccess { clearSaveError() }
                .onFailure { _ui.value = _ui.value.copy(error = saveFailed(it)) }
        }
    }

    fun newProfileId(): String = UUID.randomUUID().toString()

    /**
     * Atomic dialog save: profile fields + optional password/token/key in ONE
     * coroutine, ONE read-modify-write. Separate launches race on stale reads
     * and clobber each other's aliases.
     *
     * Password semantics: null = untouched (editing other fields never wipes
     * it), blank = explicitly cleared, non-blank = replaced. Token: null or
     * blank = untouched, non-blank = replaced. Key PEM: null/blank = untouched,
     * non-blank = imported under [keyLabel].
     */
    fun saveProfileWithSecrets(
        profile: HostProfile,
        password: String?,
        token: String?,
        keyPem: String? = null,
        keyLabel: String? = null,
        isNew: Boolean = false,
    ) {
        viewModelScope.launch {
            // Two saves for the same profile can overlap: the editor closes
            // before the suspend write finishes. Serialize read -> persist ->
            // old-alias cleanup, otherwise the second save reads the first
            // save's soon-to-be-deleted alias and resurrects it in DataStore.
            repository.withProfileLock {
            val old = repository.settings.value.hostProfiles.firstOrNull { it.id == profile.id }
            val oldPassword = old?.passwordAlias
            val oldToken = old?.bearerTokenAlias
            val oldKey = old?.privateKeyAlias
            // A known_hosts entry belongs to host:port. Moving the profile to
            // another endpoint must allow a fresh TOFU approval; keeping the
            // old alias would make JSch strict mode reject the new server.
            val endpointChanged = old != null &&
                (old.host != profile.host || old.port != profile.port)
            val passwordChanged = password != null
            val tokenChanged = !token.isNullOrBlank()
            val keyChanged = !keyPem.isNullOrBlank()
            var newPassword: String? = null
            var newToken: String? = null
            var newKey: String? = null
            runCatching {
                if (!isNew && old == null) {
                    error("The profile was deleted before this save completed")
                }
                var base = old ?: profile
                // New aliases only: never overwrite the credential that the
                // still-persisted profile points at. The old alias is deleted
                // only after the durable profile write succeeds.
                if (passwordChanged) {
                    if (password!!.isBlank()) {
                        base = base.copy(passwordAlias = null)
                    } else {
                        newPassword = repository.storeSshPassword(null, password)
                        base = base.copy(passwordAlias = newPassword)
                    }
                }
                if (tokenChanged) {
                    newToken = repository.storeBearerToken(null, token!!)
                    base = base.copy(bearerTokenAlias = newToken)
                }
                if (keyChanged) {
                    newKey = repository.storePrivateKey(null, keyPem!!.trim(), keyLabel ?: profile.label)
                    base = base.copy(
                        privateKeyAlias = newKey,
                        privateKeyLabel = keyLabel ?: profile.label,
                    )
                }
                // Merge only the fields this dialog owns into the CURRENT
                // record. Copying the whole dialog record would overwrite a
                // host key accepted by TOFU while the dialog was open.
                val merged = base.copy(
                    label = profile.label,
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    daemonPort = profile.daemonPort,
                    daemonHost = profile.daemonHost,
                    useTls = profile.useTls,
                    backendMode = profile.backendMode,
                    hostKeyAlias = if (endpointChanged) null else base.hostKeyAlias,
                    passwordAlias = base.passwordAlias,
                    bearerTokenAlias = base.bearerTokenAlias,
                    privateKeyAlias = base.privateKeyAlias,
                    privateKeyLabel = base.privateKeyLabel,
                )
                if (isNew) repository.addProfile(merged) else repository.updateProfile(merged)
            }.onSuccess {
                if (passwordChanged && oldPassword != null && oldPassword != newPassword) {
                    runCatching { repository.deleteSshPassword(oldPassword) }
                }
                if (tokenChanged && oldToken != null && oldToken != newToken) {
                    runCatching { repository.deleteBearerToken(oldToken) }
                }
                if (keyChanged && oldKey != null && oldKey != newKey) {
                    runCatching { repository.deletePrivateKey(oldKey) }
                }
                clearSaveError()
            }.onFailure { failure ->
                newPassword?.let { runCatching { repository.deleteSshPassword(it) } }
                newToken?.let { runCatching { repository.deleteBearerToken(it) } }
                newKey?.let { runCatching { repository.deletePrivateKey(it) } }
                _ui.value = _ui.value.copy(error = saveFailed(failure))
            }
            }
        }
    }

    fun importPrivateKey(profileId: String, pem: String, label: String) {
        viewModelScope.launch {
            repository.withProfileLock {
            val current = repository.settings.value.hostProfiles.firstOrNull { it.id == profileId }
            val oldAlias = current?.privateKeyAlias
            var newAlias: String? = null
            runCatching {
                val base = current ?: error("No such profile: $profileId")
                newAlias = repository.storePrivateKey(null, pem, label)
                repository.updateProfile(base.copy(privateKeyAlias = newAlias, privateKeyLabel = label))
            }.onSuccess {
                if (oldAlias != null && oldAlias != newAlias) {
                    runCatching { repository.deletePrivateKey(oldAlias) }
                }
                clearSaveError()
            }.onFailure { failure ->
                newAlias?.let { runCatching { repository.deletePrivateKey(it) } }
                _ui.value = _ui.value.copy(error = saveFailed(failure))
            }
            }
        }
    }

    fun saveBearerToken(profileId: String, token: String) {
        viewModelScope.launch {
            repository.withProfileLock {
            val current = repository.settings.value.hostProfiles.firstOrNull { it.id == profileId }
            val oldAlias = current?.bearerTokenAlias
            var newAlias: String? = null
            runCatching {
                val base = current ?: error("No such profile: $profileId")
                if (token.isBlank()) {
                    repository.updateProfile(base.copy(bearerTokenAlias = null))
                } else {
                    newAlias = repository.storeBearerToken(null, token)
                    repository.updateProfile(base.copy(bearerTokenAlias = newAlias))
                }
            }.onSuccess {
                if (oldAlias != null && oldAlias != newAlias) {
                    runCatching { repository.deleteBearerToken(oldAlias) }
                }
                clearSaveError()
            }.onFailure { failure ->
                newAlias?.let { runCatching { repository.deleteBearerToken(it) } }
                _ui.value = _ui.value.copy(error = saveFailed(failure))
            }
            }
        }
    }

    /**
     * Blank [password] clears the stored password; otherwise it replaces it. Either way the
     * profile's [passwordAlias] tracks the keystore entry, so key auth alone is used when no
     * password is stored.
     */
    fun saveSshPassword(profileId: String, password: String) {
        viewModelScope.launch {
            repository.withProfileLock {
            val current = repository.settings.value.hostProfiles.firstOrNull { it.id == profileId }
            val oldAlias = current?.passwordAlias
            var newAlias: String? = null
            runCatching {
                val base = current ?: error("No such profile: $profileId")
                if (password.isBlank()) {
                    repository.updateProfile(base.copy(passwordAlias = null))
                } else {
                    newAlias = repository.storeSshPassword(null, password)
                    repository.updateProfile(base.copy(passwordAlias = newAlias))
                }
            }.onSuccess {
                if (oldAlias != null && oldAlias != newAlias) {
                    runCatching { repository.deleteSshPassword(oldAlias) }
                }
                clearSaveError()
            }.onFailure { failure ->
                newAlias?.let { runCatching { repository.deleteSshPassword(it) } }
                _ui.value = _ui.value.copy(error = saveFailed(failure))
            }
            }
        }
    }

    fun setExtraKeysEnabled(enabled: Boolean) = edit { it.copy(extraKeysEnabled = enabled) }
    fun setCjkInputEnabled(enabled: Boolean) = edit { it.copy(cjkInputEnabled = enabled) }
    fun setSwipeBehavior(behavior: SwipeBehavior) = edit { it.copy(swipeBehavior = behavior) }
    fun setHaptic(enabled: Boolean) = edit { it.copy(hapticFeedback = enabled) }
    fun setBellVibration(enabled: Boolean) = edit { it.copy(bellVibration = enabled) }
    fun setScrollbackLimit(limit: Int) = edit { it.copy(scrollbackLimit = limit.coerceIn(0, 10_000)) }

    fun setNotifyDone(enabled: Boolean) = edit { it.copy(notifyDone = enabled) }
    fun setNotifyBlocked(enabled: Boolean) = edit { it.copy(notifyBlocked = enabled) }
    fun setBackgroundMonitoring(enabled: Boolean) = edit { it.copy(backgroundMonitoring = enabled) }
    fun setShowScrollDiagnostics(enabled: Boolean) = edit { it.copy(showScrollDiagnostics = enabled) }

    /** Reject Direct URLs that cannot work with the plain-HTTP daemon. */
    private fun directUrlProblem(raw: String): String? {
        val uri = runCatching { java.net.URI(raw) }.getOrNull()
            ?: return "Enter a valid daemon URL"
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return "Use an http(s)://host:port daemon URL"
        }
        // REST resolves the API path against this base while the WebSocket
        // concatenates it, so a non-root path or query can validate against
        // one transport and fail on the other. Accept an origin only.
        if (!uri.path.isNullOrEmpty() && uri.path != "/") {
            return "Use only scheme://host:port; the daemon URL cannot contain a path"
        }
        if (uri.query != null || uri.fragment != null || uri.userInfo != null) {
            return "Use only scheme://host:port; remove query, fragment, or credentials"
        }
        val loopback = uri.host in setOf("127.0.0.1", "localhost", "::1", "[::1]")
        if (uri.scheme == "http" && !loopback) {
            return "Plain HTTP is only allowed on loopback; use HTTPS or the SSH tunnel"
        }
        return null
    }

    fun testConnection() {
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(testing = true, testResult = null, error = null, pendingHostKey = null)
            runCatching { repository.testConnection() }
                .onSuccess { result ->
                    val pending = (result as? ConnectionTestResult.UnknownHostKey)?.let {
                        PendingHostKey(
                            profileId = it.profileId,
                            host = it.host,
                            fingerprint = it.fingerprint,
                            knownHostsLine = it.knownHostsLine,
                        )
                    }
                    _ui.value = _ui.value.copy(testing = false, testResult = result, pendingHostKey = pending)
                }
                .onFailure {
                    // A cancelled (superseded) test must not report: it would
                    // clobber the new run's testing=true with a phantom error.
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    _ui.value = _ui.value.copy(
                        testing = false,
                        error = UiMessage.CommandFailed(
                            UiMessage.CommandFailed.Command.TEST_CONNECTION,
                            it.message,
                        ),
                    )
                }
        }
    }

    /**
     * Approve the pending unknown host key (TOFU): persist the known_hosts line, then
     * re-run the connection test so success is visible immediately.
     */
    fun approveHostKey() {
        val pending = _ui.value.pendingHostKey ?: return
        viewModelScope.launch {
            runCatching { repository.acceptHostKey(pending.profileId, pending.knownHostsLine) }
                .onSuccess {
                    _ui.value = _ui.value.copy(pendingHostKey = null)
                    testConnection()
                }
                .onFailure {
                    _ui.value = _ui.value.copy(
                        error = UiMessage.CommandFailed(
                            UiMessage.CommandFailed.Command.APPROVE_HOST_KEY,
                            it.message,
                        ),
                    )
                }
        }
    }

    fun dismissHostKey() {
        _ui.value = _ui.value.copy(pendingHostKey = null)
    }

    fun clearError() {
        _ui.value = _ui.value.copy(error = null, testResult = null)
    }

    class Factory(private val repository: SettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repository) as T
    }
}
