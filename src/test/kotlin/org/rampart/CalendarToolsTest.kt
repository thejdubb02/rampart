package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rook's agenda and propose_event tools, against a calendar that is a function. The point
 * of most of these is what the tools do not do: agenda only reads, and propose_event only
 * ever makes a card.
 */
class CalendarToolsTest {
    private val london = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 9, 29)

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun event(json: String) = assertNotNull(calendarEventOf(args(json)))

    private val dentist = event(
        """{"id":"e1","title":"Dentist","start":"2026-10-03T14:30:00","timeZone":"Europe/London","duration":"PT45M",
            "locations":{"1":{"@type":"Location","name":"12 High Street"}}}""",
    )
    private val holiday = event("""{"id":"e2","title":"Holiday","start":"2026-10-03T00:00:00","showWithoutTime":true,"duration":"P1D"}""")

    private val asked = mutableListOf<LocalDate>()

    private fun tools(unavailable: String? = null, fail: Boolean = false) = CalendarTools(
        unavailable = unavailable,
        day = { date ->
            asked += date
            if (fail) throw JmapError("the server said no.")
            expandAll(listOf(dentist, holiday), date, date.plusDays(1), london)
        },
        zone = london,
        today = today,
    )

    @Test
    fun `agenda reads one day, all-day first`() {
        val answer = tools().run(Asked("agenda", args("""{"date":"2026-10-03"}""")))
        assertEquals(
            "On Saturday 3 October 2026:\nall day  Holiday\n14:30 to 15:15  Dentist at 12 High Street",
            answer,
        )
        assertEquals(listOf(LocalDate.of(2026, 10, 3)), asked)
    }

    @Test
    fun `agenda understands today and tomorrow, and says when a day is empty`() {
        assertEquals("Nothing is on the calendar on Tuesday 29 September 2026.", tools().run(Asked("agenda", args("{}"))))
        assertEquals("Nothing is on the calendar on Wednesday 30 September 2026.", tools().run(Asked("agenda", args("""{"date":"tomorrow"}"""))))
    }

    @Test
    fun `agenda refuses a date it cannot read, and reports a server failure as a sentence`() {
        assertTrue(tools().run(Asked("agenda", args("""{"date":"next Tuesday"}""")))!!.contains("year first"))
        assertTrue(asked.isEmpty())
        assertEquals("Could not read the calendar: the server said no.", tools(fail = true).run(Asked("agenda", args("""{"date":"2026-10-03"}"""))))
    }

    @Test
    fun `propose_event makes a card and writes nothing`() {
        val t = tools()
        val answer = t.run(Asked("propose_event", args("""{"title":"Lunch","start":"2026-10-05T12:30","end":"2026-10-05T13:30","timeZone":"Europe/London"}""")))!!
        assertTrue(answer.contains("Nothing is in the calendar until the person checks it and presses Save"))
        assertEquals(1, t.proposed.size)
        assertEquals("12:30", t.proposed.single().startTime)
        // The day reader is the only way to the calendar here, and proposing never reads it either.
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `propose_event without a start makes no card`() {
        val t = tools()
        val answer = t.run(Asked("propose_event", args("""{"title":"Lunch"}""")))!!
        assertTrue(answer.startsWith("No card was made."))
        assertTrue(t.proposed.isEmpty())
    }

    @Test
    fun `one turn may make only a few cards`() {
        val t = tools()
        repeat(MOST_PROPOSED + 2) {
            t.run(Asked("propose_event", args("""{"title":"Lunch $it","start":"2026-10-05T12:30"}""")))
        }
        assertEquals(MOST_PROPOSED, t.proposed.size)
    }

    @Test
    fun `an account without a calendar answers every calendar tool with the reason`() {
        val reason = "IMAP carries mail only, so Home has no calendar Rampart can read or add to."
        val t = tools(unavailable = reason)
        assertEquals(reason, t.run(Asked("agenda", args("{}"))))
        assertEquals(reason, t.run(Asked("propose_event", args("""{"title":"x","start":"2026-10-05T12:30"}"""))))
        assertTrue(t.proposed.isEmpty())
        assertTrue(asked.isEmpty())
        assertNotNull(noCalendarBecause(null, "Home"))
    }

    @Test
    fun `other tools are left for the mail and settings tools`() {
        assertNull(tools().run(Asked("archive", args("{}"))))
        assertNull(tools().run(Asked("change_setting", args("{}"))))
    }

    @Test
    fun `the prompt declares both tools the way the others are declared`() {
        val prompt = calendarPrompt(today, london)
        assertTrue("{\"tool\":\"agenda\"" in prompt)
        assertTrue("{\"tool\":\"propose_event\"" in prompt)
        assertTrue("Tuesday 2026-09-29" in prompt)
        assertTrue("Europe/London" in prompt)
    }
}
