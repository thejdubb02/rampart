package org.rampart

/**
 * Read receipts, and the line between one and a tracking pixel.
 *
 * An MDN (RFC 8098) is a request in a header that the recipient's client shows them, and
 * that they answer or ignore. A tracking pixel is a picture that reports back without
 * anybody being asked. Rampart does both, in different places, and must never describe one
 * as the other: the receipt is consented and the pixel is not, and that is the whole
 * difference a person is entitled to know about.
 *
 * Both directions, both opt-in. Asking for a receipt is per message. Answering one is per
 * message too, because a client that answers automatically has turned a question into a
 * tracker on the sender's behalf.
 */

/** The header that asks. One address, the one that should receive the answer. */
internal const val MDN_HEADER = "Disposition-Notification-To"

/**
 * Who a message asks to be notified, or null when it asks nobody.
 *
 * Only honoured when it matches the address the message actually came from. A request
 * pointing somewhere else is the shape a receipt takes when it is being used to confirm an
 * address for somebody who is not the sender, and answering it is worth more to them than
 * to the person reading.
 */
internal fun receiptWanted(headers: Map<String, String>, fromEmail: String): String? {
    val asked = headers.entries
        .firstOrNull { it.key.equals(MDN_HEADER, ignoreCase = true) }
        ?.value?.let(::bareAddress)
        ?.takeIf { it.isNotBlank() } ?: return null
    return asked.takeIf { it.equals(bareAddress(fromEmail), ignoreCase = true) }
}

/** The address out of `Name <a@b.test>`, or what was there if it is already bare. */
internal fun bareAddress(value: String): String {
    val open = value.indexOf('<')
    val close = value.indexOf('>', open + 1)
    return if (open >= 0 && close > open) value.substring(open + 1, close).trim() else value.trim()
}

/**
 * The receipt itself, as the plain text part a person would read.
 *
 * The machine-readable part of an MDN is a `message/disposition-notification` body that
 * almost nothing renders, so the text part is what the sender actually sees and is written
 * for them rather than for a parser.
 */
internal fun receiptBody(subject: String, at: String, by: String): String =
    "Your message \"$subject\" was displayed on $at by $by.\n\n" +
        "This is a read receipt you asked for. It says the message was opened, " +
        "not that it was read."

/** The subject of the receipt, which every client words about this way. */
internal fun receiptSubject(subject: String): String = "Read: $subject"
