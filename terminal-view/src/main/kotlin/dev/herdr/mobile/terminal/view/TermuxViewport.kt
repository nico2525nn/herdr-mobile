package dev.herdr.mobile.terminal.view

import android.graphics.Paint
import com.termux.view.TerminalView

/**
 * Row geometry and local-offset math for a [TerminalView].
 *
 * Split out of [TermuxTerminalHost] so the host reads as gesture/routing code;
 * the reflection fallbacks and paint measurement stay in one place.
 */
internal object TermuxViewport {

    /**
     * Row pitch in pixels.
     *
     * Live-exact: the renderer's row pitch is view height over rows (it lays
     * out exactly mRows rows). The old fontSpacing guess ran ~14% small
     * (typeface metrics), overscrolling every remote drag. Falls back to the
     * guess only before attach (rows unknown — never scrolled then). The
     * renderer's own pitch is the unit Termux uses for local scrolling, so
     * remote pans move by the same amount as a local drag.
     */
    fun rowHeightPx(view: TerminalView, textSizePx: Int, paint: Paint): Float {
        TermuxInternals.rowPitchPx(view)?.let { return it }
        val rows = try {
            view.currentSession?.emulator?.mRows ?: 0
        } catch (_: Exception) {
            0
        }
        if (rows > 0 && view.height > 0) return view.height.toFloat() / rows
        if (textSizePx <= 0) return 0f
        paint.textSize = textSizePx.toFloat()
        return paint.fontSpacing
    }

    /**
     * Rows the view actually shows. Measured from the view and its row pitch,
     * not the mirror's grid: the two disagree while a resize round-trip is in
     * flight (the mirror reported 22 rows for a 44-row view).
     */
    fun visibleRows(view: TerminalView, rowPitchPx: Float): Int {
        if (rowPitchPx > 0f && view.height > 0) return (view.height / rowPitchPx).toInt()
        return try {
            view.currentSession?.emulator?.mRows ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /** Shift the local transcript by [rows], clamped to the banked history. */
    fun applyLocalScroll(view: TerminalView, history: Int, rows: Int) {
        if (history <= 0 || rows == 0) return
        view.topRow = (view.topRow + rows).coerceIn(-history, 0)
        view.invalidate()
    }
}
