package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The calendar's reading, expanding and writing, against JSCalendar objects shaped the way
 * RFC 8984 and the JMAP Calendars draft describe them. There is no live server behind
 * these, so every fixture is written from the specification rather than captured.
 */
class CalendarEventsTest {
    private val london = ZoneId.of("Europe/London")
    private val newYork = ZoneId.of("America/New_York")

    private fun event(json: String): CalendarEvent =
        assertNotNull(calendarEventOf(Json.parseToJsonElement(json).jsonObject))

    private fun at(text: String) = LocalDateTime.parse(text)

    private fun starts(e: CalendarEvent, from: String, until: String, viewer: ZoneId = london) =
        occurrences(e, LocalDate.parse(from), LocalDate.parse(until), viewer).map { it.start }

    @Test
    fun `lengths read and write the RFC 8984 way`() {
        assertEquals(EventLength(0, Duration.ofHours(1)), EventLength.parse("PT1H"))
        assertEquals(EventLength(1), EventLength.parse("P1D"))
        assertEquals(EventLength(14), EventLength.parse("P2W"))
        assertEquals(EventLength(1, Duration.ofMinutes(150)), EventLength.parse("P1DT2H30M"))
        assertNull(EventLength.parse("P"))
        assertNull(EventLength.parse("an hour"))
        assertEquals("PT1H", EventLength(0, Duration.ofHours(1)).text())
        assertEquals("P1DT30M", EventLength(1, Duration.ofMinutes(30)).text())
        assertEquals("P3D", EventLength(3).text())
    }

    @Test
    fun `an ordinary event reads`() {
        val e = event(
            """{"id":"e1","@type":"Event","calendarIds":{"c1":true},"title":"Dentist","start":"2026-09-30T14:00:00",
               "timeZone":"Europe/London","duration":"PT45M","locations":{"a":{"@type":"Location","name":"High Street"}},
               "description":"Bring the form"}""",
        )
        assertEquals("Dentist", e.title)
        assertEquals(setOf("c1"), e.calendarIds)
        assertEquals("High Street", e.location)
        assertEquals(london, e.timeZone)
        assertFalse(e.allDay)
        val only = occurrences(e, LocalDate.parse("2026-09-28"), LocalDate.parse("2026-10-05"), london).single()
        assertEquals(at("2026-09-30T14:00"), only.start)
        assertEquals(at("2026-09-30T14:45"), only.end)
        assertNull(only.recurrenceId)
    }

    @Test
    fun `an event without an id or a start is not placed rather than crashing`() {
        assertNull(calendarEventOf(Json.parseToJsonElement("""{"title":"x","start":"2026-01-01T00:00:00"}""").jsonObject))
        assertNull(calendarEventOf(Json.parseToJsonElement("""{"id":"x","title":"x"}""").jsonObject))
    }

    @Test
    fun `a timed event moves with the reader's time zone`() {
        val e = event("""{"id":"e","start":"2026-09-30T09:00:00","timeZone":"Europe/London","duration":"PT1H"}""")
        assertEquals(listOf(at("2026-09-30T04:00")), starts(e, "2026-09-30", "2026-10-01", newYork))
    }

