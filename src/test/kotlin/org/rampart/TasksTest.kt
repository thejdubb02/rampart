package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val LONDON: ZoneId = ZoneId.of("Europe/London")
private val STAMP: Instant = Instant.parse("2026-09-29T08:15:00Z")
private val LINK = TaskLink("abc.123@mail.example.org", "Invoice 4471", "Pat Plumber", "2026-09-28")

private fun draft(
    title: String = "Pay the plumber",
    date: LocalDate? = LocalDate.of(2026, 10, 3),
    time: LocalTime? = null,
    notes: String = "",
    link: TaskLink? = LINK,
) = TaskDraft("uid-1", title, date, time, LONDON, notes, link)

/** The object's content lines, unfolded, with the CRLF taken off. */
private fun unfolded(ics: String): List<String> =
    ics.replace("\r\n ", "").split("\r\n").filter { it.isNotEmpty() }

class TasksTest {

    // ---- the model's answer -------------------------------------------------------------

    @Test
    fun `an answer becomes a form with the link to the message`() {
        val read = extractedTask("""{"title":"Pay the plumber","due":"2026-10-03","notes":"Invoice 4471."}""", LONDON, LINK)
        val form = assertIs<TaskExtracted.Found>(read).form
        assertEquals("Pay the plumber", form.title)
        assertEquals("2026-10-03", form.dueDate)
        assertEquals("", form.dueTime)
        assertEquals("Europe/London", form.timeZone)
        assertEquals(LINK, form.link)
        assertTrue(form.checks.isEmpty())
    }

    @Test
    fun `a time with a Z is moved into the reader's zone`() {
        val form = assertIs<TaskExtracted.Found>(extractedTask("""{"title":"Call back","due":"2026-10-03T16:00Z"}""", LONDON, null)).form
        assertEquals("2026-10-03", form.dueDate)
        assertEquals("17:00", form.dueTime)
    }

    @Test
    fun `nothing to do, and an answer that is not JSON, are both sentences`() {
        assertIs<TaskExtracted.Failed>(extractedTask("""{"none":"It is a newsletter."}""", LONDON, null))
        assertIs<TaskExtracted.Failed>(extractedTask("Sure! Here is your task: pay it.", LONDON, null))
    }

    @Test
    fun `a due date that cannot be read leaves the task undated with a note`() {
        val form = assertIs<TaskExtracted.Found>(extractedTask("""{"title":"Reply","due":"next Friday"}""", LONDON, null)).form
        assertEquals("", form.dueDate)
        assertEquals(1, form.checks.size)
    }

    @Test
    fun `dashes and ellipses from the model are taken out of the title`() {
        val form = assertIs<TaskExtracted.Found>(extractedTask("{\"title\":\"Pay \u2014 today\u2026\"}", LONDON, null)).form
        assertFalse(form.title.contains('\u2014') || form.title.contains('\u2026'))
    }

    // ---- the card ------------------------------------------------------------------------

    @Test
    fun `the form is checked in the order it is read`() {
        val ok = TaskForm("Pay", "2026-10-03", "17:00", "Europe/London")
        assertIs<TaskCheck.Ready>(taskDraft(ok))
        assertEquals("Give the task a title.", (taskDraft(ok.copy(title = " ")) as TaskCheck.Refused).reason)
        assertIs<TaskCheck.Refused>(taskDraft(ok.copy(dueDate = "3 Oct")))
        assertIs<TaskCheck.Refused>(taskDraft(ok.copy(dueDate = "", dueTime = "17:00")))
        assertIs<TaskCheck.Refused>(taskDraft(ok.copy(dueTime = "5pm")))
        assertIs<TaskCheck.Refused>(taskDraft(ok.copy(timeZone = "BST")))
        val undated = assertIs<TaskCheck.Ready>(taskDraft(ok.copy(dueDate = "", dueTime = ""))).draft
        assertNull(undated.dueDate)
    }

    // ---- JMAP Tasks ----------------------------------------------------------------------

    @Test
    fun `a task due on a day is floating midnight shown without a time`() {
        val o = jmapTask(draft(notes = "Invoice 4471."), "list-1")
        assertEquals("Task", o["@type"]!!.jsonPrimitive.content)
        assertEquals("uid-1", o["uid"]!!.jsonPrimitive.content)
        assertEquals("list-1", o["taskListId"]!!.jsonPrimitive.content)
        assertEquals("2026-10-03T00:00:00", o["due"]!!.jsonPrimitive.content)
        assertEquals("true", o["showWithoutTime"]!!.jsonPrimitive.content)
        assertNull(o["timeZone"])
        assertEquals("needs-action", o["progress"]!!.jsonPrimitive.content)
        val link = o["links"]!!.jsonObject["mail"]!!.jsonObject
        assertEquals("mid:abc.123@mail.example.org", link["href"]!!.jsonPrimitive.content)
        val description = o["description"]!!.jsonPrimitive.content
        assertTrue(description.startsWith("Invoice 4471."))
        assertTrue("Invoice 4471\" from Pat Plumber" in description)
    }

