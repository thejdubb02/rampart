package org.rampart

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A meeting, read out of the `text/calendar` part an invitation carries.
 *
 * Deliberately far smaller than iCalendar. RFC 5545 describes journals, to-dos, free/busy
 * queries, alarms, timezone definitions and recurrence arithmetic, and a client that
 * implements all of it is a calendar application. This is the half a mail client needs:
 * what the meeting is, when, where, who called it, who is invited, and what has been
 * answered.
 */
internal data class Invitation(
    /**
     * REQUEST, REPLY or CANCEL, or empty when the sender did not say.
     *
     * It decides what the message means, not what it contains. The same VEVENT is an
     * invitation, somebody's answer to one, or a meeting being called off, and only this
     * tells them apart.
     */
    val method: String,
    /** The meeting's identity, carried unchanged through every message about it. */
    val uid: String,
    /** Which version of the meeting. An organiser moving it sends a higher one. */
    val sequence: Int,
    val summary: String,
    val description: String,
    val location: String,
    val organiser: Invitee?,
    val attendees: List<Invitee>,
    val starts: EventTime?,
    val ends: EventTime?,
    /** The recurrence as a sentence, or empty when the meeting happens once. */
    val repeats: String,
)

/** Somebody on the invitation, whether they called it or were asked to it. */
internal data class Invitee(
    val name: String,
    /** Lowercased and without the `mailto:` an iCalendar address always carries. */
    val email: String,
    /** The PARTSTAT parameter uppercased, or empty when they have not answered. */
    val status: String,
)

/**
 * A moment on the invitation.
 *
 * [instant] is null only when the value could not be read at all. [raw] is what arrived,
 * kept because an answer sends the organiser's own DTSTART back rather than a re-rendered
 * version of it, and because a value this could not parse is still worth showing somebody.
 */
internal data class EventTime(val instant: ZonedDateTime?, val allDay: Boolean, val raw: String)

/**
 * The meeting in an iCalendar document, or null when there is no event in it.
 *
 * **Only the first VEVENT.** A recurring meeting with a changed occurrence carries several,
 * the series first and each override after it, and showing the override would tell somebody
 * about one Tuesday in March instead of about the meeting.
 */
internal fun invitationIn(ics: String): Invitation? {
    val lines = unfold(ics)
    var method = ""
    var inEvent = false
    var done = false
    // A VALARM lives inside a VEVENT and has its own SUMMARY and DESCRIPTION. Anything
    // nested is skipped whole, or a reminder's "Meeting in 15 minutes" becomes the title.
    var nested = 0
    val props = ArrayList<Property>()

    for (line in lines) {
        val property = property(line) ?: continue
        val name = property.name
        val value = property.value.trim()
        when {
            name == "BEGIN" && value.equals("VEVENT", true) && !done -> { inEvent = true; nested = 0 }
            name == "END" && value.equals("VEVENT", true) && inEvent && nested == 0 -> {
                inEvent = false
                done = true
            }
            inEvent && name == "BEGIN" -> nested++
            inEvent && name == "END" -> if (nested > 0) nested--
            inEvent && nested == 0 -> props.add(property)
            // METHOD sits on the calendar, outside the event, so it is read at this level.
            !inEvent && name == "METHOD" -> method = value.uppercase()
        }
    }
    if (!done && props.isEmpty()) return null

    fun first(name: String) = props.firstOrNull { it.name == name }
    val starts = first("DTSTART")?.let(::timeOf)
    val ends = first("DTEND")?.let(::timeOf) ?: first("DURATION")?.let { after(starts, it.value) }
    return Invitation(
        method = method,
        uid = first("UID")?.value.orEmpty(),
        sequence = first("SEQUENCE")?.value?.trim()?.toIntOrNull() ?: 0,
        summary = unescape(first("SUMMARY")?.value.orEmpty()),
        description = unescape(first("DESCRIPTION")?.value.orEmpty()),
        location = unescape(first("LOCATION")?.value.orEmpty()),
        organiser = first("ORGANIZER")?.let(::inviteeOf),
        attendees = props.filter { it.name == "ATTENDEE" }.map(::inviteeOf),
        starts = starts,
        ends = ends,
        repeats = first("RRULE")?.let { repeatText(it.value) }.orEmpty(),
    )
}

/**
 * When the meeting is, in one line a person reads.
 *
 * Rendered in [zone], so an invitation written in Los Angeles reads in the hours the
 * recipient's own day has. **An all-day event is never converted**: a date has no timezone,
 * and shifting one is how a birthday lands on the day before.
 */
