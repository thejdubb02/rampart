package org.rampart

/*
 * Whether a message mentions a date and a time, which is what puts "Add to calendar" on it.
 *
 * **This costs nothing on purpose.** Every model call in Rampart is a button press, so the
 * chip that offers one cannot itself be a model call: it would be a bill for every message
 * opened. So the question the chip asks is a cheap one, answered by a handful of patterns on
 * the text already on screen, and the model is only asked what the event actually is once
 * somebody presses the chip.
 *
 * It errs towards quiet. A chip that turns up on every newsletter is one people stop seeing,
 * so a date on its own is not enough, and neither is a time on its own: the two have to sit
 * near each other, the way "Tuesday 3 October at 14:30" or "your flight departs 09:15 on
 * 12 Nov" do. The parts of a message that are always full of dates and times and are never
 * the event (the quoted original under a reply, the "On Monday at 10:15, Sam wrote:" line
 * above it, the Date: line of a forwarded header) are taken out before looking.
 */

/** How far apart, in characters, a date and a time can be and still read as one mention. */
internal const val MENTION_REACH = 120

/** How much of a message is looked at. A date and time worth adding is not forty pages down. */
internal const val MENTION_SCAN = 20_000

private const val MONTH = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"


/*
 * A date, in the shapes mail writes them. Case is ignored throughout.
 *
 * Weekday names count because "Tuesday at 3pm" is a meeting, and so do today, tonight and
 * tomorrow for the same reason. A bare number does not, since "3" is not a date.
 */
private val DATES = listOf(
    // 3 October, 3rd Oct 2026, 3 Oct.
    Regex("\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?$MONTH\\b\\.?(?:\\s+\\d{4})?", RegexOption.IGNORE_CASE),
    // October 3, Oct 3rd, 2026.
    Regex("\\b$MONTH\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?\\b(?:,?\\s+\\d{4})?", RegexOption.IGNORE_CASE),
    // 2026-10-03.
    Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b"),
    // 03/10/2026, 10/3/26 and 3.10.2026. Which way round is the model's problem, not this one's.
    Regex("\\b\\d{1,2}[/.]\\d{1,2}[/.](?:\\d{4}|\\d{2})\\b"),
    Regex("\\b(?:mon|tues|wednes|thurs|fri|satur|sun)day\\b", RegexOption.IGNORE_CASE),
    // Abbreviated weekdays only with their capital, because "sat" and "sun" are words.
    Regex("\\b(?:Mon|Tue|Tues|Wed|Thu|Thur|Thurs|Fri|Sat|Sun)\\b\\.?"),
    Regex("\\b(?:today|tonight|tomorrow)\\b", RegexOption.IGNORE_CASE),
)

/*
 * A time of day. A 24 hour time needs its colon (or the French h, as in 14h30), and a 12 hour
 * one needs its am or pm, so a price, a version number, a date written with dots or a count
 * is not mistaken for one. Noon and midday are times.
 */
private val TIMES = listOf(
    Regex("\\b(?:[01]?\\d|2[0-3])[:h][0-5]\\d\\b"),
    Regex("\\b(?:1[0-2]|0?[1-9])(?::[0-5]\\d)?\\s?(?:am|pm|a\\.m\\.|p\\.m\\.)(?![a-z])", RegexOption.IGNORE_CASE),
    Regex("\\b(?:noon|midday)\\b", RegexOption.IGNORE_CASE),
)

/*
 * Lines that are about the message rather than in it. The attribution a reply opens its quote
 * with, and the headers a forward repeats, all carry a date and a time and none of them is an
 * event. The Date: of a forwarded header counts the same way.
 */
private val NOT_THE_MESSAGE = Regex(
    "^(?:>.*|.*\\bwrote:\\s*|(?:date|sent|received):.*|-{3,} ?(?:forwarded|original) message ?-{3,}.*)$",
    setOf(RegexOption.IGNORE_CASE),
)

/**
 * The part of [text] worth looking in: the writer's own words, without quoted lines and the
 * header lines above them, and without anything past [MENTION_SCAN] characters.
 */
internal fun ownWords(text: String): String =
    text.take(MENTION_SCAN).lineSequence()
        .filterNot { NOT_THE_MESSAGE.matches(it.trim()) }
        .joinToString("\n")

/**
 * Whether [subject] or [text] mentions a date and a time close together.
 *
 * A plain local check with no network and no model, cheap enough to run on every message
 * that opens. The subject is looked at separately, so a subject date and a body time far
 * down the message do not add up to a meeting that neither of them describes.
 */
internal fun mentionsDateAndTime(subject: String, text: String): Boolean =
    closeTogether(subject) || closeTogether(ownWords(text))

private fun closeTogether(text: String): Boolean {
    if (text.isBlank()) return false
    val dates = DATES.flatMap { r -> r.findAll(text).map { it.range } }
    if (dates.isEmpty()) return false
    val times = TIMES.flatMap { r -> r.findAll(text).map { it.range } }
    return times.any { t ->
        dates.any { d ->
            // The gap between the two, zero when they touch or overlap.
            val gap = maxOf(t.first - d.last, d.first - t.last, 0)
            gap <= MENTION_REACH
        }
    }
}
