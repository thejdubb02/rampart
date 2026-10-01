package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The request that names a conversation, and the request that takes it out of one folder.
 *
 * Both are built here and sent as they are. A wrong patch key, or a back reference that
 * does not point at the thread, is a click that files the wrong messages or drops a copy
 * that was also in Sent.
 */
class ConversationFilingTest {
    private fun args(call: JsonArray): JsonObject = call[1].jsonObject

    @Test
    fun `naming a thread then reads where each message is, by back reference`() {
        val calls = conversationInCalls("acc", "t1")
        assertEquals(2, calls.size)
        val thread = calls[0]
        val email = calls[1]
        assertEquals("Thread/get", thread[0].jsonPrimitive.content)
        assertEquals("t", thread[2].jsonPrimitive.content)
        assertEquals("acc", args(thread)["accountId"]!!.jsonPrimitive.content)
        assertEquals(listOf("t1"), args(thread)["ids"]!!.jsonArray.map { it.jsonPrimitive.content })

        assertEquals("Email/get", email[0].jsonPrimitive.content)
        assertEquals("g", email[2].jsonPrimitive.content)
        assertEquals("acc", args(email)["accountId"]!!.jsonPrimitive.content)
        val ref = args(email)["#ids"]!!.jsonObject
        assertEquals(thread[2].jsonPrimitive.content, ref["resultOf"]!!.jsonPrimitive.content)
        assertEquals("Thread/get", ref["name"]!!.jsonPrimitive.content)
        assertEquals("/list/*/emailIds", ref["path"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("mailboxIds", "keywords"),
            args(email)["properties"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `a filing removes one folder and sets the other, and does not replace the rest`() {
        val call = moveFromCall("acc", listOf("a", "b"), "in", "arc")
        assertEquals("Email/set", call[0].jsonPrimitive.content)
        assertEquals("m", call[2].jsonPrimitive.content)
        val update = args(call)["update"]!!.jsonObject
        assertEquals(setOf("a", "b"), update.keys)
        val expected = buildJsonObject {
            put("mailboxIds/in", JsonNull)
            put("mailboxIds/arc", JsonPrimitive(true))
        }
        assertEquals(expected, update["a"]!!.jsonObject)
        assertEquals(expected, update["b"]!!.jsonObject)
    }

    @Test
    fun `filing into the folder a message is already in does not take it out`() {
        val patch = args(moveFromCall("acc", listOf("a"), "in", "in"))["update"]!!.jsonObject["a"]!!.jsonObject
        assertEquals(buildJsonObject { put("mailboxIds/in", JsonPrimitive(true)) }, patch)
    }

    @Test
    fun `a message in this folder is kept, one only in Sent is not, and a draft stays`() {
        val emails = listOf(
            email("keep", boxes("in")),
            email("both", boxes("in", "sent")),
            email("sent-only", boxes("sent")),
            email("draft", boxes("in"), keywords = buildJsonObject { put("\$draft", true) }),
            email("shouty-draft", boxes("in"), keywords = buildJsonObject { put("\$DRAFT", true) }),
            email("flagged", boxes("in"), keywords = buildJsonObject { put("\$seen", true) }),
            buildJsonObject { put("mailboxIds", boxes("in")) },
            buildJsonObject { put("id", JsonNull); put("mailboxIds", boxes("in")) },
            email("no-box", null),
        )
        assertEquals(listOf("keep", "both", "flagged"), idsInFolder(emails, "in"))
    }

    private fun boxes(vararg ids: String) = buildJsonObject { ids.forEach { put(it, true) } }

    private fun email(id: String, boxes: JsonObject?, keywords: JsonObject? = null) = buildJsonObject {
        put("id", id)
        if (boxes != null) put("mailboxIds", boxes)
        if (keywords != null) put("keywords", keywords)
    }
}
