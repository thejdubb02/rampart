package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals

class LearnedContactsTest {
    private fun to(email: String, name: String = "") = MailboxAddress(name, email)

    @Test
    fun `somebody already taken to the server is not new`() {
        val fresh = newRecipients(listOf(to("Dana@Example.org"), to("new@example.org")), listOf("dana@example.org"))
        assertEquals(listOf("new@example.org"), fresh.map { it.email })
    }

    @Test
    fun `the same address twice in one message is one card, with the name kept`() {
        val fresh = newRecipients(
            listOf(to("sam@example.org"), to(" SAM@example.org ", "Sam Lee")),
            emptyList(),
        )
        assertEquals(listOf(MailboxAddress("Sam Lee", "SAM@example.org")), fresh)
    }

    @Test
    fun `something that is not an address is never saved`() {
        assertEquals(emptyList(), newRecipients(listOf(to("undisclosed-recipients"), to("")), emptyList()))
    }

    @Test
    fun `a card on the server is matched ignoring case and white space`() {
        val cards = listOf(Contact(id = "c1", name = "Dana", emails = listOf("  DANA@example.ORG ")))
        assertEquals(emptyList(), notOnAnyCard(listOf(to("dana@example.org")), cards))
    }

    @Test
    fun `a card with the address among several is still a match`() {
        val cards = listOf(
            Contact(id = "c1", name = "Work", emails = listOf("desk@example.org")),
            Contact(id = "c2", name = "Robin", emails = listOf("robin@home.example", "Robin@Work.example", "r@x.example")),
        )
        val left = notOnAnyCard(listOf(to("robin@work.example"), to("someone@else.example")), cards)
        assertEquals(listOf("someone@else.example"), left.map { it.email })
    }

    @Test
    fun `a new card goes into the default book with the name that was typed`() {
        val books = listOf(ContactBook("b1", "Shared", isDefault = false), ContactBook("b2", "Mine", isDefault = true))
        val card = learnedCard(to("sam@example.org", " Sam Lee "), books)
        assertEquals("Sam Lee", card.name)
        assertEquals(listOf("sam@example.org"), card.emails)
        assertEquals(listOf("b2"), card.bookIds)
        assertEquals("", card.id)
    }
}
