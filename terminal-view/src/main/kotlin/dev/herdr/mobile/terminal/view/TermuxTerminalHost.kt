package dev.herdr.mobile.terminal.view

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.termux.view.TerminalView

/**
 * Remote-scroll interception for a [TerminalView] (which is final: no subclass).
 *
 * Termux's own touch pipeline has no hook for alt-screen scroll: `doScroll()`
 * sends arrow bytes into the (dead) local session when the alt buffer is active,
 * and mouse reports when mouse-tracking — both vanish with mShellPid == 0. So
 * this host installs an [View.OnTouchListener] that owns vertical scroll/fling
 * whenever [remoteScrollEffective] is true (agent pane OR live alt-screen flag),
 * forwarding the gesture as [onRemoteScroll] lines (same sign convention as the
 * old view: finger-up = positive = live-ward). Otherwise touches pass through
 * and Termux scrolls local history itself (mTopRow, fling, remainder).
 *
 * Taps and long-press (click + selection) always reach Termux: the stream is
 * consumed only after the detector's onScroll/onFling armed it.
 *
 * Entering remote mode snaps the local offset home: mixed offsets would
 * double-scroll (local shift + server repaint).
 */
class TermuxTerminalHost(
    val view: TerminalView,
) {
    /** Alt-screen scroll: lines>0 finger-up (live-ward), lines<0 older. Host forwards. */
    var onRemoteScroll: ((lines: Int) -> Unit)? = null

    /**
     * Host part of remote-scroll routing: true when the pane runs an agent
     * (main-screen TUIs like codex whose transcript lives in HOST scrollback,
     * not local history). Set by the screen from snapshot state.
     */
    var remoteScrollHost: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                view.topRow = 0
                view.invalidate()
            }
        }

    /** Live alt-screen flag, refreshed by the session pump after every chunk. */
    @Volatile
    var altActive: Boolean = false

    /** Mouse-tracking flag of the latest pump (diagnostics). */
    @Volatile
    var mouseTracking: Boolean = false

    /** Effective routing: host (agent) OR live alt-screen flag. */
    private val remoteEffective: Boolean
        get() = remoteScrollHost || altActive

    /** Diagnostic: remote scroll requests sent (alt/agent mode). */
    var remoteScrolls: Long = 0
        private set

    /** Diagnostic: gesture callbacks and applied rows (remote path only). */
    var scrollEvents: Long = 0
        private set
    var scrolledRows: Long = 0
        private set

    /** Alt-screen flag of the latest pump (diagnostics). */
    val snapshotUsingAlt: Boolean get() = altActive

    /** Transcript rows in the attached emulator (0 when unattached). */
    val snapshotHistorySize: Int
        get() = try {
            view.currentSession?.emulator?.screen?.activeTranscriptRows ?: 0
        } catch (_: Exception) {
            0
        }

    /** Current viewport offset for diagnostics (0 = live). */
    val currentTopRow: Int get() = view.topRow

    /** Last text size we set (px): row height derives from it. */
    var textSizePx: Int = 0

    private val measurePaint = android.graphics.Paint()
    private var scrollRemainder = 0f
    private var remoteArmed = false

    private val remoteGestures = GestureDetector(
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
                scrollRemainder += distanceY
                val rowPx = rowHeightPx()
                if (rowPx <= 0f) return true
                val rows = (scrollRemainder / rowPx).toInt()
                if (rows != 0) {
                    scrollRemainder -= rows * rowPx
                    remoteArmed = true
                    fireRemote(rows)
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
                val rows = (-velocityY / 2000f).toInt().coerceIn(-30, 30)
                if (rows != 0) {
                    remoteArmed = true
                    fireRemote(rows)
                }
                return true
            }
        },
    )

    init {
        view.setOnTouchListener { _, event ->
            onTouch(event)
        }
    }

    private fun onTouch(event: MotionEvent): Boolean {
        if (!remoteEffective) {
            remoteArmed = false
            return false // Termux handles everything (local history scroll).
        }
        if (event.action == MotionEvent.ACTION_DOWN) {
            scrollRemainder = 0f
            remoteArmed = false
        }
        remoteGestures.onTouchEvent(event)
        if (remoteArmed) return true
        if (event.action == MotionEvent.ACTION_UP ||
            event.action == MotionEvent.ACTION_CANCEL
        ) {
            remoteArmed = false
            return false // tap / long-press completes inside Termux.
        }
        // Pre-arm movement: consume (a remote scroll must never reach
        // Termux's doScroll — it would emit arrow bytes / mouse reports into
        // the dead session AND shift mTopRow locally, double-scrolling
        // against the server repaint). onScroll arms within touch slop.
        if (event.action == MotionEvent.ACTION_MOVE) return true
        return false
    }

    private fun fireRemote(rows: Int) {
        scrollEvents++
        scrolledRows += kotlin.math.abs(rows)
        remoteScrolls++
        onRemoteScroll?.invoke(rows)
    }

    private fun rowHeightPx(): Float {
        // Termux's renderer uses Paint.getFontSpacing() with no multiplier;
        // reproduce it from the size we set (mFontLineSpacing is
        // package-private and unreachable from here).
        val px = textSizePx
        if (px <= 0) return 0f
        measurePaint.textSize = px.toFloat()
        return measurePaint.fontSpacing
    }

    /** Snap a scrolled viewport home (user input = back at the prompt). */
    fun snapToBottom() {
        view.topRow = 0
        view.invalidate()
    }

    /** Manual keyboard summon (CJK panel button). */
    fun showKeyboard() {
        view.requestFocus()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
