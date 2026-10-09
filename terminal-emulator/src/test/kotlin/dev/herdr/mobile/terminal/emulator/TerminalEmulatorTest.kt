package dev.herdr.mobile.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {

    private fun emu(cols: Int = 10, rows: Int = 5, boldIsBright: Boolean = true) =
        TerminalEmulator(TerminalThemes.tokyoNight(), boldIsBright).also { it.resize(cols, rows) }

    private fun rowText(e: TerminalEmulator, r: Int): String {
        val sb = StringBuilder()
        for (cell in e.lines[r]) {
            if (!cell.wideContinuation) sb.appendCodePoint(cell.codepoint)
        }
        return sb.toString()
    }

    private fun esc(s: String) = "\u001B$s"

    // View-slice mirror for tests: the view owns the offset (termux mTopRow);
    // tests read history + screen directly.
    private fun historyText(e: TerminalEmulator, i: Int): String {
        val sb = StringBuilder()
        for (cell in e.history[i]) {
            if (!cell.wideContinuation) sb.appendCodePoint(cell.codepoint)
        }
        return sb.toString().trimEnd()
    }
    private fun historySize(e: TerminalEmulator): Int = e.history.size

    // ------------------------------------------------------------ ground ----------

    @Test fun plainTextAdvancesCursor() {
        val e = emu()
        e.write("hello")
        assertEquals("hello     ", rowText(e, 0))
        assertEquals(0, e.cursorRow)
        assertEquals(5, e.cursorCol)
        assertEquals(1L, e.writeCount)
    }

    @Test fun textWrapsAcrossRows() {
        val e = emu(cols = 4, rows = 3)
        e.write("abcdef")
        assertEquals("abcd", rowText(e, 0))
        assertEquals("ef  ", rowText(e, 1))
        assertEquals(1, e.cursorRow)
        assertEquals(2, e.cursorCol)
    }

    @Test fun autowrapOffOverwritesLastCell() {
        val e = emu(cols = 4, rows = 2)
        e.write(esc("[?7l"))
        e.write("abcdef")
        assertEquals("abcf", rowText(e, 0))
        assertEquals(3, e.cursorCol)
    }

    @Test fun linesShapeIsExact() {
        val e = emu(cols = 7, rows = 3)
        assertEquals(3, e.lines.size)
        for (row in e.lines) assertEquals(7, row.size)
    }

    // ------------------------------------------------------------ moves -----------

    @Test fun cupAndRelatives() {
        val e = emu(cols = 10, rows = 5)
        e.write(esc("[3;4H"))
        assertEquals(2, e.cursorRow)
        assertEquals(3, e.cursorCol)
        e.write(esc("[2A"))
        assertEquals(0, e.cursorRow)
        e.write(esc("[3B"))
        assertEquals(3, e.cursorRow)
        e.write(esc("[2C"))
        assertEquals(5, e.cursorCol)
        e.write(esc("[1D"))
        assertEquals(4, e.cursorCol)
    }

    @Test fun cnlCplCha() {
        val e = emu(cols = 10, rows = 5)
        e.write(esc("[3;5H"))
        e.write(esc("[1E"))
        assertEquals(3, e.cursorRow)
        assertEquals(0, e.cursorCol)
        e.write(esc("[1F"))
        assertEquals(2, e.cursorRow)
        assertEquals(0, e.cursorCol)
        e.write(esc("[7G"))
        assertEquals(6, e.cursorCol)
    }

    // ------------------------------------------------------------ erasing ---------

    private fun filled(cols: Int = 5, rows: Int = 3): TerminalEmulator {
        val e = emu(cols, rows)
        e.write("AAAAA\r\nBBBBB\r\nCCCCC")
        return e
    }

    @Test fun ed0ClearsToEnd() {
        val e = filled()
        e.write(esc("[2;3H"))
        e.write(esc("[0J"))
        assertEquals("BB   ", rowText(e, 1))
        assertEquals("     ", rowText(e, 2))
        assertEquals("AAAAA", rowText(e, 0))
    }

    @Test fun ed1ClearsFromStart() {
        val e = filled()
        e.write(esc("[2;3H"))
        e.write(esc("[1J"))
        assertEquals("     ", rowText(e, 0))
        assertEquals("   BB", rowText(e, 1))
        assertEquals("CCCCC", rowText(e, 2))
    }

    @Test fun ed2ClearsAll() {
        val e = filled()
        e.write(esc("[2J"))
        for (r in 0 until 3) assertEquals("     ", rowText(e, r))
    }

    @Test fun elModes() {
        val e = emu(cols = 5, rows = 2)
        e.write("abcde")
        e.write(esc("[1;3H"))
        e.write(esc("[0K"))
        assertEquals("ab   ", rowText(e, 0))
        e.write("\rabcde")
        e.write(esc("[1;3H"))
        e.write(esc("[1K"))
        assertEquals("   de", rowText(e, 0))
        e.write(esc("[2K"))
        assertEquals("     ", rowText(e, 0))
    }

    // ------------------------------------------------------------ editing ---------

    @Test fun ichInsertsBlankCells() {
        val e = emu(cols = 6, rows = 2)
        e.write("abcdef")
        e.write("\r" + esc("[2C") + esc("[2@"))
        assertEquals("ab  cd", rowText(e, 0))
    }

    @Test fun dchDeletesCells() {
        val e = emu(cols = 6, rows = 2)
        e.write("abcdef")
        e.write("\r" + esc("[2C") + esc("[2P"))
        assertEquals("abef  ", rowText(e, 0))
    }

    @Test fun ilAndDlShiftInsideRegion() {
        val e = emu(cols = 5, rows = 4)
        e.write("11111\r\n22222\r\n33333\r\n44444")
        e.write(esc("[2;1H") + esc("[1L"))
        assertEquals("11111", rowText(e, 0))
        assertEquals("     ", rowText(e, 1))
        assertEquals("22222", rowText(e, 2))
        assertEquals("33333", rowText(e, 3))
        e.write(esc("[1M"))
        assertEquals("22222", rowText(e, 1))
        assertEquals("33333", rowText(e, 2))
    }

    @Test fun echBlanksInPlace() {
        val e = emu(cols = 6, rows = 2)
        e.write("abcdef")
        e.write("\r" + esc("[2C") + esc("[2X"))
        assertEquals("ab  ef", rowText(e, 0))
    }

    // ------------------------------------------------------------ region ----------

    @Test fun decstbmScrollsOnlyRegion() {
        val e = emu(cols = 2, rows = 5)
        e.write("00\r\n11\r\n22\r\n33\r\n44")
        e.write(esc("[2;4r"))
        e.write(esc("[4;1H"))
        e.write("\n")
        assertEquals("00", rowText(e, 0))
        assertEquals("22", rowText(e, 1))
        assertEquals("33", rowText(e, 2))
        assertEquals("  ", rowText(e, 3))
        assertEquals("44", rowText(e, 4))
    }

    // ------------------------------------------------------------ sgr --------------

    @Test fun sgrBasicAndBright() {
        val e = emu()
        val scheme = TerminalThemes.tokyoNight()
        e.write(esc("[31m") + "R")
        assertEquals(scheme.ansi[1], e.lines[0][0].attrs.foreground)
        e.write(esc("[91m") + "B")
        assertEquals(scheme.ansi[9], e.lines[0][1].attrs.foreground)
        e.write(esc("[0m") + "N")
        assertEquals(null, e.lines[0][2].attrs.foreground)
    }

    @Test fun sgrIndexed256() {
        val e = emu()
        e.write(esc("[38;5;200m") + "X")
        assertEquals(TerminalColor(255, 0, 215), e.lines[0][0].attrs.foreground)
    }

    @Test fun sgrTruecolor() {
        val e = emu()
        e.write(esc("[38;2;10;20;30m") + "X")
        assertEquals(TerminalColor(10, 20, 30), e.lines[0][0].attrs.foreground)
        e.write(esc("[48;2;1;2;3m") + "Y")
        assertEquals(TerminalColor(1, 2, 3), e.lines[0][1].attrs.background)
    }

    @Test fun boldBrightensWhenEnabled() {
        val scheme = TerminalThemes.tokyoNight()
        val on = emu(boldIsBright = true)
        on.write(esc("[1;31m") + "B")
        assertEquals(scheme.ansi[9], on.lines[0][0].attrs.foreground)
        assertTrue(on.lines[0][0].attrs.bold)

        val off = emu(boldIsBright = false)
        off.write(esc("[1;31m") + "B")
        assertEquals(scheme.ansi[1], off.lines[0][0].attrs.foreground)
        assertTrue(off.lines[0][0].attrs.bold)
    }

    @Test fun sgrDecorations() {
        val e = emu()
        e.write(esc("[3;4;7;9m") + "X" + esc("[0m"))
        val a = e.lines[0][0].attrs
        assertTrue(a.italic)
        assertTrue(a.underline)
        assertTrue(a.inverse)
        assertTrue(a.strike)
        assertFalse(e.lines[0][1].attrs.italic)
    }

    // ------------------------------------------------------------ alt screen ------

    @Test fun altScreenRoundTripPreservesMain() {
        val e = emu(cols = 8, rows = 3)
        e.write("main")
        val row = e.cursorRow
        val col = e.cursorCol
        e.write(esc("[?1049h"))
        assertTrue(e.usingAlternateScreen)
        assertEquals("        ", rowText(e, 0))
        e.write("alt")
        assertEquals("alt     ", rowText(e, 0))
        e.write(esc("[?1049l"))
        assertFalse(e.usingAlternateScreen)
        assertEquals("main    ", rowText(e, 0))
        assertEquals(row, e.cursorRow)
        assertEquals(col, e.cursorCol)
    }

    @Test fun altScreenAccumulatesNoScrollback() {
        val e = emu(cols = 4, rows = 2)
        e.write(esc("[?1049h"))
        repeat(10) { e.write("abcd\r\n") }
        e.write(esc("[?1049l"))
        // Exiting the alternate screen restores the main buffer, which kept no scrollback.
        assertFalse(e.usingAlternateScreen)
        assertEquals(0, historySize(e))
        for (r in 0 until 2) assertEquals("    ", rowText(e, r))
    }

    // ------------------------------------------------------------ unicode ---------

    @Test fun utf8SurvivesChunkBoundaries() {
        val e = emu()
        val bytes = "é".toByteArray(Charsets.UTF_8)
        e.write(bytes.copyOfRange(0, 1))
        assertEquals(32, e.lines[0][0].codepoint) // nothing emitted yet
        e.write(bytes.copyOfRange(1, 2))
        assertEquals(0xE9, e.lines[0][0].codepoint)
        assertEquals(1, e.cursorCol)
    }

    @Test fun emojiSplitAcrossThreeWrites() {
        val e = emu()
        val bytes = "\uD83D\uDE00".toByteArray(Charsets.UTF_8)
        e.write(bytes.copyOfRange(0, 1))
        e.write(bytes.copyOfRange(1, 3))
        e.write(bytes.copyOfRange(3, 4))
        assertEquals(0x1F600, e.lines[0][0].codepoint)
    }

    @Test fun wideCharOccupiesTwoCells() {
        val e = emu(cols = 10, rows = 2)
        e.write("aあb")
        assertEquals('a'.code, e.lines[0][0].codepoint)
        assertEquals(0x3042, e.lines[0][1].codepoint)
        assertFalse(e.lines[0][1].wideContinuation)
        assertTrue(e.lines[0][2].wideContinuation)
        assertEquals('b'.code, e.lines[0][3].codepoint)
        assertEquals(4, e.cursorCol)
    }

    @Test fun wideCharWrapsAsPairAtMargin() {
        val e = emu(cols = 3, rows = 2)
        e.write("abあ")
        assertEquals("ab ", rowText(e, 0))
        assertEquals(0x3042, e.lines[1][0].codepoint)
        assertTrue(e.lines[1][1].wideContinuation)
    }

    @Test fun combiningMarkDoesNotAdvance() {
        val e = emu()
        e.write("é")
        assertEquals('e'.code, e.lines[0][0].codepoint)
        assertEquals(1, e.cursorCol)
    }

    // ------------------------------------------------------------ osc/queries -----

    @Test fun oscTitle() {
        val e = emu()
        e.write(esc("]0;hello title\u0007"))
        assertEquals("hello title", e.title)
    }

    @Test fun oscColourQueryResponds() {
        val e = emu()
        val out = mutableListOf<ByteArray>()
        e.onResponse = { out.add(it) }
        e.write(esc("]11;?\u0007"))
        assertEquals(1, out.size)
        val text = out[0].toString(Charsets.US_ASCII)
        assertTrue(text.startsWith("\u001B]11;rgb:"))
        assertTrue(text.endsWith("\u001B\\"))
    }

    @Test fun daAndDsrRespond() {
        val e = emu()
        val out = mutableListOf<ByteArray>()
        e.onResponse = { out.add(it) }
        e.write(esc("[c"))
        e.write(esc("[5n"))
        e.write(esc("[2;3H") + esc("[6n"))
        assertEquals("\u001B[?62;1;2c", out[0].toString(Charsets.US_ASCII))
        assertEquals("\u001B[0n", out[1].toString(Charsets.US_ASCII))
        assertEquals("\u001B[2;3R", out[2].toString(Charsets.US_ASCII))
    }

    @Test fun bellAndTabAndC0() {
        val e = emu(cols = 16, rows = 2)
        e.write("a\u0007")
        assertTrue(e.bell)
        e.bell = false
        e.write("\t")
        assertEquals(8, e.cursorCol)
        e.write("b\bc")
        assertEquals('c'.code, e.lines[0][8].codepoint)
    }

    // ------------------------------------------------------------ scrollback ------

    @Test fun originModeAppliesToCup() {
        val e = emu(cols = 10, rows = 5)
        e.write(esc("[2;4r") + esc("[?6h"))
        e.write(esc("[1;1H"))
        assertEquals(1, e.cursorRow)
        assertEquals(0, e.cursorCol)
        e.write(esc("[?6l"))
    }

    @Test fun cursorVisibilityToggle() {
        val e = emu()
        assertTrue(e.cursorVisible)
        e.write(esc("[?25l"))
        assertFalse(e.cursorVisible)
        e.write(esc("[?25h"))
        assertTrue(e.cursorVisible)
    }

    @Test fun tabStopsEveryEight() {
        val e = emu(cols = 16, rows = 2)
        e.write("a\tb")
        assertEquals('a'.code, e.lines[0][0].codepoint)
        assertEquals('b'.code, e.lines[0][8].codepoint)
        // HTS + backwards tab.
        e.write(esc("[5G") + esc("H") + esc("[16G") + esc("[Z"))
        // Cleared tab at col 4 no longer stops; falls back to col 0...8 region.
        e.write(esc("[5G") + esc("[0g"))
        e.write(esc("[16G") + esc("[Z"))
        assertEquals(8, e.cursorCol)
    }

    @Test fun scrollRegionUpDown() {
        val e = emu(cols = 2, rows = 4)
        e.write("00\r\n11\r\n22\r\n33")
        e.write(esc("[2;3r") + esc("[S"))
        assertEquals("00", rowText(e, 0))
        assertEquals("22", rowText(e, 1))
        assertEquals("  ", rowText(e, 2))
        assertEquals("33", rowText(e, 3))
        e.write(esc("[T"))
        assertEquals("  ", rowText(e, 1))
        assertEquals("22", rowText(e, 2))
        e.write(esc("[r"))
        assertEquals(0, e.cursorRow)
        assertEquals(0, e.cursorCol)
    }

    @Test fun scrollbackBanksOnScroll() {
        // The view owns the offset; the emulator only banks rows on scroll.
        val e = emu(cols = 4, rows = 3)
        repeat(5) { i -> e.write("L$i\r\n") } // L0..L4, screen holds 3
        assertEquals(3, historySize(e)) // L0,L1,L2 banked
        assertEquals("L0", historyText(e, 0))
        assertEquals("L2", historyText(e, 2))
        // Screen shows the live tail.
        assertEquals("L3", rowText(e, 0).trim())
        assertEquals("L4", rowText(e, 1).trim())
    }

    @Test fun scrollbackLimitHonoured() {
        val e = emu(cols = 4, rows = 2)
        e.setScrollbackLimit(3)
        repeat(10) { i -> e.write("L$i\r\n") }
        assertTrue(historySize(e) <= 3)
    }

    // ------------------------------------------------------------ robustness ------

    @Test fun garbageStreamNeverThrowsAndStaysUsable() {
        val e = emu()
        val garbage = byteArrayOf(
            0x1B, '['.code.toByte(), 0x39.toByte(), 0x39.toByte(), 0x39.toByte(), 0x3B.toByte(),
            0x1B.toByte(), 0x5D.toByte(), 0x39.toByte(), 0x39.toByte(), 0x39.toByte(), 0x3B.toByte(),
            0x00, 0x01, 0x02, 0x7F, 0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(),
            0x1B, '('.code.toByte(), 0x1B.toByte(), '#'.code.toByte(),
        )
        e.write(garbage)
        e.write(byteArrayOf(0x1B.toByte())) // lone ESC: subsequent bytes are a fresh CSI
        e.write("[2J".toByteArray(Charsets.US_ASCII))
        for (r in 0 until e.rows) {
            for (c in e.lines[r]) {
                assertEquals(32, c.codepoint)
            }
        }
        // ED clears without moving the cursor, so home explicitly before asserting.
        e.write("\r" + esc("[1;1H") + "ok")
        assertEquals('o'.code, e.lines[0][0].codepoint)
        assertEquals('k'.code, e.lines[0][1].codepoint)
    }

    @Test fun unterminatedOscResumes() {
        val e = emu()
        e.write(esc("]0;partial"))
        assertEquals(null, e.title)
        e.write(" title\u0007")
        assertEquals("partial title", e.title)
    }

    @Test fun pathologicalCsiIsCapped() {
        val e = emu()
        val huge = ByteArray(100000) { '9'.code.toByte() }
        e.write(esc("[").toByteArray(Charsets.US_ASCII))
        e.write(huge)
        e.write("m".toByteArray(Charsets.US_ASCII))
        e.write("ok")
        assertTrue(rowText(e, 0).startsWith("ok"))
    }

    @Test fun repRepeatsLastGraphic() {
        val e = emu(cols = 10, rows = 2)
        e.write("ab" + esc("[3b"))
        assertEquals("abbbb     ", rowText(e, 0))
    }

    @Test fun oscTerminatedBySt() {
        val e = emu()
        e.write(esc("]0;st title\u001B\\"))
        assertEquals("st title", e.title)
    }

    @Test fun dcsConsumedUntilSt() {
        val e = emu(cols = 10, rows = 2)
        e.write(esc("Pqgarbage\u001B\\") + "ok")
        assertEquals("ok        ", rowText(e, 0))
    }

    @Test fun repRepeatsAfterScroll() {
        val e = emu(cols = 10, rows = 2)
        e.write("ab" + esc("[3b"))
        e.write(esc("[2J") + esc("[1;1H") + "z" + esc("[2b"))
        assertEquals("zzz", rowText(e, 0).substring(0, 3))
    }

    @Test fun resizePreservesContent() {
        val e = emu(cols = 6, rows = 3)
        e.write("abcdef\r\nghijkl")
        e.resize(4, 2)
        assertEquals(4, e.cols)
        assertEquals(2, e.rows)
        assertEquals(2, e.lines.size)
        assertEquals(4, e.lines[0].size)
        assertEquals("abcd", rowText(e, 0))
        e.resize(10, 4)
        assertEquals("abcd      ", rowText(e, 0))
    }

    @Test fun revisionIncreasesOnMutation() {
        val e = emu()
        val r0 = e.revision
        e.write("x")
        assertTrue(e.revision > r0)
        val r1 = e.revision
        e.write(esc("[2J"))
        assertTrue(e.revision > r1)
    }

    // ------------------------------------------------------------ themes ----------

    @Test fun allThemesValid() {
        val themes = listOf(
            TerminalThemes.tokyoNight(),
            TerminalThemes.dark(),
            TerminalThemes.light(),
            TerminalThemes.solarizedDark(),
            TerminalThemes.solarizedLight(),
            TerminalThemes.dracula(),
            TerminalThemes.gruvboxDark(),
            TerminalThemes.campbell(),
        )
        for (t in themes) {
            assertEquals(16, t.ansi.size)
            val e = TerminalEmulator(t)
            e.write("hi")
            assertEquals("hi", rowText(e, 0).substring(0, 2))
        }
    }

    @Test fun defaultSizeIs80x24() {
        val e = TerminalEmulator(TerminalThemes.dark())
        assertEquals(80, e.cols)
        assertEquals(24, e.rows)
        assertEquals(24, e.lines.size)
    }

    @Test fun heightShrinkKeepsLiveBottom() {
        // Shrink keeps the live bottom rows on screen and banks the removed
        // head (rows vanish permanently otherwise).
        val e = emu(cols = 4, rows = 4)
        e.write("R0\r\nR1\r\nR2\r\nR3")
        e.resize(4, 2)
        assertEquals("R2", rowText(e, 0).trim())
        assertEquals("R3", rowText(e, 1).trim())
        assertEquals(2, historySize(e)) // R0,R1 banked
        assertEquals("R0", historyText(e, 0))
        assertEquals("R1", historyText(e, 1))
    }

    @Test fun widthChangeKeepsScrollback() {
        // Rewrap in place (banked rows keep their own widths; the view pads
        // or clips). Clearing here used to eat history on rotation.
        val e = emu(cols = 4, rows = 3)
        repeat(5) { i -> e.write("L$i\r\n") }
        assertTrue(historySize(e) > 0)
        e.resize(6, 3)
        assertTrue(historySize(e) > 0)
    }





    @Test fun historyPreludeCapturesThenPaintsLive() {
        // Daemon prelude: CLEAR + history lines + MARKER + HOME + visible.
        // History banks to scrollback; visible paints the screen; no overlap.
        val e = emu(cols = 10, rows = 3)
        val marker = "\u001B]314159;herdr-history-end\u0007"
        e.write("\u001B[2J\u001B[H" + "H0\nH1\n" + marker + "\n\u001B[H" + "V0\r\nV1")
        assertEquals("V0", rowText(e, 0).trim())
        assertEquals("V1", rowText(e, 1).trim())
        assertEquals(2, historySize(e))
        assertEquals("H0", historyText(e, 0))
        assertEquals("H1", historyText(e, 1))
    }

    @Test fun inPlaceRewriteMustNotCreateScrollback() {
        // Cursor-addressed rewrites paint in place; without scroll-push they
        // must never fabricate history (the old mergeViewportScroll did).
        val e = emu(cols = 4, rows = 3)
        e.write("A0\r\nA1\r\nA2")
        e.write(esc("[1;1H") + "B0" + esc("[2;1H") + "B1")
        assertEquals(0, historySize(e))
        assertEquals("B0", rowText(e, 0).trim())
        assertEquals("B1", rowText(e, 1).trim())
    }

    @Test fun repeatedOutputBanksOnce() {
        // Tradeoff, documented in pushScrollback: identical repeated lines
        // bank once (membership filter), because repaint-resurrected rows
        // would otherwise duplicate out of order. Order beats completeness.
        val e = emu(cols = 4, rows = 2)
        e.write("ZZ\r\n")
        e.write("ZZ\r\n")
        e.write("ZZ\r\n")
        assertEquals(1, historySize(e)) // single ZZ banked
        assertEquals("ZZ", historyText(e, 0))
    }

    @Test fun shrinkGrowRoundtripKeepsOrder() {
        // Keyboard dance: shrink banks head, grow drops re-shown tail; a
        // repaint + scroll cycle after must not duplicate or reverse.
        val e = emu(cols = 4, rows = 4)
        e.write("R0\r\nR1\r\nR2\r\nR3\r\nR4\r\n")
        e.resize(4, 2) // banks R1,R2 (head trim)
        e.resize(4, 4) // drops tail 2 (repaint re-shows)
        repeat(2) { i -> e.write("N$i\r\n") }
        val hist = (0 until 10).mapNotNull {
            try {
                historyText(e, it)
            } catch (_: Exception) {
                null
            }
        }.filter { it.isNotEmpty() }
        assertEquals(hist.distinct(), hist) // ordered, no dupes
    }

    @Test fun mouseTrackingFollowsDec1000And1002() {
        // Termux isMouseTrackingActive parity: 1000/1002 arm tap-as-click,
        // anything else (1006 SGR alone, focus 1004) does not.
        val e = emu()
        assertFalse(e.isMouseTracking)
        e.write(esc("[?1000h"))
        assertTrue(e.isMouseTracking)
        e.write(esc("[?1000l"))
        assertFalse(e.isMouseTracking)
        e.write(esc("[?1002h"))
        assertTrue(e.isMouseTracking)
        e.write(esc("[?1002l"))
        assertFalse(e.isMouseTracking)
        e.write(esc("[?1006h"))
        assertFalse(e.isMouseTracking)
        e.write(esc("[?1000h") + esc("[?1006h"))
        assertTrue(e.isMouseTracking)
        e.write(esc("[?1000l"))
        assertFalse(e.isMouseTracking)
    }

    @Test fun sameSizeResizeKeepsScrollback() {
        // No-op resizes (same dims) must not wipe history: the view can emit
        // redundant size events on any layout pass.
        val e = emu(cols = 4, rows = 3)
        repeat(5) { i -> e.write("L$i\r\n") }
        val before = historySize(e)
        assertTrue(before > 0)
        e.resize(4, 3)
        assertEquals(before, historySize(e))
    }
}
