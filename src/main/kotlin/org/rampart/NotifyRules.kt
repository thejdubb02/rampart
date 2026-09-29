package org.rampart

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/*
 * The decisions behind a new-mail notification, with no window, no tray and no clock of
 * their own, so every one of them can be tested.
 *
 * Three questions, answered in this order: which arrivals belong to the same burst, which
 * of them are still worth mentioning by the time the burst is ready, and whether anything
 * should be said at all right now.
 */

/** One message a notification can open. The summary is kept so opening it needs no second fetch. */
internal data class MailRef(val account: String, val summary: Summary)

/** A message that arrived, and the account it arrived in. */
internal data class Arrival(val account: String, val summary: Summary)

/** What a notification says, and the message a click on it opens. */
internal data class MailNotice(val title: String, val body: String, val opens: MailRef?)

/**
 * Arrivals gathered into bursts.
 *
 * A burst is released once nothing new has come for [settle] milliseconds, or once it is
 * [longest] milliseconds old, whichever is first. The first is what turns a mailing list's
 * three messages in two seconds into one notification. The second stops a steady trickle
 * from holding the first message back indefinitely.
 *
 * Not thread safe on its own: [NewMailNotices] only ever touches it from one coroutine.
 */
internal class Bursts(private val settle: Long = 4_000, private val longest: Long = 20_000) {
    private val pending = mutableListOf<Arrival>()
    private var first = 0L
    private var last = 0L

    fun add(arrivals: List<Arrival>, now: Long) {
        if (arrivals.isEmpty()) return
        if (pending.isEmpty()) first = now
        last = now
        // The same message can be reported twice when a push and a timed poll land together.
        arrivals.forEach { a -> if (pending.none { it.account == a.account && it.summary.id == a.summary.id }) pending += a }
    }

    /** The burst, if it is ready, and it is then forgotten. Null while it is still gathering. */
    fun due(now: Long): List<Arrival>? {
        if (pending.isEmpty()) return null
        if (now - last < settle && now - first < longest) return null
        return pending.toList().also { pending.clear() }
    }

    val waiting: Int get() = pending.size
}

/**
 * The arrivals in [burst] that are still new at the moment it is ready to be shown.
 *
 * [inboxNow] is each account's inbox as it stands now, or null for an account whose inbox
 * could not be read, in which case its arrivals are kept: a notification that turns out to
 * be slightly stale is better than mail that is never mentioned because the network blinked.
 *
 * Dropped:
 * - anything in an account the person turned notifications off for;
 * - anything no longer in the inbox, which is mail a filter filed away, on the server or
 *   here, between it landing and the burst being ready;
 * - anything now marked read, which is mail read in another client in the meantime.
 */
internal fun stillNew(
    burst: List<Arrival>,
    inboxNow: Map<String, List<Summary>?>,
    silenced: Set<String>,
): List<Arrival> = burst.filter { a ->
    if (a.account in silenced) return@filter false
    val inbox = inboxNow[a.account] ?: return@filter true
    val now = inbox.firstOrNull { it.id == a.summary.id } ?: return@filter false
    !now.seen
}

/**
 * The words, for one burst.
 *
 * One message says who and what. Several say how many and from how many people, because a
 * list of subjects in a notification is too long to read and gets cut off anyway. Either
 * way a click opens a message: the one there is, or the newest of several, which is the one
 * at the top of the inbox and the one most likely to be why somebody clicked.
 */
internal fun burstNotice(arrivals: List<Arrival>): MailNotice? {
    if (arrivals.isEmpty()) return null
    val newest = arrivals.maxBy { it.summary.receivedAt }
    val opens = MailRef(newest.account, newest.summary)
    if (arrivals.size == 1) {
        val only = arrivals[0].summary
        return MailNotice(sender(only), only.subject.ifBlank { "(no subject)" }, opens)
    }
    val people = arrivals.map { sender(it.summary) }.distinctBy { it.lowercase() }
    val title = if (people.size == 1) {
        "${arrivals.size} new messages from ${people[0]}"
    } else {
        "${arrivals.size} new messages from ${people.size} people"
    }
    val body = when (people.size) {
        1 -> newest.summary.subject.ifBlank { "(no subject)" }
        2, 3 -> joined(people)
        else -> people.take(2).joinToString(", ") + " and ${people.size - 2} others"
    }
    return MailNotice(title, body, opens)
}

private fun sender(s: Summary): String = s.from.ifBlank { s.fromEmail }.ifBlank { "Someone" }

private fun joined(words: List<String>): String =
    if (words.size <= 1) words.joinToString() else words.dropLast(1).joinToString(", ") + " and " + words.last()

/**
 * A daily stretch with no notifications, such as 22:00 to 07:00.
 *
 * A stretch whose end is earlier than its start runs over midnight, which is the usual
 * case. Equal ends are read as no stretch at all rather than all day: somebody who wants
 * no notifications at all has the switch for that, and a whole day of silence from a
 * mis-set clock would look like a broken app.
 */
internal data class QuietHours(val from: LocalTime, val until: LocalTime) {
    fun covers(time: LocalTime): Boolean = when {
        from == until -> false
        from < until -> !time.isBefore(from) && time.isBefore(until)
        else -> !time.isBefore(from) || time.isBefore(until)
    }

    /** As the settings file keeps it, "22:00-07:00". */
    fun encoded(): String = "${from.format(HOURS)}-${until.format(HOURS)}"

    companion object {
        private val HOURS = DateTimeFormatter.ofPattern("HH:mm")

        /** Null for anything that is not two times, which is also how "off" is stored. */
        fun parse(raw: String?): QuietHours? {
            val parts = raw?.split("-")?.map { it.trim() } ?: return null
            if (parts.size != 2) return null
            val from = runCatching { LocalTime.parse(parts[0], HOURS) }.getOrNull() ?: return null
            val until = runCatching { LocalTime.parse(parts[1], HOURS) }.getOrNull() ?: return null
            return QuietHours(from, until)
        }
    }
}

/**
 * Whether and what to show for a burst that is ready.
 *
 * The whole decision in one place, so the order is fixed: the switch, then quiet hours,
 * then what is still new. Quiet hours drop the burst rather than save it for the morning:
 * a pile of overnight notifications at seven is exactly what quiet hours are for avoiding,
 * and the mail itself is waiting in the inbox with its unread count on the taskbar.
 */
internal fun noticeFor(
    burst: List<Arrival>,
    inboxNow: Map<String, List<Summary>?>,
    enabled: Boolean,
    silenced: Set<String>,
    quiet: QuietHours?,
    time: LocalTime,
): MailNotice? {
    if (!enabled) return null
    if (quiet?.covers(time) == true) return null
    return burstNotice(stillNew(burst, inboxNow, silenced))
}
