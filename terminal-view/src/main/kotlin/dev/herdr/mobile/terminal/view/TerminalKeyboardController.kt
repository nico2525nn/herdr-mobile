package dev.herdr.mobile.terminal.view

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.termux.view.TerminalView

/**
 * The single owner of soft-keyboard state for the terminal view.
 *
 * Before this class the IME was driven from four places in the screen (pane
 * switch, input-panel switch, keyboard button, lifecycle) plus a focus hook in
 * the host, each with its own idea of whether the keyboard should be up. That
 * is how "the keyboard came back by itself after I closed it" survived several
 * fixes: nothing owned the latch.
 *
 * Contract:
 * - [show] is the only path that may raise the IME (the keyboard button);
 * - focus alone never raises it — Android re-shows the soft keyboard for a
 *   focused view that owns an InputConnection whenever the window regains
 *   focus, so that event is intercepted and the IME hidden unless [show] asked
 *   for it;
 * - the latch drops as soon as the IME disappears on its own, so the next
 *   background round-trip is silent again.
 */
class TerminalKeyboardController(private val view: TerminalView) {

    /** True between an explicit [show] and the IME going away. */
    var requested: Boolean = false
        private set

    private val imm: InputMethodManager?
        get() = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager

    init {
        // Android re-shows the IME when the window regains focus, after
        // onResume. Hook the exact event and hide once the IME had its chance.
        view.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
            if (!focused || requested) return@addOnWindowFocusChangeListener
            view.postDelayed({ hideIfUnrequested() }, WINDOW_FOCUS_HIDE_DELAY_MS)
        }
    }

    /** Keyboard button: focus the terminal and raise the IME. */
    fun show() {
        requested = true
        view.requestFocus()
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Hide unconditionally and drop the latch (pane/workspace change, exit). */
    fun dismiss() {
        requested = false
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /** Hide only when this pane never asked for the keyboard. */
    fun hideIfUnrequested() {
        if (requested) return
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /** The IME went away on its own (back gesture, system hide). */
    fun onImeHidden() {
        requested = false
    }

    /** Give the terminal input focus without touching the IME. */
    fun focusTerminal() {
        view.requestFocus()
    }

    /** Convenience for callers that only have a [View] handle. */
    companion object {
        private const val WINDOW_FOCUS_HIDE_DELAY_MS = 150L
    }
}
