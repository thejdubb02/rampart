package org.rampart

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAccessor
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Dates and times as Rampart draws them.
 *
 * Translations are RAM-49, and the language selector on Language, Region and Time is
 * where they will appear. Until then the interface stays in English. What this changes
 * is the month and day names, the number shapes, the order of a date, the clock, the
 * time zone every mail and calendar time is shown in, and which day a week starts on.
 *
 * A mail header, a JMAP timestamp and a calendar file are not drawn. They stay in the
 * form the protocol asks for, and nothing here is used to read or write them.
 */
internal object Regional {
    private var cached: Pair<String, Region>? = null
    private var checkedAt = 0L

    // Settings are a file read and a JSON parse, and a long list or a month of calendar
    // formats hundreds of dates a frame. So the choices are looked at again at most every
    // two seconds: a change made on the settings page shows up that quickly.
    private const val RECHECK_MS = 2_000L

    /** A choice was just saved here: look again on the next call rather than in two seconds. */
    @Synchronized
    fun invalidate() {
        checkedAt = 0L
    }

    /**
     * The choices in force.
     *
     * Read once, and again only when a choice changes or the computer's own language or
     * zone changes. Automatic has to follow the computer, so those two are part of the key.
     */
    @Synchronized
    internal fun current(): Region {
        val now = System.currentTimeMillis()
        cached?.let { if (now - checkedAt < RECHECK_MS) return it.second }
        checkedAt = now
        val saved = Settings.regionalRaw()
        val systemLocale = Locale.getDefault()
        val systemZone = ZoneId.systemDefault()
        val key = listOf(
            saved.language, saved.listDate, saved.dateOrder, saved.timeFormat, saved.timeZone, saved.weekStart,
            systemLocale.toLanguageTag(), systemZone.id,
        ).joinToString("\u0000")
        cached?.let { if (it.first == key) return it.second }
        val region = regionFrom(
            saved.language, saved.listDate, saved.dateOrder, saved.timeFormat, saved.timeZone, saved.weekStart,
            systemLocale, systemZone,
        )
        cached = key to region
        return region
    }

    fun zone(): ZoneId = current().zone

    fun locale(): Locale = current().locale

    fun time(t: TemporalAccessor, withSeconds: Boolean = false): String = formatTime(current(), t, withSeconds)

    fun shortDate(d: LocalDate): String = formatShort(current(), d)

    fun fullDate(d: LocalDate): String = formatFull(current(), d)

    fun listStamp(instant: Instant, now: Instant = Instant.now()): String = formatList(current(), instant, now)

    fun dateTime(t: TemporalAccessor): String = formatDateTime(current(), t, withSeconds = false)

    fun firstDayOfWeek(): DayOfWeek = current().weekStart

    fun monthTitle(d: LocalDate): String = formatMonthTitle(current(), d)

    fun dayTitle(d: LocalDate): String = formatDayTitle(current(), d)

    fun monthDay(d: LocalDate): String = formatMonthDay(current(), d)
}

/** Month first, day first, or year first. */
internal enum class DateOrder { MDY, DMY, YMD }

/**
 * One resolved set of choices.
 *
 * A test builds one and passes it in, so the sentence does not depend on the computer
 * the test runs on or on the settings file.
 */
internal data class Region(
    val locale: Locale,
    val zone: ZoneId,
    val order: DateOrder,
    val hour12: Boolean,
    val listFull: Boolean,
    val weekStart: DayOfWeek,
)

/** The six values as stored, before an unknown one is read as automatic. */
internal data class RegionalRaw(
    val language: String,
    val listDate: String,
    val dateOrder: String,
    val timeFormat: String,
    val timeZone: String,
    val weekStart: String,
)

/** "en", or "auto" for anything else. The interface has no other language yet. */
internal fun languageToken(raw: String?): String = if (raw?.trim()?.lowercase() == "en") "en" else "auto"

/** "full", or "smart" for anything else. Smart is the message list's ordinary form. */
internal fun listDateToken(raw: String?): String = if (raw?.trim()?.lowercase() == "full") "full" else "smart"

/** A known order, or "auto". */
internal fun dateOrderToken(raw: String?): String {
    val token = raw?.trim()?.lowercase()
    return if (token == "mdy" || token == "dmy" || token == "ymd") token else "auto"
}

/** "12", "24", or "auto". */
internal fun timeFormatToken(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "12" -> "12"
    "24" -> "24"
    else -> "auto"
}

