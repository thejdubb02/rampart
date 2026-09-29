package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/*
 * The calendar, read and written over JMAP for Calendars.
 *
 * Stalwart 0.16 advertises `urn:ietf:params:jmap:calendars`, and its events are JSCalendar
 * (RFC 8984) objects. Everything in this file is the part that has no screen: reading those
 * objects, working out when a repeating event actually happens, and building what goes
 * back to the server. It is kept apart from CalendarPane.kt so all of it can be tested
 * without a window, and it never throws past the caller without a sentence a person can read.
 */

internal const val CALENDARS = "urn:ietf:params:jmap:calendars"

/** One of the account's calendars, as Calendar/get describes it. */
internal data class CalendarInfo(
    val id: String,
    val name: String,
    /** A CSS colour, or blank when the server gave none and one is picked from the id. */
    val colour: String = "",
    val isDefault: Boolean = false,
    /** Whether the owner wants it drawn. Toggled from the list and written back. */
    val isVisible: Boolean = true,
    val sortOrder: Long = 0,
    /** False for a calendar shared read only, which is then left out of the editor. */
    val mayWrite: Boolean = true,
)

internal enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/** A weekday, with the "second Tuesday" number when the rule gives one. */
internal data class NDay(val day: DayOfWeek, val nth: Int = 0)

/**
 * One recurrence rule, as much of RFC 8984's RecurrenceRule as this file can expand exactly.
 *
 * [exact] is false when the rule uses a part this file does not implement, such as
 * bySetPosition or an hourly frequency. Such an event is drawn once, on its first date,
 * with a note saying so, because drawing it on the wrong days would be worse than drawing
 * it on too few.
 */
internal data class RecurrenceRule(
    val frequency: Frequency,
    val interval: Int = 1,
    val byDay: List<NDay> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val count: Int? = null,
    /** Inclusive, in the event's own time zone, as RFC 8984 defines it. */
    val until: LocalDateTime? = null,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val exact: Boolean = true,
)

/**
 * An event's length as JSCalendar writes it: whole days, then an exact time.
 *
 * Kept as two parts rather than one java.time.Duration because a day in a calendar is a
 * date, not 24 hours. A daily meeting that crosses the clocks changing stays at nine
 * o'clock; adding 24 hours to it would move it.
 */
internal data class EventLength(val days: Long = 0, val time: Duration = Duration.ZERO) {
    fun after(start: LocalDateTime): LocalDateTime = start.plusDays(days).plus(time)

    /** RFC 8984 form, such as `PT1H`, `P1D` or `P1DT30M`. */
    fun text(): String {
        if (days == 0L && time.isZero) return "PT0S"
        val hours = time.toHours()
        val minutes = time.toMinutesPart()
        val seconds = time.toSecondsPart()
        return buildString {
            append('P')
            if (days != 0L) append(days).append('D')
            if (hours != 0L || minutes != 0 || seconds != 0) {
                append('T')
                if (hours != 0L) append(hours).append('H')
                if (minutes != 0) append(minutes).append('M')
                if (seconds != 0) append(seconds).append('S')
            }
        }
    }

    companion object {
        private val SHAPE = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")

        /** Null for anything that is not an RFC 8984 duration, so the caller can default. */
        fun parse(raw: String?): EventLength? {
            val m = SHAPE.find(raw?.trim()?.uppercase() ?: return null) ?: return null
            if (m.value == "P" || m.value.endsWith("T")) return null
            fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
            val days = g(2) * 7 + g(3)
            val time = Duration.ofHours(g(4)).plusMinutes(g(5)).plusSeconds(g(6))
            // A negative length makes no sense for an event and is read as none at all.
            if (m.groupValues[1] == "-") return EventLength()
            return EventLength(days, time)
        }

        /** The length from [start] to [end], preferring whole days where it is whole days. */
        fun between(start: LocalDateTime, end: LocalDateTime): EventLength {
            if (!end.isAfter(start)) return EventLength()
            val days = ChronoUnit.DAYS.between(start, end)
            return EventLength(days, Duration.between(start.plusDays(days), end))
        }
    }
}

/**
 * One event as the server holds it, with the parts this build shows pulled out.
 *
 * [raw] is the whole JSCalendar object. Repeating instances are built by patching it, and
 * a save is built on it, so the properties this build does not draw (participants, alerts,
 * attachments) travel through untouched.
 */
internal data class CalendarEvent(
    val id: String,
    val calendarIds: Set<String>,
    val title: String,
    val description: String,
    val location: String,
    /** Local wall time in [timeZone], or floating when that is null. */
    val start: LocalDateTime,
    /** Null means floating: the same wall time wherever the reader is, which is what all-day events use. */
    val timeZone: ZoneId?,
    val length: EventLength,
    val allDay: Boolean,
    val rules: List<RecurrenceRule>,
    /** Keyed by recurrence id, the local start the rule would have given that instance. */
    val overrides: Map<LocalDateTime, JsonObject>,
    val raw: JsonObject,
    /** False when a rule could not be expanded exactly. See [RecurrenceRule.exact]. */
    val exact: Boolean = true,
)

