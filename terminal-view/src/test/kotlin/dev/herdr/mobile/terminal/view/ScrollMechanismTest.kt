package dev.herdr.mobile.terminal.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Scroll mechanism negotiation: which lever to try and in what order. The
 * actual probe runs on the device (screen hash before/after), but the policy
 * is testable and is what decides which candidate is attempted next.
 */
class ScrollMechanismTest {

    @Test
    fun `host scrollback is tried first because it is line exact`() {
        assertEquals(
            listOf(ScrollMechanism.HOST, ScrollMechanism.PAGE_KEY),
            scrollMechanisms(hostScrollable = true, wheelAgent = false),
        )
        assertEquals(
            listOf(ScrollMechanism.HOST, ScrollMechanism.PAGE_KEY, ScrollMechanism.WHEEL),
            scrollMechanisms(hostScrollable = true, wheelAgent = true),
        )
    }

    @Test
    fun `without host scrollback wheel is preferred over whole screens`() {
        assertEquals(
            listOf(ScrollMechanism.PAGE_KEY, ScrollMechanism.WHEEL),
            scrollMechanisms(hostScrollable = false, wheelAgent = true),
        )
        assertEquals(
            listOf(ScrollMechanism.PAGE_KEY),
            scrollMechanisms(hostScrollable = false, wheelAgent = false),
        )
    }

    @Test
    fun `inert mechanisms are skipped in order`() {
        val candidates = scrollMechanisms(hostScrollable = true, wheelAgent = true)
        assertEquals(
            ScrollMechanism.HOST,
            nextMechanism(candidates, failed = emptySet()),
        )
        assertEquals(
            ScrollMechanism.PAGE_KEY,
            nextMechanism(candidates, failed = setOf(ScrollMechanism.HOST)),
        )
        assertEquals(
            ScrollMechanism.WHEEL,
            nextMechanism(candidates, failed = setOf(ScrollMechanism.HOST, ScrollMechanism.PAGE_KEY)),
        )
        assertNull(
            nextMechanism(
                candidates,
                failed = setOf(
                    ScrollMechanism.HOST,
                    ScrollMechanism.PAGE_KEY,
                    ScrollMechanism.WHEEL,
                ),
            ),
        )
    }

    @Test
    fun `learning is keyed by app identity`() {
        ScrollLearning.reset()
        ScrollLearning.remember("codex", ScrollMechanism.WHEEL)
        assertEquals(ScrollMechanism.WHEEL, ScrollLearning.winner("codex"))
        assertEquals(ScrollMechanism.WHEEL, ScrollLearning.winner(" CODEX ".trim().lowercase()))
        assertNull(ScrollLearning.winner("omp"))
        ScrollLearning.forget("codex")
        assertNull(ScrollLearning.winner("codex"))
    }
}
