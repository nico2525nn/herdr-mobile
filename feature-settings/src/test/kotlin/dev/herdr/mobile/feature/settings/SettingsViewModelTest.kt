package dev.herdr.mobile.feature.settings

import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.BackendMode
import dev.herdr.mobile.core.model.ConnectionTestResult
import dev.herdr.mobile.core.model.HostProfile
import dev.herdr.mobile.core.model.TransportMode
import dev.herdr.mobile.core.model.TerminalColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** In-memory repository; records the profile operations the view model drives. */
    private class FakeRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
        private val _settings = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = _settings

        var failure: Exception? = null
        val added = mutableListOf<HostProfile>()
        val updated = mutableListOf<HostProfile>()
        val removed = mutableListOf<String>()
        var active: String? = null

        override suspend fun awaitLoaded() = Unit

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            failure?.let { throw it }
            _settings.value = transform(_settings.value)
        }

        override suspend fun storePrivateKey(alias: String?, pem: String, label: String) = alias ?: "key"
        override suspend fun deletePrivateKey(alias: String) = Unit
        override suspend fun storeSshPassword(alias: String?, password: String): String? = alias
        override suspend fun deleteSshPassword(alias: String) = Unit
        override suspend fun storeBearerToken(alias: String?, token: String) = alias ?: "token"
        override suspend fun deleteBearerToken(alias: String) = Unit
        override suspend fun storeKnownHosts(alias: String?, knownHosts: String) = alias ?: "kh"

        override suspend fun addProfile(profile: HostProfile) {
            failure?.let { throw it }
            added += profile
            _settings.value = _settings.value.copy(hostProfiles = _settings.value.hostProfiles + profile)
        }

        override suspend fun updateProfile(profile: HostProfile) {
            failure?.let { throw it }
            updated += profile
            _settings.value = _settings.value.copy(
                hostProfiles = _settings.value.hostProfiles.map {
                    if (it.id == profile.id) profile else it
                },
            )
        }

        override suspend fun removeProfile(profileId: String) {
            removed += profileId
        }

        override suspend fun setActiveProfile(profileId: String?) {
            active = profileId
        }

        override suspend fun testConnection(): ConnectionTestResult =
            ConnectionTestResult.Success(
                latencyMs = 1,
                herdr = dev.herdr.mobile.core.model.HerdrInfo(version = "0.9.5"),
                revision = 1,
                workspaceCount = 0,
            )

        override suspend fun acceptHostKey(profileId: String, knownHostsLine: String) = "kh"
    }

    private fun profile(id: String) = HostProfile(
        id = id,
        label = id,
        host = "host-$id",
        username = "nico",
    )

    @Test
    fun `numeric settings are clamped to their supported range`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val model = SettingsViewModel(repo)
        advanceUntilIdle()

        model.setFontSize(sp = 99)
        model.setLineHeight(height = 0.1f)
        model.setScrollbackLimit(limit = 999_999)
        advanceUntilIdle()

        val settings = repo.settings.value
        assertEquals(24, settings.terminalFontSizeSp)
        assertEquals(1.0f, settings.terminalLineHeight, 0.0001f)
        assertEquals(10_000, settings.scrollbackLimit)
    }

    @Test
    fun `the scroll lever and colour scheme persist through the repository`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val model = SettingsViewModel(repo)
        advanceUntilIdle()

        model.setScrollMode(dev.herdr.mobile.core.model.ScrollMode.PAGE)
        model.setTerminalScheme(TerminalColorScheme.SOLARIZED_DARK)
        advanceUntilIdle()

        assertEquals(dev.herdr.mobile.core.model.ScrollMode.PAGE, repo.settings.value.scrollMode)
        assertEquals(TerminalColorScheme.SOLARIZED_DARK, repo.settings.value.terminalColorScheme)
    }

    @Test
    fun `a direct connection edits the direct backend, a tunnel edits the active profile`() =
        runTest(dispatcher) {
            val repo = FakeRepository(
                AppSettings(
                    transportMode = TransportMode.DIRECT,
                    hostProfiles = listOf(profile("a")),
                ),
            )
            val model = SettingsViewModel(repo)
            advanceUntilIdle()

            model.setBackendMode(BackendMode.CORE)
            advanceUntilIdle()
            assertEquals(BackendMode.CORE, repo.settings.value.directBackendMode)
            assertEquals(BackendMode.TUI, repo.settings.value.hostProfiles.single().backendMode)

            model.setTransportMode(TransportMode.SSH)
            model.setActiveProfile("a")
            model.setBackendMode(BackendMode.CORE)
            advanceUntilIdle()
            assertEquals(BackendMode.CORE, repo.settings.value.hostProfiles.single().backendMode)
        }

    @Test
    fun `saving a known profile updates it, an unknown one is added`() = runTest(dispatcher) {
        val repo = FakeRepository(AppSettings(hostProfiles = listOf(profile("a"))))
        val model = SettingsViewModel(repo)
        advanceUntilIdle()

        model.saveProfile(profile("a"))
        model.saveProfile(profile("b"))
        advanceUntilIdle()

        assertEquals(listOf("a"), repo.updated.map { it.id })
        assertEquals(listOf("b"), repo.added.map { it.id })
    }

    @Test
    fun `a failed save surfaces an error and keeps the settings`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { failure = IllegalStateException("disk full") }
        val model = SettingsViewModel(repo)
        advanceUntilIdle()

        model.setDynamicColor(false)
        advanceUntilIdle()

        assertNotNull(model.uiState.value.error)
        assertEquals(true, repo.settings.value.dynamicColor)
    }
}
