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
    companion object {
        /** Repaint + network round trip for a remote scroll. */
        private const val PROBE_SETTLE_MS = 260L

        /** Upper bound for the scroll estimate (a page is well below this). */
        private const val MAX_MEASURED_ROWS = 200

        /** Wheel bursts are coalesced: apps redraw once per report. */
        private const val WHEEL_COALESCE_MS = 80L
    }

    /** Alt-screen scroll: lines>0 finger-up (live-ward), lines<0 older. Host forwards. */
    var onRemoteScroll: ((lines: Int, unit: String) -> Unit)? = null

    /**
     * Page keys sent as INPUT to the pane, for full-screen apps.
     *
     * Verified on device: Herdr's `terminal.scroll` does not forward PgUp to a
     * pane without host scrollback (codex ignores it), while the same key sent
     * as input bytes scrolls it. The app therefore types the key itself.
     */
    var onPageKey: ((up: Boolean, pages: Int) -> Unit)? = null

    /** SGR wheel notches (signed; positive = live-ward), sent as input bytes. */
    var onRemoteWheel: ((notches: Int) -> Unit)? = null
    /**
     * Reset the local viewport. Called when routing changes so a pane that was
     * being scrolled locally does not open mid-scroll once the app owns it.
     */
    fun resetLocalViewport() {
        if (view.topRow == 0) return
        view.topRow = 0
        view.invalidate()
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
    /** Rows carried between whole-page emissions (no-host-scrollback panes). */
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
            // A discrete request (page jump, fling tail, replay after the local
            // path was proved dead) must land even when it is smaller than a
            // page: otherwise the one gesture that unlocked the pane is eaten.
            flushPageRemainder()
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
     * Where this gesture goes, from the transport's own evidence
     * ([scrollSource]): host offset when herdr reports scrollback, app input
     * for full-screen panes, the local transcript for shells.
     *
     * [inputDead] is the runtime escape hatch: if every input mechanism proved
     * inert (an exited agent left its label on a shell), the pane is handed
     * back to Termux.
     */
    val remoteEffective: Boolean
        get() {
            if (inputDead) return false
            if (scrollMode == dev.herdr.mobile.core.model.ScrollMode.LOCAL) return false
            return when (source()) {
                ScrollSource.HOST, ScrollSource.APP_INPUT -> true
                ScrollSource.LOCAL, ScrollSource.NONE -> false
            }
        }

    private fun source(): ScrollSource = scrollSource(
        hostScrollKnown = hostScrollKnown,
        hostScrollable = hostScrollable,
        agentPane = diagnosticsAgent != null,
        altScreen = altActive,
        mouseTracking = mouseTracking,
        localHistoryRows = snapshotHistorySize,
    )

    /** Pane agent identity, mirrored from the screen for routing diagnostics. */
    var diagnosticsAgent: String? = null

    /**
     * True when the HOST can scroll this pane line-exactly (it reports
     * scrollback). Rows then travel as rows; otherwise the view quantizes the
     * gesture into whole screens, because the only fallback is PgUp/PgDn and
     * one key press is one screen.
     */
    var hostScrollable: Boolean = false

    /** True when the daemon actually reported scroll state for this pane. */
    var hostScrollKnown: Boolean = false

    /** User's scroll lever choice; AUTO follows the audited agent table. */
    var scrollMode: dev.herdr.mobile.core.model.ScrollMode =
        dev.herdr.mobile.core.model.ScrollMode.AUTO

    /**
     * The HOST's viewport height (rows), when it reports one. A page-key scroll
     * moves the host's screenful, which is not necessarily what this view
     * draws (herdr attached at 30 rows while the phone showed 44), so the page
     * unit has to come from the host or gestures drift.
     */
    var hostViewportRows: Int = 0

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
                val next = rowsForDrag(distanceY, scrollRemainder, rowHeightPx())
                scrollRemainder = next.remainderPx
                if (next.rows != 0) {
                    remoteArmed = true
                    requestScroll(next.rows, immediate = false)
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
            // A new gesture owns the probe: drop the pending check so a stale
            // comparison cannot "fail" a mechanism that was still settling.
            view.removeCallbacks(probeCheck)
            probe = null
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
                flushWheelRemainder()
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
        val mechanism = activeMechanism() ?: return
        // Probe the first request of a pane: capture the screen before it moves
        // so the settle check can tell "the app honoured this" from "inert".
        if (probe == null) {
            probeNotches = 0
            probe = ScrollProbe(mechanism, screenSignature(), rows, visibleLines())
        }
        dispatch(mechanism, rows)
        scheduleProbeCheck()
    }

    /** What to send, and in which unit. */
    private fun dispatch(mechanism: ScrollMechanism, rows: Int) {
        when (mechanism) {
            ScrollMechanism.HOST ->
                onRemoteScroll?.invoke(rows, "rows")

            ScrollMechanism.WHEEL -> {
                // One notch is a few lines and the app decides how many; the
                // stride starts from the per-app table and is corrected from
                // the measured shift once a gesture lands.
                wheelNotchRemainder += rows / ScrollLearning.rowsPerNotch(diagnosticsAgent)
                val notches = wheelNotchRemainder.toInt()
                if (notches != 0) {
                    wheelNotchRemainder -= notches
                    probeNotches += notches
                    // Apps redraw per report: send bursts, not one frame per
                    // notch (this is what made opencode crawl).
                    pendingWheelNotches += notches
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastWheelFlushMs >= WHEEL_COALESCE_MS) {
                        flushWheelNotches()
                    } else if (!wheelFlushQueued) {
                        wheelFlushQueued = true
                        view.postDelayed(wheelFlushRunnable, WHEEL_COALESCE_MS - (now - lastWheelFlushMs))
                    }
                }
            }

            ScrollMechanism.PAGE_KEY -> {
                // Whole screens only; accumulate the gesture so a long drag is
                // more than one page and a short one flushes exactly one on UP.
                val rowsPerPage = hostViewportRows.takeIf { it > 0 } ?: visibleRows()
                if (rowsPerPage <= 0) {
                    onRemoteScroll?.invoke(rows, "rows")
                    return
                }
                val pageScroll = pagesForRows(rows, rowsPerPage, pageRemainder)
                pageRemainder = pageScroll.remainderRows
                if (pageScroll.pages != 0) {
                    onPageKey?.invoke(pageScroll.pages < 0, kotlin.math.abs(pageScroll.pages))
                }
            }
        }
    }

    /** The lever for this pane (host offset, or the agent's wheel/page route). */
    private fun activeMechanism(): ScrollMechanism? = candidates().firstOrNull()

    /** Candidate list: one entry by design, so nothing can silently downgrade. */
    private fun candidates(): List<ScrollMechanism> = scrollMechanismsForMode(
        mode = scrollMode,
        hostScrollable = hostScrollable,
        agentPane = diagnosticsAgent != null,
        wheelAgent = dev.herdr.mobile.core.model.AgentMatrix.useWheelScroll(diagnosticsAgent),
    )

    /**
     * A scroll request whose effect is being verified.
     *
     * The app cannot know statically whether a TUI honours PgUp, wheel bytes or
     * a host offset, and the mirror carries no DECSET state to ask. So the
     * first request per pane is a probe: hash the visible screen, send, then
     * check whether anything moved. Inert mechanisms are swapped out and the
     * same rows are re-sent, so the user's gesture is never lost.
     */
    private data class ScrollProbe(
        val mechanism: ScrollMechanism,
        val signature: Int,
        val rows: Int,
        /** Visible lines when the probe started (the scroll anchor set). */
        val lines: List<String>,
    )

    private var probe: ScrollProbe? = null

    /** Notches sent during the current probe (for stride calibration). */
    /** Hash of the visible screen: cheap, and stable while the pane is idle. */
    private fun screenSignature(): Int {
        val text = screenText() ?: return 0
        var hash = 0x811c9dc5.toInt()
        for (c in text) {
            hash = (hash xor c.code) * 0x01000193
        }
        return hash
    }

    private fun screenText(): String? = try {
        view.currentSession?.emulator?.screen?.transcriptText
    } catch (_: Exception) {
        null
    }

    private fun visibleLines(): List<String> =
        screenText()?.split('\n')?.map { it.trimEnd() }.orEmpty()

    /**
     * Rows the transcript moved, estimated by finding the vertical shift that
     * best matches the old screen against the new one.
     *
     * A single anchor line is fragile: live panes repaint rows (spinners,
     * counters) and a diagnostics overlay may own the first line. Voting over
     * every non-blank line rejects those outliers. Returns 0 unless a shift
     * explains a meaningful fraction of the screen, so a live repaint is not
     * mistaken for a scroll.
     */
    private fun movedRows(before: List<String>): Int {
        val after = visibleLines()
        if (before.isEmpty() || after.isEmpty()) return 0
        val anchors = before.count { it.isNotBlank() }
        if (anchors == 0) return 0
        var bestShift = 0
        var bestScore = 0
        val maxShift = minOf(after.size - 1, MAX_MEASURED_ROWS)
        for (shift in 1..maxShift) {
            var score = 0
            for (i in 0 until minOf(before.size, after.size - shift)) {
                if (before[i].isNotBlank() && before[i] == after[i + shift]) score++
            }
            if (score > bestScore) {
                bestScore = score
                bestShift = shift
            }
        }
        return if (bestScore >= maxOf(3, anchors / 3)) bestShift else 0
    }

    /**
     * Called on a timer after a probed request: did the pane actually move?
     *
     * Success records the mechanism and, for wheel, the measured stride. Failure
     * advances to the next mechanism and replays the same rows; when nothing is
     * left the pane is handed back to Termux for good (a stale agent label).
     */
    fun settleProbe() {
        val pending = probe ?: return
        probe = null
        val moved = movedRows(pending.lines)
        val changed = screenSignature() != pending.signature
        // A live pane repaints constantly (spinners, clocks), so "the screen
        // changed" is not proof that the gesture scrolled. The anchor line
        // moving down the screen is: that is the transcript scrolling.
        val scrolled = if (pending.lines.any { it.isNotBlank() }) moved > 0 else changed
        if (scrolled) {
            ScrollLearning.remember(diagnosticsAgent, pending.mechanism)
            if (pending.mechanism == ScrollMechanism.WHEEL) {
                ScrollLearning.observeRowsPerNotch(diagnosticsAgent, probeNotches, moved)
            }
            probeNotches = 0
            return
        }
        // The lever is fixed by the agent table; a live repaint is not proof of
        // failure, so nothing is downgraded here. Only a pane the host knows
        // nothing about may fall back to its local transcript.
        probeNotches = 0
        if (!hostScrollKnown) {
            inputDead = true
            applyLocalScroll(pending.rows)
        }
    }

    /** True once every input mechanism failed: hand the pane back to Termux. */
    private var inputDead = false

    /** Fractional-row carry across wheel gestures. */
    private var wheelNotchRemainder = 0.0

    /** Notches waiting for the next coalesced flush. */
    private var pendingWheelNotches = 0
    private var lastWheelFlushMs = 0L

    /** Notches sent during the current probe (for stride calibration). */
    private var probeNotches = 0

    /** Re-check the current probe once the repaint had time to land. */
    fun scheduleProbeCheck() {
        view.removeCallbacks(probeCheck)
        view.postDelayed(probeCheck, PROBE_SETTLE_MS)
    }

    private val probeCheck = Runnable { settleProbe() }

    /**
     * Rows the view actually shows. Measured from the view and its row pitch,
     * not the mirror's grid: the two disagree while a resize round-trip is in
     * flight (the mirror reported 22 rows for a 44-row view).
     */
    private fun visibleRows(): Int {
        val pitch = rowHeightPx()
        if (pitch > 0f && view.height > 0) return (view.height / pitch).toInt()
        return try {
            view.currentSession?.emulator?.mRows ?: 0
        } catch (_: Exception) {
            0
        }
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

    /** Shift the local transcript by [rows], clamped to the banked history. */
    private fun applyLocalScroll(rows: Int) {
        val history = snapshotHistorySize
        if (history <= 0 || rows == 0) return
        view.topRow = (view.topRow + rows).coerceIn(-history, 0)
        view.invalidate()
    }

    /**
     * A short drag produces less than one notch; without this the gesture
     * would do nothing at all. One notch in the gesture's direction is the
     * smallest unit a full-screen app understands.
     */
    private fun flushWheelRemainder() {
        if (hostScrollable || activeMechanism() != ScrollMechanism.WHEEL) return
        if (wheelNotchRemainder != 0.0) {
            // A sub-notch drag still has to do something.
            pendingWheelNotches += if (wheelNotchRemainder > 0) 1 else -1
            wheelNotchRemainder = 0.0
        }
        flushWheelNotches()
    }

    /** Send the accumulated notches as one burst. */
    private fun flushWheelNotches() {
        view.removeCallbacks(wheelFlushRunnable)
        wheelFlushQueued = false
        val notches = pendingWheelNotches
        pendingWheelNotches = 0
        if (notches == 0) return
        lastWheelFlushMs = android.os.SystemClock.uptimeMillis()
        onRemoteWheel?.invoke(notches)
    }

    private val wheelFlushRunnable = Runnable { flushWheelNotches() }
    private var wheelFlushQueued = false

    /** Emit a sub-page gesture as one page when the finger lifts. */
    private fun flushPageRemainder() {
        if (hostScrollable) return
        val page = flushPage(pageRemainder)
        if (page == 0) return
        pageRemainder = 0
        onRemoteScroll?.invoke(page, "pages")
    }

    /** Shift+PgUp/PgDn page scroll (Termux parity: remote panes page remotely). */
    fun pageRemote(up: Boolean) {
        val rows = visibleRows()
        if (rows > 0) requestScroll(if (up) -rows else rows, immediate = true)
    }
}
