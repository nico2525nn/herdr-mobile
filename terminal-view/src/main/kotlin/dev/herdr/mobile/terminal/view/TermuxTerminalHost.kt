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
    /** SGR wheel notches for mouse-aware apps (signed; positive = live-ward). */
    var onRemoteWheel: ((notches: Int) -> Unit)? = null

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

    /**
     * AgentMatrix-selected SGR wheel route. The host sends one wheel event per
     * requested row; this matches measured Codex/Pi behavior, while agents
     * with different wheel strides (for example Qwen's documented 3 lines per
     * tick) remain a known limitation of the two-route matrix.
     */
    /** App-specific transcript rows advanced by one agent wheel event. */
    @Volatile
    var wheelRowsPerEvent: Int = 1
        set(value) {
            if (field == value) return
            field = value
            wheelNotchRemainder = 0.0
        }

    /** Fractional-row carry across agent wheel gestures. */
    private var wheelNotchRemainder = 0.0

    /** Fractional-row carry across conventional 3-row wheel gestures. */
    private var wheelRemainder = 0.0

    /** Rows carried between page-key emissions (page granularity). */
    private var pageRemainder = 0

    /**
     * Scroll-request coalescing: OMP/codex repaints the whole screen per
     * request, so a drag firing per onScroll callback stutters through full
     * repaints that race live output frames (the reported judder). Rows
     * accumulate and flush at most once per 150ms; UP flushes the tail
     * immediately so short drags never feel eaten.
     *
     * Single entry point: drags coalesce ([immediate] = false), fling and
     * PgUp/PgDn page jumps send at once ([immediate] = true) — merging a
     * page jump with a preceding drag tail would overshoot.
     */
    private var pendingRows = 0
    private var lastFlushMs = 0L
    private var flushQueued = false
    private val flushRunnable = Runnable { flushPending() }

    private fun requestScroll(rows: Int, immediate: Boolean) {
        if (rows == 0) return
        if (immediate) {
            // A page jump must not merge with a drag tail: flush what's
            // pending first (order!), then the jump itself.
            flushPending()
            lastFlushMs = android.os.SystemClock.uptimeMillis()
            fireRemote(rows)
            return
        }
        pendingRows += rows
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastFlushMs >= 150) {
            flushPending()
        } else if (!flushQueued) {
            flushQueued = true
            view.postDelayed(flushRunnable, 150 - (now - lastFlushMs))
        }
    }

    private fun flushPending() {
        flushQueued = false
        view.removeCallbacks(flushRunnable)
        val rows = pendingRows
        pendingRows = 0
        if (rows == 0) return
        lastFlushMs = android.os.SystemClock.uptimeMillis()
        fireRemote(rows)
    }

    /**
     * Who scrolls this pane, decided by [scrollOwner] — the single policy
     * function (see ScrollRoute.kt for the reasoning and tests).
     */
    val remoteEffective: Boolean
        get() = scrollOwner(
            altScreen = altActive,
            mouseTracking = mouseTracking,
            hostBackedAgent = remoteScrollHost,
            localHistoryRows = snapshotHistorySize,
        ) == ScrollOwner.REMOTE

    /** Wheel notches vs page keys for the remote path. */
    val remoteKind: RemoteScrollKind
        get() = remoteScrollKind(diagnosticsAgent, mouseTracking)

    /** Pane agent identity, mirrored from the screen for routing diagnostics. */
    var diagnosticsAgent: String? = null

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
                    requestScroll(rows, immediate = false)
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
                    requestScroll(rows, immediate = true)
                }
                return true
            }
        },
    )

    init {
        // Long-press DISABLED on our detector: stock GestureDetector swallows
        // every MOVE after its own 500ms long-press fires (mInLongPress gate),
        // which would deaden onScroll for dwell-then-drag — the reported
        // frozen-selection + zero-scroll. Termux's own detector (separate
        // instance) still selects on a still finger; once the finger moves
        // big, our onScroll fires and the scroll clears the selection.
        remoteGestures.setIsLongpressEnabled(false)
        view.setOnTouchListener { _, event ->
            onTouch(event)
        }
    }

    /**
     * True once the finger moved in the current stream (remote mode). The host
     * consumes pre-arm MOVEs so Termux never sees them — without this its
     * long-press timer (which MOVE cancels) would fire mid-scroll and start a
     * selection on every slow scroll. The view client consumes long-press
     * when this is set: stillness = select, motion = scroll.
     */
    @Volatile
    var touchMoved = false
        private set

    private fun onTouch(event: MotionEvent): Boolean {
        if (!remoteEffective) {
            remoteArmed = false
            touchMoved = false
            return false // Termux handles everything (local history scroll).
        }
        if (event.action == MotionEvent.ACTION_DOWN) {
            pageRemainder = 0
            scrollRemainder = 0f
            remoteArmed = false
            touchMoved = false
        }
        if (event.action == MotionEvent.ACTION_MOVE) {
            touchMoved = true
        }
        remoteGestures.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP ||
            event.action == MotionEvent.ACTION_CANCEL
        ) {
            // Dwell-then-drag: the scroll already tried to clear the
            // just-started selection, but Termux's hide() refuses within
            // 300ms of show (anti-flicker guard) — a short fast drag ends
            // inside the guard and the selection sticks. Retry at stream
            // end (show is old by now), plus one delayed retry for the
            // pathological micro-drag. Gated on remoteArmed: a still
            // long-press (no scroll) must keep its selection on UP.
            // The delayed retry cannot nuke a NEW selection: starting one
            // needs its own 500ms long-press, past the 350ms retry.
            if (remoteArmed) {
                flushPageRemainder()
                // Flush the coalesced tail NOW: the drag's last rows must not
                // wait for the 150ms timer (that lag reads as an eaten flick).
                flushPending()
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

    private fun fireRemote(rows: Int) {
        // A remote scroll replaces the content (server repaint): any local
        // selection anchors (buffer coords) would freeze on stale rows, so
        // the scroll clears the selection first (standard scroll-clears
        // -selection UX; Termux does the same for local output scroll).
        try {
            view.stopTextSelectionMode()
        } catch (_: Exception) {
        }
        scrollEvents++
        scrolledRows += kotlin.math.abs(rows)
        remoteScrolls++
        if (remoteKind == RemoteScrollKind.WHEEL) {
            // Mouse-aware agent UI: convert requested rows to notches using
            // the app's documented stride; retain fractional rows per gesture.
            wheelNotchRemainder += rows.toDouble() / wheelRowsPerEvent
            val notches = wheelNotchRemainder.toInt()
            if (notches != 0) {
                wheelNotchRemainder -= notches
                onRemoteWheel?.invoke(notches)
            }
        } else if (mouseTracking) {
            // Conventional mouse apps (vim/less) scroll 3 lines per wheel
            // notch (xterm default): accumulate fractional rows across events.
            wheelRemainder += rows / 3.0
            val notches = wheelRemainder.toInt()
            if (notches != 0) {
                wheelRemainder -= notches
                onRemoteWheel?.invoke(notches)
            }
        } else {
            // Page-key route: the host can only move in whole screens, so a
            // raw row count made the same drag travel one screen or five
            // depending on where the gesture landed. Accumulate rows and emit
            // whole pages; flushPending() turns a sub-page gesture into one
            // page on release, so a small flick still scrolls predictably.
            val rowsPerPage = screenRows()
            if (rowsPerPage <= 0) {
                onRemoteScroll?.invoke(rows)
                return
            }
            pageRemainder += rows
            val pages = pageRemainder / rowsPerPage
            if (pages != 0) {
                pageRemainder -= pages * rowsPerPage
                onRemoteScroll?.invoke(pages)
            }
        }
    }

    /**
     * Rows actually visible in the view: one page-key scroll = one screen.
     * Measured from the view and its row pitch, not the mirror's grid — the
     * two disagree while a resize round-trip is in flight (the mirror reported
     * 22 rows for a 44-row view, doubling every page scroll).
     */
    private fun screenRows(): Int {
        val pitch = rowHeightPx()
        if (pitch <= 0f || view.height <= 0) return 0
        return (view.height / pitch).toInt()
    }

    /** Emit a trailing sub-page as one page (gesture end). */
    private fun flushPageRemainder() {
        if (pageRemainder == 0) return
        val page = if (pageRemainder > 0) 1 else -1
        pageRemainder = 0
        onRemoteScroll?.invoke(page)
    }

    private fun rowHeightPx(): Float {
        // Live-exact: the renderer's row pitch is view height over rows (it lays
        // out exactly mRows rows). The old fontSpacing guess ran ~14% small
        // (typeface metrics), overscrolling every remote drag. Falls back to
        // the guess only before attach (rows unknown — never scrolled then).
        // The renderer's own pitch is the unit Termux uses for local scrolling,
        // so remote pans move by the same amount as a local drag. Deriving it
        // from view.height/rows instead made the same swipe travel a different
        // distance depending on the pane (and on whether the keyboard was up).
        TermuxInternals.rowPitchPx(view)?.let { return it }
        val em = try {
            view.currentSession?.emulator
        } catch (_: Exception) {
            null
        }
        val rows = em?.mRows ?: 0
        if (rows > 0 && view.height > 0) return view.height.toFloat() / rows
        val px = textSizePx
        if (px <= 0) return 0f
        measurePaint.textSize = px.toFloat()
        return measurePaint.fontSpacing
    }

    /** Shift+PgUp/PgDn page scroll (Termux parity: remote panes page remotely). */
    fun pageRemote(up: Boolean) {
        val rows = try {
            view.currentSession?.emulator?.mRows ?: 0
        } catch (_: Exception) {
            0
        }
        if (rows > 0) requestScroll(if (up) -rows else rows, immediate = true)
    }

    /** Snap a scrolled viewport home (user input = back at the prompt). */
    fun snapToBottom() {
        view.topRow = 0
        view.invalidate()
    }

    /** Soft-keyboard owner (summon latch, window-focus guard, hide). */
    val keyboard = TerminalKeyboardController(view)

    /** Explicit summon — the only path that may raise the IME. */
    fun showKeyboard() = keyboard.show()

    /** Pane/workspace switch or screen exit: the IME never follows. */
    fun dismissKeyboard() = keyboard.dismiss()

    /** Hide when this pane never asked for the keyboard. */
    fun hideKeyboardIfUnrequested() = keyboard.hideIfUnrequested()

    /** The IME went away on its own: re-arm the next summon. */
    fun onKeyboardHidden() = keyboard.onImeHidden()
}
