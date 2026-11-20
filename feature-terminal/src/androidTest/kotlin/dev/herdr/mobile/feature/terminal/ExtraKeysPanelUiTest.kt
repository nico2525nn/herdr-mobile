package dev.herdr.mobile.feature.terminal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import dev.herdr.mobile.core.designsystem.HerdrMobileTheme
import dev.herdr.mobile.core.model.ThemeMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The PGUP key is a dual-action key: tap sends PGUP, downward flick sends
 * PGDN. Accessibility services also need a non-gesture path to PGDN.
 */
class ExtraKeysPanelUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<TerminalKeyEncoder.Key>()

    private fun render() {
        compose.setContent {
            HerdrMobileTheme(themeMode = ThemeMode.DARK) {
                ExtraKeysPanel(
                    onKey = { sent += it },
                    armed = emptySet(),
                    haptic = false,
                )
            }
        }
    }

    private fun pgup() = compose.onNode(hasText("PGUP↓") and hasClickAction())

    @Test
    fun tapSendsPgupOnce() {
        render()
        pgup().performClick()
        assertEquals(1, sent.size)
        assertEquals("PGUP", sent.last().label)
        assertArrayEquals(
            byteArrayOf(0x1B, 0x5B, 0x35, 0x7E),
            requireNotNull(sent.last().bytes),
        )
    }

    @Test
    fun flickDownSendsPgdn() {
        render()
        pgup().performTouchInput {
            swipe(
                start = center,
                end = center + Offset(0f, 80f),
                durationMillis = 200,
            )
        }
        assertEquals(1, sent.count { it.label == "PGDN" })
        assertTrue(sent.none { it.label == "PGUP" })
        assertEquals("PGDN", sent.last().label)
        assertArrayEquals(
            byteArrayOf(0x1B, 0x5B, 0x36, 0x7E),
            requireNotNull(sent.last().bytes),
        )
    }

    @Test
    fun pgupExposesPageDownAccessibilityAction() {
        render()
        val actions = pgup().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertTrue(actions.any { it.label == "Page down" })
    }
    @Test
    fun tapNonDualKeySendsOnce() {
        render()
        compose.onNode(hasText("TAB") and hasClickAction()).performClick()
        assertEquals(1, sent.count { it.label == "TAB" })
    }

}
