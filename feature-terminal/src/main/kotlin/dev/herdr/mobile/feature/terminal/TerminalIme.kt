package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.termux.view.TerminalView
import dev.herdr.mobile.core.model.InputPanelPage
import dev.herdr.mobile.terminal.view.ImeCommand
import dev.herdr.mobile.terminal.view.ImeTarget
import dev.herdr.mobile.terminal.view.ImeTrigger
import dev.herdr.mobile.terminal.view.TermuxTerminalHost
import dev.herdr.mobile.terminal.view.imeCommand
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Every path that may move focus or raise the soft keyboard.
 *
 * Policy lives in terminal-view's `ImePolicy` (pure, unit-tested); these
 * effects only turn an event into a command and hand it to one dispatcher.
 * No other code in the screen touches focus or the IME.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalImeEffects(
    host: () -> TermuxTerminalHost?,
    view: () -> TerminalView?,
    cjkFocus: FocusRequester?,
    inputPage: InputPanelPage,
    paneId: String?,
    keyboardShowRequest: Int,
    keyboardShowHandled: Int,
    onKeyboardShowHandled: (Int) -> Unit,
) {
    val imeVisible = WindowInsets.isImeVisible

    fun apply(cmd: ImeCommand) {
        if (cmd.hide) host()?.dismissKeyboard()
        if (cmd.hideUnrequested) host()?.hideKeyboardIfUnrequested()
        when (cmd.focus) {
            ImeTarget.CJK_FIELD -> cjkFocus?.requestFocus()
            ImeTarget.TERMINAL -> {
                host()?.keyboard?.focusTerminal() ?: view()?.requestFocus()
            }
            ImeTarget.NONE -> Unit
        }
        if (cmd.show) host()?.showKeyboard()
    }

    var lastInputPage by remember { mutableStateOf(inputPage) }
    LaunchedEffect(inputPage) {
        if (lastInputPage == inputPage) return@LaunchedEffect
        lastInputPage = inputPage
        apply(imeCommand(ImeTrigger.PAGE_SWITCH, inputPage, imeVisible))
    }
    LaunchedEffect(paneId) {
        apply(imeCommand(ImeTrigger.PANE_SWITCH, inputPage, imeVisible))
    }
    // The IME going away on its own re-arms the summon latch.
    LaunchedEffect(imeVisible) {
        if (!imeVisible) host()?.keyboard?.onImeHidden()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val pageState = rememberUpdatedState(inputPage)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                apply(imeCommand(ImeTrigger.FOREGROUND, pageState.value, imeVisible))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Keyboard button: the one deliberate summon. Guard 0 so composition does
    // not steal focus on first mount.
    LaunchedEffect(keyboardShowRequest) {
        if (keyboardShowRequest > keyboardShowHandled) {
            onKeyboardShowHandled(keyboardShowRequest)
            apply(imeCommand(ImeTrigger.KEYBOARD_BUTTON, inputPage, imeVisible))
        }
    }
}
