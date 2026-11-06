package dev.herdr.mobile.terminal.view

import android.view.GestureDetector
import android.view.MotionEvent
import com.termux.view.TerminalView

/**
 * The terminal's touch stream: drag, fling and which events Termux may still see.
 *
 * Extracted from [TermuxTerminalHost] so the host is routing/state and this is
 * the gesture state machine. The rules are subtle and each one shipped as a
 * regression once:
 *
 * - long-press detection is DISABLED here: stock GestureDetector swallows every
 *   MOVE after its own 500ms long-press, deadening dwell-then-drag;
 * - pre-arm MOVEs are consumed so Termux's long-press timer never fires mid-scroll;
 * - UP flushes the coalesced tail immediately, and a selection that started
 *   under the 300ms anti-flicker guard is cleared again after 350ms.
 */
internal class TermuxScrollGestures(
    private val view: TerminalView,
    /** Routing says this pane scrolls remotely; otherwise Termux owns touches. */
    private val remote: () -> Boolean,
    private val rowHeightPx: () -> Float,
    private val requestScroll: (rows: Int, immediate: Boolean) -> Unit,
    /** New gesture: drop a pending probe and per-gesture remainders. */
    private val onGestureStart: () -> Unit,
    /** Drag/fling ended after actually scrolling: flush every tail. */
    private val onGestureEnd: () -> Unit,
) {

    /**
     * True once the finger moved in the current stream (remote mode). The host
     * consumes pre-arm MOVEs so Termux never sees them; the view client consumes
     * long-press when this is set: stillness = select, motion = scroll.
     */
    @Volatile
    var touchMoved = false
        private set

    private var scrollRemainder = 0f
    private var remoteArmed = false

    private val detector = GestureDetector(
        view.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                // Termux mScrollRemainder model: carry fractional pixels so
                // slow drags still move. Sign matches the old view exactly:
                // finger-down (distanceY<0) = older-ward = negative lines =
                // direction UP; finger-up = live-ward = positive = DOWN.
                val next = rowsForDrag(distanceY, scrollRemainder, rowHeightPx())
                scrollRemainder = next.remainderPx
                if (next.rows != 0) {
                    remoteArmed = true
                    requestScroll(next.rows, false)
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                // Fling continues the drag direction: finger-up fling
                // (velocityY < 0) heads live-ward = positive lines.
                // Bounded and measured in the same row unit as the drag: a
                // fixed ±12-row jump on top of the drag's own rows is what
                // made fast swipes overshoot unpredictably. ~0.1s of travel,
                // capped at 6 rows, keeps the flick responsive but repeatable.
                val rowPx = rowHeightPx()
                if (rowPx <= 0f) return true
                val rows = (-velocityY * 0.10f / rowPx).toInt().coerceIn(-6, 6)
                if (rows != 0) {
                    remoteArmed = true
                    requestScroll(rows, true)
                }
                return true
            }
        },
    )

    init {
        detector.setIsLongpressEnabled(false)
        view.setOnTouchListener { _, event -> onTouch(event) }
    }

    private fun onTouch(event: MotionEvent): Boolean {
        if (!remote()) {
            remoteArmed = false
            touchMoved = false
            return false // Termux handles everything (local history scroll).
        }
        if (event.action == MotionEvent.ACTION_DOWN) {
            onGestureStart()
            scrollRemainder = 0f
            remoteArmed = false
            touchMoved = false
        }
        if (event.action == MotionEvent.ACTION_MOVE) {
            touchMoved = true
        }
        detector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP ||
            event.action == MotionEvent.ACTION_CANCEL
        ) {
            // Gated on remoteArmed: a still long-press (no scroll) must keep
            // its selection on UP. The delayed retry cannot nuke a NEW
            // selection: starting one needs its own 500ms long-press, past
            // the 350ms retry.
            if (remoteArmed) {
                onGestureEnd()
                stopSelectionWithRetry()
            }
            remoteArmed = false
            return false // tap / long-press completes inside Termux.
        }
        if (remoteArmed) return true
        // Pre-arm movement: consume (a remote scroll must never reach
        // Termux's doScroll — it would emit arrow bytes / mouse reports into
        // the dead session AND shift mTopRow locally, double-scrolling
        // against the server repaint). onScroll arms within touch slop.
        if (event.action == MotionEvent.ACTION_MOVE) return true
        return false
    }

    /**
     * Dwell-then-drag: the scroll already tried to clear the just-started
     * selection, but Termux's hide() refuses within 300ms of show
     * (anti-flicker guard) — a short fast drag ends inside the guard and the
     * selection sticks. Retry at stream end, plus one delayed retry for the
     * pathological micro-drag.
     */
    private fun stopSelectionWithRetry() {
        try {
            view.stopTextSelectionMode()
        } catch (_: Exception) {
        }
        if (view.isSelectingText) {
            view.postDelayed({
                try {
                    view.stopTextSelectionMode()
                } catch (_: Exception) {
                }
            }, 350)
        }
    }
}
