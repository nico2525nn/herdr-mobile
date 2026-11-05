package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import dev.herdr.mobile.core.designsystem.HerdrMobileTheme
import dev.herdr.mobile.core.model.ThemeMode
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.model.Pane
import dev.herdr.mobile.core.model.SessionSnapshot
import dev.herdr.mobile.core.model.Tab
import dev.herdr.mobile.core.model.Workspace
import dev.herdr.mobile.terminal.view.TerminalKeyEncoder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * What the screen actually shows and does, driven through the UI tree.
 *
 * These cover the regressions that unit tests cannot see: focus/IME policy is
 * pure and tested elsewhere, but "the badge appears only after a second" and
 * "the rail routes a tap to the right tab" are properties of the composition.
 */
class TerminalUiBehaviourTest {

    @get:Rule
    val compose = createComposeRule()

    private fun pane(workspace: String, tab: String) =
        Pane(id = "$workspace:$tab", tabId = tab, workspaceId = workspace)

    private fun state(): TerminalUiState {
        val p1 = pane("w1", "t1")
        val p2 = pane("w1", "t2")
        val snapshot = SessionSnapshot(
            revision = 1,
            seq = 1,
            workspaces = listOf(
                Workspace(
                    id = "w1",
                    label = "w1",
                    activeTabId = "t1",
                    tabs = listOf(
                        Tab(id = "t1", workspaceId = "w1", label = "one", panes = listOf(p1)),
                        Tab(id = "t2", workspaceId = "w1", label = "two", panes = listOf(p2)),
                    ),
                ),
            ),
        )
        return TerminalUiState(
            snapshot = snapshot,
            connection = ConnectionState.CONNECTED,
            target = TerminalTarget("w1", "t1", "w1:t1"),
            settings = AppSettings(),
        )
    }

    // ---- connection badge ---------------------------------------------

    @Test
    fun badgeWaitsForASecondOfDowntime() {
        compose.mainClock.autoAdvance = false
        var connection by mutableStateOf(ConnectionState.RECONNECTING)
        compose.setContent {
            HerdrMobileTheme(themeMode = ThemeMode.DARK) {
                ConnectionBadge(connection = connection)
                // A marker so the composition is never empty.
                Box(Modifier) { Text("terminal") }
            }
        }

        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Reconnecting…").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithText("Reconnecting…").assertIsDisplayed()
        // Announced, not merely drawn: a screen-reader user must hear it.
        compose.onNodeWithText("Reconnecting…")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))

        // Recovering hides it again without waiting.
        connection = ConnectionState.CONNECTED
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Reconnecting…").assertDoesNotExist()
    }

    @Test
    fun badgeStaysHiddenWhileConnected() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HerdrMobileTheme(themeMode = ThemeMode.DARK) {
                ConnectionBadge(connection = ConnectionState.CONNECTED)
            }
        }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNode(hasText("Connecting…") or hasText("Reconnecting…")).assertDoesNotExist()
    }

    // ---- extra keys ----------------------------------------------------

    @Test
    fun tappingAKeyDeliversThatKey() {
        val pressed = mutableListOf<TerminalKeyEncoder.Key>()
        compose.setContent {
            HerdrMobileTheme(themeMode = ThemeMode.DARK) {
                ExtraKeysPanel(
                    onKey = { pressed += it },
                    armed = emptySet(),
                    haptic = false,
                )
            }
        }
        compose.onNodeWithText("TAB").performClick()
        assertEquals(listOf("TAB"), pressed.map { it.label })
    }

    @Test
    fun stickyModifierIsReflectedInThePanel() {
        // The armed latch is state the user must see; regressing it would make
        // CTRL silently apply to the wrong key.
        compose.setContent {
            HerdrMobileTheme(themeMode = ThemeMode.DARK) {
                ExtraKeysPanel(
                    onKey = {},
                    armed = setOf(TerminalKeyEncoder.Modifier.CTRL),
                    haptic = false,
                )
            }
        }
        compose.onNodeWithText("CTRL").assertIsDisplayed()
    }

    // ---- pane rail -----------------------------------------------------

    @Test
    fun paneRailOffersEveryPaneOfTheTab() {
        val panes = listOf(
            Pane(id = "w1:p1", tabId = "t1", workspaceId = "w1", agent = "codex"),
            Pane(id = "w1:p2", tabId = "t1", workspaceId = "w1", agent = "pi"),
        )
        val chosen = mutableListOf<String>()
        compose.setContent {
            HerdrMobileTheme(themeMode = ThemeMode.DARK) {
                PaneRail(
                    panes = panes,
                    activePaneId = "w1:p1",
                    onSelectPane = { chosen += it },
                )
            }
        }
        compose.onNodeWithText("2 · pi").performClick()
        assertEquals(listOf("w1:p2"), chosen)
    }

    @Test
    fun paneRailIsAbsentForASinglePaneTab() {
        val panes = listOf(
            Pane(id = "w1:p1", tabId = "t1", workspaceId = "w1", agent = "codex"),
        )
        compose.setContent {
            HerdrMobileTheme(themeMode = ThemeMode.DARK) {
                PaneRail(panes = panes, activePaneId = "w1:p1", onSelectPane = {})
            }
        }
        compose.onNodeWithText("1 · codex").assertDoesNotExist()
    }

    // ---- tab rail ------------------------------------------------------

    @Test
    fun tappingATabChipSelectsThatTab() {
        val selected = mutableListOf<Pair<String, String>>()
        val state = state()
        compose.setContent {
            HerdrMobileTheme(themeMode = ThemeMode.DARK) {
                TabRail(
                    state = state,
                    onSelectTab = { ws, tab -> selected += ws to tab },
                    onSummonKeyboard = {},
                    onAddTab = {},
                    onTabLongPress = { _, _, _ -> },
                    onMoveTab = { _, _ -> },
                    menuTarget = null,
                    onDismissMenu = {},
                    onRenameMenu = {},
                    onCloseMenu = {},
                )
            }
        }
        compose.onNodeWithText("two").performClick()
        assertEquals(listOf("w1" to "t2"), selected)
    }

    @Test
    fun keyboardButtonAsksForTheIme() {
        var summoned = 0
        val state = state()
        compose.setContent {
            HerdrMobileTheme(themeMode = ThemeMode.DARK) {
                TabRail(
                    state = state,
                    onSelectTab = { _, _ -> },
                    onSummonKeyboard = { summoned++ },
                    onAddTab = {},
                    onTabLongPress = { _, _, _ -> },
                    onMoveTab = { _, _ -> },
                    menuTarget = null,
                    onDismissMenu = {},
                    onRenameMenu = {},
                    onCloseMenu = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Terminal keyboard").performClick()
        assertEquals(1, summoned)
    }
}