    @Test
    fun `an all-day event stays on its date wherever it is read`() {
        val e = event("""{"id":"e","start":"2026-10-04T00:00:00","showWithoutTime":true,"duration":"P1D"}""")
        val o = occurrences(e, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-08"), newYork).single()
        assertEquals(at("2026-10-04T00:00"), o.start)
        assertEquals(listOf(LocalDate.parse("2026-10-04")), o.days())
        assertEquals("All day", timeText(o))
    }

    @Test
    fun `a weekly rule on chosen days honours its count`() {
        // Starts on a Monday. Mondays and Wednesdays, six times in all, the start included.
        val e = event(
            """{"id":"e","start":"2026-09-07T10:00:00","timeZone":"Europe/London","duration":"PT1H",
               "recurrenceRules":[{"@type":"RecurrenceRule","frequency":"weekly",
                 "byDay":[{"@type":"NDay","day":"mo"},{"@type":"NDay","day":"we"}],"count":6}]}""",
        )
        assertEquals(
            listOf("2026-09-07", "2026-09-09", "2026-09-14", "2026-09-16", "2026-09-21", "2026-09-23").map { at("${it}T10:00") },
            starts(e, "2026-09-01", "2026-10-31"),
        )
    }

    @Test
    fun `a fortnightly rule skips the weeks between`() {
        val e = event(
            """{"id":"e","start":"2026-09-01T08:00:00","duration":"PT30M",
               "recurrenceRules":[{"frequency":"weekly","interval":2}]}""",
        )
        assertEquals(
            listOf("2026-09-01", "2026-09-15", "2026-09-29").map { at("${it}T08:00") },
            starts(e, "2026-09-01", "2026-10-01"),
        )
    }

    @Test
    fun `until is inclusive`() {
        val e = event(
            """{"id":"e","start":"2026-09-01T09:00:00","duration":"PT1H",
               "recurrenceRules":[{"frequency":"daily","until":"2026-09-03T09:00:00"}]}""",
        )
        assertEquals(3, starts(e, "2026-08-01", "2026-12-01").size)
    }

    @Test
    fun `a monthly rule on the 31st skips the months without one`() {
        val e = event(
            """{"id":"e","start":"2026-01-31T09:00:00","duration":"PT1H","recurrenceRules":[{"frequency":"monthly"}]}""",
        )
        assertEquals(
            listOf("2026-01-31", "2026-03-31", "2026-05-31").map { at("${it}T09:00") },
            starts(e, "2026-01-01", "2026-06-15"),
        )
    }

    @Test
    fun `monthly by weekday finds the second Tuesday and the last Friday`() {
        val second = event(
            """{"id":"a","start":"2026-09-08T19:00:00","duration":"PT2H",
               "recurrenceRules":[{"frequency":"monthly","byDay":[{"day":"tu","nthOfPeriod":2}]}]}""",
        )
        assertEquals(
            listOf("2026-09-08", "2026-10-13", "2026-11-10").map { at("${it}T19:00") },
            starts(second, "2026-09-01", "2026-12-01"),
        )
        val last = event(
            """{"id":"b","start":"2026-09-25T17:00:00","duration":"PT1H",
               "recurrenceRules":[{"frequency":"monthly","byDay":[{"day":"fr","nthOfPeriod":-1}]}]}""",
        )
        assertEquals(
            listOf("2026-09-25", "2026-10-30", "2026-11-27").map { at("${it}T17:00") },
            starts(last, "2026-09-01", "2026-12-01"),
        )
    }

    @Test
    fun `a yearly rule on the 29th of February waits for a leap year`() {
        val e = event(
            """{"id":"e","start":"2024-02-29T00:00:00","showWithoutTime":true,"duration":"P1D",
               "recurrenceRules":[{"frequency":"yearly"}]}""",
        )
        assertEquals(listOf(at("2024-02-29T00:00"), at("2028-02-29T00:00")), starts(e, "2024-01-01", "2029-01-01"))
    }

    @Test
    fun `a daily meeting keeps its wall time across the clocks changing`() {
        // British Summer Time ends on 25 October 2026. Nine o'clock stays nine o'clock.
        val e = event(
            """{"id":"e","start":"2026-10-24T09:00:00","timeZone":"Europe/London","duration":"PT1H",
               "recurrenceRules":[{"frequency":"daily","count":3}]}""",
        )
        assertEquals(
            listOf("2026-10-24", "2026-10-25", "2026-10-26").map { at("${it}T09:00") },
            starts(e, "2026-10-20", "2026-10-30"),
        )
    }

    @Test
    fun `exceptions remove, move and add instances`() {
        val e = event(
            """{"id":"e","title":"Standup","start":"2026-09-07T09:00:00","timeZone":"Europe/London","duration":"PT15M",
               "recurrenceRules":[{"frequency":"daily","count":5}],
               "recurrenceOverrides":{
                 "2026-09-08T09:00:00":{"excluded":true},
                 "2026-09-09T09:00:00":{"start":"2026-09-09T11:30:00","title":"Standup, late"},
                 "2026-09-20T09:00:00":{}
               }}""",
        )
        val found = occurrences(e, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), london)
        assertEquals(
            listOf("2026-09-07T09:00", "2026-09-09T11:30", "2026-09-10T09:00", "2026-09-11T09:00", "2026-09-20T09:00").map(::at),
            found.map { it.start },
        )
        val moved = found[1]
        assertEquals("Standup, late", moved.title)
        assertEquals(at("2026-09-09T11:45"), moved.end)
        // The moved instance is still keyed by the time the rule gave it, which is what an edit writes back to.
        assertEquals(at("2026-09-09T09:00"), moved.recurrenceId)
    }

