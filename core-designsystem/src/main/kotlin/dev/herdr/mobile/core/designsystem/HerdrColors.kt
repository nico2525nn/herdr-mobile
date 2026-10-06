package dev.herdr.mobile.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import dev.herdr.mobile.core.model.AgentStatus

/**
 * Semantic Herdr state colours, the only custom token family outside Material.
 *
 * Everything else defers to `MaterialTheme.colorScheme`. These five dots carry agent meaning,
 * so they must stay recognisable in both light and dark schemes without clashing with the
 * dynamic-colour palette.
 */
@Immutable
data class HerdrColors(
    val blocked: Color,
    val working: Color,
    val done: Color,
    val idle: Color,
    val unknown: Color,
) {
    fun forStatus(status: AgentStatus): Color = when (status) {
        AgentStatus.BLOCKED -> blocked
        AgentStatus.WORKING -> working
        AgentStatus.DONE -> done
        AgentStatus.IDLE -> idle
        AgentStatus.UNKNOWN -> unknown
    }
}

internal val HerdrColorsDark = HerdrColors(
    blocked = Color(0xFFFF6B6B),
    working = Color(0xFFFFD166),
    done = Color(0xFF8ECAE6),
    idle = Color(0xFF80ED99),
    unknown = Color(0xFF9AA0A6),
)

internal val HerdrColorsLight = HerdrColors(
    // Darkened one step from the dark tokens so dots keep ~4.5:1 contrast on light surfaces.
    blocked = Color(0xFFC62828),
    working = Color(0xFF8A6D00),
    done = Color(0xFF1565C0),
    idle = Color(0xFF1B8A4B),
    unknown = Color(0xFF61656B),
)

/**
 * Very light tonal wash for a surface that belongs to [status]. Used sparingly — a dot does
 * the talking; the wash only groups. Alpha is deliberately small so dynamic colour stays
 * dominant.
 */
fun HerdrColors.tintFor(status: AgentStatus, alpha: Float = 0.08f): Color =
    forStatus(status).copy(alpha = alpha)

/** Content description for a status dot, so TalkBack never reports colour alone. */
fun AgentStatus.contentDescription(): String = when (this) {
    AgentStatus.BLOCKED -> "Blocked, waiting for input"
    AgentStatus.WORKING -> "Working"
    AgentStatus.DONE -> "Done, new results"
    AgentStatus.IDLE -> "Idle"
    AgentStatus.UNKNOWN -> "Status unknown"
}

/** Short visible label for a status, shown next to the dot where space allows. */
fun AgentStatus.shortLabel(): String = when (this) {
    AgentStatus.BLOCKED -> "blocked"
    AgentStatus.WORKING -> "working"
    AgentStatus.DONE -> "done"
    AgentStatus.IDLE -> "idle"
    AgentStatus.UNKNOWN -> "unknown"
}
