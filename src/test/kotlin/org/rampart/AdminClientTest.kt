package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * The admin client's wire handling, without a server: what it will refuse to send, the
 * request it builds, and how it reads what comes back.
 */
class AdminClientTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun theDirectoryIsOutOfReachWhateverAskedForIt() {
        // The guardrail from CLAUDE.md: a renderer bug must not reach x:Directory/set.
        assertNotNull(adminMethodRefusal("x:Directory/set", obj("{}")))
        assertNotNull(adminMethodRefusal("x:Directory/get", obj("{}")))
        assertNotNull(adminMethodRefusal("Email/set", obj("{}")))
        assertNotNull(adminMethodRefusal("x:Action/set", obj("{}")))
    }

    @Test
    fun domainsAndAccountsCanBeReadAndChangedButNotDeleted() {
        assertNull(adminMethodRefusal("x:Domain/get", obj("{}")))
        assertNull(adminMethodRefusal("x:Domain/query", obj("{}")))
        assertNull(adminMethodRefusal("x:Account/set", obj("""{"update":{"a":{}}}""")))
        assertNotNull(adminMethodRefusal("x:Account/set", obj("""{"destroy":["a"]}""")))
        assertNotNull(adminMethodRefusal("x:Domain/changes", obj("{}")))
    }

    @Test
    fun requestsNameOnlyCoreAndStalwart() {
        val body = adminRequestBody(listOf(Triple("x:Domain/get", obj("""{"accountId":"a"}"""), "g")))
        assertEquals(
            listOf("urn:ietf:params:jmap:core", "urn:stalwart:jmap"),
            (body["using"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        val call = (body["methodCalls"] as JsonArray)[0] as JsonArray
        assertEquals("x:Domain/get", (call[0] as JsonPrimitive).content)
        assertEquals("g", (call[2] as JsonPrimitive).content)
    }

    @Test
    fun aMethodErrorBecomesASentence() {
        val e = assertFailsWith<AdminError> {
            readMethodResponses("""{"methodResponses":[["error",{"type":"forbidden"},"g"]]}""")
        }
        assertTrue(e.message!!.startsWith("The server refused"))
        assertFailsWith<AdminError> { readMethodResponses("not json") }
        val ok = readMethodResponses("""{"methodResponses":[["x:Domain/get",{"list":[]},"g"]]}""")
        assertEquals("x:Domain/get", ok[0].first)
    }

    @Test
    fun aRefusedSaveNamesTheFieldsAtFault() {
        val saved = readSetResult(
            obj(
                """{"notUpdated":{"d1":{"type":"invalidProperties","properties":["catchAllAddress","dkimManagement/selector"],""" +
                    """"description":"Invalid email address"}}}""",
            ),
            "d1",
            creating = false,
        )
        assertNull(saved.id)
        assertEquals("The server refused a value: Invalid email address.", saved.refusal)
        assertEquals(setOf("catchAllAddress", "dkimManagement"), saved.problems.keys)
    }

    @Test
    fun aCreatedObjectBringsBackItsId() {
        val saved = readSetResult(obj("""{"created":{"new":{"id":"b7"}}}"""), "new", creating = true)
        assertEquals("b7", saved.id)
        assertNull(saved.refusal)
        val updated = readSetResult(obj("""{"updated":{"d1":null}}"""), "d1", creating = false)
        assertEquals("d1", updated.id)
    }

    @Test
    fun anAnswerThatSaysNothingIsNotTakenAsSuccess() {
        val saved = readSetResult(obj("""{}"""), "d1", creating = false)
        assertNotNull(saved.refusal)
    }

    @Test
    fun theSchemaHashIsTheCacheKeyAndNothingElse() {
        assertEquals("abc123", schemaHash("/api/schema/abc123"))
        // A hash that could climb out of the cache directory is not a hash.
        assertNull(schemaHash("/api/schema/../../etc"))
        assertNull(schemaHash("/api/schema/a.b"))
        assertNull(schemaHash(null))
    }

    @Test
    fun aGzippedSchemaIsUnpacked() {
        val text = """{"objects":{},"fields":{}}"""
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()
        assertEquals(text, gunzipIfNeeded(zipped))
        assertEquals(text, gunzipIfNeeded(text.toByteArray()))
    }

    @Test
    fun theCredentialPicksBearerOrBasicAndNeverPrintsItsSecret() {
        assertEquals("Bearer key123", AdminCredential("mail.example.com", "", "key123").header)
        assertTrue(AdminCredential("mail.example.com", "admin", "pw").header.startsWith("Basic "))
        assertFalse("pw" in AdminCredential("mail.example.com", "admin", "pw").toString())
    }
}
