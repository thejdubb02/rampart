package org.rampart

import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import java.time.Instant
import java.util.Properties

/**
 * The second backend, and the point Rampart stops being a client for one server.
 *
 * JMAP is better in every way that matters and almost nobody offers it. IMAP is what a
 * Gmail account, a Fastmail account and a box in somebody's cupboard all have, so it is
 * what "anybody can install this" actually means.
 *
 * Deliberately shaped to hand back the same [Mailbox] and [Summary] the JMAP side does.
 * Where IMAP cannot do a thing, it says so and the feature is visibly absent rather than
 * broken: see [Lacks].
 */
internal class Imap private constructor(
    private val store: IMAPStore,
    val host: String,
) : AutoCloseable {

    companion object {
        /**
         * Signs in and leaves the connection open.
         *
         * Implicit TLS on 993 rather than STARTTLS on 143. A server that offers both is
         * the same server; a server that only offers 143 is one where an interception can
         * strip the upgrade before either end notices, and there is no version of reading
         * somebody's mail where that is an acceptable default.
         */
        fun connect(host: String, user: String, password: String, port: Int = 993): Imap {
            val properties = Properties().apply {
                put("mail.store.protocol", "imap")
                put("mail.imap.ssl.enable", "true")
                put("mail.imap.host", host)
                put("mail.imap.port", port.toString())
                // A certificate that does not match is the one thing this must not shrug at.
                put("mail.imap.ssl.checkserveridentity", "true")
                put("mail.imap.connectiontimeout", "15000")
                put("mail.imap.timeout", "30000")
                // Asked for on connect rather than per folder. Without it every folder
                // listing is a second round trip to ask what kind of folder it is.
                put("mail.imap.folder.class", "org.eclipse.angus.mail.imap.IMAPFolder")
            }
            val session = Session.getInstance(properties)
            val store = session.getStore("imap") as IMAPStore
            store.connect(host, port, user, password)
            return Imap(store, host)
        }
    }

    /**
     * Every folder, with the role the server itself declares.
     *
     * **SPECIAL-USE (RFC 6154) is asked first, and the name is only a fallback.** A server
     * that says `\Sent` is telling you which folder Sent is in whatever language the
     * account was created in; matching on the word "Sent" finds nothing in a French
     * mailbox and finds the wrong folder in one that happens to have a label called Sent.
     */
    fun mailboxes(): List<Mailbox> {
        val root = store.defaultFolder
        val found = mutableListOf<Mailbox>()
        fun walk(folder: Folder, parent: String?) {
            val selectable = folder.type and Folder.HOLDS_MESSAGES != 0
            val id = folder.fullName
            if (id.isNotBlank()) {
                found += Mailbox(
                    id = id,
                    name = folder.name,
                    role = roleOf(folder),
                    // Asking a folder that holds no messages for a count is an error on
                    // some servers and a round trip on the rest.
                    unread = if (selectable) runCatching { folder.unreadMessageCount }.getOrDefault(0) else 0,
                    parentId = parent,
                )
            }
            if (folder.type and Folder.HOLDS_FOLDERS != 0) {
                runCatching { folder.list() }.getOrDefault(emptyArray()).forEach {
                    walk(it, id.takeIf { s -> s.isNotBlank() })
                }
            }
        }
        walk(root, null)
        return found
    }

    /**
     * The newest [limit] messages in a folder, newest first.
     *
     * Headers only. IMAP will happily hand over whole messages and a folder of a thousand
     * is then megabytes to draw a list, so the envelope and the flags are fetched and the
     * body is left until something opens it.
     */
    fun emails(mailboxId: String, limit: Int = 100): List<Summary> {
        val folder = store.getFolder(mailboxId) as IMAPFolder
        if (folder.type and Folder.HOLDS_MESSAGES == 0) return emptyList()
        folder.open(Folder.READ_ONLY)
        try {
            val count = folder.messageCount
            if (count == 0) return emptyList()
            val from = maxOf(1, count - limit + 1)
            return folder.getMessages(from, count)
                .filterIsInstance<MimeMessage>()
                .map { summaryOf(it, folder) }
                .asReversed()
        } finally {
            runCatching { folder.close(false) }
        }
    }

    override fun close() {
        runCatching { store.close() }
    }

    private fun summaryOf(message: MimeMessage, folder: IMAPFolder): Summary {
        val sender = runCatching { message.from?.firstOrNull() as? InternetAddress }.getOrNull()
        val flags = runCatching { message.flags }.getOrNull() ?: Flags()
        return Summary(
            // The UID, not the sequence number. A sequence number is only meaningful while
            // the folder is open and shifts under you the moment anything is deleted.
            id = runCatching { folder.getUID(message).toString() }.getOrDefault(""),
            from = sender?.personal?.takeIf { it.isNotBlank() } ?: sender?.address.orEmpty(),
            fromEmail = sender?.address.orEmpty(),
            subject = runCatching { message.subject }.getOrNull().orEmpty(),
            receivedAt = runCatching {
                (message.receivedDate ?: message.sentDate)?.toInstant()?.toString()
            }.getOrNull() ?: Instant.EPOCH.toString(),
            // No preview. IMAP has no equivalent, and fetching the first part of every
            // message in a folder to build one is the download this method exists to avoid.
            preview = "",
            seen = flags.contains(Flags.Flag.SEEN),
            flagged = flags.contains(Flags.Flag.FLAGGED),
            keywords = runCatching { flags.userFlags.toSet() }.getOrDefault(emptySet()),
        )
    }
}

