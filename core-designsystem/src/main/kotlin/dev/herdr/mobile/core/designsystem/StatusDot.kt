package dev.herdr.mobile.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
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
) {
    val colors = HerdrTheme
    val color by animateColorAsState(
        targetValue = colors.forStatus(status),
        label = "status-dot",
    )
    Canvas(
        modifier = modifier
            .size(diameter)
            .then(
                if (describe) {
                    Modifier.semantics { contentDescription = status.contentDescription() }
                } else {
                    Modifier
                },
            ),
    ) {
        when (status) {
            AgentStatus.IDLE -> {
                // Hollow ring as a stroke: true transparency (whatever is
                // behind shows through). The old BlendMode.Clear punch
                // rendered BLACK without a saved layer (classic Compose
                // pitfall — Clear on the window canvas has nothing to
                // composite against). Geometry preserved: outer edge at R,
                // inner at 0.68R.
                val outer = size.minDimension / 2
                val width = outer * 0.32f
                drawCircle(
                    color = color,
                    radius = outer - width / 2,
                    style = Stroke(width = width),
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