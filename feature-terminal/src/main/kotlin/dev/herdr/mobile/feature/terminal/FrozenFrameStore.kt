package dev.herdr.mobile.feature.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Owns the frozen terminal frame, its capture epoch and the exact pane/backend
 * that produced it.
 *
 * The stored owner is checked both when a delayed capture lands and when the
 * frame is read for rendering. Compose can render one frame with the old
 * state before LaunchedEffect clears it; the render-time owner check closes
 * that window. This is a plain state holder so a JVM test can drive the race.
 */
internal class FrozenFrameStore<T : Any> {
    private data class OwnedFrame<T>(
        val backend: Any,
        val target: Any,
        val value: T,
    )

    var epoch by mutableLongStateOf(0L)
        private set

    private var owned by mutableStateOf<OwnedFrame<T>?>(null)

    fun onOwnerChanged() {
        epoch += 1
        owned = null
    }

    fun onCaptured(
        ownerEpoch: Long,
        ownerBackend: Any?,
        ownerTarget: Any?,
        captured: T?,
        currentBackend: Any?,
        currentTarget: Any?,
    ) {
        owned = if (
            captured != null &&
            ownerEpoch == epoch &&
            ownerBackend != null &&
            ownerBackend === currentBackend &&
            ownerTarget != null &&
            ownerTarget == currentTarget
        ) {
            OwnedFrame(ownerBackend, ownerTarget, captured)
        } else {
            null
        }
    }

    /** Render-time owner check, before the owner-change effect runs. */
    fun frameFor(currentBackend: Any?, currentTarget: Any?): T? =
        owned
            ?.takeIf { it.backend === currentBackend && it.target == currentTarget }
            ?.value
}
