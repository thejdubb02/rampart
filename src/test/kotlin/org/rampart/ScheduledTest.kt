package org.rampart

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A scheduled send is a draft plus a time. The draft is what survives a lost
 * file, and the time is what decides whether this pass should send it.
 */
class ScheduledTest {
    private var previous: String? = null
    private var hadProperty = false

    /**
     * Its own directory, put back afterwards. The store writes beside the real
     * accounts file, and a test that left that property set would aim every
     * later test at a folder that is about to be deleted.
     */
    @BeforeTest
    fun scratch() {
        hadProperty = System.getProperties().containsKey("rampart.config.dir")
        previous = System.getProperty("rampart.config.dir")
        val dir = java.nio.file.Files.createTempDirectory("rampart-scheduled")
        dir.toFile().deleteOnExit()
        System.setProperty("rampart.config.dir", dir.toString())
    }

    @AfterTest
    fun putBack() {
        if (hadProperty && previous != null) System.setProperty("rampart.config.dir", previous)
        else System.clearProperty("rampart.config.dir")
    }

    private val zone = ZoneOffset.UTC
    private val afternoon = ZonedDateTime.of(2026, 9, 22, 15, 30, 0, 0, zone)

    @Test
    fun `an hour from now keeps the minutes`() {
        assertEquals(ZonedDateTime.of(2026, 9, 22, 16, 30, 0, 0, zone), inOneHour(afternoon))
    }

    @Test
    fun `an hour from now can cross midnight`() {
        val late = ZonedDateTime.of(2026, 9, 22, 23, 15, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 9, 23, 0, 15, 0, 0, zone), inOneHour(late))
    }

    @Test
    fun `this evening is 18 00 while that is still ahead`() {
        assertEquals(ZonedDateTime.of(2026, 9, 22, 18, 0, 0, 0, zone), thisEvening(afternoon))
    }

    @Test
    fun `18 00 exactly is still this evening`() {
        val six = ZonedDateTime.of(2026, 9, 22, 18, 0, 0, 0, zone)
        assertEquals(six, thisEvening(six))
    }

    @Test
    fun `past 18 00 this evening means tomorrow morning`() {
        val night = ZonedDateTime.of(2026, 9, 22, 18, 1, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 9, 23, 9, 0, 0, 0, zone), thisEvening(night))
    }

    @Test
    fun `tomorrow morning is 09 00 the next day`() {
        assertEquals(ZonedDateTime.of(2026, 9, 23, 9, 0, 0, 0, zone), tomorrowMorning(afternoon))
    }

    @Test
    fun `a typed time in the future is accepted`() {
        val parsed = parseSchedule("2026-09-23", "09:15", afternoon)
        val at = (parsed as ScheduleWhen.At).millis
        assertEquals(
            ZonedDateTime.of(2026, 9, 23, 9, 15, 0, 0, zone).toInstant().toEpochMilli(),
            at,
        )
    }

    @Test
    fun `a typed time that has passed is refused`() {
        val parsed = parseSchedule("2026-09-22", "15:00", afternoon)
        assertEquals(ScheduleWhen.Problem("That time has already passed."), parsed)
    }

    @Test
    fun `the exact current minute is refused`() {
        val parsed = parseSchedule("2026-09-22", "15:30", afternoon)
        assertEquals(ScheduleWhen.Problem("That time has already passed."), parsed)
    }

    @Test
    fun `a date or time in the wrong shape is refused`() {
        val message = "Enter the date as yyyy-MM-dd and the time as HH:mm."
        assertEquals(ScheduleWhen.Problem(message), parseSchedule("22-09-2026", "09:00", afternoon))
        assertEquals(ScheduleWhen.Problem(message), parseSchedule("2026-09-22", "9:00", afternoon))
        assertEquals(ScheduleWhen.Problem(message), parseSchedule("2026-02-31", "09:00", afternoon))
        assertEquals(ScheduleWhen.Problem(message), parseSchedule("2026-09-22", "25:00", afternoon))
        assertEquals(ScheduleWhen.Problem(message), parseSchedule("", "", afternoon))
    }

    @Test
    fun `a scheduled send survives a trip through json`() {
        val item = sample("s1", "d1", 1_700_000_000_000)
        val back = Json.decodeFromString<ScheduledSend>(Json.encodeToString(item))
        assertEquals(item, back)
        assertEquals("notes.pdf", back.draft.attachments.single().name)
        assertEquals(true, back.draft.attachments.single().inline)
    }

    @Test
    fun `a draft with only a from address keeps its defaults`() {
        val decoded = Json.decodeFromString<Draft>("""{"from":"me@example.com"}""")
        assertEquals("me@example.com", decoded.from)
        assertEquals("", decoded.to)
        assertEquals(emptyList(), decoded.attachments)
        assertEquals(false, decoded.tracked)
        assertNull(decoded.messageId)
    }

    @Test
    fun `the list round trips through the file`() {
        val item = sample("s1", "d1", 50)
        ScheduledSends.schedule(item)
        assertEquals(listOf(item), ScheduledSends.pending())
    }

    @Test
    fun `scheduling the same draft again replaces the earlier time`() {
        ScheduledSends.schedule(sample("s1", "d1", 10))
        ScheduledSends.schedule(sample("s2", "d1", 20))
        ScheduledSends.schedule(sample("s3", "d2", 30))
        val left = ScheduledSends.pending()
        assertEquals(listOf("s2", "s3"), left.map { it.id })
        assertEquals(20, left.first { it.draftId == "d1" }.sendAt)
    }

    @Test
    fun `cancel drops one id and leaves the rest`() {
        ScheduledSends.schedule(sample("s1", "d1", 10))
        ScheduledSends.schedule(sample("s2", "d2", 20))
        ScheduledSends.cancel("s1")
        assertEquals(listOf("s2"), ScheduledSends.pending().map { it.id })
        ScheduledSends.cancel("missing")
        assertEquals(listOf("s2"), ScheduledSends.pending().map { it.id })
    }

    @Test
    fun `due includes a time that is exactly now and nothing later`() {
        ScheduledSends.schedule(sample("past", "d1", 1_000))
        ScheduledSends.schedule(sample("now", "d2", 5_000))
        ScheduledSends.schedule(sample("later", "d3", 5_001))
        assertEquals(listOf("past", "now"), ScheduledSends.due(5_000).map { it.id })
    }

    @Test
    fun `a missing file is an empty list`() {
        assertTrue(ScheduledSends.pending().isEmpty())
        assertTrue(ScheduledSends.due().isEmpty())
    }

    private fun sample(id: String, draftId: String, sendAt: Long) = ScheduledSend(
        id = id,
        account = "me@example.com@mail.example",
        draftId = draftId,
        identityEmail = "me@example.com",
        draft = Draft(
            from = "me@example.com",
            to = "you@example.com",
            cc = "cc@example.com",
            subject = "Hello",
            body = "The notes are attached.",
            inReplyTo = "<a@b>",
            references = listOf("<a@b>"),
            attachments = listOf(
                Attachment("blob1", "notes.pdf", "application/pdf", 12L, cid = "pic", inline = true),
            ),
            textSignature = "-- \nMe",
            htmlSignature = "<p>Me</p>",
            replying = true,
            receipt = true,
            tracked = true,
        ),
        sendAt = sendAt,
    )
}
