package dev.herdr.mobile.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.core.model.AgentStatus

/**
 * The single visual idiom for Herdr state, shared by every screen.
 *
 * A dot plus a TalkBack description; colour never stands alone. Status changes animate
 * subtly instead of snapping, so a flapping agent does not strobe the UI.
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
        drawCircle(color = color)
    }
}