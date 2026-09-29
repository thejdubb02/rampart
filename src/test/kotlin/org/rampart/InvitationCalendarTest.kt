package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Answering invitations through the calendar: the requests built, the answers read, and
 * which way each answer goes. Fixtures are shaped the way Stalwart 0.16 writes JSCalendar
 * (calcard 0.3.14: `calendarAddress`, `organizerCalendarAddress`, `recurrenceRule` singular),
 * read from its source rather than captured from a live server.
 */
class InvitationCalendarTest {
    private val me = "me@example.org"
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 9, 28, 12, 0, 0, 0, ZoneOffset.UTC)

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun invitation(
        method: String = "REQUEST",
        sequence: Int = 0,
        recurrenceId: EventTime? = null,
    ): Invitation = Invitation(
        method = method,
        uid = "uid-1@example.com",
        sequence = sequence,
        summary = "Planning",
        description = "",
        location = "",
        organiser = Invitee("Olive", "olive@example.com", ""),
        attendees = listOf(Invitee("", me, "NEEDS-ACTION")),
        starts = EventTime(ZonedDateTime.of(2026, 10, 5, 9, 0, 0, 0, ZoneId.of("Europe/London")), false, "20261005T090000"),
        ends = EventTime(ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, ZoneId.of("Europe/London")), false, "20261005T100000"),
        repeats = "",
        recurrenceId = recurrenceId,
    )

    private fun stored(
        status: String = "needs-action",
        sequence: Int = 0,
        agent: String? = null,
        isOrigin: Boolean = false,
        eventStatus: String? = null,
    ): JsonObject = obj(
        """
        {"id":"ev1","uid":"uid-1@example.com","calendarIds":{"cal1":true},"isOrigin":$isOrigin,
         "title":"Planning","start":"2026-10-05T09:00:00","timeZone":"Europe/London","duration":"PT1H",
         "sequence":$sequence,${eventStatus?.let { "\"status\":\"$it\"," } ?: ""}
         "organizerCalendarAddress":"mailto:olive@example.com",
         "participants":{
           "p0":{"@type":"Participant","calendarAddress":"mailto:olive@example.com","roles":{"owner":true}},
           "p1":{"@type":"Participant","calendarAddress":"mailto:ME@example.org","participationStatus":"$status"${agent?.let { ",\"scheduleAgent\":\"$it\"" } ?: ""}}
         }}
        """,
    )

    private fun response(name: String, body: String, id: String): JsonArray = buildJsonArray {
        add(JsonPrimitive(name)); add(Json.parseToJsonElement(body)); add(JsonPrimitive(id))
    }

    /** A pretend server: records every call and answers each by method name. */
    private class Server(val answer: (JsonArray) -> JsonArray) {
        val calls = mutableListOf<JsonArray>()
        fun send(batch: List<JsonArray>): List<JsonArray> {
            calls += batch
            return batch.map(answer)
        }
        fun names() = calls.map { it[0].jsonPrimitive.content }
        fun args(i: Int) = calls[i][1].jsonObject
    }

    // ---- request shapes ----

    @Test
    fun `lookup by UID is a query with the events fetched by back reference`() {
        val client = InvitationCalendarClient("acc") { error("not called") }
        val (query, get) = client.lookupCalls("uid-1@example.com")
        assertEquals("CalendarEvent/query", query[0].jsonPrimitive.content)
        assertEquals(obj("""{"accountId":"acc","filter":{"uid":"uid-1@example.com"}}"""), query[1])
        assertEquals("CalendarEvent/get", get[0].jsonPrimitive.content)
        assertEquals(
            obj("""{"accountId":"acc","#ids":{"resultOf":"u","name":"CalendarEvent/query","path":"/ids"}}"""),
            get[1],
        )
    }

    @Test
    fun `accept tentative and decline are one participationStatus path each`() {
        assertEquals(obj("""{"participants/p1/participationStatus":"accepted"}"""), participationPatch("p1", Rsvp.ACCEPT))
        assertEquals(obj("""{"participants/p1/participationStatus":"tentative"}"""), participationPatch("p1", Rsvp.TENTATIVE))
        assertEquals(obj("""{"participants/p1/participationStatus":"declined"}"""), participationPatch("p1", Rsvp.DECLINE))
    }

    @Test
    fun `a participant id with a slash is escaped in the pointer`() {
        assertEquals(
            obj("""{"participants/a~1b~0c/participationStatus":"accepted"}"""),
            participationPatch("a/b~c", Rsvp.ACCEPT),
        )
    }

    @Test
    fun `an answer through the server is an update with sendSchedulingMessages true`() {
        val server = Server { call ->
            when (call[0].jsonPrimitive.content) {
                "CalendarEvent/query" -> response("CalendarEvent/query", """{"ids":["ev1"]}""", "u")
                "CalendarEvent/get" -> response("CalendarEvent/get", """{"list":[${stored()}]}""", "g")
                else -> response("CalendarEvent/set", """{"updated":{"ev1":null}}""", "s")
            }
        }
        val result = answerInCalendar(InvitationCalendarClient("acc", server::send), invitation(), Rsvp.ACCEPT, listOf(me), "blob1", now)
        assertEquals(CalendarAnswer(replySent = true), result)
        assertEquals(listOf("CalendarEvent/query", "CalendarEvent/get", "CalendarEvent/set"), server.names())
        assertEquals(
            obj("""{"accountId":"acc","update":{"ev1":{"participants/p1/participationStatus":"accepted"}},"sendSchedulingMessages":true}"""),
            server.args(2),
        )
    }

    @Test
    fun `a meeting not in the calendar is parsed by the server and created in the default calendar`() {
        val server = Server { call ->
            when (call[0].jsonPrimitive.content) {
                "CalendarEvent/query" -> response("CalendarEvent/query", """{"ids":[]}""", "u")
                "CalendarEvent/get" -> response("CalendarEvent/get", """{"list":[]}""", "g")
                "CalendarEvent/parse" -> response(
                    "CalendarEvent/parse",
                    """{"parsed":{"blob1":[{"@type":"Event","uid":"uid-1@example.com","method":"request","title":"Planning",
                        "start":"2026-10-05T09:00:00","timeZone":"Europe/London","duration":"PT1H",
                        "participants":{"a":{"calendarAddress":"mailto:me@example.org","participationStatus":"needs-action"},
                                        "b":{"calendarAddress":"mailto:olive@example.com","roles":{"owner":true}}}}]}}""",
                    "p",
                )
                "Calendar/get" -> response(
                    "Calendar/get",
                    """{"list":[{"id":"work","name":"Work"},{"id":"home","name":"Home","isDefault":true}]}""",
                    "c",
                )
                else -> response("CalendarEvent/set", """{"created":{"invite":{"id":"new1"}}}""", "s")
            }
        }
        val result = answerInCalendar(InvitationCalendarClient("acc", server::send), invitation(), Rsvp.ACCEPT, listOf(me), "blob1", now)
        // Added, but the reply is the email's to send: Stalwart sends none for an event it did not import.
        assertEquals(CalendarAnswer(replySent = false), result)
        assertEquals(
            listOf("CalendarEvent/query", "CalendarEvent/get", "CalendarEvent/parse", "Calendar/get", "CalendarEvent/set"),
            server.names(),
        )
        assertEquals(obj("""{"accountId":"acc","blobIds":["blob1"]}"""), server.args(2))
        val create = server.args(4)
        assertEquals(JsonPrimitive(false), create["sendSchedulingMessages"])
        val event = create["create"]!!.jsonObject["invite"]!!.jsonObject
        assertNull(event["method"], "method is immutable on Stalwart and refused on create")
        assertEquals(obj("""{"home":true}"""), event["calendarIds"])
        val mine = event["participants"]!!.jsonObject["a"]!!.jsonObject
        assertEquals("accepted", mine["participationStatus"]!!.jsonPrimitive.content)
        assertEquals("client", mine["scheduleAgent"]!!.jsonPrimitive.content)
        assertNull(event["participants"]!!.jsonObject["b"]!!.jsonObject["scheduleAgent"])
    }

    @Test
    fun `a decline for a meeting not in the calendar adds nothing`() {
        val server = Server { call ->
            when (call[0].jsonPrimitive.content) {
                "CalendarEvent/query" -> response("CalendarEvent/query", """{"ids":[]}""", "u")
                else -> response("CalendarEvent/get", """{"list":[]}""", "g")
            }
        }
        val result = answerInCalendar(InvitationCalendarClient("acc", server::send), invitation(), Rsvp.DECLINE, listOf(me), "blob1", now)
        assertEquals(CalendarAnswer(replySent = false), result)
        assertEquals(listOf("CalendarEvent/query", "CalendarEvent/get"), server.names())
    }

    @Test
    fun `an update from the organiser replaces what the organiser owns and removes what they dropped`() {
        val old = obj("""{"id":"ev1","title":"Planning","start":"2026-10-05T09:00:00","locations":{"1":{"name":"Room 1"}},"sequence":1,"alerts":{"a":{}},"calendarIds":{"c":true}}""")
        val parsed = obj("""{"uid":"u","method":"request","title":"Planning","start":"2026-10-06T09:00:00","sequence":2}""")
        assertEquals(
            obj("""{"start":"2026-10-06T09:00:00","locations":null,"sequence":2}"""),
            organiserUpdatePatch(old, parsed),
        )
    }

    @Test
    fun `a cancellation removes the event without scheduling, so no decline goes back`() {
        val server = Server { response("CalendarEvent/set", """{"destroyed":["ev1"]}""", "s") }
        assertNull(InvitationCalendarClient("acc", server::send).remove("ev1"))
        assertEquals(obj("""{"accountId":"acc","destroy":["ev1"],"sendSchedulingMessages":false}"""), server.args(0))
    }

    @Test
    fun `a cancelled time of a series is excluded in the event's own zone`() {
        val event = assertNotNull(calendarEventOf(stored()))
        val utc = EventTime(ZonedDateTime.of(2026, 10, 12, 8, 0, 0, 0, ZoneOffset.UTC), false, "20261012T080000Z")
        val key = assertNotNull(recurrenceKey(utc, event))
        assertEquals(LocalDateTime.of(2026, 10, 12, 9, 0), key)
        assertEquals(
            obj("""{"recurrenceOverrides":{"2026-10-12T09:00:00":{"excluded":true}}}"""),
            excludedPatch(event, key),
        )
    }

    // ---- reading answers ----

    @Test
    fun `this account's participant is found by calendarAddress ignoring case`() {
        val match = assertNotNull(calendarMatchOf(stored(status = "accepted"), listOf(me)))
        assertEquals("p1", match.myParticipantId)
        assertEquals("accepted", match.myStatus)
        assertFalse(match.clientScheduled)
    }

    @Test
    fun `the older sendTo form of a participant is understood as well`() {
        assertEquals("me@example.org", participantAddress(obj("""{"sendTo":{"imip":"mailto:Me@Example.org"}}""")))
    }

    @Test
    fun `a UID is matched exactly, and a missing one is no match`() {
        val list = listOf(stored())
        assertNotNull(matchFor(list, "uid-1@example.com", listOf(me)))
        assertNull(matchFor(list, "UID-1@example.com", listOf(me)))
    }

    @Test
    fun `a refused query falls back to reading every event`() {
        val server = Server { call ->
            if (call[0].jsonPrimitive.content == "CalendarEvent/query") throw JmapError("The server refused the request: unknownMethod")
            assertEquals(JsonNull, call[1].jsonObject["ids"])
            response("CalendarEvent/get", """{"list":[${stored()}]}""", "g")
        }
        assertEquals("ev1", InvitationCalendarClient("acc", server::send).find("uid-1@example.com", listOf(me))?.eventId)
    }

    @Test
    fun `parse picks the event with the invitation's UID`() {
        val response = obj("""{"parsed":{"b":[{"uid":"other"},{"uid":"mine","title":"Yes"}]}}""")
        assertEquals("Yes", parsedEvent(response, "b", "mine")?.get("title")?.jsonPrimitive?.content)
        assertNull(parsedEvent(obj("""{"notParsable":["b"]}"""), "b", "mine"))
    }

    @Test
    fun `the default calendar is the one marked default, else the first writable`() {
        val a = CalendarInfo("a", "A", mayWrite = false, isDefault = true)
        val b = CalendarInfo("b", "B")
        val c = CalendarInfo("c", "C", isDefault = true)
        assertEquals("c", pickDefault(listOf(a, b, c))?.id)
        assertEquals("b", pickDefault(listOf(a, b))?.id)
        assertNull(pickDefault(listOf(a)))
    }

    @Test
    fun `a forbidden refusal is told apart from any other`() {
        val refused = assertNotNull(
            refusalIn(
                obj("""{"notUpdated":{"ev1":{"type":"forbidden","description":"Calendar scheduling is disabled on this server."}}}"""),
                "ev1", "updated", "notUpdated", "The server would not change the event",
            ),
        )
        assertTrue(refused.forbidden)
        assertEquals("The server would not change the event: Calendar scheduling is disabled on this server.", refused.sentence)
        assertNull(refusalIn(obj("""{"updated":{"ev1":null}}"""), "ev1", "updated", "notUpdated", "x"))
    }

    // ---- which way an answer goes ----

    @Test
    fun `the server sends only when it will actually send`() {
        val inv = invitation()
        assertIs<AnswerPlan.ServerReply>(planAnswer(inv, Rsvp.ACCEPT, calendarMatchOf(stored(), listOf(me)), now))
        // Added by Rampart itself, so the server has no schedule tag and would send nothing.
        assertIs<AnswerPlan.RecordThenMail>(planAnswer(inv, Rsvp.ACCEPT, calendarMatchOf(stored(agent = "client"), listOf(me)), now))
        // The same answer again: nothing changes on the event, so nothing would be sent.
        val same = planAnswer(inv, Rsvp.ACCEPT, calendarMatchOf(stored(status = "accepted"), listOf(me)), now)
        assertEquals(AnswerPlan.RecordThenMail("ev1", "p1", changed = false), same)
        // Not on the guest list, or organised here: nothing for the server to do.
        assertEquals(AnswerPlan.MailOnly, planAnswer(inv, Rsvp.ACCEPT, calendarMatchOf(stored(), listOf("other@example.org")), now))
        assertEquals(AnswerPlan.MailOnly, planAnswer(inv, Rsvp.ACCEPT, calendarMatchOf(stored(isOrigin = true), listOf(me)), now))
        // Over: Stalwart sends nothing for a meeting in the past.
        val later = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        assertIs<AnswerPlan.RecordThenMail>(planAnswer(inv, Rsvp.ACCEPT, calendarMatchOf(stored(), listOf(me)), later))
        assertEquals(AnswerPlan.MailOnly, planAnswer(invitation(method = "CANCEL"), Rsvp.ACCEPT, null, now))
    }

    @Test
    fun `an answer to a newer version than the calendar has brings the calendar up to date first`() {
        val plan = planAnswer(invitation(sequence = 2), Rsvp.ACCEPT, calendarMatchOf(stored(sequence = 1), listOf(me)), now)
        assertEquals(AnswerPlan.ServerReply("ev1", "p1", outdated = true), plan)
    }

    @Test
    fun `a server that refuses to schedule gets the answer recorded quietly and the email goes`() {
        var sets = 0
        val server = Server { call ->
            when (call[0].jsonPrimitive.content) {
                "CalendarEvent/query" -> response("CalendarEvent/query", """{"ids":["ev1"]}""", "u")
                "CalendarEvent/get" -> response("CalendarEvent/get", """{"list":[${stored()}]}""", "g")
                else -> {
                    sets++
                    if (call[1].jsonObject["sendSchedulingMessages"] == JsonPrimitive(true)) {
                        response("CalendarEvent/set", """{"notUpdated":{"ev1":{"type":"forbidden","description":"Calendar scheduling is disabled on this server."}}}""", "s")
                    } else {
                        response("CalendarEvent/set", """{"updated":{"ev1":null}}""", "s")
                    }
                }
            }
        }
        val result = answerInCalendar(InvitationCalendarClient("acc", server::send), invitation(), Rsvp.TENTATIVE, listOf(me), "blob1", now)
        assertFalse(result.replySent)
        assertEquals("The server would not send the answer, so it went by email instead.", result.note)
        assertEquals("The server would not change the event: Calendar scheduling is disabled on this server.", result.detail)
        assertEquals(2, sets)
    }

    @Test
    fun `a calendar that cannot be reached still lets the email go, and says so`() {
        val client = InvitationCalendarClient("acc") { throw JmapError("The server answered HTTP 503 to CalendarEvent/query.") }
        val result = answerInCalendar(client, invitation(), Rsvp.ACCEPT, listOf(me), "blob1", now)
        assertFalse(result.replySent)
        assertNotNull(result.note)
    }

    @Test
    fun `no calendar client means the plain email path and nothing to say`() {
        assertEquals(CalendarAnswer(false), answerInCalendar(null, invitation(), Rsvp.ACCEPT, listOf(me), null, now))
    }

    // ---- what the card says ----

    @Test
    fun `the card says where the meeting stands and offers what fits`() {
        val inCal = calendarMatchOf(stored(status = "accepted"), listOf(me))
        assertEquals(CalendarLine("In your calendar. You accepted.", listOf(CalendarAction.SHOW)), calendarLine(invitation(), inCal))
        assertEquals(emptyList(), calendarLine(invitation(), null).actions)
        assertEquals(
            listOf(CalendarAction.SHOW, CalendarAction.UPDATE),
            calendarLine(invitation(sequence = 3), calendarMatchOf(stored(sequence = 1), listOf(me))).actions,
        )
        assertEquals(
            listOf(CalendarAction.SHOW, CalendarAction.REMOVE),
            calendarLine(invitation(method = "CANCEL"), calendarMatchOf(stored(eventStatus = "cancelled"), listOf(me))).actions,
        )
        assertEquals(
            "Marked cancelled in your calendar.",
            calendarLine(invitation(method = "CANCEL"), calendarMatchOf(stored(eventStatus = "cancelled"), listOf(me))).text,
        )
        val one = EventTime(ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, ZoneId.of("Europe/London")), false, "20261012T090000")
        assertEquals(
            listOf(CalendarAction.SHOW, CalendarAction.REMOVE_ONE),
            calendarLine(invitation(method = "CANCEL", recurrenceId = one), calendarMatchOf(stored(), listOf(me))).actions,
        )
    }

    @Test
    fun `the calendar page opens on the meeting's day in the reader's zone`() {
        val match = assertNotNull(calendarMatchOf(stored(), listOf(me)))
        assertEquals(LocalDate.of(2026, 10, 5), jumpDate(invitation(), match, ZoneId.of("Europe/London")))
        // Nine in the morning in London is still the fourth in Honolulu.
        assertEquals(LocalDate.of(2026, 10, 4), jumpDate(invitation(), match, ZoneId.of("Pacific/Honolulu")))
    }

    @Test
    fun `RECURRENCE-ID is read from the invitation`() {
        val ics = "BEGIN:VCALENDAR\r\nMETHOD:CANCEL\r\nBEGIN:VEVENT\r\nUID:x\r\nRECURRENCE-ID;TZID=Europe/London:20261012T090000\r\n" +
            "DTSTART;TZID=Europe/London:20261012T090000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val parsed = assertNotNull(invitationIn(ics))
        assertEquals("20261012T090000", parsed.recurrenceId?.raw)
        assertNull(assertNotNull(invitationIn(ics.replace("RECURRENCE-ID;TZID=Europe/London:20261012T090000\r\n", ""))).recurrenceId)
    }
}