/**
 * "auto", or a zone id this Java knows.
 *
 * The id is kept as written. Zone ids are case sensitive, and lowercasing one would
 * turn a real zone into one that does not exist.
 */
internal fun timeZoneToken(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed.equals("auto", ignoreCase = true)) return "auto"
    return if (runCatching { ZoneId.of(trimmed) }.isSuccess) trimmed else "auto"
}

/** Sunday, Monday, Saturday, or "auto". Those are the three a calendar actually offers. */
internal fun weekStartToken(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "sunday" -> "sunday"
    "monday" -> "monday"
    "saturday" -> "saturday"
    else -> "auto"
}

/**
 * The choices resolved against [systemLocale] and [systemZone].
 *
 * Anything that is not a known token is automatic, except the message list, whose
 * unknown value is Smart. Automatic follows the language for the date order, the clock
 * and the first day of the week, and follows the computer for the zone.
 */
internal fun regionFrom(
    language: String?,
    listDate: String?,
    dateOrder: String?,
    timeFormat: String?,
    timeZone: String?,
    weekStart: String?,
    systemLocale: Locale,
    systemZone: ZoneId,
): Region {
    val locale = if (languageToken(language) == "en") Locale.ENGLISH else systemLocale
    val order = when (dateOrderToken(dateOrder)) {
        "mdy" -> DateOrder.MDY
        "dmy" -> DateOrder.DMY
        "ymd" -> DateOrder.YMD
        else -> orderFromLocale(locale)
    }
    val hour12 = when (timeFormatToken(timeFormat)) {
        "12" -> true
        "24" -> false
        else -> hour12FromLocale(locale)
    }
    val zone = when (val token = timeZoneToken(timeZone)) {
        "auto" -> systemZone
        else -> ZoneId.of(token)
    }
    val start = when (weekStartToken(weekStart)) {
        "sunday" -> DayOfWeek.SUNDAY
        "monday" -> DayOfWeek.MONDAY
        "saturday" -> DayOfWeek.SATURDAY
        else -> WeekFields.of(locale).firstDayOfWeek
    }
    return Region(locale, zone, order, hour12, listDateToken(listDate) == "full", start)
}

/**
 * [base], shown in [locale] and [zone].
 *
 * The same locale keeps the saved date order and clock, which is what the settings
 * page chose. A different locale, the way a test names one, follows that locale so the
 * line does not depend on the computer.
 */
internal fun shownIn(base: Region, locale: Locale, zone: ZoneId): Region {
    if (locale == base.locale) return base.copy(zone = zone)
    return base.copy(
        locale = locale,
        zone = zone,
        order = orderFromLocale(locale),
        hour12 = hour12FromLocale(locale),
    )
}

/** Which order a short date in [locale] uses: whichever of year, month and day comes first. */
internal fun orderFromLocale(locale: Locale): DateOrder {
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale,
    )
    val rank = patternLetters(pattern) { ch ->
        when (ch) {
            'y', 'u' -> 'y'
            'M', 'L' -> 'M'
            'd' -> 'd'
            else -> null
        }
    }
    return when (rank.take(3).joinToString("")) {
        "Mdy" -> DateOrder.MDY
        "dMy" -> DateOrder.DMY
        "yMd" -> DateOrder.YMD
        else -> DateOrder.MDY
    }
}

/** Whether a short time in [locale] is a 12-hour clock. A pattern with a, h or K is. */
internal fun hour12FromLocale(locale: Locale): Boolean {
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale,
    )
    return patternLetters(pattern) { ch ->
        if (ch == 'a' || ch == 'h' || ch == 'K') 'a' else null
    }.isNotEmpty()
}

/**
 * The letters of a date pattern, skipping quoted text.
 *
 * A quote is how a pattern keeps a word like "at" from being read as fields. Counting
 * those letters would invent a clock in a sentence.
 */
private fun patternLetters(pattern: String, keep: (Char) -> Char?): List<Char> {
    val out = ArrayList<Char>()
    var quoted = false
    for (ch in pattern) {
        if (ch == '\'') {
            quoted = !quoted
            continue
        }
        if (quoted) continue
        val key = keep(ch) ?: continue
        if (key !in out) out += key
    }
    return out
}

/** The seven days, starting on [start]. Monday is the first of [DayOfWeek.entries], so Sunday rotates. */
internal fun weekDays(start: DayOfWeek): List<DayOfWeek> {
    val all = DayOfWeek.entries
    val at = all.indexOf(start)
    return all.drop(at) + all.take(at)
}

