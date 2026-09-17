package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewMailTest {
    private fun mail(id: String, from: String = "Dana", subject: String = "Hello", seen: Boolean = false) =
        Summary(id, from, "$from@example.org", subject, "2026-09-17T09:00:00Z", "", seen)

    @Test
    fun theFirstLookAnnouncesNothing() {
        val found = arrivals("s1", listOf(mail("a"), mail("b")), known = null)
        assertTrue(found.fresh.isEmpty(), "a full inbox on startup is not a pile of new mail")
        assertEquals(setOf("a", "b"), found.summaries.map { it.id }.toSet())
    }

    @Test
    fun onlyUnseenMessagesThatAreActuallyNew() {
        val found = arrivals("s2", listOf(mail("c"), mail("b", seen = true), mail("a")), known = setOf("a", "b"))
        assertEquals(listOf("c"), found.fresh.map { it.id })
    }

    /** Marking something read in another client changes the state but is not an arrival. */
    @Test
    fun readingElsewhereIsNotAnArrival() {
        val found = arrivals("s3", listOf(mail("a", seen = true)), known = setOf("a"))
        assertTrue(found.fresh.isEmpty())
    }

    @Test
    fun oneNotificationForHowEverManyArrived() {
        assertNull(arrivalText(emptyList()))
        assertEquals("Dana" to "Hello", arrivalText(listOf(mail("a"))))
        assertEquals("(no subject)", arrivalText(listOf(mail("a", subject = " ")))!!.second)
        val many = listOf(mail("a", "Dana"), mail("b", "Alex"), mail("c", "Sam"), mail("d", "Jo"))
        assertEquals("4 new messages" to "Dana, Alex, Sam", arrivalText(many))
    }
}
