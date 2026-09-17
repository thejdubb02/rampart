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

/** A folder and how deep it sits, ready to draw. */
internal data class Nested(val mailbox: Mailbox, val depth: Int)

/**
 * The folder list as a tree, flattened back out in the order it should be drawn.
 *
 * Inbox first wherever it is, then everything else by name, and each folder's children
 * directly under it. The server hands back a flat list with parent ids in it, so this is
 * the only place that knows what the shape actually is.
 *
 * A folder whose parent is missing, which happens when a server hides one you cannot see,
 * is drawn at the top level rather than dropped. Losing a folder because its parent was
 * filtered out is worse than showing it in slightly the wrong place.
 *
 * A parent loop, which no server should produce and one might, is broken rather than
 * followed: anything not reached from the top is appended at the end.
 */
internal fun nested(boxes: List<Mailbox>): List<Nested> {
    val known = boxes.map { it.id }.toSet()
    val byParent = boxes.groupBy { it.parentId?.takeIf { parent -> parent in known } }
    val order = compareBy<Mailbox>({ if (it.role == "inbox") 0 else 1 }, { it.name.lowercase() })
    val out = mutableListOf<Nested>()
    val drawn = mutableSetOf<String>()

    fun walk(parent: String?, depth: Int) {
        byParent[parent].orEmpty().sortedWith(order).forEach { box ->
            if (!drawn.add(box.id)) return@forEach
            out += Nested(box, depth)
            walk(box.id, depth + 1)
        }
    }
    walk(null, 0)
    boxes.filterNot { it.id in drawn }.sortedWith(order).forEach { out += Nested(it, 0) }
    return out
}

/**
 * Whether this folder is one of the ones a mail client should not let you delete.
 *
 * Judged on the role rather than the name: a folder called Archive that the server does not
 * treat as one is somebody's own folder and is theirs to delete.
 */
internal fun isProtected(mailbox: Mailbox): Boolean = !mailbox.role.isNullOrBlank()

/**
 * The subfolder a message archived today belongs in, or null to use Archive itself.
 *
 * Taken from the message's own date rather than today's, so filing last year's mail in a
 * clear-out puts it under last year. The name is the plain one every other client uses, so
 * a folder made here is the folder Thunderbird would have made.
 */
internal fun archiveBucket(receivedAt: String, by: String): String? = when (by) {
    "year" -> receivedAt.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }
    "month" -> receivedAt.take(7).takeIf { it.length == 7 && it[4] == '-' }
    else -> null
}
