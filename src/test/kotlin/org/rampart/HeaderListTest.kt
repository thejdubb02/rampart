package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Taken from what the server actually answers. A header the message does not have comes
 * back as JSON null rather than being left out, and JsonNull is a value, so a null-safe
 * call does not skip it. Every message in the mailbox answers null for Cc, so every one of
 * them stopped opening and showed "Element class JsonNull is not a JsonArray" instead.
 */
class HeaderListTest {
    private val answer = Json.parseToJsonElement(
        """
        {
          "to": [{"name": "Justin", "email": "justin@willhitestrategy.com"}],
          "cc": null,
          "messageId": ["<a@x>"],
          "references": null
        }
        """.trimIndent(),
    ).jsonObject

    @Test
    fun aHeaderAnsweredAsNullIsNoAddresses() {
        assertTrue(addressesIn(answer["cc"]).isEmpty())
        assertTrue(stringsIn(answer["references"]).isEmpty())
    }

    @Test
    fun aHeaderThatIsThereStillReads() {
        assertEquals(listOf("justin@willhitestrategy.com"), addressesIn(answer["to"]))
        assertEquals(listOf("<a@x>"), stringsIn(answer["messageId"]))
    }

    @Test
    fun aHeaderLeftOutAltogetherIsAlsoNothing() {
        assertTrue(addressesIn(answer["bcc"]).isEmpty())
        assertTrue(stringsIn(null).isEmpty())
    }

    /** An address entry with no email at all is skipped rather than becoming a blank one. */
    @Test
    fun anEntryWithNoAddressIsSkipped() {
        val odd = Json.parseToJsonElement("""{"to": [{"name": "Nobody"}, {"email": "a@b.c"}, null]}""").jsonObject
        assertEquals(listOf("a@b.c"), addressesIn(odd["to"]))
    }
}
