package dev.herdr.mobile.terminal.view

import android.view.KeyEvent
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The hardware-key path of [TermuxViewClient]: what reaches the network for a
 * key. This is the Termux fork's most error-prone seam — anything unclaimed
 * falls through to Termux's own handler, whose bytes go to the dead local PTY
 * instead of the pane.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermuxViewClientKeyTest {

    private val bytes = mutableListOf<Byte>()
    private val texts = mutableListOf<String>()
    private var deletes = 0
    private val pages = mutableListOf<Boolean>()
    private val session = TerminalSession("", "", arrayOf(), arrayOf(), 1000, TermuxSessionClient())

    private fun client(host: TermuxTerminalHost? = null): TermuxViewClient {
        val client = TermuxViewClient()
        client.onDirectBytes = { bytes += it.toList() }
        client.onDirectInput = { texts += it }
        client.onDirectDelete = { deletes++ }
        client.onPageScroll = { pages += it }
        client.host = host
        return client
    }

    private fun key(keyCode: Int, metaState: Int = 0) =
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)

    private fun text() = bytes.toByteArray().toString(Charsets.ISO_8859_1)

    @Test
    fun `an arrow is sent as its xterm sequence, not left to Termux`() {
        assertTrue(client().onKeyDown(KeyEvent.KEYCODE_DPAD_UP, key(KeyEvent.KEYCODE_DPAD_UP), session))
        assertEquals("\u001b[A", text())
    }

    @Test
    fun `delete travels its own path so the screen sends DEL verbatim`() {
        assertTrue(client().onKeyDown(KeyEvent.KEYCODE_DEL, key(KeyEvent.KEYCODE_DEL), session))
        assertEquals(1, deletes)
        assertTrue(bytes.isEmpty())
    }

    @Test
    fun `ctrl-letter becomes the control byte`() {
        val ctrlC = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON)
        assertTrue(client().onKeyDown(KeyEvent.KEYCODE_C, ctrlC, session))
        assertEquals(listOf<Byte>(0x03), bytes)
    }

    @Test
    fun `alt-letter is prefixed with escape`() {
        val altB = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, 0, KeyEvent.META_ALT_LEFT_ON)
        assertTrue(client().onKeyDown(KeyEvent.KEYCODE_B, altB, session))
        assertEquals(listOf<Byte>(0x1b, 'b'.code.toByte()), bytes)
    }

    @Test
    fun `a printable key travels as its utf-8 byte, not as text`() {
        val a = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, 0)
        assertTrue(client().onKeyDown(KeyEvent.KEYCODE_A, a, session))
        assertEquals(listOf<Byte>('a'.code.toByte()), bytes)
        assertTrue(texts.isEmpty())
    }

    @Test
    fun `shift-pgup pages remotely only when the host owns scrolling`() {
        val view = TerminalView(RuntimeEnvironment.getApplication(), null)
        val host = TermuxTerminalHost(view)
        host.diagnosticsAgent = "codex" // an agent pane scrolls by input
        val client = client(host)
        val shiftPgUp = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_UP, 0, KeyEvent.META_SHIFT_ON)
        assertTrue(client.onKeyDown(KeyEvent.KEYCODE_PAGE_UP, shiftPgUp, session))
        assertEquals(listOf(true), pages)
    }

    @Test
    fun `shift-pgup never pages a pane whose host does not own scrolling`() {
        val view = TerminalView(RuntimeEnvironment.getApplication(), null)
        val host = TermuxTerminalHost(view).apply {
            // The user forced local history scrolling: Termux owns the gesture.
            scrollMode = dev.herdr.mobile.core.model.ScrollMode.LOCAL
        }
        val client = client(host)
        val shiftPgUp = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_UP, 0, KeyEvent.META_SHIFT_ON)
        client.onKeyDown(KeyEvent.KEYCODE_PAGE_UP, shiftPgUp, session)
        assertTrue("must not page remotely", pages.isEmpty())
    }
}
