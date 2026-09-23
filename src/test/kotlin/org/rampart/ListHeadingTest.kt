package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals

class ListHeadingTest {
    @Test
    fun `a search replaces the folder name`() {
        assertEquals("Results for invoice", listHeading(searching = true, query = "invoice", folder = "Inbox"))
    }

    @Test
    fun `the folder name comes back when the search is cleared`() {
        assertEquals("Inbox", listHeading(searching = false, query = "invoice", folder = "Inbox"))
        assertEquals("Inbox", listHeading(searching = true, query = "   ", folder = "Inbox"))
    }
}
