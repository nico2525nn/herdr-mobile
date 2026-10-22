package dev.herdr.mobile.terminal.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import com.termux.view.TerminalRenderer
import com.termux.view.TerminalView
import java.lang.reflect.Field
import kotlin.math.max
import kotlin.math.min

/**
 * Bridges box-drawing characters across terminal cell boundaries.
 *
 * Termux draws every cell with the system font and derives the row pitch from
 * `ceil(Paint.getFontSpacing())`. Box-drawing glyphs in Android's monospace
 * fallbacks stop short of the cell edges, so tables and frames read as dashed
 * lines (a small gap at every row/column boundary). We repaint each box cell
 * with its resolved background and draw the line segments ourselves, so
 * strokes connect across cells regardless of the font — no line-height tuning.
 *
 * Mirrors [TerminalRenderer.render]: same external-row mapping, same style
 * decoding, same palette, same cursor/selection skips. Drawn by
 * [BoxDrawingDrawable] as the terminal view's foreground, so it is recorded
 * with the same display list as the cells it covers.
 */
internal object BoxDrawing {

    private const val BASE = 0x2500

    /**
     * 2 bits per direction (0 none, 1 light/single, 2 heavy, 3 double) plus
     * bit 8 for rounded corners, indexed by code point - [BASE]
     * (U+2500..U+257F). Diagonals (U+2571..U+2573) are 0: left to the font.
     */
    private val SEGMENTS = intArrayOf(
        // Dashed variants (U+2504..U+250B, U+254C..U+254F) stay 0: the font's
        // intentional dashes must not be bridged into solid rules.
        0x050, 0x0a0, 0x005, 0x00a, 0x000, 0x000, 0x000, 0x000, 0x000, 0x000, 0x000, 0x000,
        0x044, 0x084, 0x048, 0x088, 0x014, 0x024, 0x018, 0x028, 0x041, 0x081, 0x042, 0x082,
        0x011, 0x021, 0x012, 0x022, 0x045, 0x085, 0x046, 0x049, 0x04a, 0x086, 0x089, 0x08a,
        0x015, 0x025, 0x016, 0x019, 0x01a, 0x026, 0x029, 0x02a, 0x054, 0x064, 0x094, 0x0a4,
        0x058, 0x068, 0x098, 0x0a8, 0x051, 0x061, 0x091, 0x0a1, 0x052, 0x062, 0x092, 0x0a2,
        0x055, 0x065, 0x095, 0x0a5, 0x056, 0x059, 0x05a, 0x066, 0x096, 0x069, 0x099, 0x0a6,
        0x0a9, 0x06a, 0x09a, 0x0aa, 0x000, 0x000, 0x000, 0x000, 0x0f0, 0x00f, 0x0c4, 0x04c,
        0x0cc, 0x034, 0x01c, 0x03c, 0x0c1, 0x043, 0x0c3, 0x031, 0x013, 0x033, 0x0c5, 0x04f,
        0x0cf, 0x035, 0x01f, 0x03f, 0x0f4, 0x05c, 0x0fc, 0x0f1, 0x053, 0x0f3, 0x0f5, 0x05f,
        0x0ff, 0x144, 0x114, 0x111, 0x141, 0x000, 0x000, 0x000, 0x010, 0x001, 0x040, 0x004,
        0x020, 0x002, 0x080, 0x008, 0x090, 0x009, 0x060, 0x006,
    )

    /** Reused by [resolveColors]: 0 = foreground, 1 = background. */
    private val colorScratch = IntArray(2)

    private val topRowField: Field? = fieldOf(TerminalView::class.java, "mTopRow")
    private val spacingAndAscentField: Field? =
        fieldOf(TerminalRenderer::class.java, "mFontLineSpacingAndAscent")
    private val selectorsField: Field? = fieldOf(TerminalView::class.java, "mDefaultSelectors")

    private fun fieldOf(type: Class<*>, name: String): Field? = runCatching {
        type.getDeclaredField(name).apply { isAccessible = true }
    }.getOrNull()

