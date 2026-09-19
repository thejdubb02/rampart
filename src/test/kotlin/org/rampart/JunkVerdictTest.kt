package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JunkVerdictTest {
    @Test
    fun `into junk says spam`() {
        assertEquals(true, junkVerdict(junk = "j", from = "inbox", into = "j"))
    }

    @Test
    fun `out of junk says not spam`() {
        assertEquals(false, junkVerdict(junk = "j", from = "j", into = "inbox"))
    }

    @Test
    fun `archiving says nothing about spam`() {
        assertNull(junkVerdict(junk = "j", from = "inbox", into = "archive"))
        assertNull(junkVerdict(junk = "j", from = null, into = "trash"))
    }

    @Test
    fun `a server with no junk folder is never asked`() {
        assertNull(junkVerdict(junk = null, from = "j", into = "inbox"))
    }
}
