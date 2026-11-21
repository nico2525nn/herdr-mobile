package dev.herdr.mobile.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.delay

/** Semantic link state used by the candle light and the terminal status row. */
enum class LinkTone { NONE, BUSY, WARNING, ERROR }

/**
 * M3 Expressive busy indicator: one continuous shape morph through circle,
 * rounded square, diamond and hexagon, with a full turn per cycle. It is an
 * inline 14-16dp glyph, never a container or a spinner.
 */
@Composable
fun ExpressiveStatusGlyph(
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "expressive-shape")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "expressive-shape-phase",
    )
    Canvas(modifier = modifier.size(size)) {
        val shapes = ExpressiveShape.entries
        val scaled = phase * shapes.size
        val from = shapes[scaled.toInt() % shapes.size]
        val to = shapes[(scaled.toInt() + 1) % shapes.size]
        val t = smoothstep(scaled - scaled.toInt())
        // Sample the unrotated silhouette, then rotate the drawn point, so a
        // symmetric shape visibly turns instead of sliding its samples.
        val rotation = phase * 2.0 * PI
        val radius = this.size.minDimension * 0.42f
        val path = Path()
        val samples = 48
        for (i in 0..samples) {
            val shapeAngle = i.toDouble() / samples * 2.0 * PI
            val r = lerp(
                shapeRadius(from, shapeAngle),
                shapeRadius(to, shapeAngle),
                t,
            ) * radius
            val drawAngle = shapeAngle + rotation
            val x = this.size.width / 2f + (cos(drawAngle) * r).toFloat()
            val y = this.size.height / 2f + (sin(drawAngle) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    }
}

private enum class ExpressiveShape { CIRCLE, SQUIRCLE, DIAMOND, HEXAGON }

private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun shapeRadius(shape: ExpressiveShape, theta: Double): Float {
    val c = abs(cos(theta))
    val s = abs(sin(theta))
    return when (shape) {
        ExpressiveShape.CIRCLE -> 1f
        ExpressiveShape.SQUIRCLE -> (1.0 / (c.pow(8.0) + s.pow(8.0)).pow(1.0 / 8.0)).toFloat()
        ExpressiveShape.DIAMOND -> (1.0 / (c + s)).toFloat()
        ExpressiveShape.HEXAGON -> {
            val t = theta - PI / 2.0
            val sector = ((t % (PI / 3.0)) + PI / 3.0) % (PI / 3.0) - PI / 6.0
            (cos(PI / 6.0) / cos(sector)).toFloat()
        }
    }
}

/**
 * Seconds spent in the current connecting/reconnecting episode. Resets when
 * [active] turns false, so the header never accumulates a stale counter.
 */
@Composable
fun rememberElapsedSeconds(active: Boolean): Long {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        if (!active) {
            elapsed = 0L
            return@LaunchedEffect
        }
        val startedAt = System.currentTimeMillis()
        while (true) {
            elapsed = (System.currentTimeMillis() - startedAt) / 1_000L
            delay(1_000L)
        }
    }
    return elapsed
}
