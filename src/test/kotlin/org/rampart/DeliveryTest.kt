package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The envelope Rampart hands the server, and what it makes of the answer. */
class DeliveryTest {

    private val everything = setOf(REQUIRETLS, DSN, FUTURERELEASE, "SIZE")
    private val recipients = listOf("ana@example.org", "Ben@Example.net", "ben@example.net")

    // ---- the envelope ---------------------------------------------------------------------

    @Test
    fun `no option means no envelope, which is how every message was sent before`() {
        assertNull(submissionEnvelope("me@example.com", recipients, false, false, everything))
        // Asked for, but the server does not offer it: still nothing to carry.
        assertNull(submissionEnvelope("me@example.com", recipients, true, true, emptySet()))
    }

    @Test
    fun `secure delivery is a bare REQUIRETLS on the sender`() {
        val envelope = submissionEnvelope("me@example.com", recipients, true, false, everything)!!
        val from = envelope["mailFrom"]!!.jsonObject
        assertEquals("me@example.com", from["email"]!!.jsonPrimitive.content)
        val parameters = from["parameters"]!!.jsonObject
        assertEquals(setOf(REQUIRETLS), parameters.keys)
        assertEquals(JsonNull, parameters[REQUIRETLS])
        // Each recipient once, whatever the case it was written in, with no parameters.
        val to = envelope["rcptTo"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("ana@example.org", "Ben@Example.net"), to.map { it["email"]!!.jsonPrimitive.content })
        assertTrue(to.all { it["parameters"] == JsonNull })
    }

