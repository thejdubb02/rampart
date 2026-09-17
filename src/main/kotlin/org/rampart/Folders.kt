package org.rampart

/**
 * Finding the Archive, Trash, Junk, Drafts and Sent folders on a server that did not say
 * which is which.
 *
 * JMAP gives a mailbox a `role`, and a server that sets them makes this trivial. Not every
 * server does. One of the two accounts here has an Archive folder with no role on it, and
 * the result was an Archive button on one mailbox and no Archive button at all on the
 * other, for no reason a person could see.
 *
 * So the role is tried first and always wins, and the name is only a fallback. Matching on
 * the name alone would be worse than nothing: a server that calls its Archive folder
 * something else in another language would quietly get no button, and a folder somebody
 * named "Trash" by hand would quietly become the real one.
 */
private val NAMES = mapOf(
    "archive" to setOf("archive", "archives", "archived"),
    "trash" to setOf("trash", "deleted", "deleted items", "deleted messages", "bin"),
    "junk" to setOf("junk", "spam", "junk email", "junk e-mail", "bulk mail"),
    "drafts" to setOf("drafts", "draft"),
    "sent" to setOf("sent", "sent items", "sent mail", "sent messages"),
    "inbox" to setOf("inbox"),
)

/** The folder for [role], or null when this account genuinely has not got one. */
internal fun folderFor(role: String, boxes: List<Mailbox>): Mailbox? {
    boxes.firstOrNull { it.role == role }?.let { return it }
    val names = NAMES[role] ?: return null
    // Only a folder with no role of its own. A mailbox the server already called something
    // else is not up for being renamed by us because its label happens to read as Trash.
    return boxes.firstOrNull { it.role.isNullOrBlank() && it.name.trim().lowercase() in names }
}

/** What to call the move afterwards, in the past tense, for the undo notice. */
internal fun pastTense(role: String): String = when (role) {
    "archive" -> "archived"
    "trash" -> "moved to trash"
    "junk" -> "marked as spam"
    else -> "moved"
}

/** "1 message archived" rather than "1 messages archived". */
internal fun movedNotice(count: Int, what: String): String =
    if (count == 1) "1 message $what." else "$count messages $what."
