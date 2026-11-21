package dev.herdr.mobile.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrozenFrameOwnershipTest {

    private val backend = Any()
    private val otherBackend = Any()
    private val target = Any()
    private val otherTarget = Any()

    @Test
    fun `a current capture is accepted`() {
        val store = FrozenFrameStore<String>()
        store.onOwnerChanged()
        store.onCaptured(
            ownerEpoch = store.epoch,
            ownerBackend = backend,
            ownerTarget = target,
            captured = "frame",
            currentBackend = backend,
            currentTarget = target,
        )
        assertEquals("frame", store.frameFor(backend, target))
    }

    @Test
    fun `a callback that lands after a tab switch is rejected`() {
        val store = FrozenFrameStore<String>()
        val pendingEpoch = store.epoch
        // The tab switch happens while the 80ms capture is still in flight.
        store.onOwnerChanged()
        store.onCaptured(
            ownerEpoch = pendingEpoch,
            ownerBackend = backend,
            ownerTarget = target,
            captured = "old-frame",
            currentBackend = otherBackend,
            currentTarget = otherTarget,
        )
        assertNull(store.frameFor(otherBackend, otherTarget))
        assertNull(store.frameFor(backend, target))
    }

    @Test
    fun `an accepted frame is hidden as soon as the rendered owner changes`() {
        val store = FrozenFrameStore<String>()
        store.onOwnerChanged()
        store.onCaptured(
            ownerEpoch = store.epoch,
            ownerBackend = backend,
            ownerTarget = target,
            captured = "pane-a",
            currentBackend = backend,
            currentTarget = target,
        )
        assertEquals("pane-a", store.frameFor(backend, target))

        // A new target/backend renders before LaunchedEffect clears the store.
        assertNull(store.frameFor(otherBackend, otherTarget))
        store.onOwnerChanged()
        assertNull(store.frameFor(backend, target))
    }

    @Test
    fun `an epoch mismatch alone is rejected`() {
        val store = FrozenFrameStore<String>()
        store.onOwnerChanged()
        store.onCaptured(
            ownerEpoch = store.epoch - 1,
            ownerBackend = backend,
            ownerTarget = target,
            captured = "stale",
            currentBackend = backend,
            currentTarget = target,
        )
        assertNull(store.frameFor(backend, target))
    }

    @Test
    fun `a target mismatch alone is rejected`() {
        val store = FrozenFrameStore<String>()
        store.onOwnerChanged()
        store.onCaptured(
            ownerEpoch = store.epoch,
            ownerBackend = backend,
            ownerTarget = target,
            captured = "other-pane",
            currentBackend = backend,
            currentTarget = otherTarget,
        )
        assertNull(store.frameFor(backend, otherTarget))
    }

    @Test
    fun `a backend mismatch alone is rejected`() {
        val store = FrozenFrameStore<String>()
        store.onOwnerChanged()
        store.onCaptured(
            ownerEpoch = store.epoch,
            ownerBackend = backend,
            ownerTarget = target,
            captured = "old-backend",
            currentBackend = otherBackend,
            currentTarget = target,
        )
        assertNull(store.frameFor(otherBackend, target))
    }
}
