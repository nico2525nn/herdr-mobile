package dev.herdr.mobile.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.herdr.mobile.core.model.AppSettings
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

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val testing: Boolean = false,
    val testResult: ConnectionTestResult? = null,
    val error: String? = null,
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

    private fun edit(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            runCatching { repository.update(transform) }
                .onFailure { _ui.value = _ui.value.copy(error = it.message) }
        }
    }

    fun setThemeMode(mode: ThemeMode) = edit { it.copy(themeMode = mode) }
    fun setDynamicColor(enabled: Boolean) = edit { it.copy(dynamicColor = enabled) }
    fun setTerminalScheme(scheme: TerminalColorScheme) = edit { it.copy(terminalColorScheme = scheme) }
    fun setTerminalFont(font: TerminalFont) = edit { it.copy(terminalFont = font) }
    fun setFontSize(sp: Int) = edit { it.copy(terminalFontSizeSp = sp.coerceIn(8, 24)) }
    fun setLineHeight(height: Float) = edit { it.copy(terminalLineHeight = height.coerceIn(1.0f, 2.0f)) }
    fun setCursorStyle(style: CursorStyle) = edit { it.copy(cursorStyle = style) }
    fun setBoldIsBright(enabled: Boolean) = edit { it.copy(boldIsBright = enabled) }

    fun setTransportMode(mode: TransportMode) = edit { it.copy(transportMode = mode) }
    fun setDirectUrl(url: String) = edit { it.copy(directUrl = url.trim()) }
    fun setActiveProfile(id: String?) {
        viewModelScope.launch { runCatching { repository.setActiveProfile(id) } }
    }

    fun saveProfile(profile: HostProfile) {
        viewModelScope.launch {
            runCatching {
                if (repository.settings.value.hostProfiles.any { it.id == profile.id }) {
                    repository.updateProfile(profile)
                } else {
                    repository.addProfile(profile)
                }
            }.onFailure { _ui.value = _ui.value.copy(error = it.message) }
        }
    }

    fun removeProfile(id: String) {
        viewModelScope.launch { runCatching { repository.removeProfile(id) } }
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
    ) {
        viewModelScope.launch {
            runCatching {
                var base = repository.settings.value.hostProfiles
                    .firstOrNull { it.id == profile.id }
                    ?: profile
                // Secrets first (need current aliases), then ONE profile write.
                if (password != null) {
                    val alias = repository.storeSshPassword(base.passwordAlias, password)
                    base = base.copy(passwordAlias = alias)
                }
                if (!token.isNullOrBlank()) {
                    val alias = repository.storeBearerToken(base.bearerTokenAlias, token)
                    base = base.copy(bearerTokenAlias = alias)
                }
                if (!keyPem.isNullOrBlank()) {
                    val alias = repository.storePrivateKey(
                        base.privateKeyAlias,
                        keyPem.trim(),
                        keyLabel ?: profile.label,
                    )
                    base = base.copy(
                        privateKeyAlias = alias,
                        privateKeyLabel = keyLabel ?: profile.label,
                    )
                }
                val merged = profile.copy(
                    passwordAlias = base.passwordAlias,
                    bearerTokenAlias = base.bearerTokenAlias,
                    privateKeyAlias = base.privateKeyAlias,
                    privateKeyLabel = base.privateKeyLabel,
                )
                if (repository.settings.value.hostProfiles.any { it.id == merged.id }) {
                    repository.updateProfile(merged)
                } else {
                    repository.addProfile(merged)
                }
            }.onFailure { _ui.value = _ui.value.copy(error = it.message) }
        }
    }

    fun importPrivateKey(profileId: String, pem: String, label: String) {
        viewModelScope.launch {
            runCatching {
                val current = repository.settings.value.hostProfiles.firstOrNull { it.id == profileId }
                val alias = repository.storePrivateKey(current?.privateKeyAlias, pem, label)
                val base = current ?: HostProfile(id = profileId, label = label, host = "", username = "")
                repository.updateProfile(base.copy(privateKeyAlias = alias, privateKeyLabel = label))
            }.onFailure { _ui.value = _ui.value.copy(error = it.message) }
        }
    }

    fun saveBearerToken(profileId: String, token: String) {
        viewModelScope.launch {
            runCatching {
                val current = repository.settings.value.hostProfiles.firstOrNull { it.id == profileId }
                val alias = repository.storeBearerToken(current?.bearerTokenAlias, token)
                val base = current ?: HostProfile(id = profileId, label = profileId, host = "", username = "")
                repository.updateProfile(base.copy(bearerTokenAlias = alias))
            }.onFailure { _ui.value = _ui.value.copy(error = it.message) }
        }
    }

    /**
     * Blank [password] clears the stored password; otherwise it replaces it. Either way the
     * profile's [passwordAlias] tracks the keystore entry, so key auth alone is used when no
     * password is stored.
     */
    fun saveSshPassword(profileId: String, password: String) {
        viewModelScope.launch {
            runCatching {
                val current = repository.settings.value.hostProfiles.firstOrNull { it.id == profileId }
                val alias = repository.storeSshPassword(current?.passwordAlias, password)
                val base = current ?: HostProfile(id = profileId, label = profileId, host = "", username = "")
                repository.updateProfile(base.copy(passwordAlias = alias))
            }.onFailure { _ui.value = _ui.value.copy(error = it.message) }
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
                        error = it.message,
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
                .onFailure { _ui.value = _ui.value.copy(error = it.message) }
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
