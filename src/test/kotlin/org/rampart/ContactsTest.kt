package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The card shapes here are what the live Stalwart 0.16 actually returned to a round trip,
 * not what the spec says it might. That distinction has already caught one thing in this
 * repo: the Sieve metadata format.
 */
private fun card(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

private const val PROBE = """
{
  "@type": "Card",
  "version": "1.0",
  "id": "c",
  "addressBookIds": { "b": true },
  "name": { "full": "Probe Two" },
  "emails": {
    "e1": { "address": "a@example.test" },
    "e2": { "address": "b@example.test" }
  },
  "notes": { "n1": { "note": "a note about them" } }
}
"""

class ContactsTest {
    @Test
    fun `a card read off the server keeps every address`() {
        val contact = contactOf(card(PROBE))
        assertEquals("c", contact.id)
        assertEquals("Probe Two", contact.name)
        assertEquals(listOf("a@example.test", "b@example.test"), contact.emails.sorted())
        assertEquals("a note about them", contact.note)
        assertEquals(listOf("b"), contact.bookIds)
    }

    @Test
    fun `organisation and phone come off the groups the server writes`() {
        val contact = contactOf(
            card(
                """
                { "@type": "Card", "version": "1.0", "id": "b",
                  "name": { "full": "Rampart Probe" },
                  "emails": { "e1": { "address": "probe@example.test", "contexts": { "work": true } } },
                  "organizations": { "o1": { "name": "Probe Co" } },
                  "phones": { "p1": { "number": "+15555550123" } } }
                """,
            ),
        )
        assertEquals("Probe Co", contact.organisation)
        assertEquals(listOf("+15555550123"), contact.phones)
        assertEquals(listOf("probe@example.test"), contact.emails)
    }

    @Test
    fun `a card with nothing on it does not throw`() {
        val contact = contactOf(card("""{ "@type": "Card", "version": "1.0", "id": "z" }"""))
        assertEquals("z", contact.id)
        assertEquals("", contact.name)
        assertTrue(contact.emails.isEmpty())
    }

    @Test
    fun `a saved card round trips through the reader`() {
        val original = Contact(
            id = "c",
            name = "Dana Reyes",
            emails = listOf("dana@example.test", "d@example.test"),
            phones = listOf("+15555550100"),
            organisation = "Reyes and Co",
            note = "prefers mornings",
            bookIds = listOf("b"),
        )
        val again = contactOf(merged(original).toMutableMap().plus("id" to card("""{"id":"c"}""")["id"]!!).let(::JsonObject))
        assertEquals(original, again)
    }

    @Test
    fun `properties this build does not draw survive an edit`() {
        val fromServer = card(
            """
            { "@type": "Card", "version": "1.0", "id": "c",
              "name": { "full": "Old Name" },
              "anniversaries": { "a1": { "kind": "birth", "date": { "year": 1990 } } },
              "photos": { "p1": { "uri": "https://example.test/p.png" } } }
            """,
        )
        val written = merged(contactOf(fromServer).copy(name = "New Name"), fromServer)
        assertEquals("New Name", written["name"]?.jsonObject?.get("full")?.jsonPrimitive?.content)
        assertTrue("anniversaries" in written, "the birthday was thrown away")
        assertTrue("photos" in written, "the photo was thrown away")
    }

    @Test
    fun `the server's id is never written back inside the card`() {
        val fromServer = card("""{ "@type": "Card", "version": "1.0", "id": "c", "name": { "full": "X" } }""")
        assertNull(merged(contactOf(fromServer), fromServer)["id"])
    }

    @Test
    fun `an emptied group is removed rather than left holding what it said`() {
        val fromServer = card(
            """
            { "@type": "Card", "version": "1.0", "id": "c", "name": { "full": "X" },
              "phones": { "p1": { "number": "+15555550100" } } }
            """,
        )
        val written = merged(contactOf(fromServer).copy(phones = emptyList()), fromServer)
        assertNull(written["phones"])
    }

    @Test
    fun `blank entries never reach the server`() {
        val written = merged(Contact(name = "X", emails = listOf("  ", "a@example.test", "")))
        assertEquals(1, written["emails"]?.jsonObject?.size)
    }

    @Test
    fun `contacts fold into the book autocomplete already uses`() {
        val book = listOf(Person(email = "dana@example.test", name = "Dana Reyes", seen = 9))
        val folded = withContacts(
            book,
            listOf(
                Contact(name = "Dana Reyes", emails = listOf("dana@example.test")),
                Contact(name = "Sam Okafor", emails = listOf("sam@example.test")),
            ),
        )
        assertEquals(2, folded.size, "the same person was added twice")
        // 10, not 1: folding goes through noted(), which counts a sighting, so somebody
        // already written to nine times keeps that history instead of being replaced by a
        // fresh entry and dropping to the bottom of the suggestions.
        assertEquals(10, folded.first { it.email == "dana@example.test" }.seen)
        assertEquals("Sam Okafor", folded.first { it.email == "sam@example.test" }.name)
    }

    @Test
    fun `a contact with no name never wipes a name already on file`() {
        val book = listOf(Person(email = "dana@example.test", name = "Dana Reyes"))
        val folded = withContacts(book, listOf(Contact(emails = listOf("dana@example.test"))))
        assertEquals("Dana Reyes", folded.first().name)
    }

    @Test
    fun `search looks at more than the name`() {
        val people = listOf(
            Contact(name = "Dana Reyes", emails = listOf("dana@example.test"), organisation = "Reyes and Co"),
            Contact(name = "Sam Okafor", emails = listOf("sam@other.test"), note = "met at the show"),
        )
        assertEquals(listOf("Dana Reyes"), matching(people, "reyes and").map { it.name })
        assertEquals(listOf("Sam Okafor"), matching(people, "THE SHOW").map { it.name })
        assertEquals(listOf("Dana Reyes"), matching(people, "example.test").map { it.name })
        assertEquals(2, matching(people, "  ").size)
    }

    @Test
    fun `a contact with only an address still has something to show`() {
        assertEquals("a@example.test", Contact(emails = listOf("a@example.test")).label)
    }
}

/**
 * A picture on a card is read, and only ever the kind that is already in hand.
 */
class ContactPhotoTest {
    private fun card(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a photo carried inside the card is read`() {
        val contact = contactOf(
            card(
                """
                { "@type": "Card", "version": "1.0", "id": "c",
                  "name": { "full": "Dana Reyes" },
                  "photos": { "p1": { "uri": "data:image/png;base64,AAAA" } } }
                """,
            ),
        )
        assertEquals("data:image/png;base64,AAAA", contact.photo)
    }

    @Test
    fun `a card with no photo says so rather than throwing`() {
        assertEquals("", contactOf(card("""{ "@type": "Card", "version": "1.0", "id": "c" }""")).photo)
    }

    @Test
    fun `the photo survives an edit made here`() {
        // merged() builds on the card that was read, and photos is not a group Rampart
        // replaces, so a picture put there by a phone is still there afterwards.
        val fromServer = card(
            """
            { "@type": "Card", "version": "1.0", "id": "c", "name": { "full": "Old" },
              "photos": { "p1": { "uri": "data:image/png;base64,AAAA" } } }
            """,
        )
        val written = merged(contactOf(fromServer).copy(name = "New"), fromServer)
        assertEquals("data:image/png;base64,AAAA", contactOf(written).photo)
    }
}
