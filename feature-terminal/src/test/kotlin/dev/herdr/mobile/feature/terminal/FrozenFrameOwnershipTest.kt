package dev.herdr.mobile.feature.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenFrameOwnershipTest {

    private val backend = Any()
    private val otherBackend = Any()
    private val target = Any()
    private val otherTarget = Any()

    @Test
    fun `current epoch and owners are accepted`() {
        assertTrue(
            frozenFrameBelongsToCurrent(
                ownerEpoch = 4,
                currentEpoch = 4,
                ownerBackend = backend,
                currentBackend = backend,
                ownerTarget = target,
                currentTarget = target,
            ),
        )
    }

    @Test
    fun `a capture that landed after a tab switch is rejected`() {
        assertFalse(
            frozenFrameBelongsToCurrent(
                ownerEpoch = 4,
                currentEpoch = 5,
                ownerBackend = backend,
                currentBackend = backend,
                ownerTarget = target,
                currentTarget = otherTarget,
            ),
        )
    }

    @Test
    fun `a frame from a replaced backend is rejected`() {
        assertFalse(
            frozenFrameBelongsToCurrent(
                ownerEpoch = 4,
                currentEpoch = 4,
                ownerBackend = backend,
                currentBackend = otherBackend,
                ownerTarget = target,
                currentTarget = target,
            ),
        )
    }

    @Test
    fun `an ownerless capture is rejected`() {
        assertFalse(
            frozenFrameBelongsToCurrent(
                ownerEpoch = 4,
                currentEpoch = 4,
                ownerBackend = null,
                currentBackend = backend,
                ownerTarget = target,
                currentTarget = target,
            ),
        )
    }
}
