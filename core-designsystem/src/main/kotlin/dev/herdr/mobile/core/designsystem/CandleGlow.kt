package dev.herdr.mobile.core.designsystem

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * D's connection light: a slow, candle-like bloom behind the top chrome.
 *
 * Four translucent radial sources drift for six seconds and fade before the
 * layer's lower edge, so the chrome and the content never meet at a line.
 * The layer is purely visual; it does not consume touches.
 *
 * A terminal surface can draw the same light as a low-intensity overlay so
 * the app chrome and the terminal background share one source.
 */
@Composable
fun CandleGlow(
    tone: LinkTone,
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
) {
    if (tone == LinkTone.NONE) return
    val accent = when (tone) {
        LinkTone.ERROR -> HerdrTheme.failed
        else -> HerdrTheme.working
    }
    val secondary = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val animated = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }
    val transition = rememberInfiniteTransition(label = "candle-glow")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "candle-phase",
    )
    Canvas(modifier = modifier) {
        // Read the animation inside the draw scope: no recomposition per frame.
        val p = if (animated) phase.value else 0.34f
        val t = p * 2.0 * PI
        val amberDx = (sin(t) * 16.dp.toPx()).toFloat()
        val amberDy = (sin(t + 1.1) * 5.dp.toPx()).toFloat()
        val coreDx = (sin(t + 0.7) * 14.dp.toPx()).toFloat()
        val coreDy = (cos(t + 0.2) * 5.dp.toPx()).toFloat()
        val purpleDx = (sin(t + PI) * 16.dp.toPx()).toFloat()
        val purpleDy = (cos(t) * 6.dp.toPx()).toFloat()
        val warmDx = (sin(t + 2.1) * 10.dp.toPx()).toFloat()
        val warmDy = (cos(t + 0.8) * 4.dp.toPx()).toFloat()
        val amberScale = 1f + 0.08f * sin(t + 0.4).toFloat()
        val coreScale = 1f + 0.07f * sin(t + 0.9).toFloat()
        val flicker = 0.55f + 0.45f * (0.5f + 0.5f * sin(t * 3f + 0.5f).toFloat())

        val w = size.width
        val h = size.height
        val amber = Offset(w * 0.18f + amberDx, h * 0.30f + amberDy)
        val core = Offset(w * 0.22f + coreDx, h * 0.34f + coreDy)
        val purple = Offset(w * 0.92f + purpleDx, h * 0.18f + purpleDy)
        val warm = Offset(w * 0.38f + warmDx, h * 0.46f + warmDy)

        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, w, h), Paint())
            blob(amber, w * 0.92f * amberScale, accent, 0.22f * intensity)
            blob(purple, w * 0.62f, secondary, 0.10f * intensity)
            blob(core, w * 0.44f * coreScale, accent, 0.20f * intensity)
            blob(warm, w * 0.36f, accent, 0.10f * flicker * intensity)
            // Vertical mask: the light dissolves before the layer's bottom.
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    0.55f to Color.Black,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
            canvas.restore()
        }
    }
}

private fun DrawScope.blob(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
) {
    if (radius <= 0f || alpha <= 0f) return
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = alpha),
                color.copy(alpha = alpha * 0.45f),
                Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
