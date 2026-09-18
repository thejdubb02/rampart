package org.rampart

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Putting a message away until later.
 *
 * **The state lives on the server, and that is the whole design.** The message is moved
 * into a real folder and marked with a keyword saying when it is due, so every client
 * agrees about where it is: it is not in the inbox on the desktop and still in the inbox on
 * the phone, which is the failure that makes a snooze worse than none.
 *
 * ponytail: it comes back when Rampart next runs, not at the minute it is due. Neither
 * protocol can schedule anything, and Sieve only runs at delivery, so nothing on the server
 * can move it back by itself. The folder is still honest while it waits, and the companion
 * server (docs/self-hosting.md) is where punctuality would come from if it is ever wanted.
 */
internal data class Snoozed(val until: Instant, val keyword: String)

/** The folder snoozed mail waits in. Found by name, because no protocol has a role for it. */
internal const val SNOOZE_FOLDER = "Snoozed"

/**
 * The keyword that says when a message is due back.
 *
 * Seconds since the epoch, in the keyword itself, because there is nowhere else both
 * clients can read. A keyword is printable ASCII with no spaces, which an integer satisfies
 * and a timestamp with colons in it does not travel as comfortably through.
 */
internal fun snoozeKeyword(until: Instant): String = "\$snooze-${until.epochSecond}"

/** When a message is due back, or null when it is not snoozed. */
internal fun snoozedIn(keywords: Collection<String>): Snoozed? = keywords.firstNotNullOfOrNull { keyword ->
    val seconds = keyword.lowercase().removePrefix("\$snooze-").takeIf { it != keyword.lowercase() }
        ?.toLongOrNull() ?: return@firstNotNullOfOrNull null
    Snoozed(Instant.ofEpochSecond(seconds), keyword)
}

/** Whether a snoozed message has come due. */
internal fun dueBack(keywords: Collection<String>, now: Instant): Boolean =
    snoozedIn(keywords)?.until?.isBefore(now) == true

/**
 * The choices offered, and what each one means.
 *
 * Deliberately a short list of named times rather than a calendar. "Tomorrow morning" is
 * what somebody means; picking 09:00 on a date from a grid is that same thing with four
 * more clicks, and the times below are the ones every client settled on.
 */
internal enum class SnoozeUntil(val label: String) {
    LATER("Later today"),
    TOMORROW("Tomorrow morning"),
    WEEKEND("This weekend"),
    NEXT_WEEK("Next week"),
    ;

    /**
     * When this choice falls, from [now] in [zone].
     *
     * Every answer is pushed forward until it is actually in the future. Choosing "later
     * today" at eleven at night otherwise means a message that is already due and comes
     * straight back, which reads as the button not working.
     */
    fun dueAt(now: ZonedDateTime, zone: ZoneId = now.zone): ZonedDateTime {
        val here = now.withZoneSameInstant(zone)
        val morning = LocalTime.of(9, 0)
        return when (this) {
            LATER -> here.plusHours(3)
            TOMORROW -> here.plusDays(1).with(morning)
            // Saturday morning, and next Saturday if it is already the weekend: somebody
            // asking on a Saturday means the one coming, not the hour they are in.
            WEEKEND -> here.with(morning).nextOrSame(DayOfWeek.SATURDAY, here)
            NEXT_WEEK -> here.with(morning).nextOrSame(DayOfWeek.MONDAY, here)
        }.let { if (it.isAfter(here)) it else it.plusWeeks(1) }
    }
}

private fun ZonedDateTime.nextOrSame(day: DayOfWeek, after: ZonedDateTime): ZonedDateTime {
    var at = this
    while (at.dayOfWeek != day || !at.isAfter(after)) at = at.plusDays(1)
    return at
}

/**
 * When a message is coming back, as a person would say it.
 *
 * Shown on the row and in the reader, because a folder full of messages with no due date on
 * them is a folder nobody trusts to give them back.
 */
internal fun snoozeText(until: Instant, now: ZonedDateTime): String {
    val due = until.atZone(now.zone)
    return when {
        due.toLocalDate() == now.toLocalDate() -> "back at " + due.format(CLOCK)
        due.toLocalDate() == now.toLocalDate().plusDays(1) -> "back tomorrow at " + due.format(CLOCK)
        due.isBefore(now.plusDays(7)) -> "back " + due.format(WEEKDAY) + " at " + due.format(CLOCK)
        else -> "back on " + due.format(DATE)
    }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.UK)
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.UK)
