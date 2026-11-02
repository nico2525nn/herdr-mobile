package dev.herdr.mobile.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.herdr.mobile.core.model.ThemeMode

private val LocalHerdrColors = staticCompositionLocalOf { HerdrColorsDark }

/**
 * Pure-black AMOLED scheme: every background/surface #000000 so pixels stay
 * off. Content colors follow M3 dark defaults; status dots keep the Herdr
 * dark tokens (unchanged — they read fine on black).
 */
private fun midnightColorScheme() = darkColorScheme(
    background = Color.Black,
    onBackground = Color(0xFFE8E8E8),
    surface = Color.Black,
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF0A0A0A),
    onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF111111),
    surfaceContainerHighest = Color(0xFF181818),
    inverseSurface = Color(0xFFE8E8E8),
    inverseOnSurface = Color.Black,
    surfaceTint = Color.Black,
)

/** The semantic token family, resolved for the current light/dark theme. */
val HerdrTheme: HerdrColors
    @Composable
    get() = LocalHerdrColors.current

/**
 * App theme. Dynamic colour when available and enabled, M3 typography, and the Herdr token
 * family on the side. Terminal content styling is deliberately not here — see
 * `:terminal-view`, which takes `TerminalColorScheme` directly.
 */
@Composable
fun HerdrMobileTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.MIDNIGHT -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val useDynamic = dynamicColor && themeMode != ThemeMode.MIDNIGHT &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        themeMode == ThemeMode.MIDNIGHT -> midnightColorScheme()
        useDynamic && dark -> dynamicDarkColorScheme(LocalContext.current)
        useDynamic -> dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    CompositionLocalProvider(LocalHerdrColors provides if (dark) HerdrColorsDark else HerdrColorsLight) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}