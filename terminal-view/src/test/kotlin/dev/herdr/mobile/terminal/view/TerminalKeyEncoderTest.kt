package dev.herdr.mobile.terminal.view

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalKeyEncoderTest {
    // AOSP keycode literals (android.* is unavailable on JVM tests; values are
    // stable platform constants, verified against KeyEvent docs).
    private val DPAD_UP = 19
    private val DPAD_DOWN = 20
    private val DPAD_LEFT = 21
    private val DPAD_RIGHT = 22
    private val DPAD_CENTER = 23
    private val ENTER = 66
    private val ESCAPE = 111
    private val TAB = 61
    private val DEL = 67
    private val SPACE = 62
    private val MOVE_HOME = 122
    private val MOVE_END = 123
    private val PAGE_UP = 92
    private val PAGE_DOWN = 93
    private val NUM_LOCK = 143
    private val INSERT = 124
    private val FORWARD_DEL = 112
    private val SYSRQ = 120
    private val BREAK = 121
    private val BACK = 4
    private val F1 = 131
    private val F5 = 135
    private val F12 = 142

    private fun code(keyCode: Int, mode: Int = 0, cursorApp: Boolean = false, keypadApp: Boolean = false) =
        TerminalKeyEncoder.termuxKey(keyCode, mode, cursorApp, keypadApp)

    @Test
    fun `termux arrows plain and app mode`() {
        assertEquals("\u001B[A", code(DPAD_UP))
        assertEquals("\u001B[B", code(DPAD_DOWN))
        assertEquals("\u001B[C", code(DPAD_RIGHT))
        assertEquals("\u001B[D", code(DPAD_LEFT))
        assertEquals("\u001BOA", code(DPAD_UP, cursorApp = true))
        assertEquals("\r", code(DPAD_CENTER))
    }

    @Test
    fun `termux arrows with modifiers`() {
        val c = TerminalKeyEncoder.KEYMOD_CTRL
        val s = TerminalKeyEncoder.KEYMOD_SHIFT
        val a = TerminalKeyEncoder.KEYMOD_ALT
        assertEquals("\u001B[1;5A", code(DPAD_UP, c))
        assertEquals("\u001B[1;2B", code(DPAD_DOWN, s))
        assertEquals("\u001B[1;3C", code(DPAD_RIGHT, a))
        assertEquals("\u001B[1;7D", code(DPAD_LEFT, c or a))
    }

    @Test
    fun `termux home end insert delete`() {
        assertEquals("\u001B[H", code(MOVE_HOME))
        assertEquals("\u001B[F", code(MOVE_END))
        assertEquals("\u001BOH", code(MOVE_HOME, cursorApp = true))
        assertEquals("\u001B[2~", code(INSERT))
        assertEquals("\u001B[3~", code(FORWARD_DEL))
        assertEquals("\u001B[3;5~", code(FORWARD_DEL, TerminalKeyEncoder.KEYMOD_CTRL))
        assertEquals("\u001B[5~", code(PAGE_UP))
        assertEquals("\u001B[6~", code(PAGE_DOWN))
        assertEquals(null, code(NUM_LOCK))
        assertEquals("\u001BOP", code(NUM_LOCK, keypadApp = true))
    }

    @Test
    fun `termux function keys`() {
        assertEquals("\u001BOP", code(F1))
        assertEquals("\u001B[15~", code(F5))
        assertEquals("\u001B[24~", code(F12))
        assertEquals("\u001B[1;5P", code(F1, TerminalKeyEncoder.KEYMOD_CTRL))
    }

    @Test
    fun `termux del enter tab space esc back`() {
        assertEquals("\u007F", code(DEL))
        assertEquals("\u0008", code(DEL, TerminalKeyEncoder.KEYMOD_CTRL))
        assertEquals("\u001B\u007F", code(DEL, TerminalKeyEncoder.KEYMOD_ALT))
        assertEquals("\r", code(ENTER))
        assertEquals("\u001B\r", code(ENTER, TerminalKeyEncoder.KEYMOD_ALT))
        assertEquals("\u0009", code(TAB))
        assertEquals("\u001B[Z", code(TAB, TerminalKeyEncoder.KEYMOD_SHIFT))
        assertEquals(null, code(SPACE))
        assertEquals("\u0000", code(SPACE, TerminalKeyEncoder.KEYMOD_CTRL))
        assertEquals("\u001B", code(ESCAPE))
        assertEquals("\u001B", code(BACK))
        assertEquals("\u001B[32~", code(SYSRQ))
        assertEquals("\u001B[34~", code(BREAK))
    }

    @Test
    fun `termux printables fall through`() {
        // Letters/digits return null (unicode path handles them).
        assertEquals(null, code(29)) // KEYCODE_A
        assertEquals(null, code(7)) // KEYCODE_0
    }

    @Test
    fun `termux codepoint ctrl table`() {
        fun cp(c: Int) = TerminalKeyEncoder.termuxCodePoint(c, ctrlDown = true, altDown = false)
        assertArrayEquals(byteArrayOf(0x01), cp('a'.code))
        assertArrayEquals(byteArrayOf(0x1A), cp('Z'.code))
        assertArrayEquals(byteArrayOf(0x00), cp(' '.code))
        assertArrayEquals(byteArrayOf(0x1B), cp('['.code))
        assertArrayEquals(byteArrayOf(0x1C), cp('\\'.code))
        assertArrayEquals(byteArrayOf(0x1D), cp(']'.code))
        assertArrayEquals(byteArrayOf(0x1E), cp('^'.code))
        assertArrayEquals(byteArrayOf(0x1F), cp('/'.code))
        assertArrayEquals(byteArrayOf(0x7F), cp('8'.code))
    }

    @Test
    fun `termux codepoint alt prefix and bt quirks`() {
        val alt = TerminalKeyEncoder.termuxCodePoint('b'.code, ctrlDown = false, altDown = true)
        assertArrayEquals(byteArrayOf(0x1B, 'b'.code.toByte()), alt)
        // Bluetooth modifier-letter variants normalize to ASCII.
        assertArrayEquals(
            "~".toByteArray(),
            TerminalKeyEncoder.termuxCodePoint(0x02DC, ctrlDown = false, altDown = false),
        )
        // CJK passes through as UTF-8.
        assertArrayEquals(
            "あ".toByteArray(Charsets.UTF_8),
            TerminalKeyEncoder.termuxCodePoint("あ"[0].code, ctrlDown = false, altDown = false),
        )
    }


    @Test
    fun `default layout has two rows of seven`() {
        assertEquals(2, TerminalKeyEncoder.DEFAULT_LAYOUT.size)
        assertEquals(7, TerminalKeyEncoder.DEFAULT_LAYOUT[0].size)
        assertEquals(7, TerminalKeyEncoder.DEFAULT_LAYOUT[1].size)
    }

    @Test
    fun `arrows encode as CSI`() {
        val row1 = TerminalKeyEncoder.DEFAULT_LAYOUT[0]
        val up = row1.first { it.label == "↑" }
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()), up.bytes)
    }

    @Test
    fun `ctrl modifier maps letters to control codes`() {
        val c = TerminalKeyEncoder.Key("c", bytes = byteArrayOf('c'.code.toByte()))
        assertArrayEquals(
            byteArrayOf(0x03),
            TerminalKeyEncoder.withModifier(TerminalKeyEncoder.Modifier.CTRL, c),
        )
    }

    @Test
    fun `alt modifier prefixes escape`() {
        val x = TerminalKeyEncoder.Key("x", bytes = byteArrayOf('x'.code.toByte()))
        assertArrayEquals(
            byteArrayOf(0x1B, 'x'.code.toByte()),
            TerminalKeyEncoder.withModifier(TerminalKeyEncoder.Modifier.ALT, x),
        )
    }

    @Test
    fun `interrupt is ctrl-c byte`() {
        assertArrayEquals(byteArrayOf(0x03), TerminalKeyEncoder.interrupt())
    }

    @Test
    fun `withModifiers maps single char to ctrl chord`() {
        assertArrayEquals(
            byteArrayOf(0x03),
            TerminalKeyEncoder.withModifiers(setOf(TerminalKeyEncoder.Modifier.CTRL), "c"),
        )
    }

    @Test
    fun `withModifiers stacks ctrl and alt`() {
        assertArrayEquals(
            byteArrayOf(0x1B, 0x03),
            TerminalKeyEncoder.withModifiers(
                setOf(TerminalKeyEncoder.Modifier.CTRL, TerminalKeyEncoder.Modifier.ALT),
                "c",
            ),
        )
    }

    @Test
    fun `withModifiers rejects paste and non-ascii`() {
        val ctrl = setOf(TerminalKeyEncoder.Modifier.CTRL)
        assertEquals(null, TerminalKeyEncoder.withModifiers(ctrl, "ab"))
        assertEquals(null, TerminalKeyEncoder.withModifiers(ctrl, "あ"))
        assertEquals(null, TerminalKeyEncoder.withModifiers(emptySet(), "c"))
    }

    @Test
    fun `layout has no letter row`() {
        // Ctrl chords come from the soft keyboard with the latch armed.
        val flat = TerminalKeyEncoder.DEFAULT_LAYOUT.flatten()
        assertTrue(flat.none { it.label.length == 1 && it.label[0].isLetter() && it.bytes?.size == 1 })
    }

    @Test
    fun `pasteNormalize maps newlines to CR`() {
        // Termux paste(): \n and CRLF both become what Enter sends.
        assertEquals("a\rb", TerminalKeyEncoder.pasteNormalize("a\nb"))
        assertEquals("a\rb", TerminalKeyEncoder.pasteNormalize("a\r\nb"))
        assertEquals("a\rb\r", TerminalKeyEncoder.pasteNormalize("a\nb\n"))
        assertEquals("plain", TerminalKeyEncoder.pasteNormalize("plain"))
    }

    @Test
    fun `pasteNormalize strips ESC and C1`() {
        assertEquals("ab", TerminalKeyEncoder.pasteNormalize("a\u001Bb"))
        assertEquals("ab", TerminalKeyEncoder.pasteNormalize("a\u0085b"))
        // CJK passes through untouched.
        assertEquals("あい", TerminalKeyEncoder.pasteNormalize("あい"))
    }

    @Test
    fun `withModifiers rejects meaningless ctrl combo`() {
        // CTRL+TAB has no control byte: null keeps the latch armed.
        assertEquals(
            null,
            TerminalKeyEncoder.withModifiers(setOf(TerminalKeyEncoder.Modifier.CTRL), "\t"),
        )
    }

    @Test
    fun `shift uppercases letters`() {
        val shift = setOf(TerminalKeyEncoder.Modifier.SHIFT)
        assertArrayEquals(
            byteArrayOf('C'.code.toByte()),
            TerminalKeyEncoder.withModifiers(shift, "c"),
        )
        // Already upper / non-letter: pass through.
        assertArrayEquals(
            byteArrayOf('C'.code.toByte()),
            TerminalKeyEncoder.withModifiers(shift, "C"),
        )
        assertArrayEquals(
            byteArrayOf('/'.code.toByte()),
            TerminalKeyEncoder.withModifiers(shift, "/"),
        )
    }

    @Test
    fun `shift rewrites CSI to modified form`() {
        fun shifted(vararg bytes: Byte): ByteArray =
            TerminalKeyEncoder.withShift(byteArrayOf(*bytes))
        assertArrayEquals(
            "\u001B[1;2A".toByteArray(Charsets.US_ASCII),
            shifted(0x1B, '['.code.toByte(), 'A'.code.toByte()),
        )
        assertArrayEquals(
            "\u001B[5;2~".toByteArray(Charsets.US_ASCII),
            shifted(0x1B, '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte()),
        )
        // Unknown sequences pass through, never swallowed.
        val unknown = byteArrayOf(0x1B, '['.code.toByte(), 'Z'.code.toByte())
        assertArrayEquals(unknown, shifted(0x1B, '['.code.toByte(), 'Z'.code.toByte()))
    }

    @Test
    fun `withModifiers stacks shift ctrl and alt`() {
        assertArrayEquals(
            byteArrayOf(0x1B, 0x03),
            TerminalKeyEncoder.withModifiers(
                setOf(
                    TerminalKeyEncoder.Modifier.SHIFT,
                    TerminalKeyEncoder.Modifier.CTRL,
                    TerminalKeyEncoder.Modifier.ALT,
                ),
                "c",
            ),
        )
    }

    @Test
    fun `layout keeps seven keys per row with shift and flick pg`() {
        val layout = TerminalKeyEncoder.DEFAULT_LAYOUT
        assertEquals(2, layout.size)
        assertTrue(layout.all { it.size == 7 })
        val flat = layout.flatten()
        assertTrue(flat.any { it.modifier == TerminalKeyEncoder.Modifier.SHIFT })
        val pg = flat.first { it.label == "PGUP" }
        assertArrayEquals(
            "\u001B[6~".toByteArray(Charsets.US_ASCII),
            pg.flickDownBytes,
        )
        assertEquals("PGDN", pg.flickDownLabel)
        // No standalone PGDN key anymore (flick replaces it).
        assertTrue(flat.none { it.label == "PGDN" && it.bytes != null })
    }

    @Test
    fun `bug class 1 bracket markers use core bytes and preserve TUI text input`() {
        val payload = TerminalKeyEncoder.pastePayload(
            "a\u001B[200~b\n",
            bracketed = true,
            binaryBackend = true,
        )

        // Regression for bug class #1: core framing ESC bytes bypass text
        // normalization, while TUI keeps its input.text route below.
        assertTrue(payload is TerminalKeyEncoder.PastePayload.Bytes)
        assertArrayEquals(
            "\u001B[200~a[200~b\r\u001B[201~".toByteArray(Charsets.UTF_8),
            (payload as TerminalKeyEncoder.PastePayload.Bytes).value,
        )

        val tuiPayload = TerminalKeyEncoder.pastePayload(
            "a\u001B[200~b\n",
            bracketed = true,
            binaryBackend = false,
        )
        assertTrue(tuiPayload is TerminalKeyEncoder.PastePayload.Text)
        assertEquals(
            "\u001B[200~a[200~b\r\u001B[201~",
            (tuiPayload as TerminalKeyEncoder.PastePayload.Text).value,
        )

        val plain = TerminalKeyEncoder.pastePayload(
            "a\nb",
            bracketed = false,
            binaryBackend = true,
        )
        assertTrue(plain is TerminalKeyEncoder.PastePayload.Text)
        assertEquals("a\rb", (plain as TerminalKeyEncoder.PastePayload.Text).value)
    }

    @Test
    fun `bracketedPaste wraps markers exactly`() {
        assertEquals(
            "\u001B[200~abc\u001B[201~",
            TerminalKeyEncoder.bracketedPaste("abc"),
        )
        // Markers end in ~, never m: a stray m can never come from these.
        assertTrue(TerminalKeyEncoder.BRACKETED_PASTE_START.endsWith("~"))
        assertTrue(TerminalKeyEncoder.BRACKETED_PASTE_END.endsWith("~"))
    }

    @Test
    fun `isBracketedPasteMode reads the 2004 bit`() {
        // Regression for bug class #4: use Termux's exact 2004 mode bit.
        // Fake emulator with the real field name/shape.
        class FakeEmu(@JvmField var mCurrentDecSetFlags: Int)
        assertTrue(TerminalKeyEncoder.isBracketedPasteMode(FakeEmu(1024)))
        assertTrue(TerminalKeyEncoder.isBracketedPasteMode(FakeEmu(1024 or 512)))
        assertTrue(!TerminalKeyEncoder.isBracketedPasteMode(FakeEmu(0)))
        assertTrue(!TerminalKeyEncoder.isBracketedPasteMode(FakeEmu(512)))
        assertTrue(!TerminalKeyEncoder.isBracketedPasteMode(null))
        assertTrue(!TerminalKeyEncoder.isBracketedPasteMode(Any()))
    }
}
