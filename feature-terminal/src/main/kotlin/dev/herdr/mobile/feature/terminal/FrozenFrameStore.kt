package dev.herdr.mobile.feature.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Owns the frozen terminal frame and the capture epoch.
 *
 * A capture starts before it knows whether the target will change. The frame
 * is accepted only when the epoch, backend identity and target still match;
 * the owner change also clears any previously accepted frame. This is a plain
 * state holder so a JVM test can drive the pending-callback race directly.
 */
internal class FrozenFrameStore<T : Any> {
    var epoch by mutableLongStateOf(0L)
        private set

    var frame by mutableStateOf<T?>(null)
        private set

    fun onOwnerChanged() {
        epoch += 1
        frame = null
    }

    fun onCaptured(
        ownerEpoch: Long,
        ownerBackend: Any?,
        ownerTarget: Any?,
        captured: T?,
        currentBackend: Any?,
        currentTarget: Any?,
    ) {
        frame = if (
            captured != null &&
            ownerEpoch == epoch &&
            ownerBackend != null &&
            ownerBackend === currentBackend &&
            ownerTarget != null &&
            ownerTarget == currentTarget
        ) {
            captured
        } else {
            null
        }
    }
}
