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
    fun setNotifyFailed(enabled: Boolean) = edit { it.copy(notifyFailed = enabled) }

    fun testConnection() {
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(testing = true, testResult = null, error = null)
            runCatching { repository.testConnection() }
                .onSuccess { _ui.value = _ui.value.copy(testing = false, testResult = it) }
                .onFailure {
                    _ui.value = _ui.value.copy(
                        testing = false,
                        error = it.message,
                    )
                }
        }
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
