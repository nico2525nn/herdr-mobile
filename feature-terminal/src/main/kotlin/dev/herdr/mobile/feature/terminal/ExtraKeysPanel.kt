package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.core.designsystem.HerdrTheme
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Page A of the bottom input panel: the Termux default two-row extra-keys layout.
 *
 * CTRL/ALT are sticky multi-select toggles owned by the ViewModel: armed modifiers
 * highlight here AND apply to the next soft-keyboard char (keyboard Ctrl+C works,
 * the latch clears on use). Everything sends raw bytes — the daemon forwards them
 * verbatim.
 *
 * Arrow keys (and other repeatable keys) fire repeatedly while held: one press on
 * tap, then 400ms pause, then every 60ms until release. Modifier toggles never
 * repeat (holding CTRL must not strobe the latch).
 */
@Composable
fun ExtraKeysPanel(
    onKey: (TerminalKeyEncoder.Key) -> Unit,
    armed: Set<TerminalKeyEncoder.Modifier>,
    haptic: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    fun press(key: TerminalKeyEncoder.Key) {
        if (haptic) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onKey(key)
    }

    fun flick(key: TerminalKeyEncoder.Key) {
        // Down-flick on a dual-action key: synthesize the alternate key so the
        // ViewModel path stays identical (sticky latch applies to PGDN too).
        val bytes = key.flickDownBytes ?: return
        if (haptic) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onKey(TerminalKeyEncoder.Key(label = key.flickDownLabel ?: key.label, bytes = bytes))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TerminalKeyEncoder.DEFAULT_LAYOUT.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    val isArmed = key.modifier != null && key.modifier in armed
                    RepeatableKeyButton(
                        key = key,
                        isArmed = isArmed,
                        onPress = { press(key) },
                        onFlickDown = if (key.flickDownBytes != null) {
                            { flick(key) }
                        } else {
                            null
                        },
                        modifier = Modifier.weight(1f),
                        scope = scope,
                    )
                }
            }
        }
    }
}

/**
 * A single extra key with hold-to-repeat. Fires once on press-down, then (if
 * [repeatable]) every 60ms after a 400ms hold. All firing happens on the press
 * interaction — onClick stays empty so a hold never double-fires on release.
 *
 * Dual-action keys ([onFlickDown] != null): press-down does NOT fire (a tap
 * firing PGUP before a PGDN flick would page twice). Release fires the flick
 * action when the finger traveled down past slop, else the tap action.
 * No hold-repeat: a dual-action hold is ambiguous (repeat which?).
 */