    @Test
    fun `delivery confirmation asks for headers back and every notice on every recipient`() {
        val envelope = submissionEnvelope("me@example.com", recipients, false, true, everything)!!
        val parameters = envelope["mailFrom"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals("HDRS", parameters["RET"]!!.jsonPrimitive.content)
        assertFalse(REQUIRETLS in parameters)
        envelope["rcptTo"]!!.jsonArray.forEach {
            assertEquals(
                "SUCCESS,FAILURE,DELAY",
                it.jsonObject["parameters"]!!.jsonObject["NOTIFY"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `both options and a hold time travel together`() {
        val at = Instant.parse("2026-10-01T09:30:15.250Z")
        val envelope = submissionEnvelope("me@example.com", recipients, true, true, everything, holdUntil = at)!!
        val parameters = envelope["mailFrom"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals(setOf(REQUIRETLS, "RET", "HOLDUNTIL"), parameters.keys)
        assertEquals("2026-10-01T09:30:15Z", parameters["HOLDUNTIL"]!!.jsonPrimitive.content)
        assertEquals(2, envelope["rcptTo"]!!.jsonArray.size)
    }

    @Test
    fun `a hold time alone is an envelope, and is dropped where the server cannot hold`() {
        val at = Instant.parse("2026-10-01T09:30:00Z")
        assertNotNull(submissionEnvelope("me@example.com", recipients, false, false, setOf(FUTURERELEASE), at))
        assertNull(submissionEnvelope("me@example.com", recipients, false, false, setOf(DSN), at))
    }

    @Test
    fun `secure delivery the server cannot give refuses to send, a confirmation it cannot give does not`() {
        val secure = Draft(from = "me@example.com", to = "ana@example.org", requireTls = true)
        assertNotNull(refusedOption(secure, setOf(DSN)))
        assertNull(refusedOption(secure, setOf(REQUIRETLS)))
        val confirmed = Draft(from = "me@example.com", to = "ana@example.org", confirmDelivery = true)
        assertNull(refusedOption(confirmed, emptySet()))
    }

    @Test
    fun `the extensions are read from the capability object, in either shape`() {
        val advertised = Json.parseToJsonElement(
            """{"maxDelayedSend":2592000,"submissionExtensions":{"FUTURERELEASE":["2592000"],"DSN":[],"requiretls":[]}}""",
        )
        assertEquals(setOf("FUTURERELEASE", "DSN", "REQUIRETLS"), submissionExtensionsIn(advertised))
        assertEquals(setOf("DSN"), submissionExtensionsIn(Json.parseToJsonElement("""{"submissionExtensions":["dsn"]}""")))
        assertEquals(emptySet(), submissionExtensionsIn(Json.parseToJsonElement("{}")))
        assertEquals(emptySet(), submissionExtensionsIn(null))
    }

    // ---- reading the status back ----------------------------------------------------------

    private fun record(json: String): SubmissionRecord =
        submissionRecordOf(Json.parseToJsonElement(json) as JsonObject)

    @Test
    fun `every recipient accepted reads as delivered`() {
        val report = deliveryReport(
            listOf(
                record(
                    """{"sendAt":"2026-09-28T10:00:00Z","undoStatus":"final","deliveryStatus":{
                    "ana@example.org":{"delivered":"yes","smtpReply":"250 2.0.0 OK","displayed":"unknown"}}}""",
                ),
            ),
        )!!
        assertEquals("Delivered.", report.says)
        assertFalse(report.failed)
    }

    @Test
    fun `a refusal names the recipient and the reason, and keeps the server's words`() {
        val report = deliveryReport(
            listOf(
                record(
                    """{"sendAt":"2026-09-28T10:00:00Z","undoStatus":"final","deliveryStatus":{
                    "ana@example.org":{"delivered":"yes","smtpReply":"250 2.0.0 OK"},
                    "nobody@example.net":{"delivered":"no","smtpReply":"550 5.1.1 <nobody@example.net>: user unknown"}}}""",
                ),
            ),
        )!!
        assertTrue(report.failed)
        assertEquals("Not delivered to nobody@example.net. That address does not exist.", report.says)
        assertEquals("550 5.1.1 <nobody@example.net>: user unknown", report.because)
    }

    @Test
    fun `a message still in the queue is waiting, and a retry says why`() {
        val waiting = deliveryReport(
            listOf(
                record(
                    """{"sendAt":"2026-09-28T10:00:00Z","undoStatus":"pending","deliveryStatus":{
                    "ana@example.org":{"delivered":"queued","smtpReply":"250 2.1.5 Queued"}}}""",
                ),
            ),
        )!!
        assertEquals("Waiting to be delivered.", waiting.says)
        val retrying = deliveryReport(
            listOf(
                record(
                    """{"sendAt":"2026-09-28T10:00:00Z","undoStatus":"pending","deliveryStatus":{
                    "ana@example.org":{"delivered":"queued","smtpReply":"421 4.7.0 Try again later"}}}""",
                ),
            ),
        )!!
        assertTrue(retrying.says.startsWith("Not delivered yet to ana@example.org"))
        assertFalse(retrying.failed)
    }

    @Test
    fun `nothing is said when the server kept no record, or does not know`() {
        // Stalwart expunges old submissions, so an old sent message has none at all.
        assertNull(deliveryReport(emptyList()))
        // Once its queue is done, Stalwart reports every recipient as unknown.
        assertNull(
            deliveryReport(
                listOf(
                    record(
                        """{"sendAt":"2026-09-28T10:00:00Z","undoStatus":"final","deliveryStatus":{
                        "ana@example.org":{"delivered":"unknown","smtpReply":"250 2.1.5 Queued"}}}""",
                    ),
                ),
            ),
        )
        assertNull(deliveryReport(listOf(record("""{"sendAt":"2026-09-28T10:00:00Z","deliveryStatus":null}"""))))
        // A cancelled send is not a delivery to report on.
        assertNull(
            deliveryReport(
                listOf(
                    record(
                        """{"sendAt":"2026-09-28T10:00:00Z","undoStatus":"canceled","deliveryStatus":{
                        "ana@example.org":{"delivered":"no","smtpReply":"cancelled"}}}""",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `the latest submission is the one that counts`() {
        val first = record(
            """{"sendAt":"2026-09-27T10:00:00Z","undoStatus":"final","deliveryStatus":{
            "ana@example.org":{"delivered":"no","smtpReply":"552 5.2.2 Mailbox full"}}}""",
        )
        val second = record(
            """{"sendAt":"2026-09-28T10:00:00Z","undoStatus":"final","deliveryStatus":{
            "ana@example.org":{"delivered":"yes","smtpReply":"250 OK"}}}""",
        )
        assertEquals("Delivered.", deliveryReport(listOf(second, first))!!.says)
        assertEquals("That mailbox is full.", reasonFor(first.recipients.single().smtpReply))
    }

    @Test
    fun `a message that required encryption the path could not give says so`() {
        assertEquals(
            "The receiving server could not promise an encrypted connection, which this message required.",
            reasonFor("550 5.7.30 REQUIRETLS support required"),
        )
        assertNull(reasonFor("something with no code"))
    }
}
