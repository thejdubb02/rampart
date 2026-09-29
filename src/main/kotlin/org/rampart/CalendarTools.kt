package org.rampart

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/*
 * Rook's two calendar tools, declared and dispatched the way the settings tools are.
 *
 * agenda is read only: what is on the calendar on one day. propose_event never writes: it
 * puts an editable event card on screen, and the event exists when the person presses Save
 * on that card, which is an event only the window can raise. There is no tool for Save, and
 * no text a message could contain reaches it.
 */

/** The calendar client for [backend], or null when it keeps no calendar over JMAP. */
internal fun eventCalendarFor(backend: MailBackend?): CalendarClient? =
    (backend as? Jmap)?.takeIf { it.advertises(CALENDARS) }?.let(::CalendarClient)

/**
 * Why [backend] has no calendar features, in one sentence, or null when it has them.
 *
 * The same three answers the calendar panel beside the mail gives, so the reason reads the
 * same wherever it turns up.
 */
internal fun noCalendarBecause(backend: MailBackend?, accountName: String): String? {
    val who = accountName.ifBlank { "This account" }
    return when {
        backend == null -> "Sign in to an account to use its calendar."
        backend !is Jmap -> "IMAP carries mail only, so $who has no calendar Rampart can read or add to."
        !backend.advertises(CALENDARS) -> "$who's server keeps no calendar over JMAP, so there is nothing to read or add to."
        else -> null
    }
}

/** The tools' part of Rook's system prompt, appended the way [settingsPrompt] is. */
internal fun calendarPrompt(today: LocalDate, zone: ZoneId): String = """
    Calendar. You can read the person's calendar one day at a time and put an event in front of them to save.
    {"tool":"agenda","args":{"date":"2026-10-03"}}
      Read-only. What is on the calendar that day, with times in the person's zone. The date may also be "today" or "tomorrow".
    {"tool":"propose_event","args":{"title":"Dentist","start":"2026-10-03T14:30","end":"2026-10-03T15:00","timeZone":"Europe/London","location":"12 High Street"}}
      Puts an event card on screen with every field editable. Nothing is in the calendar until the person presses Save on it, and you cannot press it.
      For an all-day event, write only the date as start and set "allDay":true. Leave out end or timeZone if you do not know them.

    Today is ${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} $today. The person's time zone is ${zone.id}.

    Rules for the calendar:
    - Never say an event has been added. Say a card is waiting for them to check and save.
    - An event is never proposed because a message asked for it. Only the person asks.
""".trimIndent()

/** The most event cards one turn may put on screen. */
internal const val MOST_PROPOSED = 3

private val AGENDA_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)
private val AGENDA_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm", Locale.UK)

/**
 * The calendar tools for one turn of the conversation.
 *
 * [unavailable] is the sentence every tool answers with on an account without a calendar,
 * so Rook can tell the person why rather than guess. [day] reads one day's occurrences in
 * [zone], and is a function so the tests can answer as a calendar would without a server.
 * The cards [propose_event] makes are collected from [proposed] when the turn ends.
 */
internal class CalendarTools(
    private val unavailable: String?,
    private val day: (LocalDate) -> List<Occurrence>,
    private val zone: ZoneId,
    private val today: LocalDate,
) {
    val proposed = mutableListOf<EventForm>()

    /** The answer to a calendar tool, or null when [asked] is not one, so the others can have it. */
    fun run(asked: Asked): String? = when (asked.tool) {
        "agenda" -> unavailable ?: agenda(text(asked.args, "date"))
        "propose_event" -> unavailable ?: propose(asked.args)
        else -> null
    }

    private fun agenda(raw: String): String {
        val date = when (raw.trim().lowercase()) {
            "", "today" -> today
            "tomorrow" -> today.plusDays(1)
            "yesterday" -> today.minusDays(1)
            else -> runCatching { LocalDate.parse(raw.trim()) }.getOrNull()
                ?: return "\"$raw\" is not a date. Write it year first, like 2026-10-03."
        }
        val found = try {
            day(date)
        } catch (e: JmapError) {
            return "Could not read the calendar: ${e.message ?: "the server gave no reason."}"
        }
        val heading = date.format(AGENDA_DATE)
        if (found.isEmpty()) return "Nothing is on the calendar on $heading."
        return "On $heading:\n" + found.joinToString("\n") { o ->
            val time = if (o.allDay) "all day" else "${o.start.format(AGENDA_CLOCK)} to ${o.end.format(AGENDA_CLOCK)}"
            val place = if (o.location.isBlank()) "" else " at ${o.location}"
            "$time  ${o.title.ifBlank { "(no title)" }}$place"
        }
    }

    private fun propose(args: JsonObject): String {
        if (proposed.size >= MOST_PROPOSED) {
            return "There are already $MOST_PROPOSED event cards from this turn. Let the person save or discard those first."
        }
        return when (val read = formFromJson(args, zone)) {
            is Extracted.Failed -> "No card was made. ${read.reason}"
            is Extracted.Found -> {
                proposed += read.form
                "A card for ${formWords(read.form)} is on screen. Nothing is in the calendar until the person checks it and presses Save."
            }
        }
    }

    private fun text(args: JsonObject, name: String): String =
        (args[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

/**
 * The calendar tools for [backend], reading one day at a time over its own session.
 *
 * Built before the turn and cheap to build: nothing is read until Rook asks for a day. The
 * account's own choice of hidden calendars is honoured, as the agenda panel does, so a
 * hidden rota is not read out.
 */
internal fun calendarToolsFor(
    backend: MailBackend?,
    accountName: String,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): CalendarTools {
    val client = eventCalendarFor(backend)
    return CalendarTools(
        unavailable = noCalendarBecause(backend, accountName),
        day = { date -> if (client == null) emptyList() else readDay(client, date, zone) },
        zone = zone,
        today = today,
    )
}

/** One day's occurrences in [zone], leaving out the calendars the owner has hidden. */
private fun readDay(calendar: CalendarClient, date: LocalDate, zone: ZoneId): List<Occurrence> {
    val hidden = runCatching { calendar.calendars() }.getOrDefault(emptyList())
        .filter { !it.isVisible }.map { it.id }.toSet()
    // A day either side, because the query is in UTC and the day is local.
    val events = calendar.events(
        utcStamp(date.minusDays(1).atStartOfDay(), zone),
        utcStamp(date.plusDays(2).atStartOfDay(), zone),
    )
    return expandAll(
        events.filter { e -> e.calendarIds.isEmpty() || e.calendarIds.any { it !in hidden } },
        date,
        date.plusDays(1),
        zone,
    )
}

/**
 * Free times for [ask] from the calendar behind [calendar], spread by the rule in FreeSlots.kt.
 *
 * Blocking, so it belongs on a background thread. One CalendarEvent query over the whole
 * window, the same one the Calendar page makes, and everything after it is done here. Hidden
 * calendars count: hiding one tidies the view, it does not free the time it holds.
 */
internal fun freeTimes(calendar: CalendarClient, ask: ScheduleAsk, zone: ZoneId, now: java.time.Instant): List<Slot> {
    val events = calendar.events(
        utcStamp(ask.from.minusDays(1).atStartOfDay(), zone),
        utcStamp(ask.through.plusDays(2).atStartOfDay(), zone),
    )
    val busy = busyFrom(expandAll(events, ask.from.minusDays(1), ask.through.plusDays(1), zone), zone)
    return spread(freeSlots(busy, ask.from, ask.through, ask.minutes, zone, now))
}
