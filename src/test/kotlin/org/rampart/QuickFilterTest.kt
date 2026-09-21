package org.rampart

import jakarta.mail.search.AndTerm
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The quick filter row, with no window involved.
 *
 * What is checked here is the part that decides which messages match: the toggles
 * combine, clearing puts them all back, and attachment is not asked where nothing
 * can answer it. The chips are a drawing of this.
 */
class QuickFilterTest {
    private fun mail(
        id: String,
        seen: Boolean = true,
        flagged: Boolean = false,
        keywords: Set<String> = emptySet(),
        fromEmail: String = "dana@example.org",
    ) = Summary(id, "Dana", fromEmail, "Hello", "2026-09-17T09:00:00Z", "", seen, flagged, keywords)

    @Test
    fun togglesCombineAndDoNotOverlap() {
        val known = setOf("dana@example.org")
        val both = QuickFilters(unread = true, starred = true)
        val hit = mail("keep", seen = false, flagged = true, keywords = setOf("invoices"))
        // Starred but already read is not "unread and starred". OR would have kept it.
        assertTrue(matchesQuick(hit, both, known))
        assertFalse(matchesQuick(hit.copy(id = "read", seen = true), both, known))
        assertFalse(matchesQuick(hit.copy(id = "plain", flagged = false), both, known))

        val all = both.copy(tagged = true, knownSender = true)
        assertTrue(matchesQuick(hit, all, known))
        assertFalse(matchesQuick(hit.copy(keywords = setOf("\$seen", "\$flagged")), all, known))
        assertFalse(matchesQuick(hit.copy(keywords = setOf("\$snooze-10")), all, known))
        assertFalse(matchesQuick(hit.copy(fromEmail = "other@example.org"), all, known))
        // The book stores the address in one case. A message that arrived in another
        // is still the same person.
        assertTrue(matchesQuick(hit.copy(fromEmail = "Dana@example.org"), all, known))
    }

    @Test
    fun clearingResetsEveryToggle() {
        val on = QuickFilters(
            unread = true,
            starred = true,
            tagged = true,
            attachment = true,
            knownSender = true,
        )
        assertTrue(on.active)
        val cleared = on.cleared()
        assertEquals(QuickFilters(), cleared)
        assertFalse(cleared.active)
        assertFalse(cleared.unread)
        assertFalse(cleared.starred)
        assertFalse(cleared.tagged)
        assertFalse(cleared.attachment)
        assertFalse(cleared.knownSender)
    }

    @Test
    fun attachmentIsNotAskedWhereNothingCanAnswerIt() {
        assertNull(attachmentReason(imapAccount = false, mergedWithImap = false))
        val imap = attachmentReason(imapAccount = true, mergedWithImap = false)
        val merged = attachmentReason(imapAccount = false, mergedWithImap = true)
        assertNotNull(imap)
        assertNotNull(merged)
        assertNotEquals(imap, merged)
        assertTrue(imap.isNotBlank())
        assertTrue(merged.isNotBlank())

        val asked = QuickFilters(unread = true, attachment = true)
        val dropped = asked.asked(canFilterAttachment = false)
        assertFalse(dropped.attachment)
        assertTrue(dropped.unread)
        // Unread still applies. Dropping attachment must not drop the other toggles,
        // and it must not leave attachment on to be ignored.
        val unread = mail("u", seen = false)
        assertTrue(matchesQuick(unread, dropped, emptySet()))
        assertFalse(matchesQuick(unread.copy(id = "read", seen = true), dropped, emptySet()))
        assertTrue(asked.asked(canFilterAttachment = true).attachment)
    }

    @Test
    fun theStoreCombinesTheSameWayAndRefusesAttachment() {
        val path = Files.createTempDirectory("rampart-quick").resolve("mail.db")
        try {
            Store.open(path, null).use { store ->
                store.put(
                    "inbox",
                    listOf(
                        mail("keep", seen = false, flagged = true, keywords = setOf("invoices")),
                        mail("read", seen = true, flagged = true, keywords = setOf("invoices")),
                        mail("stranger", seen = false, flagged = true, keywords = setOf("invoices"), fromEmail = "other@example.org"),
                        mail("notag", seen = false, flagged = true, keywords = setOf("\$seen")),
                    ),
                )
                val filters = QuickFilters(unread = true, starred = true, tagged = true, knownSender = true)
                assertEquals(
                    listOf("keep"),
                    store.messages("inbox", filters = filters, knownSenders = listOf("Dana@example.org")).map { it.id },
                )
                // Asked of the copy, attachment matches nothing. Matching everything
                // would look like every message had a file.
                assertTrue(store.messages("inbox", filters = QuickFilters(attachment = true)).isEmpty())
                // Unread and starred, without the tag and the book. They share one
                // timestamp, so the order between them is not the point.
                assertEquals(
                    setOf("keep", "stranger", "notag"),
                    store.messages("inbox", filters = QuickFilters(unread = true, starred = true)).map { it.id }.toSet(),
                )
            }
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun jmapAsksTheServerForTaggedAndKnownSender() {
        val plain = emailQueryFilter("inbox", QuickFilters())
        assertNotNull(plain)
        assertEquals("inbox", plain["inMailbox"]!!.jsonPrimitive.content)
        assertNull(plain["operator"])

        val simple = emailQueryFilter("inbox", QuickFilters(unread = true, starred = true, attachment = true))!!
        assertNull(simple["operator"])
        assertEquals("\$seen", simple["notKeyword"]!!.jsonPrimitive.content)
        assertEquals("\$flagged", simple["hasKeyword"]!!.jsonPrimitive.content)
        assertEquals("true", simple["hasAttachment"]!!.jsonPrimitive.content)

        val both = emailQueryFilter(
            "inbox",
            QuickFilters(unread = true, knownSender = true, tagged = true, attachment = true),
            knownSenders = listOf("dana@example.org", "alex@example.org"),
            userKeywords = listOf("\$seen", "invoices", "\$snooze-9"),
        )!!
        assertEquals("AND", both["operator"]!!.jsonPrimitive.content)
        val conditions = both["conditions"]!!.jsonArray
        assertEquals("\$seen", conditions[0].jsonObject["notKeyword"]!!.jsonPrimitive.content)
        assertEquals("true", conditions[0].jsonObject["hasAttachment"]!!.jsonPrimitive.content)
        val sender = conditions[1].jsonObject
        val tagged = conditions[2].jsonObject
        assertEquals("OR", sender["operator"]!!.jsonPrimitive.content)
        assertEquals("OR", tagged["operator"]!!.jsonPrimitive.content)
        assertEquals(2, sender["conditions"]!!.jsonArray.size)
        // Protocol keywords are not tags. Only the one a person made survives.
        assertEquals(1, tagged["conditions"]!!.jsonArray.size)
        assertEquals("invoices", tagged["conditions"]!!.jsonArray[0].jsonObject["hasKeyword"]!!.jsonPrimitive.content)

        // An empty book must not become "everyone".
        assertNull(emailQueryFilter("inbox", QuickFilters(knownSender = true)))
        assertNull(emailQueryFilter("inbox", QuickFilters(tagged = true), userKeywords = listOf("\$flagged")))
    }

    @Test
    fun imapSearchAndsTheFlagsItCanAnswer() {
        val both = quickSearchTerm(QuickFilters(unread = true, starred = true))
        assertTrue(both is AndTerm)
        assertEquals(2, both.terms.size)
        assertNull(quickSearchTerm(QuickFilters()))
        val unread = quickSearchTerm(QuickFilters(unread = true))
        assertNotNull(unread)
        assertFalse(unread is AndTerm)
    }
}