/**
 * The role a server declares for a folder, or one guessed from its name.
 *
 * The declaration always wins. Names are a last resort and are matched only in English,
 * because a wrong guess is worse than none here: taking a folder called "Sent" as the real
 * Sent folder is how mail ends up filed somewhere nobody looks.
 */
internal fun roleOf(folder: Folder): String? = roleFor(
    folder.name,
    runCatching { (folder as? IMAPFolder)?.attributes.orEmpty() }.getOrDefault(emptyArray()).toList(),
)

/**
 * The same decision, without a server attached, which is the half worth testing.
 *
 * Checked against our own Stalwart on 2026-09-18: it declares all six, and the folders are
 * named "Deleted Items", "Junk Mail" and "Sent Items", so a client matching on "Trash" and
 * "Spam" would have found none of them.
 */
internal fun roleFor(name: String, attributes: List<String>): String? =
    attributes.firstNotNullOfOrNull { SPECIAL_USE[it.lowercase()] }
        ?: NAMED[name.trim().lowercase()]

/** RFC 6154's attributes, as the roles the rest of Rampart already speaks. */
private val SPECIAL_USE = mapOf(
    "\\inbox" to "inbox",
    "\\archive" to "archive",
    "\\drafts" to "drafts",
    "\\junk" to "junk",
    "\\sent" to "sent",
    "\\trash" to "trash",
    "\\all" to "all",
    "\\flagged" to "flagged",
)

private val NAMED = mapOf(
    "inbox" to "inbox",
    "archive" to "archive",
    "drafts" to "drafts",
    "draft" to "drafts",
    "junk" to "junk",
    "spam" to "junk",
    "sent" to "sent",
    "sent items" to "sent",
    "sent mail" to "sent",
    "trash" to "trash",
    "deleted items" to "trash",
    "bin" to "trash",
)

/**
 * What IMAP cannot do, named so the UI can be absent with a reason rather than broken.
 *
 * Each of these is a real capability on the JMAP side and has no IMAP equivalent at all.
 * Threading and push have workarounds and are not listed: threading can walk References,
 * and push is IDLE, which is one connection per folder but does exist.
 */
internal enum class Lacks(val why: String) {
    BLOB_UPLOAD("This server has no way to hold an attachment before the message is sent."),
    SIEVE("Rules live on a separate service on this server, which Rampart does not speak yet."),
    VACATION("An away reply is set on the server itself, not from a mail client."),
    SERVER_SEARCH_ALL("This server can only search one folder at a time."),
}
