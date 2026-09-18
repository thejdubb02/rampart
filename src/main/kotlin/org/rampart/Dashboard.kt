package org.rampart

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the mailbox is actually going, on one screen.
 *
 * **Every number here comes out of the local copy**, which is the whole reason this is the
 * first thing Rampart does that webmail does not. There is no pixel, no endpoint, no DNS
 * record, no setting and no model behind it: the mail has already been read, so counting it
 * is a query. That means it works on Stalwart, on Gmail and on plain IMAP, offline, for
 * anybody who installs the app and signs in.
 *
 * It is therefore also honest about its own limits. It describes what has been fetched, not
 * what exists on the server, and [kept] says so on screen rather than letting somebody read
 * a month's figures off a week's mail.
 */
internal data class MailStats(
    /** Received per day, oldest first, with the empty days present so a chart has no gaps. */
    val received: List<DayCount>,
    val sent: List<DayCount>,
    /** How many of the arrivals in the window landed in Junk. */
    val junk: Int,
    /** Everything that arrived in the window, Junk included. */
    val arrived: Int,
    val topSenders: List<Counted>,
    /** Unread, bucketed by how old it is. The buckets are [UNREAD_AGES]. */
    val unread: List<Counted>,
    /** Conversations where the last word is somebody else's, oldest first. */
    val waiting: List<Summary>,
    /** How long a reply took, in minutes, for every conversation that got one. */
    val replyMinutes: List<Long>,
    /** How many messages the local copy holds at all, so the figures can be read in scale. */
    val kept: Int,
)

/** A day and how many messages it carried. */
internal data class DayCount(val day: LocalDate, val count: Int)

/** Anything counted and ranked: a sender, a bucket, a correspondent. */
internal data class Counted(val label: String, val count: Int, val detail: String = "")

/**
 * The buckets unread mail falls into.
 *
 * Age rather than folder, because the useful question about unread mail is not where it is,
 * it is how long it has been ignored. Two hundred unread from this morning is a busy
 * morning; forty from last quarter is a decision nobody made.
 */
internal val UNREAD_AGES = listOf(
    "Today" to 1L,
    "This week" to 7L,
    "This month" to 31L,
    "This year" to 366L,
    "Older" to Long.MAX_VALUE,
)

/** The middle reply time, which is the one worth showing: one forgotten thread skews a mean. */
internal fun medianMinutes(gaps: List<Long>): Long? {
    if (gaps.isEmpty()) return null
    val sorted = gaps.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

/**
 * A length of time as somebody would say it.
 *
 * Rounded hard on purpose. "About 3 hours" is the answer to how fast you reply; "3 hours 14
 * minutes" is a number pretending the next one will be the same.
 */
internal fun spokenMinutes(minutes: Long?): String = when {
    minutes == null -> ""
    minutes < 1 -> "under a minute"
    minutes < 60 -> "$minutes minute" + plural(minutes)
    minutes < 60 * 24 -> (minutes / 60).let { "about $it hour" + plural(it) }
    minutes < 60 * 24 * 14 -> (minutes / (60 * 24)).let { "about $it day" + plural(it) }
    else -> (minutes / (60 * 24 * 7)).let { "about $it week" + plural(it) }
}

private fun plural(n: Long) = if (n == 1L) "" else "s"

/** A share as a whole number of percent. Zero out of zero is zero, not a crash. */
internal fun percent(part: Int, whole: Int): Int = if (whole <= 0) 0 else (part * 100.0 / whole).toInt()

/**
 * Every day in the window, in order, with nothing on the days nothing arrived.
 *
 * A chart built from only the days that have a message in them lies twice: it hides the
 * quiet days and it puts the busy ones next to each other, which makes a fortnight of
 * silence look like steady traffic.
 */
internal fun byDay(receivedAt: List<String>, days: Int, today: LocalDate, zone: ZoneId): List<DayCount> {
    val counts = HashMap<LocalDate, Int>()
    receivedAt.forEach { at ->
        dayOf(at, zone)?.let { counts[it] = (counts[it] ?: 0) + 1 }
    }
    return (days - 1 downTo 0).map { back ->
        val day = today.minusDays(back.toLong())
        DayCount(day, counts[day] ?: 0)
    }
}

/**
 * The date part of a stored timestamp, in the reader's own zone.
 *
 * Stored as the server wrote it, which is an ISO instant from JMAP and can be an offset
 * from IMAP, so both are tried. A value neither can read counts for nothing rather than
 * throwing: a dashboard is not worth a crash over one malformed header.
 */
internal fun dayOf(receivedAt: String, zone: ZoneId): LocalDate? = runCatching {
    Instant.parse(receivedAt).atZone(zone).toLocalDate()
}.recoverCatching {
    java.time.OffsetDateTime.parse(receivedAt).atZoneSameInstant(zone).toLocalDate()
}.getOrNull()

/** The same, as an instant, for working out how long a reply took. */
internal fun instantOf(receivedAt: String): Instant? = runCatching {
    Instant.parse(receivedAt)
}.recoverCatching {
    java.time.OffsetDateTime.parse(receivedAt).toInstant()
}.getOrNull()

/**
 * Unread mail sorted into [UNREAD_AGES].
 *
 * Each bucket holds what is older than the one before it, so a message is counted once.
 */
internal fun unreadByAge(receivedAt: List<String>, now: Instant): List<Counted> {
    val counts = IntArray(UNREAD_AGES.size)
    receivedAt.forEach { at ->
        val days = instantOf(at)?.let { Duration.between(it, now).toDays() } ?: return@forEach
        val bucket = UNREAD_AGES.indexOfFirst { days < it.second }.takeIf { it >= 0 } ?: UNREAD_AGES.lastIndex
        counts[bucket]++
    }
    return UNREAD_AGES.mapIndexed { at, (label, _) -> Counted(label, counts[at]) }
        // A bucket with nothing in it is a row saying nothing, and there are five of them.
        .filter { it.count > 0 }
}

/** The day a chart's axis shows, short because there are thirty of them across. */
internal fun axisDay(day: LocalDate): String = day.format(AXIS)

private val AXIS: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
