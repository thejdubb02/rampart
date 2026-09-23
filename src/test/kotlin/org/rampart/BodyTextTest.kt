package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Taken from what the server actually answered for a DMARC report that would not read
 * properly: the same text/plain part is listed under both htmlBody and textBody, because
 * plain text is the best HTML representation the server has of a message with no HTML in it.
 */
class BodyTextTest {
    private val answer = Json.parseToJsonElement(
        """
        {
          "textBody": [{"partId": "1", "type": "text/plain"}],
          "htmlBody": [{"partId": "1", "type": "text/plain"}],
          "bodyValues": {"1": {"value": "Report ID: <secureserver.net!1789516800>", "isTruncated": false}}
        }
        """.trimIndent(),
    ).jsonObject

    private fun parts(name: String) = answer[name] as JsonArray
    private val values get() = answer["bodyValues"]!!.jsonObject

    @Test
    fun plainTextIsNotTakenAsHtml() {
        assertNull(
            bodyText(parts("htmlBody"), values, wantedType = "text/html"),
            "a text/plain part listed under htmlBody is still plain text, and running it " +
                "through the HTML parser eats everything in angle brackets",
        )
    }

    @Test
    fun thePlainTextIsStillThere() {
        assertEquals(
            "Report ID: <secureserver.net!1789516800>",
            bodyText(parts("textBody"), values, wantedType = null),
        )
    }

    @Test
    fun realHtmlIsStillPickedUp() {
        val html = Json.parseToJsonElement(
            """{"htmlBody": [{"partId": "2", "type": "text/html"}],
                "bodyValues": {"2": {"value": "<p>hello</p>"}}}""",
        ).jsonObject
        assertEquals(
            "<p>hello</p>",
            bodyText(html["htmlBody"] as JsonArray, html["bodyValues"]!!.jsonObject, "text/html"),
        )
    }

    @Test
    fun severalPartsJoinInOrder() {
        val many = Json.parseToJsonElement(
            """{"textBody": [{"partId": "a", "type": "text/plain"}, {"partId": "b", "type": "text/plain"}],
                "bodyValues": {"a": {"value": "one"}, "b": {"value": "two"}}}""",
        ).jsonObject
        assertEquals("one\ntwo", bodyText(many["textBody"] as JsonArray, many["bodyValues"]!!.jsonObject, null))
    }

    @Test
    fun nothingToShowIsNullRatherThanAnEmptyBody() {
        assertNull(bodyText(null, values, null))
        assertNull(bodyText(parts("textBody"), JsonObject(emptyMap()), null), "a part with no value is nothing")
    }

    @Test
    fun `a truncated part is replaced by the downloaded bytes`() {
        val raw = Json.parseToJsonElement(
            """{"htmlBody": [{"partId": "1", "type": "text/html", "blobId": "B", "size": 12}],
               "bodyValues": {"1": {"value": "<p>hel", "isTruncated": true}}}""",
        ).jsonObject
        val parts = raw["htmlBody"] as JsonArray
        val values = raw["bodyValues"]!!.jsonObject
        assertTrue(bodyCut(parts, values, "text/html"))
        assertEquals(
            "<p>hello</p>",
            resolveBody(parts, values, "text/html") { id, _, _ ->
                if (id == "B") "<p>hello</p>".encodeToByteArray() else null
            },
        )
    }

    @Test
    fun `a truncated part with no further bytes stays short and is still reported`() {
        val raw = Json.parseToJsonElement(
            """{"textBody": [{"partId": "1", "type": "text/plain", "blobId": "B", "size": 3}],
               "bodyValues": {"1": {"value": "hel", "isTruncated": true}}}""",
        ).jsonObject
        val parts = raw["textBody"] as JsonArray
        val values = raw["bodyValues"]!!.jsonObject
        assertEquals("hel", resolveBody(parts, values, null) { _, _, _ -> null })
        assertTrue(bodyCut(parts, values, null))
    }

    @Test
    fun `a part that arrived whole is not cut`() {
        assertFalse(bodyCut(parts("textBody"), values, null))
    }
}
