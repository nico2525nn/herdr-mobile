package dev.herdr.mobile.terminal.view

import dev.herdr.mobile.core.model.InputPanelPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The soft-keyboard policy. Each case is a real device scenario that used to
 * be encoded in a different place of the screen, which is how the keyboard
 * ended up raising itself on return from the background.
 */
class ImePolicyTest {

    @Test
    fun `keyboard button raises the terminal IME`() {
        assertEquals(
            ImeCommand(focus = ImeTarget.TERMINAL, show = true),
            imeCommand(ImeTrigger.KEYBOARD_BUTTON, InputPanelPage.EXTRA_KEYS, imeVisible = false),
        )
    }

    @Test
    fun `keyboard button on the CJK page targets the draft field`() {
        assertEquals(
            ImeCommand(focus = ImeTarget.CJK_FIELD),
            imeCommand(ImeTrigger.KEYBOARD_BUTTON, InputPanelPage.CJK_INPUT, imeVisible = false),
        )
    }

    @Test
    fun `panel swipe with the keyboard closed changes nothing`() {
        for (page in InputPanelPage.entries) {
            assertEquals(
                ImeCommand(),
                imeCommand(ImeTrigger.PAGE_SWITCH, page, imeVisible = false),
            )
        }
    }

    @Test
    fun `panel swipe with the keyboard open keeps it and moves the target`() {
        assertEquals(
            ImeCommand(focus = ImeTarget.CJK_FIELD),
            imeCommand(ImeTrigger.PAGE_SWITCH, InputPanelPage.CJK_INPUT, imeVisible = true),
        )
        assertEquals(
            ImeCommand(focus = ImeTarget.TERMINAL),
            imeCommand(ImeTrigger.PAGE_SWITCH, InputPanelPage.EXTRA_KEYS, imeVisible = true),
        )
    }

    @Test
    fun `pane switch always drops the previous keyboard`() {
        assertEquals(
            ImeCommand(hide = true, focus = ImeTarget.TERMINAL),
            imeCommand(ImeTrigger.PANE_SWITCH, InputPanelPage.EXTRA_KEYS, imeVisible = true),
        )
        assertEquals(
            ImeCommand(hide = true),
            imeCommand(ImeTrigger.PANE_SWITCH, InputPanelPage.CJK_INPUT, imeVisible = false),
        )
    }

    @Test
    fun `returning to the foreground never summons the keyboard`() {
        for (visible in listOf(true, false)) {
            val command = imeCommand(ImeTrigger.FOREGROUND, InputPanelPage.EXTRA_KEYS, visible)
            assertEquals("foreground must never show the IME", false, command.show)
            assertEquals("foreground must not steal focus", ImeTarget.NONE, command.focus)
            assertEquals("self-raised IME is hidden on return", true, command.hideUnrequested)
        }
    }
}
