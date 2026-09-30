package org.rampart

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Follow-up flags (RAM-110): "come back to this on Tuesday", kept on the message itself.
 *
 * **The flag lives on the server, as keywords, the way a snooze does.** Every device that
 * reads the mailbox sees the same flag and the same date, and the Follow up list is a
 * question asked of the server rather than a file on one computer. Three keywords:
 *
 * - `$followup` says the message is flagged. It is the one that is searched for.
 * - `$followup-20261003t0900z` says when it is due, as a UTC minute in lowercase.
 * - `$followup-noreply` says a reply from somebody else cancels it.
 *
 * Why these shapes, from Stalwart 0.16's own source (crates/types/src/keyword.rs):
 * a keyword that is not one of the named system ones is kept exactly as sent, **case and
 * all**, and cut at 128 characters. JMAP says keywords are case-insensitive, but Stalwart
 * compares the stored text as it is, so `$FollowUp` and `$followup` would be two different
 * keywords there. Everything here is therefore written in lowercase and read ignoring case.
 * The characters are all inside RFC 9051's flag-keyword atom (no space, no parenthesis,
 * brace, percent, star, quote, backslash or closing bracket), and Stalwart's IMAP answers
 * `PERMANENTFLAGS (... \*)`, so an IMAP client can see and keep all three, and Rampart's
 * own IMAP backend stores them as ordinary user flags.
 *
 * One cost worth knowing: Stalwart's per-account cache has room for 99 distinct custom
 * keywords (crates/email/src/cache/email.rs, 128 bits less the 29 system ones), and a
 * keyword past that is stored but not shown. Every distinct due minute is one of those,
 * as every distinct snooze time already is, so the date is kept to the minute and a
 * cleared flag takes its date keyword with it.
 *
 * The reminder itself only fires while Rampart is running: neither protocol can schedule
 * anything, exactly as with snooze. The flag and the list are on the server regardless, so
 * a flag set on one computer is due on every one.
 */

/** The keyword that marks a message as flagged for follow-up. */
internal const val FOLLOW_UP = "\$followup"

/** The keyword that asks for the flag to go away by itself when somebody answers. */
internal const val FOLLOW_UP_NO_REPLY = "\$followup-noreply"

private const val DUE_PREFIX = "\$followup-"

/** yyyymmddThhmmZ, lowercased when written. Minutes, because seconds would only spend keywords. */
private val DUE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'").withZone(ZoneOffset.UTC)

private val DUE_SHAPE = Regex("^\\\$followup-(\\d{8})t(\\d{4})z$", RegexOption.IGNORE_CASE)

/**
 * A message's follow-up flag, read from its keywords.
 *
 * [due] null means flagged without a date Rampart can read: another client set only
 * `$followup`, or the date keyword was mangled. That is still a flag, listed as undated and
 * never announced, because guessing a date is worse than showing none. [dateKeywords] is
 * every date keyword actually on the message, as written, so clearing removes all of them.
 */
internal data class FollowUp(
    val due: Instant?,
    val noReply: Boolean,
    val dateKeywords: List<String>,
)

/** The keyword that carries [due]. Truncated to the minute and always lowercase. */
internal fun followUpDueKeyword(due: Instant): String =
    DUE_PREFIX + DUE_FORMAT.format(due.truncatedTo(ChronoUnit.MINUTES)).lowercase()

/** When a date keyword says, or null for anything that is not exactly one. */
internal fun followUpDueOf(keyword: String): Instant? {
    val match = DUE_SHAPE.matchEntire(keyword) ?: return null
    val (day, clock) = match.destructured
    return runCatching {
        LocalDateTime.of(
            day.substring(0, 4).toInt(), day.substring(4, 6).toInt(), day.substring(6, 8).toInt(),
            clock.substring(0, 2).toInt(), clock.substring(2, 4).toInt(),
        ).toInstant(ZoneOffset.UTC)
    }.getOrNull()
}

/**
 * The flag on a message with these keywords, or null when there is none.
 *
 * Only `$followup` makes a flag. A date keyword left behind without it (another client
 * cleared half of it) is not one, since the thing that is searched for is not there.
 * When two devices raced and left two dates, the earlier one wins: reminding early is a
 * nuisance, reminding late is the failure the feature exists to prevent.
 */