@Composable
private fun RepeatableKeyButton(
    key: TerminalKeyEncoder.Key,
    isArmed: Boolean,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    scope: kotlinx.coroutines.CoroutineScope,
    onFlickDown: (() -> Unit)? = null,
) {
    // Modifier toggles and single-shot keys (ESC/TAB) don't repeat: holding
    // them strobes state the user can't see. Arrows, PGUP, HOME/END and
    // letters do. Dual-action keys never repeat (ambiguous which to repeat).
    val repeatable = onFlickDown == null && key.modifier == null && key.bytes != null &&
        key.label !in setOf("ESC", "TAB")
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val dualAction = onFlickDown != null
    // One pointer path for normal keys: down fires once, a hold repeats, and
    // release cancels. The Button's onClick is reserved for keyboard/D-pad and
    // the explicit semantics action for accessibility, so a pointer tap cannot
    // be delivered twice by an asynchronous PressInteraction.
    val nonDualPointer = Modifier.pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            var sent = false
            var up = false
            val holdJob = scope.launch {
                // Wait for a real hold before sending: a horizontal page
                // swipe that starts on a key must not type that key. A short
                // tap sends on release; a hold sends at 400 ms and repeats.
                delay(400)
                if (!sent) {
                    sent = true
                    onPress()
                }
                if (repeatable) {
                    while (true) {
                        delay(60)
                        onPress()
                    }
                }
            }
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        up = true
                        break
                    }
                    if (change.isConsumed) return@awaitEachGesture
                    val dx = change.position.x - change.previousPosition.x
                    val dy = change.position.y - change.previousPosition.y
                    if (abs(dx) > slop || abs(dy) > slop) {
                        return@awaitEachGesture
                    }
                }
            } finally {
                holdJob.cancel()
            }
            // Send only on a real UP. Pointer cancellation (scroll takeover,
            // screen disposal) must not turn into a key press.
            if (up && !sent) onPress()
        }
    }

    // Dual-action keys: a vertical-drag detector owns the gesture (the press
    // interaction above stays silent for them). Release with net downward
    // travel past slop = flick action; anything else = tap action.
    FilledTonalButton(
        // Pointer taps are handled by the pointer path. This click is the
        // hardware keyboard/D-pad path; the explicit semantics onClick handles
        // TalkBack and switch access.
        onClick = {
            if (dualAction) onPress()
        },
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .heightIn(min = 40.dp)
            .then(
                if (dualAction) {
                    Modifier.pointerInput(key) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var dx = 0f
                            var dy = 0f
                            var claimed = false
                            val slop = viewConfiguration.touchSlop
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                // The panel pager claims horizontal drags in the
                                // Initial pass; never turn a consumed swipe into
                                // a flick on top of the page switch.
                                if (change.pressed && change.isConsumed) {
                                    return@awaitEachGesture
                                }
                                dx += change.position.x - change.previousPosition.x
                                dy += change.position.y - change.previousPosition.y
                                if (!change.pressed) break
                                if (!claimed && keyDragAxis(dx, dy, slop) == DragAxis.VERTICAL) {
                                    // A horizontal lead is left to the pager:
                                    // its threshold is higher, so a 1.6x lead
                                    // followed by a deep downward flick still
                                    // becomes vertical here. Once the pager
                                    // claims, consumption cancels this path.
                                    claimed = true
                                }
                                if (claimed) change.consume()
                            }
                            if (claimed) {
                                // Past slop and the fixed 24dp travel threshold:
                                // classify a short downward drag as a tap so a
                                // tap near the edge never pages down.
                                if (dy > 24.dp.toPx()) onFlickDown?.invoke() else onPress()
                            }
                        }
                    }
                } else {
                    nonDualPointer
                },
            )
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.Enter ||
                        event.key == Key.NumPadEnter ||
                        event.key == Key.Spacebar ||
                        event.key == Key.DirectionCenter)
                ) {
                    onPress()
                    true
                } else {
                    false
                }
            }
            // Sticky modifiers (SHIFT/CTRL/ALT) are latches, not one-shot
            // keys: expose the armed state as checkable so TalkBack hears
            // "checked"/"not checked" instead of only seeing a colour change.
            // Button already supplies Role.Button; adding it again here would
            // collide with that semantics node.
            .semantics {
                if (key.modifier != null) {
                    toggleableState = if (isArmed) ToggleableState.On else ToggleableState.Off
                }
                if (!dualAction) {
                    onClick(label = "Press ${key.label}") {
                        onPress()
                        true
                    }
                } else {
                    // A downward flick is pointer-only by construction; expose
                    // the alternate action so TalkBack and switch access can
                    // reach PGDN without the gesture.
                    customActions = listOf(
                        CustomAccessibilityAction(label = "Page down") {
                            onFlickDown?.invoke()
                            true
                        },
                    )
                }
            },
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
        // The hairline is only for the pure-black AMOLED scheme, where a
        // tonal fill disappears. Standard themes use the plain filled button.
        border = if (isArmed || !HerdrTheme.amoled) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        colors = if (isArmed) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        },
        interactionSource = interactions,
    ) {
        if (onFlickDown != null && key.flickDownLabel != null) {
            // Dual label in one line: tap action + flick hint (two lines
            // would stretch this button taller than its row siblings).
            // "PGUP ↓PGDN" is too wide for a seventh of the panel and got
            // ellipsized. The arrow is the whole hint (flick down for the
            // alternate); screen readers still get the full description.
            Text(
                text = "${key.label}↓",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics {
                    contentDescription = "${key.label}, flick down for ${key.flickDownLabel}"
                },
            )
        } else {
            Text(
                text = key.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
