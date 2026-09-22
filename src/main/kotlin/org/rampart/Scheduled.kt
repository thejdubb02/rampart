package org.rampart

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * One message waiting to go out, and when.
 *
 * The message itself is already in the server's Drafts folder. This record only
 * says when to send that copy. If the record is lost, the draft is still sitting
 * in Drafts, where it can be sent by hand. Nothing here is the only copy.
 */
@Serializable
internal data class ScheduledSend(
    /** A random id, so cancelling one does not depend on the server's id staying put. */
    val id: String,
    /** The account key, the same shape used everywhere else. */
    val account: String,
    /** The server-side Drafts message this will send. */
    val draftId: String,
    val identityEmail: String,
    val draft: Draft,
    /** Epoch millis. */
    val sendAt: Long,
)

/**
 * The local list of messages waiting for a time.
 *
 * One JSON array under one key, in its own file, written the same way [Settings]
 * writes: read the file, change that one key, move the new file over the old one.
 * A missing or unreadable file is an empty list, so a hand-edited file costs the
 * schedule and not the application. The drafts those entries pointed at are still
 * on the server.
 */
internal object ScheduledSends {
    private val store = JsonStore("scheduled.json")
    private val json = Json { ignoreUnknownKeys = true }
    private const val KEY = "sends"

    fun pending(): List<ScheduledSend> = decode(store.read()[KEY])

    /** Replaces any existing entry for the same [ScheduledSend.draftId]. */
    fun schedule(item: ScheduledSend) = store.write {
        val next = decode(this[KEY]).filterNot { it.draftId == item.draftId } + item
        put(KEY, json.encodeToJsonElement(next))
    }

    fun cancel(id: String) = store.write {
        put(KEY, json.encodeToJsonElement(decode(this[KEY]).filterNot { it.id == id }))
    }

    /** Anything whose time has arrived, including one scheduled for exactly [now]. */
    fun due(now: Long = System.currentTimeMillis()): List<ScheduledSend> =
        pending().filter { it.sendAt <= now }

    private fun decode(element: JsonElement?): List<ScheduledSend> {
        if (element !is JsonArray) return emptyList()
        return runCatching { json.decodeFromJsonElement<List<ScheduledSend>>(element) }
            .getOrDefault(emptyList())
    }
}

/** One hour on from [now], keeping the minutes. */
internal fun inOneHour(now: ZonedDateTime): ZonedDateTime = now.plusHours(1)

/**
 * 18:00 today, or 09:00 tomorrow when 18:00 has already passed.
 *
 * "This evening" asked at 19:30 is not a time today. The next named time that
 * slot can honestly mean is tomorrow morning, rather than a clock that has
 * already gone and would send the moment it was chosen.
 */
internal fun thisEvening(now: ZonedDateTime): ZonedDateTime {
    val evening = now.withHour(18).withMinute(0).withSecond(0).withNano(0)
    return if (now.isAfter(evening)) tomorrowMorning(now) else evening
}

/** 09:00 on the next calendar day, in [now]'s zone. */
internal fun tomorrowMorning(now: ZonedDateTime): ZonedDateTime =
    now.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)

/** A date and time the schedule dialog can accept, or a sentence saying why not. */
internal sealed class ScheduleWhen {
    data class At(val millis: Long) : ScheduleWhen()
    data class Problem(val message: String) : ScheduleWhen()
}

/**
 * Reads a `yyyy-MM-dd` date and an `HH:mm` time in [now]'s zone.
 *
 * A time that is not after [now] is refused. Clamping it forward would send a
 * message at a time the person did not type.
 */
internal fun parseSchedule(date: String, time: String, now: ZonedDateTime): ScheduleWhen {
    val dayText = date.trim()
    val timeText = time.trim()
    val day = if (DATE.matches(dayText)) runCatching { LocalDate.parse(dayText) }.getOrNull() else null
    val clock = if (CLOCK.matches(timeText)) runCatching { LocalTime.parse(timeText) }.getOrNull() else null
    if (day == null || clock == null) {
        return ScheduleWhen.Problem("Enter the date as yyyy-MM-dd and the time as HH:mm.")
    }
    val at = ZonedDateTime.of(day, clock, now.zone)
    if (!at.isAfter(now)) return ScheduleWhen.Problem("That time has already passed.")
    return ScheduleWhen.At(at.toInstant().toEpochMilli())
}

private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
private val CLOCK = Regex("\\d{2}:\\d{2}")