internal fun followUpIn(keywords: Collection<String>): FollowUp? {
    if (keywords.none { it.equals(FOLLOW_UP, ignoreCase = true) }) return null
    val dated = keywords.mapNotNull { k -> followUpDueOf(k)?.let { k to it } }
    return FollowUp(
        due = dated.minOfOrNull { it.second },
        noReply = keywords.any { it.equals(FOLLOW_UP_NO_REPLY, ignoreCase = true) },
        dateKeywords = dated.map { it.first },
    )
}

/** Whether a message is flagged at all, dated or not. */
internal fun isFollowUp(keywords: Collection<String>): Boolean = followUpIn(keywords) != null

/** Whether the flag on these keywords has a date and it has come. */
internal fun followUpDue(keywords: Collection<String>, now: Instant): Boolean =
    followUpIn(keywords)?.due?.let { !it.isAfter(now) } == true

/**
 * What to add and take away in one Email/set.
 *
 * Both halves go in the same request, so a message is never left with two dates or with a
 * date and no flag. [remove] names each keyword exactly as it is on the message, because
 * Stalwart matches that text as it is.
 */
internal data class KeywordChange(val add: Set<String>, val remove: Set<String>) {
    /** The keywords after this change has been accepted, for painting the row. */
    fun appliedTo(keywords: Set<String>): Set<String> = keywords - remove + add
}

/**
 * Flagging for [due], replacing any date already there.
 *
 * [noReply] is written when asked for and taken away when not, so choosing again without
 * it really does turn it off.
 */
internal fun setFollowUp(keywords: Collection<String>, due: Instant, noReply: Boolean): KeywordChange {
    val date = followUpDueKeyword(due)
    val add = linkedSetOf(FOLLOW_UP, date)
    if (noReply) add += FOLLOW_UP_NO_REPLY
    val remove = keywords.filter { k ->
        (k.startsWith(DUE_PREFIX, ignoreCase = true) && followUpDueOf(k) != null && k != date) ||
            (!noReply && k.equals(FOLLOW_UP_NO_REPLY, ignoreCase = true)) ||
            // Upper or mixed case copies of our own keywords, left by another client, are
            // replaced by the lowercase ones rather than kept beside them.
            (k != FOLLOW_UP && k.equals(FOLLOW_UP, ignoreCase = true)) ||
            (noReply && k != FOLLOW_UP_NO_REPLY && k.equals(FOLLOW_UP_NO_REPLY, ignoreCase = true))
    }.toSet()
    return KeywordChange(add - remove, remove)
}

/**
 * Taking the flag off: `$followup`, every date and the no-reply mark, whatever their case.
 *
 * Anything else starting `$followup-` goes too, even when it is not a date Rampart can
 * read, since it can only be a broken copy of one of ours and leaving it spends one of the
 * account's keywords for nothing.
 */
internal fun clearFollowUp(keywords: Collection<String>): KeywordChange =
    KeywordChange(
        add = emptySet(),
        remove = keywords.filter { it.equals(FOLLOW_UP, ignoreCase = true) || it.startsWith(DUE_PREFIX, ignoreCase = true) }.toSet(),
    )

/**
 * The named times offered, each the same local time the snooze choices use.
 *
 * Tomorrow and Next week are the snooze's own ([SnoozeUntil]), so the two features cannot
 * drift apart on what "tomorrow" means. In 3 days is tomorrow morning counted from two
 * days on, which is the same rule applied once more.
 */
internal enum class FollowUpWhen(val label: String) {
    TOMORROW("Tomorrow"),
    IN_THREE_DAYS("In 3 days"),
    NEXT_WEEK("Next week"),
    ;

    fun dueAt(now: ZonedDateTime, weekStart: DayOfWeek = Regional.firstDayOfWeek()): ZonedDateTime = when (this) {
        TOMORROW -> SnoozeUntil.TOMORROW.dueAt(now)
        IN_THREE_DAYS -> SnoozeUntil.TOMORROW.dueAt(now.plusDays(2)).withZoneSameInstant(now.zone)
        NEXT_WEEK -> SnoozeUntil.NEXT_WEEK.dueAt(now, weekStart = weekStart)
    }
}

