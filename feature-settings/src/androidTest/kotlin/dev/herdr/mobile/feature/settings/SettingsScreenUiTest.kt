package dev.herdr.mobile.feature.settings

import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.core.designsystem.HerdrMobileTheme
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.ConnectionTestResult
import dev.herdr.mobile.core.model.HerdrInfo
import dev.herdr.mobile.core.model.HostProfile
import dev.herdr.mobile.core.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * Settings rendered against an in-memory repository.
 *
 * The assertions are about the loop that actually persists things: a tap on a
 * row's control must reach [SettingsRepository.update], and the rendered state
 * must come back from the stored values (never from a local guess).
 */
class SettingsScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    /** In-memory repository; every persisted value is recorded in order. */
    private class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
        private val _settings = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

        val updates = mutableListOf<AppSettings>()

        override suspend fun awaitLoaded() = Unit

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            val next = transform(_settings.value)
            updates += next
            _settings.value = next
        }

        override suspend fun storePrivateKey(alias: String?, pem: String, label: String) =
            alias ?: "private-key"

        override suspend fun deletePrivateKey(alias: String) = Unit

        override suspend fun storeSshPassword(alias: String?, password: String): String? = alias

        override suspend fun deleteSshPassword(alias: String) = Unit

        override suspend fun storeBearerToken(alias: String?, token: String) = alias ?: "bearer-token"

        override suspend fun deleteBearerToken(alias: String) = Unit

        override suspend fun storeKnownHosts(alias: String?, knownHosts: String) =
            alias ?: "known-hosts"

        override suspend fun addProfile(profile: HostProfile) = Unit
        override suspend fun updateProfile(profile: HostProfile) = Unit
        override suspend fun removeProfile(profileId: String) = Unit
        override suspend fun setActiveProfile(profileId: String?) = Unit

        override suspend fun testConnection(): ConnectionTestResult =
            ConnectionTestResult.Success(
                latencyMs = 1,
                herdr = HerdrInfo(version = "0.9.5"),
                revision = 1,
                workspaceCount = 0,
            )

        override suspend fun acceptHostKey(profileId: String, knownHostsLine: String) =
            "known-hosts"
    }

    private fun render(repository: SettingsRepository) {
        compose.setContent {
            val viewModel = remember { SettingsViewModel(repository) }
            HerdrMobileTheme(themeMode = ThemeMode.DARK) {
                SettingsScreen(viewModel = viewModel)
            }
        }
        compose.waitForIdle()
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    /**
     * The switch that belongs to the row with [headline]: the only `toggleable`
     * node whose vertical centre sits on that row's label.
     */
    private fun rowToggle(headline: String): SemanticsNodeInteraction {
        // Bring the row fully on screen: a switch below the fold is composed
        // but its centre is outside the window, so a tap would be dropped.
        scrollTo(headline)
        val label = compose.onNodeWithText(headline, useUnmergedTree = true)
            .fetchSemanticsNode()
        val labelCenterY = label.boundsInRoot.center.y
        val toggles = compose.onAllNodes(isToggleable(), useUnmergedTree = true)
        val nodes = toggles.fetchSemanticsNodes()
        val index = nodes.indices.minByOrNull {
            abs(nodes[it].boundsInRoot.center.y - labelCenterY)
        }
        assertNotNull("no switch found for \"$headline\"", index)
        val distance = abs(nodes[index!!].boundsInRoot.center.y - labelCenterY)
        val tolerance = with(compose.density) { 32.dp.toPx() }
        assertTrue(
            "the nearest switch is not on the \"$headline\" row (Δ=${distance}px)",
            distance <= tolerance,
        )
        return toggles[index]
    }

    @Test
    fun togglingDonePersistsTheNewValue() {
        val repository = FakeSettingsRepository(AppSettings(notifyDone = true))
        render(repository)

        rowToggle("Done").assertIsOn().performClick()
        compose.waitForIdle()

        assertFalse(repository.settings.value.notifyDone)
        assertFalse(repository.updates.last().notifyDone)
        // The rendered state follows the stored value, not a local toggle.
        rowToggle("Done").assertIsOff()
        // Only the tapped setting changed.
        assertTrue(repository.settings.value.notifyBlocked)
    }

    @Test
    fun notificationTogglesReflectStoredValues() {
        val repository = FakeSettingsRepository(
            AppSettings(
                notifyDone = false,
                notifyBlocked = false,
                backgroundMonitoring = false,
            ),
        )
        render(repository)

        rowToggle("Done").assertIsOff()
        rowToggle("Blocked").assertIsOff()
        rowToggle("Background monitoring").assertIsOff()

        rowToggle("Blocked").performClick()
        compose.waitForIdle()

        assertTrue(repository.settings.value.notifyBlocked)
        assertTrue(repository.updates.last().notifyBlocked)
        rowToggle("Blocked").assertIsOn()
        rowToggle("Done").assertIsOff()
    }

    @Test
    fun fontSizeStepperCyclesWithinItsBounds() {
        val repository = FakeSettingsRepository(AppSettings(terminalFontSizeSp = 16))
        render(repository)

        scrollTo("Font size")
        compose.onNodeWithText("16sp").assertIsDisplayed()

        // 16 is the top of the cycle: the next tap wraps to 10, never past the
        // view model's 8..24 clamp.
        compose.onNodeWithText("16sp").performClick()
        compose.waitForIdle()
        assertEquals(10, repository.settings.value.terminalFontSizeSp)
        compose.onNodeWithText("10sp").assertIsDisplayed()
        compose.onNodeWithText("10 sp · tap to cycle 10 → 12 → 14 → 16").assertIsDisplayed()

        compose.onNodeWithText("10sp").performClick()
        compose.waitForIdle()
        assertEquals(12, repository.settings.value.terminalFontSizeSp)

        compose.onNodeWithText("12sp").performClick()
        compose.waitForIdle()
        assertEquals(14, repository.settings.value.terminalFontSizeSp)

        compose.onNodeWithText("14sp").performClick()
        compose.waitForIdle()
        assertEquals(16, repository.settings.value.terminalFontSizeSp)

        assertTrue(
            "stepper left its supported range: ${repository.updates.map { it.terminalFontSizeSp }}",
            repository.updates.all { it.terminalFontSizeSp in 8..24 },
        )
        assertEquals(4, repository.updates.size)
    }
}
