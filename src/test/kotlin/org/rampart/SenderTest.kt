package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A sender is one line when the name says nothing the address does not. */
class SenderTest {
    @Test
    fun `a blank name shows the address once`() {
        val (name, extra) = displaySender("", "no-reply@x.com")
        assertEquals("no-reply@x.com", name)
        assertNull(extra)
    }

    @Test
    fun `a name that is the address shows it once`() {
        val (name, extra) = displaySender("no-reply@x.com", "no-reply@x.com")
        assertEquals("no-reply@x.com", name)
        assertNull(extra)
    }

    @Test
    fun `a name that repeats the address shows it once`() {
        val (name, extra) = displaySender(
            "steve@sierrawestco.com <steve@sierrawestco.com>",
            "steve@sierrawestco.com",
        )
        assertEquals("steve@sierrawestco.com", name)
        assertNull(extra)
    }

    @Test
    fun `a real name keeps the address underneath`() {
        val (name, extra) = displaySender("Dana Whitfield", "dana@example.org")
        assertEquals("Dana Whitfield", name)
        assertEquals("dana@example.org", extra)
    }

    @Test
    fun `a name with the address in angle brackets keeps the name`() {
        val (name, extra) = displaySender("Steve <steve@sierrawestco.com>", "steve@sierrawestco.com")
        assertEquals("Steve", name)
        assertEquals("steve@sierrawestco.com", extra)
    }

    @Test
    fun `nothing at all is a missing sender`() {
        val (name, extra) = displaySender("  ", "")
        assertEquals("(no sender)", name)
        assertNull(extra)
    }
}