internal fun whenText(
    starts: EventTime?,
    ends: EventTime?,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val from = starts?.instant ?: return ""
    val here = if (starts.allDay) from else from.withZoneSameInstant(zone)
    val to = ends?.instant?.let { if (ends.allDay) it else it.withZoneSameInstant(zone) }

    if (starts.allDay) {
        // DTEND on an all-day event is the day after the last one, which is correct in the
        // protocol and wrong on a screen: a one day meeting would read as two.
        val last = to?.toLocalDate()?.minusDays(1)
        return if (last == null || !last.isAfter(here.toLocalDate())) dayText(here)
        else dayText(here) + " to " + dayText(last.atStartOfDay(here.zone))
    }
    val start = dayText(here) + ", " + here.format(CLOCK)
    return when {
        to == null -> start
        to.toLocalDate() == here.toLocalDate() -> start + " to " + to.format(CLOCK)
        else -> start + " to " + dayText(to) + ", " + to.format(CLOCK)
    }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)
private val UNTIL_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)

private fun dayText(at: ZonedDateTime) = at.format(DAY)

/** One property line, split into its name, its parameters and its value. */
private data class Property(val name: String, val params: Map<String, String>, val value: String)

/**
 * A line as `NAME;PARAM=VALUE:VALUE`.
 *
 * The splits ignore anything inside double quotes, because a parameter value is allowed to
 * be quoted and a quoted one is allowed to contain both a colon and a semicolon. That is
 * not a corner case: `CN="Willhite, Justin: Director"` is what a display name with a comma
 * in it looks like on the wire, and splitting on the first colon would cut it in half.
 */
private fun property(line: String): Property? {
    if (line.isBlank()) return null
    val colon = indexOutsideQuotes(line, ':') ?: return null
    val head = line.substring(0, colon)
    val value = line.substring(colon + 1)
    val parts = splitOutsideQuotes(head, ';')
    val name = parts.firstOrNull()?.trim()?.uppercase() ?: return null
    val params = parts.drop(1).mapNotNull { param ->
        val at = param.indexOf('=')
        if (at <= 0) null
        else param.substring(0, at).trim().uppercase() to param.substring(at + 1).trim().trim('"')
    }.toMap()
    return Property(name, params, value)
}

private fun indexOutsideQuotes(text: String, needle: Char): Int? {
    var quoted = false
    text.forEachIndexed { at, ch ->
        when {
            ch == '"' -> quoted = !quoted
            ch == needle && !quoted -> return at
        }
    }
    return null
}

private fun splitOutsideQuotes(text: String, needle: Char): List<String> {
    val out = ArrayList<String>()
    val current = StringBuilder()
    var quoted = false
    for (ch in text) {
        when {
            ch == '"' -> { quoted = !quoted; current.append(ch) }
            ch == needle && !quoted -> { out.add(current.toString()); current.setLength(0) }
            else -> current.append(ch)
        }
    }
    out.add(current.toString())
    return out
}

/**
 * The document with its folded lines joined back up.
 *
 * iCalendar wraps at 75 octets and marks the continuation with a leading space or tab,
 * which the reader takes off again. Without this a long DESCRIPTION arrives in pieces and,
 * worse, a folded ATTENDEE line parses as a property whose name is the middle of an email
 * address.
 */
private fun unfold(ics: String): List<String> {
    val out = ArrayList<String>()
    ics.replace("\r\n", "\n").split('\n').forEach { line ->
        if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
            out[out.size - 1] = out.last() + line.substring(1)
        } else {
            out.add(line)
        }
    }
    return out
}

/**
 * A TEXT value with its escapes undone.
 *
 * Only for the three properties that are TEXT. Running it over an address or a date would
 * corrupt a value that never had escapes in it: a `\` in a Windows path inside a LOCATION
 * is text and is unescaped, the same character in a UID is not.
 */
private fun unescape(text: String): String {
    val out = StringBuilder(text.length)
    var at = 0
    while (at < text.length) {
        val ch = text[at]
        if (ch != '\\' || at == text.lastIndex) {
            out.append(ch)
            at++
            continue
        }
        when (val next = text[at + 1]) {
            'n', 'N' -> out.append('\n')
            '\\', ';', ',' -> out.append(next)
            else -> { out.append(ch); out.append(next) }
        }
        at += 2
    }
    return out.toString()
}

private fun inviteeOf(property: Property) = Invitee(
    name = unescape(property.params["CN"].orEmpty()),
    email = property.value.trim().removePrefix("mailto:").removePrefix("MAILTO:").trim().lowercase(),
    status = property.params["PARTSTAT"].orEmpty().uppercase(),
)