/**
 * One time an event happens, placed in the reader's own time zone.
 *
 * All-day occurrences keep their dates exactly as written: a birthday is on the fourth
 * wherever you read it from, which is what floating time is for.
 */
internal data class Occurrence(
    val event: CalendarEvent,
    /** Null for an event that does not repeat. Otherwise what an override is keyed by. */
    val recurrenceId: LocalDateTime?,
    val title: String,
    val description: String,
    val location: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
) {
    val calendarId: String get() = event.calendarIds.firstOrNull().orEmpty()

    /** The dates it covers, for placing it on a month or on the all-day strip. */
    fun days(): List<LocalDate> {
        val first = start.toLocalDate()
        // An end at midnight belongs to the day before, or a one hour meeting that ends at
        // midnight would be drawn on two days.
        val last = if (end.toLocalTime() == LocalTime.MIDNIGHT && end.isAfter(start)) {
            end.toLocalDate().minusDays(1)
        } else {
            end.toLocalDate()
        }
        if (last.isBefore(first)) return listOf(first)
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.take(366).toList()
    }
}

internal val LOCAL_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/** A JSCalendar LocalDateTime. Seconds are optional on the way in because not every server writes them. */
internal fun localStamp(raw: String?): LocalDateTime? = raw?.trim()?.let {
    runCatching { LocalDateTime.parse(it.removeSuffix("Z")) }.getOrNull()
}

