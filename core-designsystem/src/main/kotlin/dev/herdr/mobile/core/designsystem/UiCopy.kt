package dev.herdr.mobile.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Wording for user-facing status messages, behind a seam so a translation is a
 * new implementation rather than a rewrite of the screen.
 *
 * [UiMessage.text] stays the canonical English rendering (pure, so unit tests
 * can assert on it); the composition local lets a host swap in localized copy
 * without the view-models or the message model knowing about resources.
 */
interface UiCopy {
    fun render(message: UiMessage): String
}

/** The bundled copy: English, identical to [UiMessage.text]. */
object EnglishUiCopy : UiCopy {
    override fun render(message: UiMessage): String = message.text()
}

val LocalUiCopy = staticCompositionLocalOf<UiCopy> { EnglishUiCopy }

/** Provide a different copy for a subtree (a locale, a test). */
@Composable
fun ProvideUiCopy(copy: UiCopy, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUiCopy provides copy, content = content)
}
