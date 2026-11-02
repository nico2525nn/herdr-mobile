package dev.herdr.mobile.terminal.view

import dev.herdr.mobile.core.model.InputPanelPage

/** What just happened, from the IME's point of view. */
enum class ImeTrigger {
    /** The keyboard button in the tab rail. */
    KEYBOARD_BUTTON,

    /** The floating input panel moved to another page. */
    PAGE_SWITCH,

    /** Another pane/workspace/tab became the target. */
    PANE_SWITCH,

    /** The window regained focus (app switch, return from background). */
    FOREGROUND,
}

/** Which surface should own the input connection. */
enum class ImeTarget { NONE, TERMINAL, CJK_FIELD }

/** What to do. All fields default to "nothing". */
data class ImeCommand(
    /** Close the IME regardless of who opened it (the pane is gone). */
    val hide: Boolean = false,
    /** Close the IME only when this pane never asked for it (window focus). */
    val hideUnrequested: Boolean = false,
    val focus: ImeTarget = ImeTarget.NONE,
    val show: Boolean = false,
)

/**
 * The whole soft-keyboard policy, as a pure function.
 *
 * Every rule the app needs is here, so the Compose effects only translate an
 * event into a command:
 *
 * - only [ImeTrigger.KEYBOARD_BUTTON] can raise the IME;
 * - switching the input panel moves the IME *target* and never dismisses it
 *   (Extra Keys + keyboard is the sticky-chord flow: arm CTRL, type C);
 * - a pane switch owns a fresh pane, so the previous pane's keyboard goes away;
 * - returning to the foreground never leaves a keyboard behind.
 */
fun imeCommand(
    trigger: ImeTrigger,
    page: InputPanelPage,
    imeVisible: Boolean,
): ImeCommand = when (trigger) {
    ImeTrigger.KEYBOARD_BUTTON ->
        if (page == InputPanelPage.CJK_INPUT) {
            ImeCommand(focus = ImeTarget.CJK_FIELD)
        } else {
            ImeCommand(focus = ImeTarget.TERMINAL, show = true)
        }

    ImeTrigger.PAGE_SWITCH -> when {
        // Nothing is on screen: a page swipe must not summon the keyboard.
        !imeVisible -> ImeCommand()
        page == InputPanelPage.CJK_INPUT -> ImeCommand(focus = ImeTarget.CJK_FIELD)
        else -> ImeCommand(focus = ImeTarget.TERMINAL)
    }

    ImeTrigger.PANE_SWITCH -> ImeCommand(
        hide = true,
        focus = if (page == InputPanelPage.CJK_INPUT) ImeTarget.NONE else ImeTarget.TERMINAL,
    )

    // A keyboard the user summoned survives an app switch; one Android
    // re-raised on its own (focus restored) does not.
    ImeTrigger.FOREGROUND -> ImeCommand(hideUnrequested = true)
}
