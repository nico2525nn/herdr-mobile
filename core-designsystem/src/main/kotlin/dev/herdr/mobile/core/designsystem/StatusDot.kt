package dev.herdr.mobile.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.core.model.AgentStatus

/**
 * The single visual idiom for Herdr state, shared by every screen.
 *
 * Glyph and colour follow the Herdr TUI verbatim (`status_icon` with Dots style in
 * `src/client/shell.rs`): working/blocked/done are solid `●`, idle is hollow `○`, unknown
 * is `·`. Colour never stands alone — every dot also carries a TalkBack description.
 */
@Composable
fun StatusDot(
    status: AgentStatus,
    modifier: Modifier = Modifier,
    diameter: Dp = 14.dp,
    describe: Boolean = true,
    /** Seen done (SeenDots): same hue at reduced alpha, "(seen)" for TalkBack. */
    muted: Boolean = false,
) {
    val colors = HerdrTheme
    val color by animateColorAsState(
        targetValue = colors.forStatus(status).let { if (muted) it.copy(alpha = 0.4f) else it },
        label = "status-dot",
    )
    Canvas(
        modifier = modifier
            .size(diameter)
            .then(
                if (describe) {
                    Modifier.semantics {
                        contentDescription = status.contentDescription() +
                            if (muted) ", seen" else ""
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        when (status) {
            AgentStatus.IDLE -> {
                // Hollow ring: outer circle plus background-coloured centre.
                drawCircle(color = color)
                drawCircle(
                    color = Color.Transparent,
                    radius = size.minDimension / 2 * 0.55f,
                    blendMode = BlendMode.Clear,
                )
            }

            AgentStatus.UNKNOWN -> {
                // Middle dot, matching the TUI's `·`.
                drawCircle(
                    color = color,
                    radius = size.minDimension / 2 * 0.35f,
                )
            }

            else -> {
                drawCircle(color = color)
            }
        }
    }
}