private fun zoneOf(raw: String?): ZoneId? {
    if (raw.isNullOrBlank()) return null
    // A name starting with a slash is a custom zone defined inside the event. Reading those
    // is a whole parser for one server in a hundred, so they are treated as floating, which
    // is at worst an hour out and never on the wrong day for most readers.
    if (raw.startsWith("/")) return null
    return runCatching { ZoneId.of(raw) }.getOrNull()
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonElement?.flag(): Boolean = (this as? JsonPrimitive)?.booleanOrNull == true

private val DAY_CODES = mapOf(
    "mo" to DayOfWeek.MONDAY, "tu" to DayOfWeek.TUESDAY, "we" to DayOfWeek.WEDNESDAY,
    "th" to DayOfWeek.THURSDAY, "fr" to DayOfWeek.FRIDAY, "sa" to DayOfWeek.SATURDAY,
    "su" to DayOfWeek.SUNDAY,
)

internal fun dayCode(day: DayOfWeek): String = DAY_CODES.entries.first { it.value == day }.key

/** Parts of RFC 8984's RecurrenceRule this file does not expand, so a rule naming one is not exact. */
private val UNSUPPORTED_PARTS = listOf("bySetPosition", "byYearDay", "byWeekNo", "byHour", "byMinute", "bySecond")

/**
 * Stalwart 0.16 speaks `recurrenceRule`, one object, not RFC 8984's `recurrenceRules` array,
 * and refuses the array on create. Both are read; only the singular is written.
 */
private fun recurrenceRulesOf(o: JsonObject): List<RecurrenceRule> =
    (o["recurrenceRules"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::recurrenceRuleOf) }
        ?: (o["recurrenceRule"] as? JsonObject)?.let(::recurrenceRuleOf)?.let(::listOf)
        ?: emptyList()

internal fun recurrenceRuleOf(o: JsonObject): RecurrenceRule? {
    val frequency = when (o["frequency"].text()?.lowercase()) {
        "daily" -> Frequency.DAILY
        "weekly" -> Frequency.WEEKLY
        "monthly" -> Frequency.MONTHLY
        "yearly" -> Frequency.YEARLY
        // Hourly and finer exist and are rare in a person's calendar. They are drawn once.
        "hourly", "minutely", "secondly" -> return RecurrenceRule(Frequency.DAILY, exact = false)
        else -> return null
    }
    val byDay = (o["byDay"] as? JsonArray).orEmpty().mapNotNull { element ->
        val d = element as? JsonObject ?: return@mapNotNull null
        val day = DAY_CODES[d["day"].text()?.lowercase()] ?: return@mapNotNull null
        NDay(day, (d["nthOfPeriod"] as? JsonPrimitive)?.intOrNull ?: 0)
    }
    fun ints(name: String) = (o[name] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
    val unsupported = UNSUPPORTED_PARTS.any { (o[it] as? JsonArray)?.isNotEmpty() == true } ||
        o["rscale"].text()?.lowercase()?.let { it != "gregorian" } == true ||
        o["skip"].text()?.lowercase()?.let { it != "omit" } == true ||
        // A leap month is written as "3L", which is not a Gregorian month and cannot be drawn.
        (o["byMonth"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() == null }
    return RecurrenceRule(
        frequency = frequency,
        interval = ((o["interval"] as? JsonPrimitive)?.intOrNull ?: 1).coerceAtLeast(1),
        byDay = byDay,
        byMonthDay = ints("byMonthDay").filter { it != 0 && it in -31..31 },
        byMonth = ints("byMonth").filter { it in 1..12 },
        count = (o["count"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
        until = localStamp(o["until"].text()),
        firstDayOfWeek = DAY_CODES[o["firstDayOfWeek"].text()?.lowercase()] ?: DayOfWeek.MONDAY,
        exact = !unsupported,
    )
}

/** The first location's name, which is what a person means by "where". */
private fun locationIn(o: JsonObject): String {
    val locations = o["locations"] as? JsonObject ?: return ""
    return locations.values.firstNotNullOfOrNull { (it as? JsonObject)?.get("name").text()?.takeIf(String::isNotBlank) }.orEmpty()
}

/** Reads one CalendarEvent/get object. Null when it has no id or no start, which is not an event we can place. */
internal fun calendarEventOf(o: JsonObject): CalendarEvent? {
    val id = o["id"].text() ?: return null
    val start = localStamp(o["start"].text()) ?: return null
    val allDay = o["showWithoutTime"].flag()
    val rules = recurrenceRulesOf(o)
    val overrides = (o["recurrenceOverrides"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
        val at = localStamp(key) ?: return@mapNotNull null
        at to ((value as? JsonObject) ?: JsonObject(emptyMap()))
    }.toMap()
    val excludedRules = (o["excludedRecurrenceRules"] as? JsonArray)?.isNotEmpty() == true
    return CalendarEvent(
        id = id,
        calendarIds = (o["calendarIds"] as? JsonObject).orEmpty().filterValues { it.flag() }.keys,
        title = o["title"].text().orEmpty(),
        description = o["description"].text().orEmpty(),
        location = locationIn(o),
        start = start,
        timeZone = zoneOf(o["timeZone"].text()),
        length = EventLength.parse(o["duration"].text()) ?: if (allDay) EventLength(days = 1) else EventLength(),
        allDay = allDay,
        rules = rules,
        overrides = overrides,
        raw = o,
        exact = rules.all { it.exact } && !excludedRules,
    )
}

internal fun calendarInfoOf(o: JsonObject): CalendarInfo? {
    val id = o["id"].text() ?: return null
    val rights = o["myRights"] as? JsonObject
    return CalendarInfo(
        id = id,
        name = o["name"].text().orEmpty().ifBlank { "Calendar" },
        colour = o["color"].text().orEmpty(),
        isDefault = o["isDefault"].flag(),
        // Absent means visible: a server that does not track it has nothing hidden.
        isVisible = (o["isVisible"] as? JsonPrimitive)?.booleanOrNull ?: true,
        sortOrder = (o["sortOrder"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0,
        // Absent rights mean the server does not say, and refusing to edit on a guess would
        // leave the whole editor disabled on a server that simply does not send them.
        mayWrite = rights == null || rights["mayWriteAll"].flag() || rights["mayWriteOwn"].flag(),
    )
}

/**
 * Applies a JMAP PatchObject (RFC 8620 section 5.3) to [base].
 *
 * The keys are JSON Pointer paths without the leading slash, and a null value removes. A
 * recurrence override is exactly this shape, which is why instances are built by patching
 * the event rather than by reading the override's properties one by one: an override may
 * change a location's name by path, and a property-by-property reader would miss it.
 */
internal fun patched(base: JsonObject, patch: JsonObject): JsonObject {
    var result = base
    patch.forEach { (path, value) ->
        val parts = path.split("/").map { it.replace("~1", "/").replace("~0", "~") }
        result = setAt(result, parts, value)
    }
    return result
}

private fun setAt(target: JsonObject, path: List<String>, value: JsonElement): JsonObject {
    val key = path.first()
    val map = target.toMutableMap()
    if (path.size == 1) {
        if (value is JsonNull) map.remove(key) else map[key] = value
    } else {
        val inner = map[key] as? JsonObject ?: JsonObject(emptyMap())
        map[key] = setAt(inner, path.drop(1), value)
    }
    return JsonObject(map)
}

/**
 * Where a rule's candidate dates come from, one period at a time.
 *
 * Periods are counted from the one holding the start, stepped by the interval. Inside a
 * period the parts that expand (byDay in a week or month, byMonthDay) produce dates, and
 * the parts that limit (byMonth, byDay on a daily rule) filter them, as RFC 5545's table
 * of BYxxx behaviour says.
 */
private fun candidates(rule: RecurrenceRule, start: LocalDate, period: Long): List<LocalDate> {
    val step = period * rule.interval
    val dates: List<LocalDate> = when (rule.frequency) {
        Frequency.DAILY -> listOf(start.plusDays(step)).filter { d ->
            (rule.byDay.isEmpty() || rule.byDay.any { it.day == d.dayOfWeek }) &&
                (rule.byMonthDay.isEmpty() || monthDayMatches(d, rule.byMonthDay))
        }
        Frequency.WEEKLY -> {
            val weekStart = start.with(TemporalAdjusters.previousOrSame(rule.firstDayOfWeek)).plusWeeks(step)
            val days = rule.byDay.map { it.day }.ifEmpty { listOf(start.dayOfWeek) }.toSet()
            (0L until 7L).map { weekStart.plusDays(it) }.filter { it.dayOfWeek in days }
        }
        Frequency.MONTHLY -> inMonth(rule, YearMonth.from(start).plusMonths(step), start)
        Frequency.YEARLY -> {
            val year = start.year + step
            if (year > 9999) return emptyList()
            if (rule.byDay.isNotEmpty() && rule.byMonth.isEmpty() && rule.byMonthDay.isEmpty()) {
                inYear(rule.byDay, year.toInt())
            } else {
                rule.byMonth.ifEmpty { listOf(start.monthValue) }.sorted()
                    .flatMap { month -> inMonth(rule.copy(byMonth = emptyList()), YearMonth.of(year.toInt(), month), start) }
            }
        }
    }
    return dates.filter { rule.byMonth.isEmpty() || it.monthValue in rule.byMonth }.distinct().sorted()
}

private fun monthDayMatches(d: LocalDate, wanted: List<Int>): Boolean {
    val length = d.lengthOfMonth()
    return wanted.any { if (it > 0) it == d.dayOfMonth else length + it + 1 == d.dayOfMonth }
}

private fun inMonth(rule: RecurrenceRule, month: YearMonth, start: LocalDate): List<LocalDate> {
    val byDay = rule.byDay
    val fromMonthDays = rule.byMonthDay.mapNotNull { n ->
        val day = if (n > 0) n else month.lengthOfMonth() + n + 1
        // A month without that day simply has no occurrence: the 31st skips April.
        if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
    }
    val fromWeekdays = byDay.flatMap { nday ->
        val all = (1..month.lengthOfMonth()).map { month.atDay(it) }.filter { it.dayOfWeek == nday.day }
        when {
            nday.nth == 0 -> all
            nday.nth > 0 -> listOfNotNull(all.getOrNull(nday.nth - 1))
            else -> listOfNotNull(all.getOrNull(all.size + nday.nth))
        }
    }
    return when {
        rule.byMonthDay.isNotEmpty() && byDay.isNotEmpty() -> fromMonthDays.filter { d -> byDay.any { it.day == d.dayOfWeek } }
        rule.byMonthDay.isNotEmpty() -> fromMonthDays
        byDay.isNotEmpty() -> fromWeekdays
        start.dayOfMonth <= month.lengthOfMonth() -> listOf(month.atDay(start.dayOfMonth))
        else -> emptyList()
    }
}

private fun inYear(byDay: List<NDay>, year: Int): List<LocalDate> {
    val first = LocalDate.of(year, 1, 1)
    val all = (0 until first.lengthOfYear()).map { first.plusDays(it.toLong()) }
    return byDay.flatMap { nday ->
        val matching = all.filter { it.dayOfWeek == nday.day }
        when {
            nday.nth == 0 -> matching
            nday.nth > 0 -> listOfNotNull(matching.getOrNull(nday.nth - 1))
            else -> listOfNotNull(matching.getOrNull(matching.size + nday.nth))
        }
    }
}

/** A limit on how many periods one rule may walk, so a rule that never matches cannot hang the window. */
private const val MAX_PERIODS = 40_000

/**
 * The recurrence ids a rule produces, from the start up to [through].
 *
 * The start always counts as the first occurrence, whether or not the rule would have
 * produced it: RFC 5545 says so, and RFC 8984 keeps it. A count therefore includes it.
 */
internal fun recurrenceIds(start: LocalDateTime, rule: RecurrenceRule, through: LocalDateTime): List<LocalDateTime> {
    val out = mutableListOf(start)
    if (!rule.exact) return out
    val time = start.toLocalTime()
    var period = 0L
    while (period < MAX_PERIODS) {
        val dates = candidates(rule, start.toLocalDate(), period)
        val periodBegins = periodStart(rule, start.toLocalDate(), period)
        if (periodBegins.atStartOfDay().isAfter(through)) break
        for (date in dates) {
            val at = date.atTime(time)
            if (!at.isAfter(start)) continue
            if (rule.until != null && at.isAfter(rule.until)) return out
            if (rule.count != null && out.size >= rule.count) return out
            if (at.isAfter(through)) return out
            out += at
        }
        period++
    }
    return out
}

private fun periodStart(rule: RecurrenceRule, start: LocalDate, period: Long): LocalDate {
    val step = period * rule.interval
    return when (rule.frequency) {
        Frequency.DAILY -> start.plusDays(step)
        Frequency.WEEKLY -> start.with(TemporalAdjusters.previousOrSame(rule.firstDayOfWeek)).plusWeeks(step)
        Frequency.MONTHLY -> YearMonth.from(start).plusMonths(step).atDay(1)
        Frequency.YEARLY -> if (start.year + step > 9999) LocalDate.MAX else LocalDate.of((start.year + step).toInt(), 1, 1)
    }
}

/**
 * Every time [event] happens that overlaps [from] up to but not including [until], in [viewer]'s time.
 *
 * Exceptions are honoured the way RFC 8984 defines them: an override keyed by an instance
 * the rule produces patches that instance, one marked `excluded` removes it, and one keyed
 * by a time the rule does not produce adds an extra instance.
 */
internal fun occurrences(event: CalendarEvent, from: LocalDate, until: LocalDate, viewer: ZoneId): List<Occurrence> {
    val zone = event.timeZone ?: viewer
    // The ids are in the event's own zone, and the window is in the reader's, so the edge is
    // widened by a day either side and the exact overlap is checked once each is placed.
    val through = until.plusDays(1).atStartOfDay()
    val ids = if (event.rules.isEmpty()) {
        listOf(event.start)
    } else {
        event.rules.flatMap { recurrenceIds(event.start, it, through) }.distinct()
    }
    val all = (ids + event.overrides.keys).distinct().sorted()
    val windowStart = from.atStartOfDay()
    val windowEnd = until.atStartOfDay()
    return all.mapNotNull { id ->
        val override = event.overrides[id]
        if (override != null && override["excluded"].flag()) return@mapNotNull null
        val repeating = event.rules.isNotEmpty() || event.overrides.isNotEmpty()
        val instance = if (override == null) {
            event.raw
        } else {
            // The override is applied on top of the master with the instance's own start,
            // so an override that changes only the title keeps the time it would have had.
            patched(buildJsonObject { event.raw.forEach { (k, v) -> put(k, v) }; put("start", LOCAL_STAMP.format(id)) }, override)
        }
        val allDay = if (override == null) event.allDay else instance["showWithoutTime"].flag()
        val localStart = if (override == null) id else localStamp(instance["start"].text()) ?: id
        val length = if (override == null) event.length else EventLength.parse(instance["duration"].text()) ?: event.length
        val instanceZone = if (override == null) zone else zoneOf(instance["timeZone"].text()) ?: event.timeZone ?: viewer
        val (start, end) = if (allDay) {
            localStart to length.after(localStart).let { if (it == localStart) it.plusDays(1) else it }
        } else {
            val s = localStart.atZone(instanceZone)
            val e = s.plusDays(length.days).plus(length.time)
            s.withZoneSameInstant(viewer).toLocalDateTime() to e.withZoneSameInstant(viewer).toLocalDateTime()
        }
        // A zero length event still has to land somewhere, so it is kept if it starts inside.
        val overlaps = start.isBefore(windowEnd) && (end.isAfter(windowStart) || (end == start && !start.isBefore(windowStart)))
        if (!overlaps) return@mapNotNull null
        Occurrence(
            event = event,
            recurrenceId = if (repeating) id else null,
            title = if (override == null) event.title else instance["title"].text().orEmpty(),
            description = if (override == null) event.description else instance["description"].text().orEmpty(),
            location = if (override == null) event.location else locationIn(instance),
            start = start,
            end = end,
            allDay = allDay,
        )
    }
}

/** Every occurrence of every event in the window, in the order a day is read. */
internal fun expandAll(events: List<CalendarEvent>, from: LocalDate, until: LocalDate, viewer: ZoneId): List<Occurrence> =
    events.flatMap { occurrences(it, from, until, viewer) }
        .sortedWith(compareBy<Occurrence>({ it.start.toLocalDate() }, { !it.allDay }, { it.start }, { it.title.lowercase() }))

/**
 * What the simple repeat picker can say, and what it writes.
 *
 * Deliberately the handful of shapes people actually make by hand. A rule from elsewhere
 * that does not fit one of these is shown as a sentence and kept exactly as it is, rather
 * than squeezed into the nearest shape and saved back wrong.
 */
internal data class SimpleRepeat(
    val frequency: Frequency? = null,
    val interval: Int = 1,
    /** Weekly only. Empty means the start's own weekday. */
    val weekdays: Set<DayOfWeek> = emptySet(),
    val until: LocalDate? = null,
    val count: Int? = null,
) {
    fun rule(): JsonObject? {
        val frequency = frequency ?: return null
        return buildJsonObject {
            put("@type", "RecurrenceRule")
            put("frequency", frequency.name.lowercase())
            if (interval > 1) put("interval", interval)
            if (frequency == Frequency.WEEKLY && weekdays.isNotEmpty()) {
                putJsonArray("byDay") {
                    weekdays.sorted().forEach { day ->
                        add(buildJsonObject { put("@type", "NDay"); put("day", dayCode(day)) })
                    }
                }
            }
            when {
                count != null -> put("count", count)
                // Inclusive of the whole last day, whatever time the event starts at.
                until != null -> put("until", LOCAL_STAMP.format(until.atTime(23, 59, 59)))
            }
        }
    }

    companion object {
        /** The picker's reading of [rules], or null when they are not a shape it can show. */
        fun of(rules: List<RecurrenceRule>): SimpleRepeat? {
            if (rules.isEmpty()) return SimpleRepeat()
            val rule = rules.singleOrNull() ?: return null
            if (!rule.exact || rule.byMonth.isNotEmpty() || rule.byMonthDay.isNotEmpty()) return null
            if (rule.byDay.isNotEmpty() && (rule.frequency != Frequency.WEEKLY || rule.byDay.any { it.nth != 0 })) return null
            return SimpleRepeat(
                frequency = rule.frequency,
                interval = rule.interval,
                weekdays = rule.byDay.map { it.day }.toSet(),
                until = rule.until?.toLocalDate(),
                count = rule.count,
            )
        }
    }
}

/** The rule as a sentence, for the details dialog and for a rule the picker cannot edit. */
internal fun repeatSentence(
    rules: List<RecurrenceRule>,
    start: LocalDateTime,
    region: Region = Regional.current(),
): String {
    if (rules.isEmpty()) return ""
    val rule = rules.first()
    if (!rule.exact) return "Repeats by a rule Rampart cannot draw yet, so only its first date is shown"
    val every = when (rule.frequency) {
        Frequency.DAILY -> if (rule.interval == 1) "Every day" else "Every ${rule.interval} days"
        Frequency.WEEKLY -> {
            val base = if (rule.interval == 1) "Every week" else "Every ${rule.interval} weeks"
            val days = rule.byDay.map { it.day }.ifEmpty { listOf(start.dayOfWeek) }.sorted()
            "$base on " + joinWords(days.map { it.getDisplayName(java.time.format.TextStyle.FULL, region.locale) })
        }
        Frequency.MONTHLY -> if (rule.interval == 1) "Every month" else "Every ${rule.interval} months"
        Frequency.YEARLY -> if (rule.interval == 1) "Every year" else "Every ${rule.interval} years"
    }
    val end = when {
        rule.count != null -> ", ${rule.count} times"
        rule.until != null -> ", until " + formatFull(region, rule.until.toLocalDate())
        else -> ""
    }
    return every + end + if (rules.size > 1) ", and by other rules as well" else ""
}

private fun joinWords(words: List<String>): String = when (words.size) {
    0 -> ""
    1 -> words[0]
    else -> words.dropLast(1).joinToString(", ") + " and " + words.last()
}

/** What the editor produces, whether the event is new or not. */
internal data class EventDraft(
    val title: String,
    val calendarId: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val location: String = "",
    val description: String = "",
    /** Null keeps the event's existing rules untouched, for a rule the picker cannot show. */
    val repeat: SimpleRepeat? = SimpleRepeat(),
    /** The zone a timed event is written in. The zone mail and calendars are shown in. */
    val timeZone: ZoneId = Regional.zone(),
)

/** Why a draft cannot be saved, in one sentence, or null when it can. */
internal fun draftProblem(draft: EventDraft): String? = when {
    draft.title.isBlank() -> "Give the event a title."
    draft.calendarId.isBlank() -> "Pick a calendar to put it in."
    draft.end.isBefore(draft.start) -> "The event ends before it starts."
    draft.allDay && draft.end.toLocalDate().isBefore(draft.start.toLocalDate()) -> "The event ends before it starts."
    draft.repeat?.until != null && draft.repeat.until.isBefore(draft.start.toLocalDate()) ->
        "The repeat ends before the event starts."
    draft.repeat?.count != null && draft.repeat.count < 1 -> "Repeat it at least once."
    else -> null
}

/** The properties a draft sets, shared by create and update so the two cannot drift apart. */
private fun draftProperties(draft: EventDraft, existing: JsonObject?): Map<String, JsonElement> = buildMap {
    put("title", JsonPrimitive(draft.title.trim()))
    put("description", JsonPrimitive(draft.description.trim()))
    put("calendarIds", buildJsonObject { put(draft.calendarId, true) })
    put("showWithoutTime", JsonPrimitive(draft.allDay))
    if (draft.allDay) {
        // All-day events are floating: the fourth is the fourth wherever it is read.
        val first = draft.start.toLocalDate()
        val last = draft.end.toLocalDate().coerceAtLeast(first)
        put("start", JsonPrimitive(LOCAL_STAMP.format(first.atStartOfDay())))
        put("timeZone", JsonNull)
        put("duration", JsonPrimitive(EventLength(days = ChronoUnit.DAYS.between(first, last) + 1).text()))
    } else {
        put("start", JsonPrimitive(LOCAL_STAMP.format(draft.start)))
        put("timeZone", JsonPrimitive(draft.timeZone.id))
        put("duration", JsonPrimitive(EventLength.between(draft.start, draft.end).text()))
    }
    put("locations", locationsFor(draft.location, existing))
    // Written as `recurrenceRule`, singular: see recurrenceRulesOf for why. The picker only
    // ever produces one rule, so there is nothing lost by never writing the array form.
    draft.repeat?.let { repeat ->
        put("recurrenceRule", repeat.rule() ?: JsonNull)
    }
}

/**
 * The locations map with its first entry renamed, or null when the location was cleared.
 *
 * The existing entry's id and its other properties are kept, because a location can carry
 * coordinates and links another client put there, and renaming it here should not drop them.
 */
private fun locationsFor(name: String, existing: JsonObject?): JsonElement {
    if (name.isBlank()) return JsonNull
    val old = existing?.get("locations") as? JsonObject
    val (key, value) = old?.entries?.firstOrNull()?.let { it.key to (it.value as? JsonObject) } ?: ("1" to null)
    val renamed = buildJsonObject {
        value?.forEach { (k, v) -> put(k, v) }
        put("@type", "Location")
        put("name", name.trim())
    }
    return buildJsonObject {
        old?.forEach { (k, v) -> if (k != key) put(k, v) }
        put(key, renamed)
    }
}

/** A new event, ready for CalendarEvent/set create. */
internal fun newEventObject(draft: EventDraft, uid: String = UUID.randomUUID().toString()): JsonObject = buildJsonObject {
    put("@type", "Event")
    put("uid", uid)
    draftProperties(draft, null).forEach { (k, v) -> if (v !is JsonNull) put(k, v) }
}

/**
 * The patch that turns [event] into [draft], for CalendarEvent/set update.
 *
 * Top level properties only, each replaced whole. A deeper path would fail on a server
 * where the parent property is absent, which is the common case for locations.
 */
internal fun eventPatch(event: CalendarEvent, draft: EventDraft): JsonObject =
    JsonObject(draftProperties(draft, event.raw))

/**
 * The recurrenceOverrides map with one instance changed, for "only this one".
 *
 * The whole map is sent rather than a path into it, for the same reason as above: a path
 * under a property the event does not have yet is refused.
 */
internal fun overridePatch(event: CalendarEvent, recurrenceId: LocalDateTime, change: JsonObject): JsonObject {
    val existing = (event.raw["recurrenceOverrides"] as? JsonObject).orEmpty()
    val key = LOCAL_STAMP.format(recurrenceId)
    // Any earlier override of this instance is kept underneath, so moving it once and then
    // renaming it does not put it back at its original time.
    val merged = buildJsonObject {
        (existing[key] as? JsonObject)?.forEach { (k, v) -> put(k, v) }
        change.forEach { (k, v) -> put(k, v) }
    }
    return buildJsonObject {
        putJsonObject("recurrenceOverrides") {
            existing.forEach { (k, v) -> if (k != key) put(k, v) }
            put(key, merged)
        }
    }
}

/** The change an edit of one instance makes, as an override. Only what differs from the series. */
internal fun instanceChange(event: CalendarEvent, draft: EventDraft): JsonObject {
    val props = draftProperties(draft.copy(repeat = null), event.raw)
    return buildJsonObject {
        listOf("title", "description", "start", "duration", "showWithoutTime", "locations").forEach { key ->
            val value = props[key] ?: return@forEach
            // Absent on the series and cleared here are the same thing, and not a change.
            if ((event.raw[key] ?: JsonNull) != value) put(key, value)
        }
        // A timed instance moved from a floating series, or the reverse, needs its zone as well.
        val zone = props["timeZone"]
        if (zone != null && zone != (event.raw["timeZone"] ?: JsonNull)) put("timeZone", zone)
    }
}

/** An override that removes one instance, for "delete only this one". */
internal fun excludedPatch(event: CalendarEvent, recurrenceId: LocalDateTime): JsonObject {
    val existing = (event.raw["recurrenceOverrides"] as? JsonObject).orEmpty()
    val key = LOCAL_STAMP.format(recurrenceId)
    return buildJsonObject {
        putJsonObject("recurrenceOverrides") {
            existing.forEach { (k, v) -> if (k != key) put(k, v) }
            putJsonObject(key) { put("excluded", true) }
        }
    }
}

/** UTC as the JMAP filter wants it, for the edges of the window being drawn. */
internal fun utcStamp(at: LocalDateTime, zone: ZoneId): String =
    at.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))

/** The editor's starting point for an existing occurrence. */
internal fun draftFrom(occurrence: Occurrence, calendars: List<CalendarInfo>): EventDraft {
    val event = occurrence.event
    val end = if (occurrence.allDay) occurrence.end.minusDays(1).coerceAtLeast(occurrence.start) else occurrence.end
    return EventDraft(
        title = occurrence.title,
        calendarId = event.calendarIds.firstOrNull { id -> calendars.any { it.id == id } } ?: event.calendarIds.firstOrNull().orEmpty(),
        start = occurrence.start,
        end = end,
        allDay = occurrence.allDay,
        location = occurrence.location,
        description = occurrence.description,
        repeat = SimpleRepeat.of(event.rules),
        // Timed events are shown in the reader's zone, so an edit is written back in it.
        timeZone = Regional.zone(),
    )
}

/** A property of a JSON object list the server sent, or empty. */
internal fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

/** The ids a /set named in a list such as destroyed. */
internal fun JsonElement?.idList(): List<String> = (this as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/**
 * The calendar half of the JMAP session.
 *
 * A thin layer on [Jmap.request], which owns the connection, the credential and the
 * refusal handling. Every method here throws [JmapError] with a sentence, so the pane only
 * ever has one kind of failure to show. [send] is the round trip, taken as a function so
 * the tests can answer as a server would without one.
 */
internal class CalendarClient(
    private val accountId: String,
    private val send: (List<JsonArray>) -> List<JsonArray>,
) {
    constructor(jmap: Jmap) : this(jmap.accountId, { calls -> jmap.request(CALENDARS, *calls.toTypedArray()) })

    private fun request(vararg calls: JsonArray): List<JsonArray> = send(calls.toList())

    private fun invocation(name: String, id: String, args: Map<String, JsonElement>): JsonArray = buildJsonArray {
        add(JsonPrimitive(name))
        add(JsonObject(mapOf("accountId" to JsonPrimitive(accountId)) + args))
        add(JsonPrimitive(id))
    }

    fun calendars(): List<CalendarInfo> = try {
        request(invocation("Calendar/get", "c", mapOf("ids" to JsonNull)))[0][1]
            .jsonObject["list"].objects().mapNotNull(::calendarInfoOf)
            .sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
    } catch (e: JmapError) {
        throw JmapError("Could not read your calendars: ${e.message}")
    }

    /**
     * Every event that touches the window, repeating ones included.
     *
     * The query asks the server for events whose occurrences overlap the window and fetches
     * them in the same round trip by back reference. The masters come back, not the
     * instances, and this file expands them, because expandRecurrences hands back synthetic
     * ids that cannot be edited and is optional for a server to support at all.
     *
     * If the query itself is refused, the whole calendar is fetched instead. ContactCard/query
     * turned out to be unimplemented on Stalwart 0.16 while ContactCard/get worked, and a
     * calendar that shows everything is better than one that shows an error.
     */
    fun events(after: String, before: String): List<CalendarEvent> {
        val query = invocation(
            "CalendarEvent/query",
            "q",
            mapOf("filter" to buildJsonObject { put("after", after); put("before", before) }),
        )
        val get = invocation(
            "CalendarEvent/get",
            "g",
            mapOf(
                "#ids" to buildJsonObject {
                    put("resultOf", "q")
                    put("name", "CalendarEvent/query")
                    put("path", "/ids")
                },
            ),
        )
        val list = try {
            request(query, get)[1][1].jsonObject["list"]
        } catch (first: JmapError) {
            try {
                request(invocation("CalendarEvent/get", "g", mapOf("ids" to JsonNull)))[0][1].jsonObject["list"]
            } catch (e: JmapError) {
                throw JmapError("Could not read your events: ${e.message}")
            }
        }
        return list.objects().mapNotNull(::calendarEventOf)
    }

    /** Creates an event and returns its id. */
    fun create(event: JsonObject): String = set("create", mapOf("create" to buildJsonObject { put("new", event) })) { response ->
        (response["created"] as? JsonObject)?.get("new")?.jsonObject?.get("id").text()
            ?: throw JmapError(calendarRefusal(response, "notCreated", "The server would not create the event"))
    }

    fun update(id: String, patch: JsonObject) {
        set("update", mapOf("update" to buildJsonObject { put(id, patch) })) { response ->
            if ((response["updated"] as? JsonObject)?.containsKey(id) != true) {
                throw JmapError(calendarRefusal(response, "notUpdated", "The server would not change the event"))
            }
        }
    }

    fun destroy(id: String) {
        set("destroy", mapOf("destroy" to JsonArray(listOf(JsonPrimitive(id))))) { response ->
            if (id !in response["destroyed"].idList()) {
                throw JmapError(calendarRefusal(response, "notDestroyed", "The server would not delete the event"))
            }
        }
    }

    /** Writes whether a calendar is drawn, so the choice follows the account to other clients. */
    fun setVisible(calendarId: String, visible: Boolean) {
        val response = request(
            invocation("Calendar/set", "v", mapOf("update" to buildJsonObject { putJsonObject(calendarId) { put("isVisible", visible) } })),
        )[0][1].jsonObject
        if ((response["updated"] as? JsonObject)?.containsKey(calendarId) != true) {
            throw JmapError(calendarRefusal(response, "notUpdated", "The server would not remember that choice"))
        }
    }

    private fun <T> set(what: String, args: Map<String, JsonElement>, read: (JsonObject) -> T): T {
        val response = try {
            request(invocation("CalendarEvent/set", what, args))[0][1].jsonObject
        } catch (e: JmapError) {
            throw JmapError("The calendar did not save: ${e.message}")
        }
        return read(response)
    }
}

/**
 * The server's reason for refusing one item of a /set, as a sentence.
 *
 * The description when there is one, since that is the server explaining itself, and the
 * type otherwise, which is at least something to search for.
 */
internal fun calendarRefusal(response: JsonObject, field: String, prefix: String): String {
    val reason = (response[field] as? JsonObject)?.values?.firstOrNull() as? JsonObject
    val words = reason?.get("description").text()?.takeIf { it.isNotBlank() }
        ?: reason?.get("type").text()
        ?: "no reason given"
    val properties = reason?.get("properties").idList()
    return "$prefix: $words" + if (properties.isNotEmpty()) " (${properties.joinToString(", ")})." else "."
}

/** A calendar's colour, falling back to a stable one from its id when the server gave none. */
internal fun calendarColour(calendar: CalendarInfo?): androidx.compose.ui.graphics.Color =
    calendar?.colour?.let(::parseCssColour) ?: avatarColor(calendar?.id.orEmpty())
