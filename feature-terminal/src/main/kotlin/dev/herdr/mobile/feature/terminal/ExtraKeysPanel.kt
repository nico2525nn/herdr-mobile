package dev.herdr.mobile.feature.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.terminal.view.TerminalKeyEncoder
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
    // Flick tracking: press Y in button coords. Release carries its own
    // pressPosition? No — Release only references the original Press, so the
    // drag distance comes from a pointer-input modifier below, not here.
    // Simpler: track the max Y seen via DragInteraction? Also absent.
    // Pragmatic: pointerInput + detectVerticalDragGestures owns dual-action
    // keys entirely (below); this interaction flow only serves tap/repeat keys.
    val dualAction = onFlickDown != null

    androidx.compose.runtime.LaunchedEffect(interactions) {
        var repeatJob: Job? = null
        interactions.interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.PressInteraction.Press -> {
                    if (!dualAction) {
                        onPress()
                        if (repeatable) {
                            repeatJob?.cancel()
                            repeatJob = scope.launch {
                                delay(400)
                                while (true) {
                                    onPress()
                                    delay(60)
                                }
                            }
                        }
                    }
                }
                is androidx.compose.foundation.interaction.PressInteraction.Release,
                is androidx.compose.foundation.interaction.PressInteraction.Cancel -> {
                    repeatJob?.cancel()
                    repeatJob = null
                }
            }
        }
    }

    // Dual-action keys: a vertical-drag detector owns the gesture (the press
    // interaction above stays silent for them). Release with net downward
    // travel past slop = flick action; anything else = tap action.
    FilledTonalButton(
        onClick = {},
        modifier = modifier
            .heightIn(min = 44.dp)
            .then(
                if (dualAction) {
                    Modifier.pointerInput(key) {
                        awaitEachGesture {
                            var traveled = 0f
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var slop: PointerInputChange? =
                                awaitTouchSlopOrCancellation(down.id) { c, over ->
                                    c.consume()
                                    traveled += over.y
                                }
                            // Slop passed: drain the rest so a long swipe
                            // counts fully, not just the slop overshoot.
                            while (slop != null && slop.pressed) {
                                val event = awaitPointerEvent()
                                val c = event.changes.firstOrNull { it.id == down.id }
                                if (c == null || !c.pressed) break
                                traveled += c.position.y - c.previousPosition.y
                                c.consume()
                                slop = c
                            }
                            if (traveled > 24.dp.toPx()) {
                                onFlickDown?.invoke()
                            } else {
                                onPress()
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
        shape = ButtonDefaults.filledTonalShape,
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
        colors = if (isArmed) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
        interactionSource = interactions,
    ) {
        if (onFlickDown != null && key.flickDownLabel != null) {
            // Dual label in one line: tap action + flick hint (two lines
            // would stretch this button taller than its row siblings).
            Text(
                text = "${key.label} ↓${key.flickDownLabel}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