    @Test
    fun `an override can change a nested property by path`() {
        val e = event(
            """{"id":"e","start":"2026-09-07T09:00:00","duration":"PT1H","locations":{"x":{"@type":"Location","name":"Room 1"}},
               "recurrenceRules":[{"frequency":"weekly","count":2}],
               "recurrenceOverrides":{"2026-09-14T09:00:00":{"locations/x/name":"Room 2"}}}""",
        )
        assertEquals(listOf("Room 1", "Room 2"), occurrences(e, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), london).map { it.location })
    }

    @Test
    fun `an event starting before the window still shows while it runs`() {
        val e = event("""{"id":"e","start":"2026-09-27T00:00:00","showWithoutTime":true,"duration":"P5D"}""")
        val o = occurrences(e, LocalDate.parse("2026-09-29"), LocalDate.parse("2026-09-30"), london).single()
        assertEquals(5, o.days().size)
    }

    @Test
    fun `a rule this file cannot expand is drawn once and says so`() {
        val e = event(
            """{"id":"e","start":"2026-09-01T09:00:00","duration":"PT1H",
               "recurrenceRules":[{"frequency":"monthly","byDay":[{"day":"mo"},{"day":"tu"}],"bySetPosition":[-1]}]}""",
        )
        assertFalse(e.exact)
        assertEquals(listOf(at("2026-09-01T09:00")), starts(e, "2026-09-01", "2026-12-31"))
        assertTrue(repeatSentence(e.rules, e.start).contains("cannot draw"))
    }

    @Test
    fun `a patch with a null removes and a path creates what is missing`() {
        val base = Json.parseToJsonElement("""{"a":1,"b":{"c":2}}""").jsonObject
        val out = patched(base, buildJsonObject { put("a", JsonNull); put("b/d", 3); put("e/f", "g") })
        assertEquals("""{"b":{"c":2,"d":3},"e":{"f":"g"}}""", out.toString())
    }

    @Test
    fun `a new event is written as JSCalendar wants it`() {
        val draft = EventDraft(
            title = " Lunch ",
            calendarId = "c1",
            start = at("2026-09-30T12:30"),
            end = at("2026-09-30T13:45"),
            allDay = false,
            location = "Cafe",
            repeat = SimpleRepeat(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY), count = 4),
            timeZone = london,
        )
        val o = newEventObject(draft, uid = "u1")
        assertEquals("Event", o["@type"]!!.jsonPrimitive.content)
        assertEquals("u1", o["uid"]!!.jsonPrimitive.content)
        assertEquals("Lunch", o["title"]!!.jsonPrimitive.content)
        assertEquals("2026-09-30T12:30:00", o["start"]!!.jsonPrimitive.content)
        assertEquals("Europe/London", o["timeZone"]!!.jsonPrimitive.content)
        assertEquals("PT1H15M", o["duration"]!!.jsonPrimitive.content)
        assertEquals("""{"c1":true}""", o["calendarIds"].toString())
        assertEquals("Cafe", o["locations"]!!.jsonObject.values.single().jsonObject["name"]!!.jsonPrimitive.content)
        val rule = o["recurrenceRules"]!!.jsonArray.single().jsonObject
        assertEquals("weekly", rule["frequency"]!!.jsonPrimitive.content)
        assertEquals(listOf("mo", "we"), rule["byDay"]!!.jsonArray.map { it.jsonObject["day"]!!.jsonPrimitive.content })
        assertEquals("4", rule["count"]!!.jsonPrimitive.content)
        // Read back, it is the same event.
        val back = assertNotNull(calendarEventOf(JsonObject(o + ("id" to JsonPrimitive("e")))))
        assertEquals(SimpleRepeat(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY), count = 4), SimpleRepeat.of(back.rules))
    }

    @Test
    fun `an all-day event is floating and counts its last day in`() {
        val o = newEventObject(
            EventDraft("Holiday", "c1", at("2026-10-05T00:00"), at("2026-10-09T00:00"), allDay = true, repeat = SimpleRepeat()),
        )
        assertEquals("P5D", o["duration"]!!.jsonPrimitive.content)
        assertEquals("true", o["showWithoutTime"]!!.jsonPrimitive.content)
        assertFalse(o.containsKey("timeZone"), "a floating event carries no zone")
        assertFalse(o.containsKey("recurrenceRules"))
    }

    @Test
    fun `an edit keeps the location's other properties and clears it with a null`() {
        val e = event(
            """{"id":"e","start":"2026-09-30T12:00:00","duration":"PT1H",
               "locations":{"loc9":{"@type":"Location","name":"Old","coordinates":"geo:1,2"}}}""",
        )
        val draft = EventDraft("T", "c1", at("2026-09-30T12:00"), at("2026-09-30T13:00"), false, location = "New", timeZone = london)
        val patch = eventPatch(e, draft)
        val loc = patch["locations"]!!.jsonObject["loc9"]!!.jsonObject
        assertEquals("New", loc["name"]!!.jsonPrimitive.content)
        assertEquals("geo:1,2", loc["coordinates"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, eventPatch(e, draft.copy(location = ""))["locations"])
        // A rule the picker could not show is left out of the patch altogether, so it survives.
        assertFalse(eventPatch(e, draft.copy(repeat = null)).containsKey("recurrenceRules"))
        assertEquals(JsonNull, eventPatch(e, draft)["recurrenceRules"])
    }

    @Test
    fun `changing or deleting one instance keeps the other exceptions`() {
        val e = event(
            """{"id":"e","start":"2026-09-07T09:00:00","duration":"PT1H","recurrenceRules":[{"frequency":"daily"}],
               "recurrenceOverrides":{"2026-09-08T09:00:00":{"excluded":true},"2026-09-10T09:00:00":{"title":"Renamed"}}}""",
        )
        val gone = excludedPatch(e, at("2026-09-09T09:00"))["recurrenceOverrides"]!!.jsonObject
        assertEquals(setOf("2026-09-08T09:00:00", "2026-09-09T09:00:00", "2026-09-10T09:00:00"), gone.keys)
        assertEquals("""{"excluded":true}""", gone["2026-09-09T09:00:00"].toString())

        val moved = overridePatch(e, at("2026-09-10T09:00"), buildJsonObject { put("start", "2026-09-10T15:00:00") })
        val tenth = moved["recurrenceOverrides"]!!.jsonObject["2026-09-10T09:00:00"]!!.jsonObject
        assertEquals("Renamed", tenth["title"]!!.jsonPrimitive.content, "moving it must not undo the earlier rename")
        assertEquals("2026-09-10T15:00:00", tenth["start"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an instance change carries only what differs from the series`() {
        val e = event("""{"id":"e","title":"Standup","description":"","start":"2026-09-07T09:00:00","timeZone":"Europe/London","duration":"PT15M","showWithoutTime":false}""")
        val change = instanceChange(
            e,
            EventDraft("Standup", "c1", at("2026-09-09T10:00"), at("2026-09-09T10:15"), false, timeZone = london),
        )
        assertEquals(setOf("start"), change.keys)
    }

    @Test
    fun `the picker reads simple rules and refuses the ones it would mangle`() {
        assertEquals(SimpleRepeat(), SimpleRepeat.of(emptyList()))
        assertEquals(
            SimpleRepeat(Frequency.MONTHLY, interval = 2, until = LocalDate.parse("2027-01-01")),
            SimpleRepeat.of(listOf(RecurrenceRule(Frequency.MONTHLY, interval = 2, until = at("2027-01-01T23:59:59")))),
        )
        assertNull(SimpleRepeat.of(listOf(RecurrenceRule(Frequency.MONTHLY, byDay = listOf(NDay(DayOfWeek.TUESDAY, 2))))))
        assertNull(SimpleRepeat.of(listOf(RecurrenceRule(Frequency.DAILY), RecurrenceRule(Frequency.WEEKLY))))
        val until = SimpleRepeat(Frequency.DAILY, until = LocalDate.parse("2026-12-31")).rule()!!
        assertEquals("2026-12-31T23:59:59", until["until"]!!.jsonPrimitive.content)
        assertNull(SimpleRepeat().rule())
    }

    @Test
    fun `a draft that cannot be saved says why in one sentence`() {
        val ok = EventDraft("Walk", "c1", at("2026-09-30T10:00"), at("2026-09-30T11:00"), false)
        assertNull(draftProblem(ok))
        assertEquals("Give the event a title.", draftProblem(ok.copy(title = " ")))
        assertEquals("The event ends before it starts.", draftProblem(ok.copy(end = at("2026-09-30T09:00"))))
        assertEquals(
            "The repeat ends before the event starts.",
            draftProblem(ok.copy(repeat = SimpleRepeat(Frequency.DAILY, until = LocalDate.parse("2026-09-01")))),
        )
    }

    @Test
    fun `calendars read with colours, visibility and rights`() {
        val c = assertNotNull(
            calendarInfoOf(
                Json.parseToJsonElement(
                    """{"id":"c","name":"Work","color":"#3366ff","isVisible":false,"isDefault":true,
                       "myRights":{"mayReadItems":true,"mayWriteAll":false,"mayWriteOwn":false}}""",
                ).jsonObject,
            ),
        )
        assertEquals("Work", c.name)
        assertEquals("#3366ff", c.colour)
        assertFalse(c.isVisible)
        assertTrue(c.isDefault)
        assertFalse(c.mayWrite)
        val bare = assertNotNull(calendarInfoOf(Json.parseToJsonElement("""{"id":"d"}""").jsonObject))
        assertTrue(bare.isVisible)
        assertTrue(bare.mayWrite, "a server that sends no rights is not read as refusing them")
    }

    /** A pretend server: answers each method by name, and records what it was asked. */
    private class Server(val answer: (String, JsonObject) -> JsonObject?) {
        val asked = mutableListOf<String>()
        fun send(calls: List<JsonArray>): List<JsonArray> = calls.map { call ->
            val name = call[0].jsonPrimitive.content
            asked += name
            val args = call[1].jsonObject
            val result = answer(name, args) ?: throw JmapError("The server refused the request: unknownMethod")
            buildJsonArray { add(JsonPrimitive(name)); add(result); add(call[2]) }
        }
    }

    @Test
    fun `events come from a query and a get in one round trip`() {
        val server = Server { name, args ->
            when (name) {
                "CalendarEvent/query" -> {
                    assertEquals("2026-09-01T00:00:00Z", args["filter"]!!.jsonObject["after"]!!.jsonPrimitive.content)
                    buildJsonObject { put("ids", buildJsonArray { add(JsonPrimitive("e1")) }) }
                }
                "CalendarEvent/get" -> {
                    assertEquals("q", args["#ids"]!!.jsonObject["resultOf"]!!.jsonPrimitive.content)
                    Json.parseToJsonElement("""{"list":[{"id":"e1","start":"2026-09-02T10:00:00","duration":"PT1H"}]}""").jsonObject
                }
                else -> null
            }
        }
        val client = CalendarClient("acc", server::send)
        assertEquals(listOf("e1"), client.events("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z").map { it.id })
        assertEquals(listOf("CalendarEvent/query", "CalendarEvent/get"), server.asked)
    }

    @Test
    fun `a refused query falls back to reading the whole calendar`() {
        var queried = false
        val client = CalendarClient("acc") { calls ->
            val name = calls.first()[0].jsonPrimitive.content
            if (name == "CalendarEvent/query") { queried = true; throw JmapError("The server refused the request: serverUnavailable") }
            assertEquals(JsonNull, calls.single()[1].jsonObject["ids"])
            listOf(buildJsonArray { add(JsonPrimitive(name)); add(Json.parseToJsonElement("""{"list":[{"id":"x","start":"2026-01-01T00:00:00"}]}""")); add(JsonPrimitive("g")) })
        }
        assertEquals(1, client.events("a", "b").size)
        assertTrue(queried)
    }

    @Test
    fun `a refused save says what the server said`() {
        val client = CalendarClient("acc") { calls ->
            listOf(
                buildJsonArray {
                    add(JsonPrimitive("CalendarEvent/set"))
                    add(Json.parseToJsonElement("""{"notCreated":{"new":{"type":"invalidProperties","description":"start is required","properties":["start"]}}}"""))
                    add(JsonPrimitive("create"))
                },
            )
        }
        val e = assertFailsWith<JmapError> { client.create(buildJsonObject { put("title", "x") }) }
        assertEquals("The server would not create the event: start is required (start).", e.message)
    }

    @Test
    fun `a failed read is a sentence, not a stack trace`() {
        val client = CalendarClient("acc") { throw JmapError("The server answered HTTP 500 to Calendar/get.") }
        val e = assertFailsWith<JmapError> { client.calendars() }
        assertEquals("Could not read your calendars: The server answered HTTP 500 to Calendar/get.", e.message)
    }
}
