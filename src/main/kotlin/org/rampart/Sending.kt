package org.rampart

/**
 * The two things worth stopping somebody on the way out.
 *
 * Both are questions, never refusals. A client that will not send a message with no subject
 * is a client people learn to fight; one that asks once is a client that saves them.
 */

/** Words that mean a file was meant to be on this message. */
private val PROMISES = listOf(
    "attached", "attaching", "attachment", "attachments",
    "enclosed", "i have attached", "please find",
)

/**
 * Whether the text promises a file, so the missing one is worth mentioning.
 *
 * Only the part the person wrote: quoted text below a reply is somebody else's sentence and
 * almost always mentions an attachment that was on *their* message, which is exactly the
 * false alarm that trains people to click through the warning without reading it.
 */
internal fun mentionsAttachment(body: String): Boolean {
    val written = body.substringBefore("\n\nOn ").lowercase()
    return PROMISES.any { promise ->
        val at = written.indexOf(promise)
        at >= 0 && written.isWordAt(at, promise.length)
    }
}

/** So "unattached" and "attachments" do not both count as the same promise. */
private fun String.isWordAt(at: Int, length: Int): Boolean {
    val before = getOrNull(at - 1)
    val after = getOrNull(at + length)
    return (before == null || !before.isLetter()) && (after == null || !after.isLetter())
}

/**
 * The question to ask before sending, or null when there is nothing to ask.
 *
 * One question, not two: being stopped twice on the way out of the same message is the
 * point at which somebody turns the warnings off.
 */
internal fun sendWarning(subject: String, body: String, attachments: Int): String? = when {
    attachments == 0 && mentionsAttachment(body) ->
        "This message mentions an attachment and does not have one. Send it anyway?"
    subject.isBlank() -> "This message has no subject. Send it anyway?"
    else -> null
}

/**
 * The one question to ask before this message goes, or null when it should just go.
 *
 * A content warning wins. It is the more specific of the two, and asking both would be
 * the second stop [sendWarning] exists to avoid. The plain confirm is only asked when
 * nothing else was, and only when [confirmEvery] is on.
 */
internal fun askBeforeSend(
    subject: String,
    body: String,
    attachments: Int,
    confirmEvery: Boolean,
): String? = sendWarning(subject, body, attachments) ?: if (confirmEvery) "Send this message?" else null