    @Test
    fun `a timed task carries its zone and no showWithoutTime`() {
        val o = jmapTask(draft(time = LocalTime.of(17, 0), link = null), "l")
        assertEquals("2026-10-03T17:00:00", o["due"]!!.jsonPrimitive.content)
        assertEquals("Europe/London", o["timeZone"]!!.jsonPrimitive.content)
        assertNull(o["showWithoutTime"])
        assertNull(o["links"])
        assertNull(o["description"])
    }

    @Test
    fun `an undated task has no due at all`() {
        val o = jmapTask(draft(date = null), "l")
        assertNull(o["due"])
        assertNull(o["showWithoutTime"])
    }

    @Test
    fun `the created id is read, and a refusal is a sentence`() {
        val created = Json.parseToJsonElement("""["Task/set",{"created":{"t":{"id":"T9"}}},"s"]""").jsonArray
        assertEquals("T9", createdTaskId(created))
        val refused = Json.parseToJsonElement(
            """["Task/set",{"notCreated":{"t":{"type":"invalidProperties","description":"Bad due."}}},"s"]""",
        ).jsonArray
        val e = assertFailsWith<JmapError> { createdTaskId(refused) }
        assertEquals("The server would not accept the task: Bad due.", e.message)
    }

    @Test
    fun `the create call names Task set with the task under t`() {
        val call = taskCreateCall("acc", draft(), "list-1")
        assertEquals("Task/set", call[0].jsonPrimitive.content)
        val args = call[1].jsonObject
        assertEquals("acc", args["accountId"]!!.jsonPrimitive.content)
        assertEquals("Pay the plumber", args["create"]!!.jsonObject["t"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    }

    // ---- iCalendar -----------------------------------------------------------------------

    @Test
    fun `every line ends in CRLF and there is no bare line feed`() {
        val ics = vtodo(draft(notes = "Line one\nLine two"), STAMP)
        assertTrue(ics.endsWith("\r\n"))
        assertFalse(Regex("(?<!\r)\n").containsMatchIn(ics), "a line feed without a carriage return")
        val lines = unfolded(ics)
        assertEquals("BEGIN:VCALENDAR", lines.first())
        assertEquals("END:VCALENDAR", lines.last())
        assertTrue("VERSION:2.0" in lines)
        assertTrue(lines.any { it.startsWith("PRODID:") })
        assertTrue("BEGIN:VTODO" in lines && "END:VTODO" in lines)
        assertTrue("UID:uid-1" in lines)
        assertTrue("DTSTAMP:20260929T081500Z" in lines)
        assertTrue("STATUS:NEEDS-ACTION" in lines)
    }

    @Test
    fun `text is escaped as RFC 5545 section 3_3_11 says`() {
        assertEquals("a\\, b\\; c\\\\d\\ne", icalText("a, b; c\\d\ne"))
        assertEquals("tab\tkept", icalText("tab\tkept"))
        assertEquals("nobell", icalText("no\u0007bell"))
        assertEquals("crlf\\nand\\ncr", icalText("crlf\r\nand\rcr"))
        val lines = unfolded(vtodo(draft(title = "Call Anna, then Bob; bring \\docs"), STAMP))
        assertTrue("SUMMARY:Call Anna\\, then Bob\\; bring \\\\docs" in lines)
    }

    @Test
    fun `escaped text reads back as it was written`() {
        val title = "Call Anna, then Bob; bring \\docs"
        val back = todosIn(vtodo(draft(title = title), STAMP), LONDON).single()
        assertEquals(title, back.title)
        assertEquals("uid-1", back.uid)
        assertFalse(back.done)
    }

    @Test
    fun `long lines are folded at 75 octets and unfold to the original`() {
        val notes = "word ".repeat(60).trim()
        val ics = vtodo(draft(notes = notes), STAMP)
        ics.split("\r\n").forEach { line ->
            assertTrue(line.toByteArray(Charsets.UTF_8).size <= 75, "too long: $line")
        }
        val description = unfolded(ics).single { it.startsWith("DESCRIPTION:") }
        assertTrue(description.startsWith("DESCRIPTION:" + notes))
    }

    @Test
    fun `folding never splits a character that takes several octets`() {
        val line = "SUMMARY:" + "é中😀".repeat(30)
        val folded = fold(line)
        folded.split("\r\n").forEach { physical ->
            assertTrue(physical.toByteArray(Charsets.UTF_8).size <= 75)
            // A split surrogate pair or UTF-8 sequence would not survive a round trip.
            assertEquals(physical, String(physical.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
        assertEquals(line, folded.replace("\r\n ", ""))
        assertEquals("SHORT:1", fold("SHORT:1"))
    }

    @Test
    fun `a day is a DATE and a time is written in UTC`() {
        val day = unfolded(vtodo(draft(), STAMP))
        assertTrue("DUE;VALUE=DATE:20261003" in day)
        // 17:00 in London on 3 October is British Summer Time, an hour ahead of UTC.
        val timed = unfolded(vtodo(draft(time = LocalTime.of(17, 0)), STAMP))
        assertTrue("DUE:20261003T160000Z" in timed)
        val undated = unfolded(vtodo(draft(date = null), STAMP))
        assertTrue(undated.none { it.startsWith("DUE") })
    }

    @Test
    fun `the link back is a mid URL with anything unsafe percent encoded`() {
        val odd = LINK.copy(messageId = "<a b/c%d@example.org>")
        val lines = unfolded(vtodo(draft(link = odd), STAMP))
        assertTrue("URL:mid:a%20b%2Fc%25d@example.org" in lines)
        assertNull(LINK.copy(messageId = "").href)
        assertTrue(lines.single { it.startsWith("DESCRIPTION:") }.contains("From the message"))
    }

    @Test
    fun `todos are read with their due dates and done ones are marked`() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:1\r\nSUMMARY:First\r\nDUE:20261003T160000Z\r\nEND:VTODO\r\n" +
            "BEGIN:VTODO\r\nUID:2\r\nSUMMARY:Sec\r\n ond\r\nSTATUS:COMPLETED\r\nEND:VTODO\r\n" +
            "BEGIN:VTODO\r\nUID:3\r\nSUMMARY;LANGUAGE=en:Third\r\nDUE;VALUE=DATE:20261001\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        val items = todosIn(ics, LONDON)
        assertEquals(listOf("First", "Second", "Third"), items.map { it.title })
        assertEquals("2026-10-03 17:00", items[0].due)
        assertTrue(items[1].done)
        assertEquals("2026-10-01", items[2].due)
        assertEquals(listOf("Third", "First"), sortedTasks(items.filter { !it.done }).map { it.title })
    }

    // ---- CalDAV --------------------------------------------------------------------------

    private val propfind = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:A="urn:ietf:params:xml:ns:caldav">
          <D:response><D:href>/dav/cal/pat%40example.org/</D:href>
            <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response><D:href>/dav/cal/pat%40example.org/default/</D:href>
            <D:propstat><D:prop><D:resourcetype><D:collection/><A:calendar/></D:resourcetype><D:displayname>Pat's calendar</D:displayname>
              <A:supported-calendar-component-set><A:comp name="VEVENT"/><A:comp name="VTODO"/></A:supported-calendar-component-set></D:prop>
              <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response><D:href>/dav/cal/pat%40example.org/events/</D:href>
            <D:propstat><D:prop><D:resourcetype><D:collection/><A:calendar/></D:resourcetype>
              <A:supported-calendar-component-set><A:comp name="VEVENT"/></A:supported-calendar-component-set></D:prop>
              <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response><D:href>https://mail.example.org/dav/cal/pat%40example.org/work/</D:href>
            <D:propstat><D:prop><D:resourcetype><D:collection/><A:calendar/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    @Test
    fun `only calendars that take tasks are offered, with the default one marked`() {
        val lists = taskCalendarsIn(propfind)
        assertEquals(listOf("Pat's calendar", "work"), lists.map { it.name })
        assertEquals("/dav/cal/pat%40example.org/default/", lists[0].id)
        assertTrue(lists[0].isDefault)
        assertEquals("/dav/cal/pat%40example.org/work/", lists[1].id)
        assertEquals("Pat's calendar", defaultList(lists)?.name)
    }

    @Test
    fun `a reply that declares a DTD is refused rather than expanded`() {
        val hostile = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:">&x;</d:multistatus>"""
        assertFailsWith<JmapError> { taskCalendarsIn(hostile) }
    }

    @Test
    fun `saving is one PUT of the VTODO that can never overwrite`() {
        val sent = mutableListOf<Triple<String, String, Map<String, String>>>()
        var put = ""
        val dav = object : DavTransport {
            override val user = "pat@example.org"
            override fun send(method: String, path: String, body: String?, headers: Map<String, String>): DavReply {
                sent += Triple(method, path, headers)
                if (method == "PUT") put = body.orEmpty()
                return if (method == "PROPFIND") DavReply(207, propfind) else DavReply(201, "")
            }
        }
        val tasks = CalDavTasks(dav) { STAMP }
        val list = defaultList(tasks.lists())!!
        val path = tasks.create(draft(), list.id)
        assertEquals("/dav/cal/pat@example.org/", sent[0].second)
        assertEquals("1", sent[0].third["Depth"])
        assertEquals("PUT", sent[1].first)
        assertEquals("/dav/cal/pat%40example.org/default/uid-1.ics", path)
        assertEquals("*", sent[1].third["If-None-Match"])
        assertTrue(sent[1].third["Content-Type"]!!.startsWith("text/calendar"))
        assertTrue(put.contains("SUMMARY:Pay the plumber\r\n"))
    }

    @Test
    fun `a refused PUT is a sentence`() {
        val dav = object : DavTransport {
            override val user = "pat"
            override fun send(method: String, path: String, body: String?, headers: Map<String, String>) = DavReply(403, "")
        }
        val e = assertFailsWith<JmapError> { CalDavTasks(dav).create(draft(), "/dav/cal/pat/default/") }
        assertEquals("This account is not allowed to save the task on its server.", e.message)
    }

    @Test
    fun `open tasks are read from every calendar and done ones left out`() {
        val report = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:A="urn:ietf:params:xml:ns:caldav">
              <D:response><D:href>/dav/cal/pat/default/1.ics</D:href><D:propstat><D:prop>
                <A:calendar-data>BEGIN:VCALENDAR&#13;
            BEGIN:VTODO&#13;
            UID:1&#13;
            SUMMARY:Open one&#13;
            END:VTODO&#13;
            BEGIN:VTODO&#13;
            UID:2&#13;
            SUMMARY:Done one&#13;
            STATUS:COMPLETED&#13;
            END:VTODO&#13;
            END:VCALENDAR&#13;
            </A:calendar-data></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
            </D:multistatus>
        """.trimIndent()
        val dav = object : DavTransport {
            override val user = "pat"
            override fun send(method: String, path: String, body: String?, headers: Map<String, String>) =
                if (method == "PROPFIND") DavReply(207, propfind) else DavReply(207, report)
        }
        val open = CalDavTasks(dav).open(LONDON)
        assertEquals(listOf("Open one"), open.map { it.title })
    }

    @Test
    fun `a login name is one path segment`() {
        assertEquals("/dav/cal/pat@example.org/", calendarHome("pat@example.org"))
        assertEquals("/dav/cal/a%2Fb%20c/", calendarHome("a/b c"))
    }

    // ---- Rook's tool ---------------------------------------------------------------------

    @Test
    fun `propose_task makes a card and never more than three a turn`() {
        val tools = TaskTools(null, LONDON, LINK)
        val args = Json.parseToJsonElement("""{"title":"Send the lease","due":"2026-10-03","fromOpenMessage":true}""").jsonObject
        val said = tools.run(Asked("propose_task", args))!!
        assertTrue(said.contains("Nothing is saved until"))
        assertEquals(LINK, tools.proposed.single().link)
        repeat(3) { tools.run(Asked("propose_task", args)) }
        assertEquals(MOST_TASKS_PROPOSED, tools.proposed.size)
        assertNull(tools.run(Asked("agenda", JsonObject(emptyMap()))))
    }

    @Test
    fun `without fromOpenMessage there is no link, and an unavailable account says why`() {
        val tools = TaskTools(null, LONDON, LINK)
        tools.run(Asked("propose_task", JsonObject(mapOf("title" to JsonPrimitive("Call Anna")))))
        assertNull(tools.proposed.single().link)
        val off = TaskTools("IMAP carries mail only.", LONDON, null)
        assertEquals("IMAP carries mail only.", off.run(Asked("propose_task", JsonObject(emptyMap()))))
        assertTrue(off.proposed.isEmpty())
    }

    @Test
    fun `a Task get answer is listed with done ones marked`() {
        val answer = Json.parseToJsonElement(
            """["Task/get",{"list":[
                {"id":"a","uid":"u1","title":"Pay","due":"2026-10-03T00:00:00","showWithoutTime":true,"progress":"needs-action"},
                {"id":"b","title":"Call","due":"2026-10-04T09:30:00","progress":"completed"}
            ]},"g"]""",
        ) as JsonArray
        val items = jmapTaskItems(answer)
        assertEquals("2026-10-03", items[0].due)
        assertEquals("2026-10-04 09:30", items[1].due)
        assertTrue(items[1].done)
        assertEquals("b", items[1].uid)
    }
}
