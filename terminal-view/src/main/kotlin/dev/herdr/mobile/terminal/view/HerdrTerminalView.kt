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
 * - vertical drag / fling scrolls the emulator scrollback through [onScrollLines];
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

    var onScrollLines: ((lines: Int) -> Unit)? = null
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

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            cellAt(e.x, e.y)?.let { (col, row) -> onTapCell?.invoke(col, row) }
            // Terminal tap always summons the keyboard: direct ASCII input goes
            // through our InputConnection, CJK goes through the bottom panel.
            requestFocus()
            showKeyboard()
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
            if (cellHeight > 0) {
                val lines = (distanceY / cellHeight).toInt()
                if (lines != 0) onScrollLines?.invoke(lines)
            }
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            if (cellHeight > 0 && selectAnchor == null) {
                val lines = (velocityY / -cellHeight / 4).toInt().coerceIn(-40, 40)
                if (lines != 0) onScrollLines?.invoke(lines)
            }
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
        textPaint.typeface = Typeface.create(familyName, Typeface.NORMAL)
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

    /** Called on the UI thread with the newest snapshot. */
    fun render(snapshot: FrameSnapshot) {
        if (snapshot.revision == frame.revision && snapshot.revision >= 0) return
        frame = snapshot
        if (snapshot.scrollbackOffset == 0) {
            selectAnchor = null
            selectActive = null
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
        val row = floor(y / cellHeight).toInt().coerceIn(0, snapshot.rowCount - 1)
        return col to row
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
            val line = snapshot.rows.getOrNull(row) ?: continue
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

    private fun isSelected(col: Int, row: Int): Boolean {
        val anchor = selectAnchor ?: return false
        val active = selectActive ?: return false
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
        return true
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        // Hardware / adb key events bypass the InputConnection entirely.
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DEL -> {
                    onDirectDelete?.invoke()
                    return true
                }
                android.view.KeyEvent.KEYCODE_ENTER -> {
                    onDirectInput?.invoke("\r")
                    return true
                }
                else -> {
                    val c = event.unicodeChar
                    if (c != 0) {
                        onDirectInput?.invoke(String(Character.toChars(c)))
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        // Consume to keep focus; the work happens on key down.
        return true
    }

    override fun onKeyPreIme(keyCode: Int, event: android.view.KeyEvent): Boolean {
        // System back while the keyboard is up: dismiss the keyboard instead of
        // leaving the terminal screen. The view keeps focus for the next tap.
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
            event.action == android.view.KeyEvent.ACTION_UP
        ) {
            hideKeyboard()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

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

        for (row in 0 until snapshot.rowCount) {
            val line = snapshot.rows.getOrNull(row) ?: continue
            val top = row * cellHeight
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
        if (snapshot.cursorVisible && showCursor &&
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

    private fun typefaceFor(cell: TerminalCell): Typeface {
        val base = textPaint.typeface ?: Typeface.MONOSPACE
        return when {
            cell.attrs.bold && cell.attrs.italic -> Typeface.create(base, Typeface.BOLD_ITALIC)
            cell.attrs.bold -> Typeface.create(base, Typeface.BOLD)
            cell.attrs.italic -> Typeface.create(base, Typeface.ITALIC)
            else -> Typeface.create(base, Typeface.NORMAL)
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
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
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
                // CJK composition belongs to the bottom pre-composition panel, not the
                // grid: commit it as-is rather than dropping keystrokes silently.
                if (!text.isNullOrEmpty()) onDirectInput?.invoke(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) { onDirectDelete?.invoke() }
                return true
            }

            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        android.view.KeyEvent.KEYCODE_DEL -> onDirectDelete?.invoke()
                        android.view.KeyEvent.KEYCODE_ENTER ->
                            onDirectInput?.invoke("\r")
                        else -> {
                            val c = event.unicodeChar
                            if (c != 0) onDirectInput?.invoke(String(Character.toChars(c)))
                        }
                    }
                }
                return true
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                if (editorAction == EditorInfo.IME_ACTION_DONE ||
                    editorAction == EditorInfo.IME_ACTION_GO
                ) {
                    onDirectInput?.invoke("\r")
                    return true
                }
                return super.performEditorAction(editorAction)
            }
        }
    }
}
