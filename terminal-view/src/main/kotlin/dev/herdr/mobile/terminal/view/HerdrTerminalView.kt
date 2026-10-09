package dev.herdr.mobile.terminal.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.herdr.mobile.core.model.CursorStyle
import dev.herdr.mobile.terminal.emulator.TerminalCell
import dev.herdr.mobile.terminal.emulator.TerminalColor
import dev.herdr.mobile.terminal.emulator.TerminalColorScheme
import kotlin.math.floor
import kotlin.math.max

private fun TerminalColor.argb(): Int =
    (alpha shl 24) or (red shl 16) or (green shl 8) or blue

/**
 * Renders [FrameSnapshot]s on Canvas with one monospace paint per style bucket.
 *
 * Interaction contract with the host:
 * - vertical drag / fling scrolls locally (view-owned offset, termux mTopRow
 *   model): no emulator round-trip, no grid copy — offset change + invalidate;
 * - single tap moves the emulator cursor only when the remote app asked for mouse events —
 *   the host decides that from its own mouse-mode flag and calls [onTapCell];
 * - pinch zooms the font through [onZoomFont], clamped by the host;
 * - text selection is word/line based and reported as plain text through [onSelection].
 */
class HerdrTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Alt-screen scroll: lines>0 finger-up (live-ward), lines<0 older. Host forwards. */
    var onRemoteScroll: ((lines: Int) -> Unit)? = null
    /**
     * Host part of remote-scroll routing: true when the pane runs an agent
     * (main-screen TUIs like codex whose transcript lives in HOST scrollback,
     * not local history). Alt-screen is OR-ed live from the frame (it flips
     * mid-session without recomposition). Entering remote snaps local home:
     * mixed offsets would double-scroll.
     */
    var remoteScrollHost: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                stopFling()
                setTopRow(0)
            }
        }

    /** Effective routing: host (agent) OR live alt-screen flag. */
    private val remoteEffective: Boolean
        get() = remoteScrollHost || frame.usingAlternateScreen
    /** Diagnostic: remote scroll requests sent (alt/agent mode). */
    var remoteScrolls: Long = 0
        private set
    var onTapCell: ((col: Int, row: Int) -> Unit)? = null
    var onZoomFont: ((deltaSp: Float) -> Unit)? = null
    var onSelection: ((text: String) -> Unit)? = null
    /** ASCII / direct keys from the soft keyboard (no composition). */
    var onDirectInput: ((text: String) -> Unit)? = null
    /** DEL key from the soft keyboard. */
    var onDirectDelete: (() -> Unit)? = null

    private var frame: FrameSnapshot = FrameSnapshot.empty()
    private var scheme: TerminalColorScheme? = null
    private var cursorStyle: CursorStyle = CursorStyle.BLOCK
    private var showCursor = true

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val fillPaint = Paint()
    private val cursorPaint = Paint()

    private var cellWidth = 0f
    private var cellHeight = 0f
    private var ascent = 0f

    private var selectAnchor: Pair<Int, Int>? = null
    private var selectActive: Pair<Int, Int>? = null

    // Viewport offset into history+screen, termux mTopRow style: 0 = pinned to
    // the live bottom, negative = scrolled up N rows. Owned entirely by the
    // view: the emulator never sees it, so drags never leave the UI thread.
    private var topRow = 0
    /** Diagnostic counters (no content): gesture callbacks and applied rows. */
    var scrollEvents: Long = 0
        private set
    var scrolledRows: Long = 0
        private set
    /** Current viewport offset for diagnostics (0 = live). */
    val currentTopRow: Int get() = topRow
    /** History rows in the latest snapshot (0 when unknown). */
    val snapshotHistorySize: Int get() = frame.history.size
    /** Alt-screen flag of the latest snapshot. */
    val snapshotUsingAlt: Boolean get() = frame.usingAlternateScreen
    /** Mouse-tracking flag of the latest snapshot. */
    val snapshotMouseTracking: Boolean get() = frame.mouseTracking
    // Fractional drag pixels carried across onScroll calls (termux
    // mScrollRemainder): without this, sub-row drags round to zero per event
    // and slow drags never move at all.
    private var scrollRemainder = 0f
    private val scroller = android.widget.OverScroller(context)
    private var flingTick: Runnable? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // A fresh touch wins over everything: kill the fling so a
            // catching tap doesn't fight the animation. Must return true:
            // all gestures begin here and false risks the rest being dropped.
            stopFling()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // A tap with an active selection clears it (termux behavior) and
            // does nothing else: without this a longpress captures every
            // future drag into the selection branch and scrolling dies.
            if (selectAnchor != null) {
                selectAnchor = null
                selectActive = null
                invalidate()
                return true
            }
            // Tap = click + optional keyboard. The click ALWAYS goes out:
            // herdr routes terminal.mouse server-side (ghostty knows the real
            // mouse state) and ignores it for non-mouse apps — so this is safe
            // on shells and correct on vim/less/tmux. We do NOT gate on our
            // own mouseTracking flag: herdr's control stream consumes DECSET
            // into server state and never forwards the raw sequence (verified:
            // a live ESC[?1000h never appears in any frame), so our flag reads
            // false even when the app tracks the mouse. Termux gates on its
            // emulator because it OWNS the PTY; we are a remote client.
            // Mouse-reporting apps address the LIVE screen (0-based): convert
            // buffer coords back by the history size.
            cellAt(e.x, e.y)?.let { (col, bufRow) ->
                onTapCell?.invoke(col, bufRow - frame.history.size)
            }
            // Click-only (Termux): the keyboard NEVER auto-shows — not on
            // tap, not on page swipe, not on focus. The CJK panel's keyboard
            // button is the single summon path. Focus is still taken so
            // hardware keys keep working.
            requestFocus()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            cellAt(e.x, e.y)?.let { (col, row) ->
                selectAnchor = col to row
                selectActive = col to row
                reportSelection()
                invalidate()
            }
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            if (selectAnchor != null) {
                cellAt(e2.x, e2.y)?.let {
                    selectActive = it
                    reportSelection()
                    invalidate()
                }
                return true
            }
            // Any new drag kills an in-flight fling (termux: don't scroll
            // until the last fling is taken care of — a fresh touch wins).
            stopFling()
            if (cellHeight > 0) {
                scrollEvents++
                val total = distanceY + scrollRemainder
                val lines = (total / cellHeight).toInt()
                scrollRemainder = total - lines * cellHeight
                if (lines != 0) {
                    if (remoteEffective) {
                        // TUI/alt: no local history applies — forward to Herdr,
                        // which routes per context (host scrollback / app
                        // arrows / mouse wheel). Repaint frames arrive as
                        // normal bytes; local offset stays pinned at live.
                        remoteScrolls++
                        onRemoteScroll?.invoke(lines)
                    } else {
                        scrollRows(lines)
                    }
                }
            }
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            if (cellHeight <= 0 || selectAnchor != null) return true
            if (!scroller.isFinished) return true
            // Remote-mode fling: one aggregated remote jump (per-tick
            // messages would spam RTTs on bad networks; a single jump lands
            // the same). Sign matches scrollRows: finger-up (velocityY<0) is
            // live-ward (lines>0), finger-down is older-ward.
            if (remoteEffective) {
                val lines = (velocityY / -cellHeight / 4).toInt().coerceIn(-40, 40)
                if (lines != 0) {
                    remoteScrolls++
                    onRemoteScroll?.invoke(lines)
                }
                return true
            }
            // Finger-up fling (velocityY < 0): content keeps flying up toward
            // LIVE (termux convention, same formula as TermuxView:
            // -(velocityY * SCALE) is positive here). Clamped to history.
            // NOTE: an earlier audit claimed this sign was reversed — it is
            // not; inverting it would break consistency with onScroll above.
            val maxUp = frame.history.size
            scroller.fling(0, topRow, 0, (velocityY / -cellHeight / 4).toInt(), 0, 0, -maxUp, 0)
            val tick = object : Runnable {
                override fun run() {
                    if (scroller.isFinished) {
                        flingTick = null
                        return
                    }
                    val more = scroller.computeScrollOffset()
                    setTopRow(scroller.currY)
                    if (more) {
                        postOnAnimation(this)
                    } else {
                        flingTick = null
                    }
                }
            }
            flingTick = tick
            postOnAnimation(tick)
            return true
        }
    })

    private var scaleDetector: ScaleGestureDetector? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val delta = (detector.scaleFactor - 1f) * 8f
                    if (delta != 0f) onZoomFont?.invoke(delta)
                    return true
                }
            },
        )
    }

    fun setColorScheme(scheme: TerminalColorScheme) {
        this.scheme = scheme
        cursorPaint.color = scheme.cursor.argb()
        invalidate()
    }

    fun setTerminalFont(familyName: String, sizeSp: Float, lineHeightMultiplier: Float) {
        baseTypeface = Typeface.create(familyName, Typeface.NORMAL)
        typeNormal = Typeface.create(baseTypeface, Typeface.NORMAL)
        typeBold = Typeface.create(baseTypeface, Typeface.BOLD)
        typeItalic = Typeface.create(baseTypeface, Typeface.ITALIC)
        typeBoldItalic = Typeface.create(baseTypeface, Typeface.BOLD_ITALIC)
        textPaint.typeface = typeNormal
        textPaint.textSize = sizeSp * resources.displayMetrics.scaledDensity
        val metrics = textPaint.fontMetrics
        cellHeight = (metrics.descent - metrics.ascent) * lineHeightMultiplier
        ascent = -metrics.ascent * lineHeightMultiplier
        cellWidth = textPaint.measureText("M")
        requestLayout()
        invalidate()
    }

    fun setCursorStyle(style: CursorStyle) {
        cursorStyle = style
        invalidate()
    }

    fun setCursorBlinking(visible: Boolean) {
        showCursor = visible
        invalidate()
    }

    /**
     * Scroll by whole rows, termux convention: positive [lines] (finger
     * dragged up, content follows finger) moves toward LIVE (topRow toward
     * 0); negative moves toward older history (topRow more negative).
     * Synchronous: offset change + invalidate on the UI thread, zero copies.
     */
    fun scrollRows(lines: Int) {
        if (lines == 0) return
        setTopRow(topRow + lines)
    }

    /** Pin back to the live bottom (input sent, user back at the prompt). */
    fun snapToBottom() {
        stopFling()
        setTopRow(0)
    }

    private fun stopFling() {
        flingTick?.let { removeCallbacks(it) }
        flingTick = null
        if (!scroller.isFinished) scroller.abortAnimation()
    }

    private fun setTopRow(row: Int) {
        // Remote mode (alt/agent) pins local live: any offset request clamps.
        val maxUp = if (remoteEffective) 0 else frame.history.size
        val next = row.coerceIn(-maxUp, 0)
        if (next == topRow) return
        scrolledRows += kotlin.math.abs(next - topRow)
        topRow = next
        if (topRow == 0) {
            selectAnchor = null
            selectActive = null
        }
        invalidate()
    }

    /** Buffer row index (history+screen concatenated) for a visible viewport row. */
    private fun bufferRow(viewRow: Int): Int {
        val snap = frame
        val total = snap.history.size + snap.rowCount
        return (total - snap.rowCount + topRow + viewRow).coerceIn(0, (total - 1).coerceAtLeast(0))
    }

    /** Row cells for a buffer index (history first, then live screen). */
    private fun bufferLine(snap: FrameSnapshot, index: Int): List<TerminalCell> {
        return if (index < snap.history.size) {
            snap.history[index]
        } else {
            snap.screen.getOrNull(index - snap.history.size) ?: emptyList()
        }
    }

    private var lastHistorySize = 0

    /** Called on the UI thread with the newest snapshot. */
    fun render(snapshot: FrameSnapshot) {
        if (snapshot.revision == frame.revision && snapshot.revision >= 0) return
        frame = snapshot
        // Alt-screen engaged live (no host round-trip): snap local home so a
        // stale shell offset never double-shifts the remote viewport.
        if (snapshot.usingAlternateScreen && topRow != 0) {
            topRow = 0
            selectAnchor = null
            selectActive = null
        }
        // Content anchor: while scrolled (topRow<0), history growth shifts the
        // offset WITH the content so the same rows stay visible (otherwise the
        // view drifts newer with every banked row). At live (topRow=0) nothing
        // to do. Shrinks (trim/limit/resize) just clamp.
        val growth = snapshot.history.size - lastHistorySize
        lastHistorySize = snapshot.history.size
        if (topRow < 0 && growth > 0) {
            topRow -= growth
        }
        // New content never moves the viewport (stable view while reading
        // history); just clamp into the new buffer. Input snaps via snapToBottom.
        val maxUp = if (snapshot.usingAlternateScreen) 0 else snapshot.history.size
        val clamped = topRow.coerceIn(-maxUp, 0)
        if (clamped != topRow) {
            topRow = clamped
            if (topRow == 0) {
                selectAnchor = null
                selectActive = null
            }
        }
        invalidate()
    }

    fun clearSelection() {
        selectAnchor = null
        selectActive = null
        invalidate()
    }

    /** Grid size the current view area fits, for resize propagation. */
    fun gridFor(widthPx: Int, heightPx: Int): Pair<Int, Int> {
        if (cellWidth <= 0 || cellHeight <= 0) return 80 to 24
        return max(20, floor(widthPx / cellWidth).toInt()) to
            max(5, floor(heightPx / cellHeight).toInt())
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        val snapshot = frame
        if (snapshot.cols <= 0 || snapshot.rowCount <= 0) return null
        val col = floor(x / cellWidth).toInt().coerceIn(0, snapshot.cols - 1)
        val viewRow = floor(y / cellHeight).toInt().coerceIn(0, snapshot.rowCount - 1)
        // Buffer coords (termux absolute style): selection anchors survive
        // scrolling. Tap cells (mouse mode) want SCREEN coords instead —
        // onTapCell callers subtract the offset (see onSingleTapUp).
        return col to bufferRow(viewRow)
    }

    private fun reportSelection() {
        val anchor = selectAnchor ?: return
        val active = selectActive ?: return
        val snapshot = frame
        val (startRow, startCol, endRow, endCol) =
            if (anchor.second < active.second ||
                (anchor.second == active.second && anchor.first <= active.first)
            ) {
                listOf(anchor.second, anchor.first, active.second, active.first)
            } else {
                listOf(active.second, active.first, anchor.second, anchor.first)
            }
        val builder = StringBuilder()
        for (row in startRow..endRow) {
            val line = bufferLine(snapshot, row)
            if (line.isEmpty()) {
                if (row != endRow) builder.append('\n')
                continue
            }
            val from = if (row == startRow) startCol else 0
            val to = if (row == endRow) endCol else line.size - 1
            for (col in from..to) {
                val cell = line.getOrNull(col) ?: continue
                if (cell.wideContinuation) continue
                builder.appendCodePoint(cell.codepoint)
            }
            if (row != endRow) builder.append('\n')
        }
        onSelection?.invoke(builder.toString().trimEnd())
    }

    private fun isSelected(col: Int, viewRow: Int): Boolean {
        val anchor = selectAnchor ?: return false
        val active = selectActive ?: return false
        val row = bufferRow(viewRow)
        val (r1, c1, r2, c2) =
            if (anchor.second < active.second ||
                (anchor.second == active.second && anchor.first <= active.first)
            ) {
                listOf(anchor.second, anchor.first, active.second, active.first)
            } else {
                listOf(active.second, active.first, anchor.second, anchor.first)
            }
        if (row < r1 || row > r2) return false
        if (row == r1 && col < c1) return false
        if (row == r2 && col > c2) return false
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector?.onTouchEvent(event)
        if (scaleDetector?.isInProgress == true) return true
        gestures.onTouchEvent(event)
        // Gesture end: drop the fractional carry (termux onUp). Kept across
        // onScroll calls within the gesture so sub-row drags accumulate.
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            scrollRemainder = 0f
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        // Hardware / adb key events bypass the InputConnection entirely.
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (handleHardwareKey(keyCode, event)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Shared mapping for onKeyDown and InputConnection.sendKeyEvent: DPAD/TAB/ESC
     * escape sequences, DEL/ENTER, printable chars, and Ctrl chords synthesized
     * from the keycode when unicodeChar==0. Returns true when consumed.
     */
    private fun handleHardwareKey(keyCode: Int, event: android.view.KeyEvent): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DEL -> {
                onDirectDelete?.invoke()
                return true
            }
            android.view.KeyEvent.KEYCODE_ENTER -> {
                onDirectInput?.invoke("\r")
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                onDirectInput?.invoke("\u001B[A")
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                onDirectInput?.invoke("\u001B[B")
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                onDirectInput?.invoke("\u001B[D")
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onDirectInput?.invoke("\u001B[C")
                return true
            }
            android.view.KeyEvent.KEYCODE_TAB -> {
                onDirectInput?.invoke("\t")
                return true
            }
            android.view.KeyEvent.KEYCODE_ESCAPE -> {
                onDirectInput?.invoke("\u001B")
                return true
            }
        }
        val c = event.unicodeChar
        // Ctrl+key often reports unicodeChar==0: synthesize the control
        // byte from the keycode letter instead of dropping it.
        if (c != 0) {
            onDirectInput?.invoke(String(Character.toChars(c)))
            return true
        } else if (event.isCtrlPressed) {
            val ctrl = keyCodeToCtrlByte(keyCode)
            if (ctrl != null) {
                onDirectInput?.invoke(String(byteArrayOf(ctrl), Charsets.ISO_8859_1))
                return true
            }
        }
        return false
    }

    private fun keyCodeToCtrlByte(keyCode: Int): Byte? {
        // KEYCODE_A..Z -> 0x01..0x1A.
        if (keyCode in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z) {
            return (keyCode - android.view.KeyEvent.KEYCODE_A + 1).toByte()
        }
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_SPACE -> 0x00.toByte()
            android.view.KeyEvent.KEYCODE_SLASH -> 0x1F.toByte()
            else -> null
        }
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        // Only consume what onKeyDown consumed; anything else belongs to the
        // framework/IME (modifiers, system keys).
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_DEL,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_TAB,
            android.view.KeyEvent.KEYCODE_ESCAPE -> true
            else -> super.onKeyUp(keyCode, event)
        }
    }

    override fun onKeyPreIme(keyCode: Int, event: android.view.KeyEvent): Boolean {
        // System back while the keyboard is up: dismiss the keyboard instead of
        // leaving the terminal screen. Consume only when WE showed the keyboard
        // and haven't hidden it since — isAcceptingText is useless here (true
        // whenever this always-editor view has focus, keyboard or not), and
        // consuming unconditionally traps back navigation with no escape.
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
            event.action == android.view.KeyEvent.ACTION_DOWN &&
            keyboardShownByUs
        ) {
            keyboardShownByUs = false
            hideKeyboard()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    /** True between our showKeyboard() and the matching hide/timeout. */
    @Volatile
    private var keyboardShownByUs = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snapshot = frame
        val scheme = scheme ?: return
        if (snapshot.cols <= 0 || snapshot.rowCount <= 0) return
        if (cellWidth <= 0 || cellHeight <= 0) return

        fillPaint.color = scheme.background.argb()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)

        val defaultFg = scheme.foreground.argb()
        val defaultBg = scheme.background.argb()

        for (viewRow in 0 until snapshot.rowCount) {
            val line = bufferLine(snapshot, bufferRow(viewRow))
            val top = viewRow * cellHeight
            val row = viewRow
            var col = 0
            while (col < snapshot.cols) {
                val cell = line.getOrNull(col) ?: break
                if (cell.wideContinuation) {
                    col++
                    continue
                }
                val widthCells = if (isWide(cell)) 2 else 1
                val left = col * cellWidth
                val right = left + cellWidth * widthCells
                val selected = isSelected(col, row)
                val cellBg = cell.attrs.background
                val cellFg = cell.attrs.foreground
                val bg = when {
                    selected -> scheme.selection.argb()
                    cell.attrs.inverse -> (cellFg ?: scheme.foreground).argb()
                    cellBg != null -> cellBg.argb()
                    else -> defaultBg
                }
                if (bg != defaultBg) {
                    fillPaint.color = bg
                    canvas.drawRect(left, top, right, top + cellHeight, fillPaint)
                }
                if (!cell.attrs.hidden && !(cell.isBlank && !selected)) {
                    textPaint.color = when {
                        cell.attrs.inverse -> (cellBg ?: scheme.background).argb()
                        cellFg != null -> cellFg.argb()
                        else -> defaultFg
                    }
                    textPaint.typeface = typefaceFor(cell)
                    textPaint.isUnderlineText = cell.attrs.underline
                    textPaint.isStrikeThruText = cell.attrs.strike
                    if (cell.attrs.dim || cell.attrs.blink) textPaint.alpha = 160 else textPaint.alpha = 255
                    val text = String(Character.toChars(cell.codepoint))
                    canvas.drawText(text, left, top + ascent, textPaint)
                }
                if (cell.attrs.underline) {
                    // Underline already drawn by the paint flag; nothing extra.
                }
                col += widthCells
            }
        }
        // Hidden while scrolled into history: the live cursor isn't in the
        // viewport, and drawing it at live coords on scrolled content puts a
        // block in the middle of unrelated text.
        if (snapshot.cursorVisible && showCursor &&
            topRow == 0 &&
            snapshot.cursorRow in 0 until snapshot.rowCount &&
            snapshot.cursorCol in 0 until snapshot.cols &&
            selectAnchor == null
        ) {
            val left = snapshot.cursorCol * cellWidth
            val top = snapshot.cursorRow * cellHeight
            when (cursorStyle) {
                CursorStyle.BLOCK -> canvas.drawRect(left, top, left + cellWidth, top + cellHeight, cursorPaint)
                CursorStyle.UNDERLINE -> canvas.drawRect(
                    left, top + cellHeight - max(2f, cellHeight * 0.08f),
                    left + cellWidth, top + cellHeight, cursorPaint,
                )

                CursorStyle.BAR -> canvas.drawRect(
                    left, top, left + max(2f, cellWidth * 0.12f), top + cellHeight, cursorPaint,
                )
            }
        }
    }

    private var baseTypeface: Typeface = Typeface.MONOSPACE
    private var typeNormal: Typeface = Typeface.MONOSPACE
    private var typeBold: Typeface = Typeface.MONOSPACE
    private var typeItalic: Typeface = Typeface.MONOSPACE
    private var typeBoldItalic: Typeface = Typeface.MONOSPACE

    private fun typefaceFor(cell: TerminalCell): Typeface {
        // Cached per setTerminalFont: Typeface.create per cell per frame costs
        // thousands of lookups per draw (scroll jank).
        return when {
            cell.attrs.bold && cell.attrs.italic -> typeBoldItalic
            cell.attrs.bold -> typeBold
            cell.attrs.italic -> typeItalic
            else -> typeNormal
        }
    }

    private fun isWide(cell: TerminalCell): Boolean {
        val cp = cell.codepoint
        // East Asian Wide/Fullwidth plus common emoji ranges. The emulator already marks
        // continuation cells; this only decides how many columns the lead cell spans.
        return cp in 0x1100..0x115F ||
            cp in 0x2E80..0xA4CF ||
            cp in 0xAC00..0xD7A3 ||
            cp in 0xF900..0xFAFF ||
            cp in 0xFE30..0xFE4F ||
            cp in 0xFF00..0xFF60 ||
            cp in 0xFFE0..0xFFE6 ||
            cp in 0x1F300..0x1FAFF ||
            cp in 0x20000..0x3FFFD
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scaleDetector = null
    }

    /** Show the soft keyboard for direct input. No-op when nothing consumes it. */
    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        keyboardShownByUs = true
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        keyboardShownByUs = false
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        imm.hideSoftInputFromWindow(windowToken, 0)
    }


    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.actionLabel = null
        // Plain text with no suggestions: the terminal interprets every key itself.
        // No TYPE_TEXT_FLAG_NO_SUGGESTIONS on purpose — Gboard needs the class only.
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) onDirectInput?.invoke(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Composing text is UNCONFIRMED (kana readings, pinyin fragments):
                // never send it to the PTY. The IME follows with commitText() for
                // the confirmed string. Sending both would duplicate + pollute
                // the remote line. CJK composition belongs to the bottom panel.
                return super.setComposingText(text, newCursorPosition)
            }

            override fun finishComposingText(): Boolean {
                return super.finishComposingText()
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // (0,0) is a composing-region cleanup ping, not a delete request:
                // honor it only when there is actually something to delete.
                // afterLength (forward delete) maps to ESC [ 3 ~.
                if (beforeLength == 0 && afterLength == 0) return super.deleteSurroundingText(0, 0)
                repeat(beforeLength) { onDirectDelete?.invoke() }
                repeat(afterLength) { onDirectInput?.invoke("\u001B[3~") }
                return true
            }

            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                // Same mapping as onKeyDown (DPAD/TAB/ESC/Ctrl chords): the IME
                // and hardware paths must agree, or keys silently differ by source.
                // Unhandled keys fall through to super (not unconditional true),
                // or the framework/IME never sees ACTION_UP and system keys.
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    if (handleHardwareKey(event.keyCode, event)) return true
                    val c = event.unicodeChar
                    if (c != 0) {
                        onDirectInput?.invoke(String(Character.toChars(c)))
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                // Every IME action is Enter on a terminal: DONE, GO, SEND, SEARCH,
                // NEXT, PREVIOUS, UNSPECIFIED — Gboard/fleet IME variants send
                // different ones depending on language, layout, and fullscreen
                // state. Restricting to DONE/GO strands Enter on some devices.
                if (editorAction == EditorInfo.IME_ACTION_DONE ||
                    editorAction == EditorInfo.IME_ACTION_GO ||
                    editorAction == EditorInfo.IME_ACTION_SEND ||
                    editorAction == EditorInfo.IME_ACTION_SEARCH ||
                    editorAction == EditorInfo.IME_ACTION_NEXT ||
                    editorAction == EditorInfo.IME_ACTION_PREVIOUS ||
                    editorAction == EditorInfo.IME_ACTION_UNSPECIFIED ||
                    editorAction == EditorInfo.IME_ACTION_NONE
                ) {
                    onDirectInput?.invoke("\r")
                    return true
                }
                return super.performEditorAction(editorAction)
            }
        }
    }
}
