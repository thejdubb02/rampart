package org.rampart

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The three answers an invitation takes.
 *
 * Three spellings of each, because the three places it appears want different words: the
 * protocol's PARTSTAT, what the button says before it is pressed, and what the subject line
 * of the answer says afterwards.
 */
internal enum class Rsvp(val partstat: String, val button: String, val word: String) {
    ACCEPT("ACCEPTED", "Accept", "Accepted"),
    TENTATIVE("TENTATIVE", "Maybe", "Tentatively accepted"),
    DECLINE("DECLINED", "Decline", "Declined"),
}

/**
 * The answer to an invitation, as the calendar file that goes back to the organiser.
 *
 * **METHOD:REPLY with one ATTENDEE, which is the whole protocol.** The reply carries only
 * the person answering, never the rest of the guest list: an attendee has no standing to
 * say anything about anybody else's answer, and a reply that lists them all is how one
 * guest's client overwrites another's response on the organiser's calendar.
 *
 * [sequence] and [uid] come back exactly as they arrived. The organiser's calendar matches
 * the reply to the invitation on the pair, and a reply to an older sequence than the one
 * the organiser last sent is one they are entitled to ignore, which is correct: it is an
 * answer to a meeting that has since been moved.
 */
internal fun rsvpCalendar(
    invitation: Invitation,
    answer: Rsvp,
    /** The address answering, which is the one the invitation was addressed to. */
    me: String,
    myName: String = "",
    now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC),
): String {
    val lines = buildList {
        add("BEGIN:VCALENDAR")
        add("PRODID:-//Rampart//Rampart Mail//EN")
        add("VERSION:2.0")
        add("METHOD:REPLY")
        add("BEGIN:VEVENT")
        add("UID:${escapeIcs(invitation.uid)}")
        add("SEQUENCE:${invitation.sequence}")
        add("DTSTAMP:${now.withZoneSameInstant(ZoneOffset.UTC).format(STAMP)}")
        invitation.organiser?.email?.takeIf { it.isNotBlank() }?.let { add("ORGANIZER:mailto:$it") }
        val cn = myName.takeIf { it.isNotBlank() }?.let { ";CN=${escapeIcs(it)}" }.orEmpty()
        add("ATTENDEE;PARTSTAT=${answer.partstat}$cn:mailto:$me")
        // Carried so the organiser's client has something to show if it cannot find the
        // original, and so a human reading the raw part knows what was answered.
        if (invitation.summary.isNotBlank()) add("SUMMARY:${escapeIcs(invitation.summary)}")
        invitation.starts?.raw?.takeIf { it.isNotBlank() }?.let { add("DTSTART:$it") }
        add("END:VEVENT")
        add("END:VCALENDAR")
    }
    // CRLF, because RFC 5545 says so and because a calendar file with bare newlines is one
    // that some servers reject and others silently truncate.
    return lines.joinToString("\r\n", postfix = "\r\n", transform = ::foldIcs)
}

/**
 * The email that carries the answer.
 *
 * Addressed to the organiser alone, and threaded onto the invitation so the answer appears
 * under the meeting rather than as a new conversation. The subject is the convention every
 * calendar uses, and it is the whole message for anyone whose client does not read the
 * attached part: a body repeating it would be read twice by people and once by nobody.
 */
internal fun rsvpDraft(
    invitation: Invitation,
    answer: Rsvp,
    summary: Summary,
    body: Body?,
    from: String,
): Draft {
    val answered = body?.messageId?.firstOrNull()
    return Draft(
        from = from,
        to = invitation.organiser?.email.orEmpty().ifBlank { summary.fromEmail },
        subject = "${answer.word}: ${invitation.summary.ifBlank { summary.subject }}",
        inReplyTo = answered,
        references = body?.references.orEmpty() + listOfNotNull(answered),
        replying = true,
    )
}

/** A filename for the answer. Named for what it is, because the organiser may well see it. */
internal fun rsvpFileName(answer: Rsvp) = "reply-${answer.partstat.lowercase()}.ics"

/**
 * Wrapped at 75 octets, which is the line length iCalendar mandates.
 *
 * Counted in bytes rather than characters, because the limit is octets and a name with an
 * accent in it is two. The continuation is a line beginning with one space, and the reader
 * takes that space off again: see the unfolding [invitationIn] does on the way in.
 */
internal fun foldIcs(line: String): String {
    val bytes = line.toByteArray(Charsets.UTF_8)
    if (bytes.size <= 75) return line
    val out = StringBuilder()
    var used = 0
    var first = true
    for (ch in line) {
        val width = ch.toString().toByteArray(Charsets.UTF_8).size
        val limit = if (first) 75 else 74
        if (used + width > limit) {
            out.append("\r\n ")
            used = 0
            first = false
        }
        out.append(ch)
        used += width
    }
    return out.toString()
}

/** A text value as iCalendar escapes one. The order matters: backslashes first. */
internal fun escapeIcs(text: String): String = text
    .replace("\\", "\\\\")
    .replace(";", "\\;")
    .replace(",", "\\,")
    .replace("\r\n", "\\n")
    .replace("\n", "\\n")

private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
