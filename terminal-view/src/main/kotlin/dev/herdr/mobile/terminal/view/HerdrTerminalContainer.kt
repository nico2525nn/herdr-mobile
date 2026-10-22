package dev.herdr.mobile.terminal.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.widget.FrameLayout
import com.termux.view.TerminalView

/**
 * Hosts a [TerminalView] and bridges box-drawing glyphs after it draws.
 *
 * Termux's renderer draws box-drawing characters with the system font, whose
 * glyphs stop short of the cell edges (dashed tables). The overlay in
 * [BoxDrawing] repaints those cells with exact geometry on top of the view, so
 * vertical/horizontal rules connect without touching line height or font.
 */
class HerdrTerminalContainer(context: Context) : FrameLayout(context) {

    val terminalView: TerminalView = TerminalView(context, null)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val arc = RectF()
    private val path = Path()

    init {
        addView(
            terminalView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        BoxDrawing.draw(canvas, terminalView, stroke, fill, arc, path)
    }
}
