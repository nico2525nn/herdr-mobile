package dev.herdr.mobile.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import dev.herdr.mobile.core.model.AgentStatus

/**
 * Semantic Herdr state colours, the only custom token family outside Material.
 *
 * Values are the Herdr TUI palette verbatim (`src/app/state.rs`, Catppuccin Mocha default
 * and Catppuccin Latte for light): `status_color` maps working→yellow, blocked→red,
 * done→teal, idle→green, unknown→overlay0. Everything else defers to
 * `MaterialTheme.colorScheme`.
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
    blocked = Color(0xFFF38BA8),
    working = Color(0xFFF9E2AF),
    done = Color(0xFF94E2D5),
    idle = Color(0xFFA6E3A1),
    unknown = Color(0xFF6C7086),
)

internal val HerdrColorsLight = HerdrColors(
    blocked = Color(0xFFD20F39),
    working = Color(0xFFDF8E1D),
    done = Color(0xFF179299),
    idle = Color(0xFF40A02B),
    unknown = Color(0xFF9CA0B0),
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
