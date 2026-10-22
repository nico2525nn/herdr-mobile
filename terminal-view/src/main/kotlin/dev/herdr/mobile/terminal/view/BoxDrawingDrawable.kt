package dev.herdr.mobile.terminal.view

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.termux.view.TerminalView

/**
 * Box-drawing bridge drawn as the [TerminalView]'s foreground.
 *
 * A parent's `dispatchDraw` is not re-recorded when only the child's display
 * list changes (hardware rendering reuses the parent's list), so an overlay
 * owned by an ancestor goes stale or never appears. A foreground drawable is
 * part of the view's own display list: it is re-recorded exactly when the
 * terminal redraws (output, scroll, cursor blink), so the strokes always match
 * the cells underneath.
 */
internal class BoxDrawingDrawable(private val view: TerminalView) : Drawable() {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val arc = RectF()

    override fun draw(canvas: Canvas) {
        BoxDrawing.draw(canvas, view, stroke, fill, arc)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Installs the box-drawing bridge as this view's foreground. */
fun com.termux.view.TerminalView.installBoxDrawingOverlay() {
    foreground = BoxDrawingDrawable(this)
}
