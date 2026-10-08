package dev.herdr.mobile.terminal.emulator

import java.io.ByteArrayOutputStream

/**
 * Incremental ANSI/VT100-compatible terminal emulator.
 *
 * Consumes a raw byte stream (as produced by a PTY) and maintains a screen buffer that a
 * platform `View` can draw. Covers UTF-8 decoding, C0 controls, CSI, ESC sequences, OSC,
 * DCS/APC/PM/SOS discard, SGR attributes, scroll regions, the alternate screen, and a bounded
 * scrollback ring.
 *
 * Threading: this class performs no locking. It is confined to a single thread — the thread
 * pumping the PTY — and every method must be called from that thread.
 */
class TerminalEmulator(
    val colorScheme: TerminalColorScheme,
    val boldIsBright: Boolean = true,
) {
    // ------------------------------------------------------------------ dimensions ---

    private var colsValue = DEFAULT_COLS
    private var rowsValue = DEFAULT_ROWS

    /** Visible width in cells. */
    val cols: Int get() = colsValue

    /** Visible height in cells. */
    val rows: Int get() = rowsValue

    // ------------------------------------------------------------------ buffers ----

    private var mainLines = blankScreen(DEFAULT_COLS, DEFAULT_ROWS)
    private var altLines = blankScreen(DEFAULT_COLS, DEFAULT_ROWS)
    private var isAltValue = false

    /** True while the alternate screen (DEC 47/1047/1049) is active. */
    val usingAlternateScreen: Boolean get() = isAltValue

    private fun active(): ArrayList<ArrayList<TerminalCell>> = if (isAltValue) altLines else mainLines

    // ---------------------------------------------------------------- scrollback ---

    private val scrollback = ArrayDeque<List<TerminalCell>>()
    private var scrollbackLimitValue = DEFAULT_SCROLLBACK_LINES
    private var scrollbackOffsetValue = 0

    /** 0 = pinned to the live bottom; > 0 = viewing history. */
    val scrollbackOffset: Int get() = scrollbackOffsetValue

    // ------------------------------------------------------------------- cursor ----

    private var cursorRowValue = 0
    private var cursorColValue = 0
    private var cursorVisibleValue = true

    val cursorRow: Int get() = cursorRowValue
    val cursorCol: Int get() = cursorColValue
    val cursorVisible: Boolean get() = cursorVisibleValue

    // ------------------------------------------------------------------- viewport --

    /**
     * Row 0 is the top of the visible viewport. Exactly [rows] rows, exactly [cols] cells each.
     * When scrolled back the top rows come from history; otherwise this is the live screen.
     */
    val lines: List<List<TerminalCell>>
        get() = viewport()

    // ------------------------------------------------------------------- counters --

    private var writeCountValue = 0L
    private var revisionValue = 0L

    /** Number of [write] calls so far. */
    val writeCount: Long get() = writeCountValue

    /** Monotonic revision incremented by every mutation, so a View can cheaply detect change. */
    val revision: Long get() = revisionValue

    // ------------------------------------------------------------------- signals ---

    /** Set on BEL; the consumer clears it after handling. */
    var bell = false

    /** Last OSC 0/1/2 title. */
    var title: String? = null
        private set

    /** Answers to DA/DSR/OSC colour queries. */
    var onResponse: ((ByteArray) -> Unit)? = null

    // ------------------------------------------------------------------- pen -------

    private var penFg: TerminalColor? = null
    private var penFgIndex: Int? = null // 0..15 when the fg came from the indexed palette
    private var penBg: TerminalColor? = null
    private var penBold = false
    private var penDim = false
    private var penItalic = false
    private var penUnderline = false
    private var penBlink = false
    private var penInverse = false
    private var penHidden = false
    private var penStrike = false

    // ------------------------------------------------------------------- modes -----

    private var autowrap = true // DECAWM 7
    private var originMode = false // DECOM 6
    private val privateModes = mutableSetOf<Int>() // recorded only (mouse/focus/paste/…)

    // ------------------------------------------------------------------- region ----

    private var regionTopValue = 0
    private var regionBottomValue = DEFAULT_ROWS - 1

    // ------------------------------------------------------------------- cursor state

    private var wrapPending = false
    private var tabStops = defaultStops(DEFAULT_COLS)

    private data class SavedState(
        val row: Int,
        val col: Int,
        val fg: TerminalColor?,
        val fgIndex: Int?,
        val bg: TerminalColor?,
        val bold: Boolean,
        val dim: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val blink: Boolean,
        val inverse: Boolean,
        val hidden: Boolean,
        val strike: Boolean,
        val origin: Boolean,
        val wrap: Boolean,
        val top: Int,
        val bottom: Int,
    )

    private var decSaved: SavedState? = null // DECSC 7 / ANSI s
    private var mainSaved: SavedState? = null // screen entering the alternate screen

    private var lastGraphicCp: Int? = null // for REP

    // ------------------------------------------------------------------- utf8 ------

    private var utf8Remaining = 0
    private var utf8Value = 0
    private var utf8Length = 0

    // ------------------------------------------------------------------- parser ----

    private enum class State {
        GROUND, ESCAPE, ESC_CHARSET, ESC_HASH, CSI, OSC, OSC_ESC, STR, STR_ESC,
    }

    private var state = State.GROUND
    private val csiBuf = StringBuilder()
    private var csiOverflow = false
    private val strBuf = ByteArrayOutputStream()
    private var strOverflow = false
    private var dirty = false

    // ================================================================== public ====

    fun resize(cols: Int, rows: Int) {
        val nc = cols.coerceIn(1, MAX_DIM)
        val nr = rows.coerceIn(1, MAX_DIM)
        if (nc == colsValue && nr == rowsValue) return
        // Shrinking rows must not discard history: push the clipped bottom rows
        // into scrollback first (main screen only; alt screen has no scrollback).
        if (nr < rowsValue && !usingAlternateScreen) {
            val clipped = rowsValue - nr
            repeat(clipped.coerceAtMost(mainLines.size)) {
                if (mainLines.isNotEmpty()) {
                    pushScrollback(adjustRowWidth(mainLines.removeAt(mainLines.size - 1), nc))
                }
            }
        }
        colsValue = nc
        rowsValue = nr
        for (i in scrollback.indices) scrollback[i] = adjustRowWidth(scrollback[i], nc)
        adjustGrid(mainLines, nc, nr)
        adjustGrid(altLines, nc, nr)
        regionTopValue = 0
        regionBottomValue = nr - 1
        cursorRowValue = cursorRowValue.coerceIn(0, nr - 1)
        cursorColValue = cursorColValue.coerceIn(0, nc - 1)
        wrapPending = false
        tabStops = defaultStops(nc)
        decSaved = decSaved?.coerced(nc, nr)
        mainSaved = mainSaved?.coerced(nc, nr)
        scrollbackOffsetValue = scrollbackOffsetValue.coerceIn(0, scrollback.size)
        touchAndBump()
    }

    fun write(bytes: ByteArray) {
        writeCountValue++
        dirty = false
        try {
            for (raw in bytes) {
                val b = raw.toInt() and 0xFF
                when (state) {
                    State.GROUND -> groundByte(b)
                    State.ESCAPE -> escByte(b)
                    State.ESC_CHARSET -> state = State.GROUND // consume one charset byte
                    State.ESC_HASH -> {
                        if (b == '8'.code) decalN()
                        state = State.GROUND
                    }
                    State.CSI -> csiByte(b)
                    State.OSC -> oscByte(b)
                    State.OSC_ESC -> oscEscByte(b)
                    State.STR -> strByte(b)
                    State.STR_ESC -> strEscByte(b)
                }
            }
        } catch (_: RuntimeException) {
            // Never let a malformed stream wedge the emulator; resync to ground.
            state = State.GROUND
            utf8Remaining = 0
        }
        if (dirty) revisionValue++
    }

    fun write(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    /** Maximum history lines kept; trims the oldest excess. 0 disables scrollback. */
    fun setScrollbackLimit(limit: Int) {
        val clamped = limit.coerceAtLeast(0)
        scrollbackLimitValue = clamped
        var changed = false
        while (scrollback.size > clamped) {
            scrollback.removeFirst()
            changed = true
        }
        val off = scrollbackOffsetValue.coerceIn(0, scrollback.size)
        if (off != scrollbackOffsetValue) {
            scrollbackOffsetValue = off
            changed = true
        }
        if (changed) touchAndBump()
    }

    /**
     * Moves [scrollbackOffset], clamped to available history. Negative values scroll up into
     * older history (so `scrollBy(-5)` shows five lines back), positive values scroll down.
     */
    fun scrollBy(lines: Int) {
        val next = (scrollbackOffsetValue - lines).coerceIn(0, scrollback.size)
        if (next != scrollbackOffsetValue) {
            scrollbackOffsetValue = next
            touchAndBump()
        }
    }

    fun scrollToBottom() {
        if (scrollbackOffsetValue != 0) {
            scrollbackOffsetValue = 0
            touchAndBump()
        }
    }

    // ================================================================== viewport ==

    private fun viewport(): List<List<TerminalCell>> {
        val screen = active()
        val off = scrollbackOffsetValue.coerceIn(0, scrollback.size)
        if (off == 0) return ArrayList(screen)
        val hist = scrollback.size
        val bottom = hist + screen.size - off
        val out = ArrayList<List<TerminalCell>>(screen.size)
        for (i in bottom - screen.size until bottom) {
            out.add(if (i < hist) scrollback[i] else screen[i - hist])
        }
        return out
    }

    // ================================================================== ground ====

    private fun groundByte(b: Int) {
        if (utf8Remaining > 0) {
            if (b and 0xC0 == 0x80) {
                utf8Value = (utf8Value shl 6) or (b and 0x3F)
                utf8Remaining--
                if (utf8Remaining == 0) emitUtf8()
                return
            }
            utf8Remaining = 0 // not a continuation: abort and reprocess this byte
        }
        when {
            b < 0x20 -> processC0(b)
            b == 0x7F -> Unit // DEL: ignore
            b < 0x80 -> putChar(b)
            b <= 0x9F -> processC1(b)
            b < 0xC2 -> Unit // stray continuation / overlong lead: ignore
            b < 0xE0 -> {
                utf8Remaining = 1
                utf8Length = 2
                utf8Value = b and 0x1F
            }
            b < 0xF0 -> {
                utf8Remaining = 2
                utf8Length = 3
                utf8Value = b and 0x0F
            }
            b <= 0xF4 -> {
                utf8Remaining = 3
                utf8Length = 4
                utf8Value = b and 0x07
            }
            else -> Unit // 0xF5..0xFF can never start a valid sequence
        }
    }

    private fun emitUtf8() {
        val cp = utf8Value
        val valid = when (utf8Length) {
            2 -> cp >= 0x80
            3 -> cp >= 0x800 && cp !in 0xD800..0xDFFF
            else -> cp in 0x10000..0x10FFFF
        }
        if (!valid) return
        when {
            cp < 0x20 -> processC0(cp)
            cp == 0x7F -> Unit
            cp < 0xA0 -> processC1(cp)
            else -> putChar(cp)
        }
    }

    // ================================================================== c0/c1 =====

    private fun processC0(b: Int) {
        when (b) {
            0x07 -> if (!bell) {
                bell = true
                touch()
            }
            0x08 -> {
                wrapPending = false
                if (cursorColValue > 0) cursorColValue--
                touch()
            }
            0x09 -> {
                wrapPending = false
                cursorColValue = nextTab(cursorColValue)
                touch()
            }
            0x0A, 0x0B, 0x0C -> index()
            0x0D -> {
                wrapPending = false
                cursorColValue = 0
                touch()
            }
            0x0E, 0x0F -> Unit // SO/SI: charset shifts, ignored
            0x1B -> state = State.ESCAPE
            else -> Unit // NUL and the rest: ignore
        }
    }

    private fun processC1(b: Int) {
        when (b) {
            0x84 -> index() // IND
            0x85 -> { // NEL
                cursorColValue = 0
                index()
            }
            0x88 -> { // HTS
                tabStops.add(cursorColValue)
                touch()
            }
            0x8D -> reverseIndex() // RI
            0x90, 0x98, 0x9E, 0x9F -> { // DCS/SOS/PM/APC: consume until ST, discard
                strBuf.reset()
                strOverflow = false
                state = State.STR
            }
            0x9B -> { // CSI (8-bit)
                csiBuf.clear()
                csiOverflow = false
                state = State.CSI
            }
            0x9C -> Unit // ST in ground: ignore
            0x9D -> { // OSC (8-bit)
                strBuf.reset()
                strOverflow = false
                state = State.OSC
            }
            else -> Unit
        }
    }

    // ================================================================== esc =======

    private fun escByte(b: Int) {
        if (b > 0x7F) {
            state = State.GROUND
            groundByte(b)
            return
        }
        when (b) {
            'D'.code -> { index(); state = State.GROUND } // IND
            'E'.code -> { // NEL
                cursorColValue = 0
                index()
                state = State.GROUND
            }
            'M'.code -> { reverseIndex(); state = State.GROUND } // RI
            '7'.code -> { decSaved = capture(); state = State.GROUND } // DECSC
            '8'.code -> { decSaved?.let { restore(it, withRegion = false) }; state = State.GROUND } // DECRC
            'c'.code -> fullReset() // RIS
            'H'.code -> { // HTS
                tabStops.add(cursorColValue)
                touch()
                state = State.GROUND
            }
            'Z'.code -> { respond(DA_RESPONSE); state = State.GROUND } // DECID, answer like primary DA
            '='.code, '>'.code -> state = State.GROUND // keypad modes, ignored
            '('.code, ')'.code, '%'.code, '$'.code -> state = State.ESC_CHARSET
            '#'.code -> state = State.ESC_HASH
            '['.code -> {
                csiBuf.clear()
                csiOverflow = false
                state = State.CSI
            }
            ']'.code -> {
                strBuf.reset()
                strOverflow = false
                state = State.OSC
            }
            'P'.code, 'X'.code, '^'.code, '_'.code -> { // DCS/SOS/PM/APC
                strBuf.reset()
                strOverflow = false
                state = State.STR
            }
            '\\'.code -> state = State.GROUND // ST in ground: ignore
            0x18, 0x1A -> state = State.GROUND // CAN/SUB abort
            else -> state = State.GROUND // unknown: ignore
        }
    }

    // ================================================================== csi =======

    private fun csiByte(b: Int) {
        when {
            b == 0x1B -> state = State.ESCAPE // ESC aborts CSI
            b == 0x18 || b == 0x1A -> state = State.GROUND // CAN/SUB abort
            b < 0x20 -> processC0(b) // controls execute immediately, CSI continues
            b >= 0x80 -> {
                state = State.GROUND
                groundByte(b)
            }
            b in 0x40..0x7E -> {
                if (!csiOverflow) dispatchCsi(b.toChar())
                state = State.GROUND
            }
            else -> { // parameter / intermediate bytes 0x20..0x3F
                if (!csiOverflow) {
                    if (csiBuf.length >= MAX_CSI_BYTES) csiOverflow = true
                    else csiBuf.append(b.toChar())
                }
            }
        }
    }

    private fun dispatchCsi(final: Char) {
        var body = csiBuf.toString()
        var prefix: Char? = null
        if (body.isNotEmpty() && body[0] in "?><=!") {
            prefix = body[0]
            body = body.substring(1)
        }
        val isPrivate = prefix == '?'
        val params = parseParams(body, colon = final == 'm')
        when (final) {
            '@' -> insertChars(countParam(params.getOrNull(0))) // ICH
            'A' -> moveRow(-countParam(params.getOrNull(0))) // CUU
            'B' -> moveRow(countParam(params.getOrNull(0))) // CUD
            'C' -> moveCol(countParam(params.getOrNull(0))) // CUF
            'D' -> moveCol(-countParam(params.getOrNull(0))) // CUB
            'E' -> { // CNL
                moveRow(countParam(params.getOrNull(0)))
                cursorColValue = 0
                wrapPending = false
                touch()
            }
            'F' -> { // CPL
                moveRow(-countParam(params.getOrNull(0)))
                cursorColValue = 0
                wrapPending = false
                touch()
            }
            'G', '`' -> { // CHA
                cursorColValue = ((params.getOrNull(0) ?: 1).coerceAtLeast(1) - 1).coerceIn(0, colsValue - 1)
                wrapPending = false
                touch()
            }
            'H', 'f' -> { // CUP / HVP
                var r = (params.getOrNull(0) ?: 1).coerceAtLeast(1) - 1
                val c = (params.getOrNull(1) ?: 1).coerceAtLeast(1) - 1
                if (originMode) r += regionTopValue
                cursorRowValue = clampRow(r)
                cursorColValue = c.coerceIn(0, colsValue - 1)
                wrapPending = false
                touch()
            }
            'I' -> { // CHT
                wrapPending = false
                var n = countParam(params.getOrNull(0)).coerceAtMost(MAX_TAB_RUN)
                while (n-- > 0) cursorColValue = nextTab(cursorColValue)
                touch()
            }
            'J' -> eraseDisplay(params.getOrNull(0) ?: 0) // ED
            'K' -> eraseLine(params.getOrNull(0) ?: 0) // EL
            'L' -> insertLines(countParam(params.getOrNull(0))) // IL
            'M' -> deleteLines(countParam(params.getOrNull(0))) // DL
            'P' -> deleteChars(countParam(params.getOrNull(0))) // DCH
            'S' -> scrollRegionUp(countParam(params.getOrNull(0))) // SU
            'T' -> scrollRegionDown(countParam(params.getOrNull(0))) // SD
            'X' -> eraseChars(countParam(params.getOrNull(0))) // ECH
            'Z' -> { // CBT
                wrapPending = false
                var n = countParam(params.getOrNull(0)).coerceAtMost(MAX_TAB_RUN)
                while (n-- > 0) cursorColValue = prevTab(cursorColValue)
                touch()
            }
            'a' -> moveCol(countParam(params.getOrNull(0))) // HPR
            'b' -> { // REP
                val cp = lastGraphicCp
                if (cp != null) {
                    val n = countParam(params.getOrNull(0)).coerceAtMost(MAX_REP)
                    repeat(n) { putChar(cp) }
                }
            }
            'c' -> respond(DA_RESPONSE) // DA
            'd' -> { // VPA
                var r = (params.getOrNull(0) ?: 1).coerceAtLeast(1) - 1
                if (originMode) r += regionTopValue
                cursorRowValue = clampRow(r)
                wrapPending = false
                touch()
            }
            'e' -> moveRow(countParam(params.getOrNull(0))) // VPR
            'g' -> { // TBC
                when (params.getOrNull(0) ?: 0) {
                    0 -> tabStops.remove(cursorColValue)
                    3 -> tabStops.clear()
                }
                touch()
            }
            'h' -> setMode(clear = false, isPrivate = isPrivate, params = params) // SM
            'l' -> setMode(clear = true, isPrivate = isPrivate, params = params) // RM
            'm' -> handleSgr(params) // SGR
            'n' -> handleDsr(params.getOrNull(0) ?: 0) // DSR
            'r' -> setScrollRegion(params.getOrNull(0), params.getOrNull(1)) // DECSTBM
            's' -> { // ANSI save cursor (position only)
                val prev = decSaved
                decSaved = (prev ?: capture()).copy(row = cursorRowValue, col = cursorColValue)
                touch()
            }
            't' -> Unit // window ops: ignored
            'u' -> { // ANSI restore cursor (position only)
                decSaved?.let {
                    cursorRowValue = it.row.coerceIn(0, rowsValue - 1)
                    cursorColValue = it.col.coerceIn(0, colsValue - 1)
                    wrapPending = false
                    touch()
                }
            }
            else -> Unit // incl. 'q' (cursor style): ignored
        }
    }

    private fun parseParams(body: String, colon: Boolean): List<Int?> {
        if (body.isEmpty()) return emptyList()
        val out = ArrayList<Int?>(8)
        for (token in body.split(';')) {
            if (colon) {
                for (sub in token.split(':')) {
                    if (out.size >= MAX_PARAMS) return out
                    out.add(parseToken(sub))
                }
            } else {
                if (out.size >= MAX_PARAMS) return out
                out.add(parseToken(token))
            }
        }
        return out
    }

    private fun parseToken(token: String): Int? {
        val digits = token.filter { it in '0'..'9' }
        if (digits.isEmpty()) return null
        return digits.toIntOrNull()
    }

    private fun countParam(p: Int?): Int = (p ?: 1).coerceIn(1, MAX_COUNT)

    // ------------------------------------------------------------ cursor moves ----

    private fun clampRow(r: Int): Int =
        if (originMode) r.coerceIn(regionTopValue, regionBottomValue)
        else r.coerceIn(0, rowsValue - 1)

    private fun moveRow(delta: Int) {
        wrapPending = false
        cursorRowValue = clampRow(cursorRowValue + delta)
        touch()
    }

    private fun moveCol(delta: Int) {
        wrapPending = false
        cursorColValue = (cursorColValue + delta).coerceIn(0, colsValue - 1)
        touch()
    }

    // ------------------------------------------------------------ editing ---------

    private fun bceBlank() = TerminalCell(32, false, TextAttributes(background = penBg))

    private fun blankRowOf(n: Int = colsValue): ArrayList<TerminalCell> =
        ArrayList<TerminalCell>(n).also { row ->
            val blank = TerminalCell(32, false, TextAttributes(background = penBg))
            repeat(n) { row.add(blank) }
        }

    private fun eraseRow(r: Int) {
        active()[r] = blankRowOf()
        touch()
    }

    private fun eraseRowRange(r: Int, from: Int, to: Int) {
        val row = active()[r]
        val lo = from.coerceIn(0, colsValue - 1)
        val hi = to.coerceIn(0, colsValue - 1)
        if (lo > hi) return
        for (i in lo..hi) row[i] = bceBlank()
        touch()
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { // cursor to end of scroll region
                eraseRowRange(cursorRowValue, cursorColValue, colsValue - 1)
                for (r in cursorRowValue + 1..regionBottomValue) eraseRow(r)
            }
            1 -> { // start of scroll region to cursor
                for (r in regionTopValue until cursorRowValue) eraseRow(r)
                eraseRowRange(cursorRowValue, 0, cursorColValue)
            }
            2 -> for (r in 0 until rowsValue) eraseRow(r)
            3 -> {
                for (r in 0 until rowsValue) eraseRow(r)
                if (scrollback.isNotEmpty()) {
                    scrollback.clear()
                    scrollbackOffsetValue = 0
                }
            }
            else -> Unit
        }
        wrapPending = false
        touch()
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> eraseRowRange(cursorRowValue, cursorColValue, colsValue - 1)
            1 -> eraseRowRange(cursorRowValue, 0, cursorColValue)
            2 -> eraseRow(cursorRowValue)
            else -> Unit
        }
        wrapPending = false
        touch()
    }

    private fun insertChars(n: Int) {
        wrapPending = false
        val row = active()[cursorRowValue]
        val k = n.coerceIn(0, colsValue)
        var i = colsValue - 1
        while (i >= cursorColValue + k) {
            row[i] = row[i - k]
            i--
        }
        while (i >= cursorColValue) {
            row[i] = bceBlank()
            i--
        }
        touch()
    }

    private fun deleteChars(n: Int) {
        wrapPending = false
        val row = active()[cursorRowValue]
        val k = n.coerceIn(0, colsValue)
        var i = cursorColValue
        while (i + k < colsValue) {
            row[i] = row[i + k]
            i++
        }
        while (i < colsValue) {
            row[i] = bceBlank()
            i++
        }
        touch()
    }

    private fun eraseChars(n: Int) {
        wrapPending = false
        val row = active()[cursorRowValue]
        val k = n.coerceIn(0, colsValue - cursorColValue)
        for (i in cursorColValue until cursorColValue + k) row[i] = bceBlank()
        touch()
    }

    private fun insertLines(n: Int) {
        wrapPending = false
        if (cursorRowValue !in regionTopValue..regionBottomValue) return
        val grid = active()
        val k = n.coerceIn(0, regionBottomValue - cursorRowValue + 1)
        repeat(k) {
            grid.removeAt(regionBottomValue)
            grid.add(cursorRowValue, blankRowOf())
        }
        touch()
    }

    private fun deleteLines(n: Int) {
        wrapPending = false
        if (cursorRowValue !in regionTopValue..regionBottomValue) return
        val grid = active()
        val k = n.coerceIn(0, regionBottomValue - cursorRowValue + 1)
        repeat(k) {
            grid.removeAt(cursorRowValue)
            grid.add(regionBottomValue, blankRowOf())
        }
        touch()
    }

    // ------------------------------------------------------------ scrolling -------

    private fun index() {
        wrapPending = false
        if (cursorRowValue == regionBottomValue) scrollRegionUp(1)
        else cursorRowValue = (cursorRowValue + 1).coerceIn(0, rowsValue - 1)
        touch()
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cursorRowValue == regionTopValue) scrollRegionDown(1)
        else cursorRowValue = (cursorRowValue - 1).coerceIn(0, rowsValue - 1)
        touch()
    }

    private fun scrollRegionUp(n: Int) {
        wrapPending = false
        val height = regionBottomValue - regionTopValue + 1
        val k = n.coerceIn(0, height)
        if (k == 0) return
        val grid = active()
        val fullHeight = regionTopValue == 0 && regionBottomValue == rowsValue - 1
        repeat(k) {
            val removed = grid.removeAt(regionTopValue)
            if (fullHeight && !isAltValue) pushScrollback(removed)
            grid.add(regionBottomValue, blankRowOf())
        }
        touch()
    }

    private fun scrollRegionDown(n: Int) {
        wrapPending = false
        val height = regionBottomValue - regionTopValue + 1
        val k = n.coerceIn(0, height)
        if (k == 0) return
        val grid = active()
        repeat(k) {
            grid.removeAt(regionBottomValue)
            grid.add(regionTopValue, blankRowOf())
        }
        touch()
    }

    private fun pushScrollback(row: List<TerminalCell>) {
        if (scrollbackLimitValue == 0) return
        scrollback.addLast(row.toList())
        while (scrollback.size > scrollbackLimitValue) scrollback.removeFirst()
    }

    private fun setScrollRegion(topParam: Int?, bottomParam: Int?) {
        val top = (topParam ?: 1).coerceAtLeast(1)
        val bottom = (bottomParam ?: rowsValue).coerceAtLeast(1)
        if (top < bottom && bottom <= rowsValue) {
            regionTopValue = top - 1
            regionBottomValue = bottom - 1
            cursorRowValue = if (originMode) regionTopValue else 0
            cursorColValue = 0
            wrapPending = false
            touch()
        }
        // Invalid margins: ignore (xterm behaviour).
    }

    // ------------------------------------------------------------ modes -----------

    private fun setMode(clear: Boolean, isPrivate: Boolean, params: List<Int?>) {
        if (!isPrivate || params.isEmpty()) return
        for (p in params) {
            val n = p ?: continue
            when (n) {
                6 -> { // DECOM origin
                    originMode = !clear
                    cursorRowValue = if (originMode) regionTopValue else 0
                    cursorColValue = 0
                    wrapPending = false
                }
                7 -> autowrap = !clear // DECAWM
                25 -> cursorVisibleValue = !clear // DECTCEM
                47 -> if (!clear) enterAlt(clear = false) else exitAlt()
                1047 -> if (!clear) enterAlt(clear = true) else exitAlt()
                1049 -> if (!clear) enterAlt(clear = true) else exitAlt()
                else -> Unit // 1/1000/1002/1003/1006/1004/2004/…: recorded only
            }
            if (clear) privateModes.remove(n) else privateModes.add(n)
        }
        touch()
    }

    private fun enterAlt(clear: Boolean) {
        if (!isAltValue) {
            mainSaved = capture()
            isAltValue = true
            scrollbackOffsetValue = 0
        }
        if (clear) {
            for (r in 0 until rowsValue) altLines[r] = blankRowOf()
        }
        cursorRowValue = 0
        cursorColValue = 0
        wrapPending = false
        regionTopValue = 0
        regionBottomValue = rowsValue - 1
        originMode = false
        touch()
    }

    private fun exitAlt() {
        if (!isAltValue) return
        isAltValue = false
        mainSaved?.let { restore(it, withRegion = true) }
        mainSaved = null
        scrollbackOffsetValue = 0
        wrapPending = false
        touch()
    }

    // ------------------------------------------------------------ queries ---------

    private fun handleDsr(n: Int) {
        when (n) {
            5 -> respond(DSR_OK)
            6 -> {
                val r = (if (originMode) cursorRowValue - regionTopValue else cursorRowValue) + 1
                respond("\u001B[$r;${cursorColValue + 1}R".toByteArray(Charsets.US_ASCII))
            }
            else -> Unit
        }
    }

    private fun respond(bytes: ByteArray) {
        onResponse?.invoke(bytes)
    }

    // ------------------------------------------------------------ sgr --------------

    private fun handleSgr(params: List<Int?>) {
        var list = params
        if (list.isEmpty() || (list.size == 1 && list[0] == null)) list = listOf(0)
        var i = 0
        while (i < list.size) {
            when (list[i] ?: 0) {
                0 -> resetPen()
                1 -> penBold = true
                2 -> penDim = true
                3 -> penItalic = true
                4 -> penUnderline = true
                5, 6 -> penBlink = true
                7 -> penInverse = true
                8 -> penHidden = true
                9 -> penStrike = true
                21 -> penBold = false // xterm: also exits bold
                22 -> {
                    penBold = false
                    penDim = false
                }
                23 -> penItalic = false
                24 -> penUnderline = false
                25 -> penBlink = false
                27 -> penInverse = false
                28 -> penHidden = false
                29 -> penStrike = false
                in 30..37 -> {
                    val idx = (list[i] ?: 30) - 30
                    penFg = colorScheme.ansi[idx]
                    penFgIndex = idx
                }
                38 -> i = sgrExtended(list, i, foreground = true)
                39 -> {
                    penFg = null
                    penFgIndex = null
                }
                in 40..47 -> penBg = colorScheme.ansi[(list[i] ?: 40) - 40]
                48 -> i = sgrExtended(list, i, foreground = false)
                49 -> penBg = null
                in 90..97 -> {
                    val idx = (list[i] ?: 90) - 90 + 8
                    penFg = colorScheme.ansi[idx]
                    penFgIndex = idx
                }
                in 100..107 -> penBg = colorScheme.ansi[(list[i] ?: 100) - 100 + 8]
                else -> Unit
            }
            i++
        }
        touch()
    }

    /** Consumes `38/48 …` and returns the index of the last consumed parameter. */
    private fun sgrExtended(list: List<Int?>, at: Int, foreground: Boolean): Int {
        fun atParam(offset: Int): Int? = if (at + offset < list.size) list[at + offset] else null
        when (atParam(1) ?: -1) {
            5 -> {
                val n = atParam(2) ?: -1
                if (n in 0..255) {
                    val (color, index) = indexedColor(n)
                    if (foreground) {
                        penFg = color
                        penFgIndex = index
                    } else {
                        penBg = color
                    }
                }
                return at + 2
            }
            2 -> {
                val r = (atParam(2) ?: 0).coerceIn(0, 255)
                val g = (atParam(3) ?: 0).coerceIn(0, 255)
                val b = (atParam(4) ?: 0).coerceIn(0, 255)
                val color = TerminalColor(r, g, b)
                if (foreground) {
                    penFg = color
                    penFgIndex = null
                } else {
                    penBg = color
                }
                return at + 4
            }
            else -> return at + 1 // bare/incomplete 38/48: skip the mode slot, change nothing
        }
    }

    private fun indexedColor(n: Int): Pair<TerminalColor, Int?> {
        if (n in 0..15) return colorScheme.ansi[n] to n
        if (n in 16..231) {
            val v = n - 16
            fun comp(c: Int) = if (c == 0) 0 else 55 + 40 * c
            return TerminalColor(comp(v / 36), comp((v % 36) / 6), comp(v % 6)) to null
        }
        val g = 8 + 10 * (n - 232)
        return TerminalColor(g, g, g) to null
    }

    private fun resetPen() {
        penFg = null
        penFgIndex = null
        penBg = null
        penBold = false
        penDim = false
        penItalic = false
        penUnderline = false
        penBlink = false
        penInverse = false
        penHidden = false
        penStrike = false
    }

    private fun currentAttrs(): TextAttributes {
        val fg = if (penBold && boldIsBright && penFgIndex != null && penFgIndex in 0..7) {
            colorScheme.ansi[penFgIndex!! + 8]
        } else {
            penFg
        }
        return TextAttributes(
            foreground = fg,
            background = penBg,
            bold = penBold,
            dim = penDim,
            italic = penItalic,
            underline = penUnderline,
            blink = penBlink,
            inverse = penInverse,
            hidden = penHidden,
            strike = penStrike,
        )
    }

    // ------------------------------------------------------------ osc/str ----------

    private fun oscByte(b: Int) {
        when {
            b == 0x07 -> {
                dispatchOsc()
                state = State.GROUND
            }
            b == 0x1B -> state = State.OSC_ESC
            b == 0x9C -> {
                dispatchOsc()
                state = State.GROUND
            }
            b == 0x18 || b == 0x1A -> {
                strBuf.reset()
                strOverflow = false
                state = State.GROUND
            }
            b < 0x20 -> Unit // other C0 inside OSC: ignore
            else -> {
                if (!strOverflow) {
                    if (strBuf.size() >= MAX_OSC_BYTES) strOverflow = true
                    else strBuf.write(b)
                }
            }
        }
    }

    private fun oscEscByte(b: Int) {
        if (b == '\\'.code) {
            dispatchOsc()
            state = State.GROUND
        } else {
            // Not ST: abort the OSC and reinterpret ESC as a fresh introducer.
            strBuf.reset()
            strOverflow = false
            state = State.ESCAPE
            escByte(b)
        }
    }

    private fun dispatchOsc() {
        val payload = strBuf.toByteArray()
        strBuf.reset()
        if (strOverflow) {
            strOverflow = false
            return
        }
        val semi = payload.indexOf(';'.code.toByte())
        if (semi <= 0) return
        val ps = payload.copyOfRange(0, semi).toString(Charsets.US_ASCII).trim().toIntOrNull() ?: return
        val pt = payload.copyOfRange(semi + 1, payload.size)
        when (ps) {
            0, 1, 2 -> {
                title = pt.toString(Charsets.UTF_8)
                touch()
            }
            10, 11, 12 -> {
                if (pt.toString(Charsets.US_ASCII).trim() == "?") {
                    val c = when (ps) {
                        10 -> penFg ?: colorScheme.foreground
                        11 -> penBg ?: colorScheme.background
                        else -> colorScheme.cursor
                    }
                    respond(
                        "\u001B]$ps;rgb:${hex4(c.red)}/${hex4(c.green)}/${hex4(c.blue)}\u001B\\"
                            .toByteArray(Charsets.US_ASCII),
                    )
                }
            }
            else -> Unit // 4/8/…: consumed and ignored
        }
    }

    private fun strByte(b: Int) {
        // DCS/APC/PM/SOS: consume until ST, discard. Nothing is stored, so no cap is needed.
        when {
            b == 0x07 || b == 0x9C || b == 0x18 || b == 0x1A -> state = State.GROUND
            b == 0x1B -> state = State.STR_ESC
            else -> Unit
        }
    }

    private fun strEscByte(b: Int) {
        if (b == '\\'.code) state = State.GROUND
        else {
            state = State.ESCAPE
            escByte(b)
        }
    }

    // ------------------------------------------------------------ printing --------

    private fun putChar(cp: Int) {
        if (isCombining(cp)) {
            // Absorbed into the previous cell: no advance, no overwrite.
            touch()
            return
        }
        if (wrapPending) {
            wrapPending = false
            if (autowrap) {
                cursorColValue = 0
                if (cursorRowValue == regionBottomValue) scrollRegionUp(1)
                else cursorRowValue = (cursorRowValue + 1).coerceIn(0, rowsValue - 1)
            }
        }
        val wide = isWide(cp)
        if (wide && colsValue >= 2 && cursorColValue == colsValue - 1) {
            // A wide glyph never straddles the margin: the pair wraps to the next row.
            if (autowrap) {
                cursorColValue = 0
                if (cursorRowValue == regionBottomValue) scrollRegionUp(1)
                else cursorRowValue = (cursorRowValue + 1).coerceIn(0, rowsValue - 1)
            } else {
                active()[cursorRowValue][cursorColValue] = TerminalCell(cp, false, currentAttrs())
                lastGraphicCp = cp
                touch()
                return
            }
        }
        val row = active()[cursorRowValue]
        val attrs = currentAttrs()
        // Clear neighbor halves of any wide glyph we overlap: writing on a
        // continuation cell must blank the old lead (else the view draws it
        // over the new char), and writing a narrow char on a lead cell must
        // blank the stale continuation (else a phantom blank follows).
        if (row.getOrNull(cursorColValue)?.wideContinuation == true && cursorColValue > 0) {
            row[cursorColValue - 1] = TerminalCell(32, false, attrs)
        }
        if (!wide && cursorColValue + 1 < colsValue &&
            row.getOrNull(cursorColValue + 1)?.wideContinuation == true
        ) {
            // Cursor sits on the lead of a wide glyph; the continuation dies.
            row[cursorColValue + 1] = TerminalCell(32, false, attrs)
        }
        if (wide && cursorColValue + 1 < colsValue) {
            row[cursorColValue] = TerminalCell(cp, false, attrs)
            row[cursorColValue + 1] = TerminalCell(32, true, attrs)
            lastGraphicCp = cp
            val nc = cursorColValue + 2
            if (nc >= colsValue) {
                cursorColValue = colsValue - 1
                wrapPending = true
            } else {
                cursorColValue = nc
            }
        } else {
            row[cursorColValue] = TerminalCell(cp, false, attrs)
            lastGraphicCp = cp
            if (cursorColValue + 1 >= colsValue) {
                cursorColValue = colsValue - 1
                wrapPending = true
            } else {
                cursorColValue++
            }
        }
        touch()
    }

    // ------------------------------------------------------------ esc actions -----

    private fun decalN() {
        for (r in 0 until rowsValue) {
            val row = active()[r]
            for (c in 0 until colsValue) row[c] = TerminalCell('E'.code)
        }
        touch()
    }

    private fun fullReset() {
        for (r in 0 until rowsValue) {
            mainLines[r] = blankRowOf()
            altLines[r] = blankRowOf()
        }
        scrollback.clear()
        scrollbackOffsetValue = 0
        cursorRowValue = 0
        cursorColValue = 0
        cursorVisibleValue = true
        wrapPending = false
        isAltValue = false
        mainSaved = null
        decSaved = null
        lastGraphicCp = null
        resetPen()
        autowrap = true
        originMode = false
        privateModes.clear()
        regionTopValue = 0
        regionBottomValue = rowsValue - 1
        tabStops = defaultStops(colsValue)
        utf8Remaining = 0
        state = State.GROUND
        touch()
    }

    // ------------------------------------------------------------ save/restore ----

    private fun capture(): SavedState = SavedState(
        row = cursorRowValue,
        col = cursorColValue,
        fg = penFg,
        fgIndex = penFgIndex,
        bg = penBg,
        bold = penBold,
        dim = penDim,
        italic = penItalic,
        underline = penUnderline,
        blink = penBlink,
        inverse = penInverse,
        hidden = penHidden,
        strike = penStrike,
        origin = originMode,
        wrap = autowrap,
        top = regionTopValue,
        bottom = regionBottomValue,
    )

    private fun restore(s: SavedState, withRegion: Boolean) {
        if (withRegion) {
            regionTopValue = s.top.coerceIn(0, rowsValue - 1)
            regionBottomValue = s.bottom.coerceIn(regionTopValue, rowsValue - 1)
        }
        penFg = s.fg
        penFgIndex = s.fgIndex
        penBg = s.bg
        penBold = s.bold
        penDim = s.dim
        penItalic = s.italic
        penUnderline = s.underline
        penBlink = s.blink
        penInverse = s.inverse
        penHidden = s.hidden
        penStrike = s.strike
        originMode = s.origin
        autowrap = s.wrap
        cursorRowValue = s.row.coerceIn(0, rowsValue - 1)
        cursorColValue = s.col.coerceIn(0, colsValue - 1)
        wrapPending = false
        touch()
    }

    private fun SavedState.coerced(nc: Int, nr: Int): SavedState =
        copy(
            row = row.coerceIn(0, nr - 1),
            col = col.coerceIn(0, nc - 1),
            top = top.coerceIn(0, nr - 1),
            bottom = bottom.coerceIn(top.coerceIn(0, nr - 1), nr - 1),
        )

    // ------------------------------------------------------------ tabs ------------

    private fun nextTab(from: Int): Int = tabStops.filter { it > from }.minOrNull() ?: (colsValue - 1)

    private fun prevTab(from: Int): Int = tabStops.filter { it < from }.maxOrNull() ?: 0

    // ------------------------------------------------------------ misc ------------

    private fun touch() {
        dirty = true
    }

    private fun touchAndBump() {
        revisionValue++
    }

    companion object {
        private const val DEFAULT_COLS = 80
        private const val DEFAULT_ROWS = 24
        private const val DEFAULT_SCROLLBACK_LINES = 1000
        private const val MAX_DIM = 512
        private const val MAX_CSI_BYTES = 256
        private const val MAX_PARAMS = 32
        private const val MAX_COUNT = 10000
        private const val MAX_REP = 16384
        private const val MAX_TAB_RUN = 1024
        private const val MAX_OSC_BYTES = 4096

        private val DA_RESPONSE = "\u001B[?62;1;2c".toByteArray(Charsets.US_ASCII)
        private val DSR_OK = "\u001B[0n".toByteArray(Charsets.US_ASCII)

        private fun blankScreen(cols: Int, rows: Int): ArrayList<ArrayList<TerminalCell>> =
            ArrayList<ArrayList<TerminalCell>>(rows).also { grid ->
                repeat(rows) { grid.add(blankRow(cols)) }
            }

        private fun blankRow(cols: Int): ArrayList<TerminalCell> =
            ArrayList<TerminalCell>(cols).also { row ->
                repeat(cols) { row.add(TerminalCell(32)) }
            }

        private fun adjustGrid(grid: ArrayList<ArrayList<TerminalCell>>, nc: Int, nr: Int) {
            for (row in grid) {
                while (row.size < nc) row.add(TerminalCell(32))
                while (row.size > nc) row.removeAt(row.size - 1)
            }
            while (grid.size < nr) grid.add(blankRow(nc))
            while (grid.size > nr) grid.removeAt(grid.size - 1)
        }

        private fun adjustRowWidth(row: List<TerminalCell>, nc: Int): List<TerminalCell> {
            if (row.size == nc) return row
            if (row.size > nc) return row.subList(0, nc).toList()
            return row + List(nc - row.size) { TerminalCell(32) }
        }

        private fun defaultStops(cols: Int): MutableSet<Int> =
            ((8 until cols) step 8).toMutableSet()

        private fun hex4(v: Int): String = (v.coerceIn(0, 255) * 257).toString(16).padStart(4, '0')

        private fun isCombining(cp: Int): Boolean {
            val type = Character.getType(cp)
            return type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
        }

        @Suppress("MagicNumber")
        private fun isWide(cp: Int): Boolean = cp in 0x1100..0x115F ||
            cp in 0x231A..0x231B ||
            cp in 0x23E9..0x23EC ||
            cp == 0x23F0 ||
            cp == 0x23F3 ||
            cp in 0x25FD..0x25FE ||
            cp in 0x2614..0x2615 ||
            cp in 0x2648..0x2653 ||
            cp == 0x267F ||
            cp == 0x2693 ||
            cp == 0x26A1 ||
            cp in 0x26AA..0x26AB ||
            cp in 0x26BD..0x26BE ||
            cp in 0x26C4..0x26C5 ||
            cp == 0x26CE ||
            cp == 0x26D4 ||
            cp == 0x26EA ||
            cp in 0x26F2..0x26F3 ||
            cp == 0x26F5 ||
            cp == 0x26FA ||
            cp == 0x26FD ||
            cp == 0x2705 ||
            cp in 0x270A..0x270B ||
            cp == 0x2728 ||
            cp in 0x274C..0x274E ||
            cp in 0x2753..0x2755 ||
            cp in 0x2795..0x2797 ||
            cp == 0x27B0 ||
            cp == 0x27BF ||
            cp in 0x2B1B..0x2B1C ||
            cp == 0x2B50 ||
            cp == 0x2B55 ||
            cp in 0x2E80..0x303E ||
            cp in 0x3041..0x33FF ||
            cp in 0x3400..0x4DBF ||
            cp in 0x4E00..0xA4CF ||
            cp in 0xA960..0xA97C ||
            cp in 0xAC00..0xD7A3 ||
            cp in 0xF900..0xFAFF ||
            cp in 0xFE10..0xFE19 ||
            cp in 0xFE30..0xFE6B ||
            cp in 0xFF00..0xFF60 ||
            cp in 0xFFE0..0xFFE6 ||
            cp in 0x20000..0x2FFFD ||
            cp in 0x30000..0x3FFFD ||
            cp in 0x1F300..0x1FAFF
    }
}