    fun draw(canvas: Canvas, view: TerminalView, stroke: Paint, fill: Paint, arc: RectF) {
        val emu = view.mEmulator ?: return
        val renderer = view.mRenderer ?: return
        val topRow = topRowField?.getInt(view) ?: return
        val topOffset = spacingAndAscentField?.getInt(renderer) ?: return
        val lineSpacing = renderer.fontLineSpacing
        val fontWidth = renderer.fontWidth
        if (lineSpacing <= 0 || fontWidth <= 0f) return
        val screen = emu.screen ?: return
        val palette = emu.mColors?.mCurrentColors ?: return
        val columns = emu.mColumns
        val rows = emu.mRows
        if (columns <= 0 || rows <= 0) return

        val reverseVideo = emu.isReverseVideo()
        val cursorVisible = emu.shouldCursorBeVisible()
        val cursorRow = emu.cursorRow
        val cursorCol = emu.cursorCol
        val selectors = selectorsField?.get(view) as? IntArray
        val hasSelection = selectors != null && selectors[0] >= 0

        for (row in 0 until rows) {
            val line = topRow + row
            val lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(line))
                ?: continue
            val text = lineObject.mText ?: continue
            val used = lineObject.spaceUsed
            val cellTop = (row * lineSpacing + topOffset).toFloat()
            val cellBottom = cellTop + lineSpacing
            var column = 0
            var charIndex = 0
            while (column < columns && charIndex < used) {
                val first = text[charIndex]
                val high = Character.isHighSurrogate(first)
                if (high && charIndex + 1 >= used) break
                val codePoint =
                    if (high) Character.toCodePoint(first, text[charIndex + 1]) else first.code
                val width = WcWidth.width(codePoint)
                if (width <= 0) {
                    // Combining mark: belongs to the previous cell (which we
                    // already painted); never a box-drawing base character.
                    charIndex += if (high) 2 else 1
                    continue
                }
                if (codePoint in BASE..(BASE + 127)) {
                    val segments = SEGMENTS[codePoint - BASE]
                    if (segments != 0) {
                        val selected = hasSelection &&
                            line >= selectors!![1] && line <= selectors[3] &&
                            column >= (if (line == selectors[1]) selectors[0] else 0) &&
                            column <= (if (line == selectors[3]) selectors[2] else columns)
                        val isCursor =
                            cursorVisible && line == cursorRow && column == cursorCol
                        if (!selected && !isCursor &&
                            resolveColors(lineObject.getStyle(column), palette, reverseVideo)
                        ) {
                            drawCell(
                                canvas, stroke, fill, arc,
                                column * fontWidth,
                                cellTop,
                                (column + width) * fontWidth,
                                cellBottom,
                                segments, colorScratch[0], colorScratch[1],
                            )
                        }
                    }
                }
                charIndex += if (high) 2 else 1
                column += width
            }
        }
    }

    /**
     * Mirrors TerminalRenderer.drawTextRun colour resolution into
     * [colorScratch]. Returns false when the cell is invisible.
     */
    private fun resolveColors(style: Long, palette: IntArray, reverseVideo: Boolean): Boolean {
        val effect = TextStyle.decodeEffect(style)
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE != 0) return false
        val bold = effect and (
            TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK
            ) != 0
        var fore = TextStyle.decodeForeColor(style)
        var back = TextStyle.decodeBackColor(style)
        if (fore and ALPHA_MASK != ALPHA_MASK) {
            if (bold && fore in 0..7) fore += 8
            if (fore !in palette.indices) return false
            fore = palette[fore]
        }
        if (back and ALPHA_MASK != ALPHA_MASK) {
            if (back !in palette.indices) return false
            back = palette[back]
        }
        val inverse = effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0
        if (reverseVideo xor inverse) {
            val swap = fore
            fore = back
            back = swap
        }
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0) {
            val red = (fore shr 16 and 0xff) * 2 / 3
            val green = (fore shr 8 and 0xff) * 2 / 3
            val blue = (fore and 0xff) * 2 / 3
            fore = ALPHA_MASK or (red shl 16) or (green shl 8) or blue
        }
        colorScratch[0] = fore
        colorScratch[1] = back
        return true
    }

    private fun drawCell(
        canvas: Canvas,
        stroke: Paint,
        fill: Paint,
        arc: RectF,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        segments: Int,
        foreground: Int,
        background: Int,
    ) {
        // Cover the font's own (short) glyph, then draw exact cell geometry.
        fill.color = background
        canvas.drawRect(x0, y0, x1, y1, fill)

        val up = segments and 3
        val down = segments shr 2 and 3
        val left = segments shr 4 and 3
        val right = segments shr 6 and 3
        val rounded = segments shr 8 and 1 == 1

        val width = x1 - x0
        val height = y1 - y0
        val cx = x0 + width * 0.5f
        val cy = y0 + height * 0.5f
        val thin = max(1f, height * 0.06f)
        val heavy = thin * 2.2f
        val gap = height * 0.075f

        stroke.color = foreground
        stroke.style = Paint.Style.STROKE
        stroke.strokeCap = Paint.Cap.BUTT

        val verticalCount = (if (up != 0) 1 else 0) + (if (down != 0) 1 else 0)
        val horizontalCount = (if (left != 0) 1 else 0) + (if (right != 0) 1 else 0)

        if (rounded && verticalCount == 1 && horizontalCount == 1) {
            drawRoundedCorner(canvas, stroke, arc, x0, y0, x1, y1, cx, cy, up, down, left, right, thin)
            return
        }
        if (verticalCount == 1 && horizontalCount == 1 &&
            (up == 3 || down == 3) && (left == 3 || right == 3)
        ) {
            drawDoubleCorner(canvas, stroke, x0, y0, x1, y1, cx, cy, up, down, left, right, thin, gap)
            return
        }

        // Vertical arms.
        if (up != 0) {
            val startY = if (left == 3 || right == 3) cy + gap else cy
            drawArm(stroke, canvas, vertical = true, horizontal = false, style = up, cx = cx, from = startY, to = y0, thin, heavy, gap)
        }
        if (down != 0) {
            val startY = if (left == 3 || right == 3) cy - gap else cy
            drawArm(stroke, canvas, vertical = true, horizontal = false, style = down, cx = cx, from = startY, to = y1, thin, heavy, gap)
        }
        // Horizontal arms.
        if (left != 0) {
            val startX = if (up == 3 || down == 3) cx + gap else cx
            drawArm(stroke, canvas, vertical = false, horizontal = true, style = left, cx = cy, from = startX, to = x0, thin, heavy, gap)
        }
        if (right != 0) {
            val startX = if (up == 3 || down == 3) cx - gap else cx
            drawArm(stroke, canvas, vertical = false, horizontal = true, style = right, cx = cy, from = startX, to = x1, thin, heavy, gap)
        }
    }

    /** One arm from [from] to [to] along the given axis; doubles get two rails. */
    private fun drawArm(
        stroke: Paint,
        canvas: Canvas,
        vertical: Boolean,
        horizontal: Boolean,
        style: Int,
        cx: Float,
        from: Float,
        to: Float,
        thin: Float,
        heavy: Float,
        gap: Float,
    ) {
        when (style) {
            1 -> {
                stroke.strokeWidth = thin
                if (vertical) canvas.drawLine(cx, from, cx, to, stroke)
                else canvas.drawLine(from, cx, to, cx, stroke)
            }
            2 -> {
                stroke.strokeWidth = heavy
                if (vertical) canvas.drawLine(cx, from, cx, to, stroke)
                else canvas.drawLine(from, cx, to, cx, stroke)
            }
            else -> {
                stroke.strokeWidth = thin
                val lo = min(from, to)
                val hi = max(from, to)
                if (vertical) {
                    canvas.drawLine(cx - gap, lo, cx - gap, hi, stroke)
                    canvas.drawLine(cx + gap, lo, cx + gap, hi, stroke)
                } else {
                    canvas.drawLine(lo, cx - gap, hi, cx - gap, stroke)
                    canvas.drawLine(lo, cx + gap, hi, cx + gap, stroke)
                }
            }
        }
    }

    /** ╔ ╗ ╚ ╝: nested outer/inner rails meeting at the corner. */
    private fun drawDoubleCorner(
        canvas: Canvas,
        stroke: Paint,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        cx: Float,
        cy: Float,
        up: Int,
        down: Int,
        left: Int,
        right: Int,
        thin: Float,
        gap: Float,
    ) {
        stroke.strokeWidth = thin
        val vSign = if (down != 0) 1 else -1
        val hSign = if (right != 0) 1 else -1
        val vExtent = if (down != 0) y1 else y0
        val hExtent = if (right != 0) x1 else x0
        // Outer rail sits on the corner side (away from the cell centre).
        val outerX = cx - hSign * gap
        val innerX = cx + hSign * gap
        val outerY = cy - vSign * gap
        val innerY = cy + vSign * gap
        canvas.drawLine(outerX, outerY, outerX, vExtent, stroke)
        canvas.drawLine(innerX, innerY, innerX, vExtent, stroke)
        canvas.drawLine(outerX, outerY, hExtent, outerY, stroke)
        canvas.drawLine(innerX, innerY, hExtent, innerY, stroke)
    }

    /** ╭ ╮ ╰ ╯: quarter arc plus the two straight tails. */
    private fun drawRoundedCorner(
        canvas: Canvas,
        stroke: Paint,
        arc: RectF,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        cx: Float,
        cy: Float,
        up: Int,
        down: Int,
        left: Int,
        right: Int,
        thin: Float,
    ) {
        stroke.strokeWidth = max(thin, 1f)
        val radius = min(x1 - x0, y1 - y0) * 0.30f
        val down = down != 0
        val right = right != 0
        val vTailEnd: Float
        val hTailEnd: Float
        val arcRect: RectF
        var startAngle: Float
        when {
            down && right -> { // ╭
                arcRect = RectF(cx, cy, cx + 2 * radius, cy + 2 * radius)
                startAngle = 180f
                vTailEnd = cy + radius
                hTailEnd = cx + radius
            }
            down && !right -> { // ╮
                arcRect = RectF(cx - 2 * radius, cy, cx, cy + 2 * radius)
                startAngle = 270f
                vTailEnd = cy + radius
                hTailEnd = cx - radius
            }
            !down && right -> { // ╰
                arcRect = RectF(cx, cy - 2 * radius, cx + 2 * radius, cy)
                startAngle = 90f
                vTailEnd = cy - radius
                hTailEnd = cx + radius
            }
            else -> { // ╯
                arcRect = RectF(cx - 2 * radius, cy - 2 * radius, cx, cy)
                startAngle = 0f
                vTailEnd = cy - radius
                hTailEnd = cx - radius
            }
        }
        arc.set(arcRect)
        canvas.drawArc(arc, startAngle, 90f, false, stroke)
        val vEdge = if (down) y1 else y0
        val hEdge = if (right) x1 else x0
        canvas.drawLine(cx, vTailEnd, cx, vEdge, stroke)
        canvas.drawLine(hTailEnd, cy, hEdge, cy, stroke)
    }

    private const val ALPHA_MASK = 0xff000000.toInt()
}