/**
 * A DTSTART or DTEND in any of the three forms an invitation uses.
 *
 * `VALUE=DATE` is a whole day, `...Z` is UTC, and a `TZID` names a zone. A fourth form
 * exists, a local time with no zone at all, which means "whatever the clock says wherever
 * you are"; it is read as the machine's own zone, which is what it means.
 *
 * **An unknown TZID must not throw.** Exchange writes zone names that are not in the IANA
 * database, and a meeting that fails to render is worse than one rendered an hour out, so
 * it falls back to UTC and keeps [EventTime.raw] for anyone who needs the truth.
 */
private fun timeOf(property: Property): EventTime {
    val raw = property.value.trim()
    val allDay = property.params["VALUE"].equals("DATE", ignoreCase = true) ||
        (raw.length == 8 && raw.all { it.isDigit() })
    val instant = runCatching {
        if (allDay) {
            LocalDate.parse(raw, DATE).atStartOfDay(ZoneOffset.UTC)
        } else {
            val zone = when {
                raw.endsWith("Z") -> ZoneOffset.UTC
                property.params["TZID"] != null ->
                    runCatching { ZoneId.of(property.params.getValue("TZID")) }.getOrDefault(ZoneOffset.UTC)
                else -> ZoneId.systemDefault()
            }
            LocalDateTime.parse(raw.removeSuffix("Z"), STAMP).atZone(zone)
        }
    }.getOrNull()
    return EventTime(instant, allDay, raw)
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

/**
 * The end, worked out from a DURATION, for the invitations that carry one instead of a
 * DTEND. Both are legal and Google sends DTEND while some CalDAV servers send DURATION.
 */
private fun after(starts: EventTime?, duration: String): EventTime? {
    val from = starts?.instant ?: return null
    val length = runCatching { java.time.Duration.parse(weeksExpanded(duration.trim())) }.getOrNull() ?: return null
    return EventTime(from.plus(length), starts.allDay, duration.trim())
}

/**
 * `PnW` turned into days, because java.time's Duration does not know what a week is.
 *
 * Everything else in the grammar, `P1DT2H30M` and the T-only `PT30M`, it parses as written.
 */
private fun weeksExpanded(duration: String): String {
    val weeks = Regex("^([+-])?P(\\d+)W$", RegexOption.IGNORE_CASE).find(duration) ?: return duration
    val days = weeks.groupValues[2].toLong() * 7
    return "${weeks.groupValues[1]}P${days}D"
}

/**
 * An RRULE as a sentence.
 *
 * The common shapes only, which is every meeting anybody actually schedules. Anything this
 * cannot read becomes the bare word "Repeats", never nothing and never an exception: a
 * recurring meeting shown as a single occurrence is a worse lie than a vague one.
 */
private fun repeatText(rule: String): String {
    val parts = rule.split(';').mapNotNull { piece ->
        val at = piece.indexOf('=')
        if (at <= 0) null else piece.substring(0, at).trim().uppercase() to piece.substring(at + 1).trim()
    }.toMap()
    val every = when (parts["FREQ"]?.uppercase()) {
        "DAILY" -> "day"
        "WEEKLY" -> "week"
        "MONTHLY" -> "month"
        "YEARLY" -> "year"
        else -> return "Repeats"
    }
    val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it > 1 }
    val head = if (interval == null) "Repeats every $every" else "Repeats every $interval ${every}s"
    val days = parts["BYDAY"]?.split(',')?.mapNotNull { dayName(it.trim()) }.orEmpty()
    val on = if (days.isEmpty()) "" else " on " + joinWithAnd(days)
    val tail = when {
        parts["COUNT"]?.toIntOrNull() != null -> ", ${parts["COUNT"]} times"
        parts["UNTIL"] != null -> untilText(parts.getValue("UNTIL"))
        else -> ""
    }
    return head + on + tail
}

private fun untilText(until: String): String {
    val raw = until.trim()
    val date = runCatching {
        if (raw.length == 8) LocalDate.parse(raw, DATE)
        else LocalDateTime.parse(raw.removeSuffix("Z"), STAMP).toLocalDate()
    }.getOrNull() ?: return ""
    return ", until " + date.format(UNTIL_DAY)
}

/** BYDAY carries an optional ordinal, as in `2TU` for the second Tuesday. */
private fun dayName(code: String): String? = when (code.takeLast(2).uppercase()) {
    "MO" -> "Monday"
    "TU" -> "Tuesday"
    "WE" -> "Wednesday"
    "TH" -> "Thursday"
    "FR" -> "Friday"
    "SA" -> "Saturday"
    "SU" -> "Sunday"
    else -> null
}

private fun joinWithAnd(words: List<String>): String = when (words.size) {
    0 -> ""
    1 -> words[0]
    else -> words.dropLast(1).joinToString(", ") + " and " + words.last()
}
