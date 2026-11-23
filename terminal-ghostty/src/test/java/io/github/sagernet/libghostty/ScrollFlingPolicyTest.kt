package io.github.sagernet.libghostty

import io.github.sagernet.libghostty.GhosttyTerminalView.ScrollRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFlingPolicyTest {

    @Test
    fun `remote host and server flings are bounded`() {
        assertTrue(boundedFling(ScrollRouting.REMOTE_HOST, altOrMouse = false))
        assertTrue(boundedFling(ScrollRouting.REMOTE_SERVER, altOrMouse = false))
    }

    @Test
    fun `local and auto keep the engine fling`() {
        assertFalse(boundedFling(ScrollRouting.LOCAL, altOrMouse = false))
        assertFalse(boundedFling(ScrollRouting.AUTO, altOrMouse = false))
    }

    @Test
    fun `alt-screen and mouse tracking keep their existing bound`() {
        assertTrue(boundedFling(ScrollRouting.AUTO, altOrMouse = true))
    }

    @Test
    fun `the host fling travels at most half a screen`() {
        assertEquals(22 * 37, flingRangePx(rows = 44, cellHeight = 37))
        assertTrue(flingRangePx(rows = 44, cellHeight = 37) < 44 * 37)
    }
}