/** When a follow-up is due, as a person would say it, in [now]'s zone. */
internal fun followUpText(due: Instant?, now: ZonedDateTime, region: Region = Regional.current()): String {
    if (due == null) return "Follow up, no date"
    if (!due.isAfter(now.toInstant())) return "Follow up now"
    // The snooze wording, which already says "back at", "back tomorrow at" and so on.
    return "Follow up " + snoozeText(due, now, region).removePrefix("back ")
}

/** Whether [message] was sent by the person, which is when "only if no reply" means anything. */
internal fun sentByMe(message: Summary, own: Set<String>): Boolean =
    message.fromEmail.isNotBlank() && message.fromEmail.trim().lowercase() in own

/**
 * Whether someone answered a message the person sent.
 *
 * An answer is another message in the same conversation, from an address that is not one
 * of the person's own, that arrived after [flagged] was sent. The person's own follow-up
 * in the thread is not an answer, and nor is anything from before the send or from
 * another conversation. [own] is lowercase.
 *
 * Only asked where the server keeps conversations across folders (JMAP): an IMAP thread
 * is one folder, and the reply is in a different one from the sent message.
 */
internal fun answeredBySomeoneElse(flagged: Summary, thread: List<Summary>, own: Set<String>): Boolean {
    if (flagged.threadId.isBlank()) return false
    val sent = instantOf(flagged.receivedAt) ?: return false
    return thread.any { m ->
        m.id != flagged.id &&
            m.threadId == flagged.threadId &&
            m.fromEmail.isNotBlank() &&
            m.fromEmail.trim().lowercase() !in own &&
            instantOf(m.receivedAt)?.isAfter(sent) == true
    }
}

/** The flagged messages that are due now, the earliest first. Undated ones are not due. */
internal fun dueFollowUps(messages: List<Summary>, now: Instant): List<Summary> =
    messages.mapNotNull { m -> followUpIn(m.keywords)?.due?.takeIf { !it.isAfter(now) }?.let { m to it } }
        .sortedBy { it.second }
        .map { it.first }

/*
 * Announcing each due flag once.
 *
 * What has been announced is local state about this computer's notifications, not the
 * flag, so it is a small file here rather than anything on the server: another device
 * announcing the same flag is right, since the person may be at either one.
 */

/** One flag at one date. A new date is a new reminder; clearing and setting again is too. */
internal fun noticeToken(id: String, due: Instant): String = id + "|" + followUpDueKeyword(due)

/**
 * Which of [due] to announce now, and what to remember afterwards.
 *
 * [flagged] is every flagged message the check could see. What is remembered is kept only
 * for flags still there, so the file cannot grow for ever and a flag cleared and set again
 * for the same date is announced again. Only called after a complete read: pruning against
 * a list that failed half way would announce everything again.
 */
internal fun toAnnounce(
    due: List<Summary>,
    flagged: List<Summary>,
    remembered: Set<String>,
): Pair<List<Summary>, Set<String>> {
    fun token(m: Summary) = followUpIn(m.keywords)?.due?.let { noticeToken(m.id, it) }
    val fresh = due.filter { m -> token(m)?.let { it !in remembered } == true }
    val live = flagged.mapNotNull(::token).toSet()
    val keep = remembered.filter { it in live }.toSet() + fresh.mapNotNull(::token)
    return fresh to keep
}

/** The remembered tokens, per account, in `followup-notified.json` beside the settings. */
internal object FollowUpNotices {
    private val store = JsonStore("followup-notified.json")

    fun remembered(account: String): Set<String> =
        (store.read()[account] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()

    fun remember(account: String, tokens: Set<String>) {
        store.write { put(account, JsonArray(tokens.sorted().map(::JsonPrimitive))) }
    }

    /** Everything this computer remembered about an account's flags. */
    fun forget(account: String) {
        store.write { remove(account) }
    }
}