/**
 * A clock.
 *
 * An [Instant] has no hour of its own, so it is placed in the region's zone. A value that
 * already has a clock, a local time or a time someone already placed in a zone, is left
 * there: moving it again would show the wrong hour.
 */
internal fun formatTime(region: Region, t: TemporalAccessor, withSeconds: Boolean = false): String {
    val clock: TemporalAccessor = if (t is Instant) t.atZone(region.zone) else t
    val pattern = when {
        withSeconds && region.hour12 -> "h:mm:ss a"
        withSeconds -> "HH:mm:ss"
        region.hour12 -> "h:mm a"
        else -> "HH:mm"
    }
    return DateTimeFormatter.ofPattern(pattern, region.locale).format(clock)
}

/** A date with no year, for a week title and a chart axis. */
internal fun formatShort(region: Region, d: LocalDate): String {
    val pattern = when (region.order) {
        DateOrder.MDY -> "MMM d"
        DateOrder.DMY -> "d MMM"
        DateOrder.YMD -> "MM-dd"
    }
    return d.format(DateTimeFormatter.ofPattern(pattern, region.locale))
}

/** A date with its year. */
internal fun formatFull(region: Region, d: LocalDate): String {
    val pattern = when (region.order) {
        DateOrder.MDY -> "MMMM d, yyyy"
        DateOrder.DMY -> "d MMMM yyyy"
        DateOrder.YMD -> "yyyy-MM-dd"
    }
    return d.format(DateTimeFormatter.ofPattern(pattern, region.locale))
}

/** "September 2026". The month and the year do not change places. */
internal fun formatMonthTitle(region: Region, d: LocalDate): String =
    d.format(DateTimeFormatter.ofPattern("MMMM yyyy", region.locale))

/** The weekday and the full date, for a day heading. */
internal fun formatDayTitle(region: Region, d: LocalDate): String =
    d.dayOfWeek.getDisplayName(TextStyle.FULL, region.locale) + " " + formatFull(region, d)

/**
 * The day and the month, without a year.
 *
 * Year first has no short form of that, so it uses the full date rather than inventing one.
 */
internal fun formatMonthDay(region: Region, d: LocalDate): String = when (region.order) {
    DateOrder.MDY -> d.format(DateTimeFormatter.ofPattern("MMMM d", region.locale))
    DateOrder.DMY -> d.format(DateTimeFormatter.ofPattern("d MMMM", region.locale))
    DateOrder.YMD -> formatFull(region, d)
}

/** The full date, then the clock. */
internal fun formatDateTime(region: Region, t: TemporalAccessor, withSeconds: Boolean = false): String {
    val zoned = when (t) {
        is Instant -> t.atZone(region.zone)
        is ZonedDateTime -> t
        is LocalDateTime -> t
        else -> return formatTime(region, t, withSeconds)
    }
    val date = when (zoned) {
        is ZonedDateTime -> zoned.toLocalDate()
        is LocalDateTime -> zoned.toLocalDate()
        else -> return formatTime(region, t, withSeconds)
    }
    return formatFull(region, date) + ", " + formatTime(region, zoned, withSeconds)
}

/**
 * The stamp on a message row.
 *
 * Today is the time. The six days before today are the weekday and the time, which is
 * what "this week" means on a list: recent enough that the day name is the useful part.
 * Anything older, and anything that has not happened yet, is the full date. The count is
 * calendar days in the region's zone, so a clock change does not add or drop a day.
 */
internal fun formatList(region: Region, instant: Instant, now: Instant): String {
    val whenAt = instant.atZone(region.zone)
    val date = whenAt.toLocalDate()
    if (region.listFull) return formatFull(region, date)
    val age = ChronoUnit.DAYS.between(date, now.atZone(region.zone).toLocalDate())
    return when (age) {
        0L -> formatTime(region, whenAt)
        in 1L..6L -> whenAt.dayOfWeek.getDisplayName(TextStyle.FULL, region.locale) + " " + formatTime(region, whenAt)
        else -> formatFull(region, date)
    }
}

/** "BST", "GMT", "PDT": the short name of the zone at that moment. */
internal fun formatZone(region: Region, at: ZonedDateTime): String =
    DateTimeFormatter.ofPattern("zzz", region.locale).format(at)
