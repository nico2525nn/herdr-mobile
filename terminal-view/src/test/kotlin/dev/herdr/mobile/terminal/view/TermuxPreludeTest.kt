package dev.herdr.mobile.terminal.view

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeOutput : TerminalOutput() {
    override fun write(data: ByteArray, offset: Int, count: Int) {}
    override fun titleChanged(oldTitle: String, newTitle: String) {}
    override fun onCopyTextToClipboard(text: String) {}
    override fun onPasteTextFromClipboard() {}
    override fun onBell() {}
    override fun onColorsChanged() {}
}

private class FakeSessionClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
}

/**
 * Prelude scroll-banking mechanics against the real Termux core (pure JVM:
 * TerminalEmulator only touches android.util.Base64 on the OSC-52 path).
 *
 * Banking rule: scroll banks the TOP row, so H history rows on an R-row grid
 * need H history newlines + R - 1 trailing blanks (H scrolls). Leading fill
 * and CUP-to-bottom both bank blanks — locked in here so nobody "optimizes"
 * the daemon prelude back into them.
 */
class TermuxPreludeTest {

    private fun emu(cols: Int = 90, rows: Int = 30) =
        TerminalEmulator(FakeOutput(), cols, rows, 0, 0, 2000, FakeSessionClient())

    private fun feed(e: TerminalEmulator, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        e.append(bytes, bytes.size)
    }

    private fun transcriptHead(e: TerminalEmulator, rows: Int): String {
        val n = e.screen.activeTranscriptRows
        return e.screen.getSelectedText(0, -n, e.mColumns, -n + rows)
    }

    @Test
    fun `trailing blanks bank exactly the history`() {
        val e = emu(cols = 56, rows = 39)
        val hist = (1..36).joinToString("") { "H$it\r\n" }
        feed(e, hist + "\n".repeat(38) + "\u001B[2J\u001B[HVIS\r\n")
        assertEquals(36, e.screen.activeTranscriptRows)
        val head = transcriptHead(e, 3)
        assertTrue(head.startsWith("H1\nH2\nH3"))
    }

    @Test
    fun `leading fill banks its own blanks`() {
        // Fill-then-history looks equivalent but scrolls blanks off the top
        // (the top is blank until the screen fills) — the history text then
        // sits on-screen and CLEAR wipes it. Never do this.
        val e = emu(cols = 56, rows = 39)
        val hist = (1..36).joinToString("") { "H$it\r\n" }
        feed(e, "\n".repeat(38) + hist + "\u001B[2J\u001B[HVIS\r\n")
        assertEquals(36, e.screen.activeTranscriptRows)
        val head = transcriptHead(e, 1)
        assertTrue(head.startsWith("\n"))
    }

    @Test
    fun `same-column resize keeps transcript content`() {
        val e = emu(cols = 56, rows = 30)
        val hist = (1..20).joinToString("") { "K$it\r\n" }
        feed(e, hist + "\n".repeat(29))
        val before = e.screen.activeTranscriptRows
        assertTrue(before > 0)
        e.resize(56, 39, 0, 0)
        // Growth consumes transcript into the screen (Termux-native reflow);
        // content survives, split between transcript and screen.
        val after = e.screen.activeTranscriptRows
        assertTrue(after >= 0)
        val all = e.screen.getSelectedText(0, -after, 56, e.mRows)
        assertTrue(all.contains("K1"))
        assertTrue(all.contains("K20"))
    }
}
