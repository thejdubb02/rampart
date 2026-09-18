package org.rampart

import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MailDateFormat
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

    /**
     * Sets or clears a keyword on the given messages.
     *
     * IMAP cannot address a message without its folder, so [mailboxId] comes first. An
     * empty [ids] list is a no-op and does not open the folder.
     */
    fun setKeyword(mailboxId: String, ids: List<String>, keyword: String, on: Boolean) {
        if (ids.isEmpty()) return
        useFolder(mailboxId, Folder.READ_WRITE) { folder ->
            val messages = messagesByUid(folder, ids)
            if (messages.isEmpty()) return@useFolder
            folder.setFlags(messages, flagsFor(keyword), on)
        }
    }

    /**
     * Moves messages from [mailboxId] to [toMailboxId].
     *
     * Empty [ids] does nothing and does not open a folder.
     */
    fun move(mailboxId: String, ids: List<String>, toMailboxId: String) {
        if (ids.isEmpty()) return
        useFolder(mailboxId, Folder.READ_WRITE) { folder ->
            val messages = messagesByUid(folder, ids)
            if (messages.isEmpty()) return@useFolder
            val destination = store.getFolder(toMailboxId)
            if (store.hasCapability("MOVE")) {
                // MOVE (RFC 6851) is one command: the message either lands in the
                // destination or it stays put. Copy then delete is two, and a failure
                // between them leaves a second copy the user did not ask for. That is
                // why the fallback is not simply always used.
                folder.moveUIDMessages(messages, destination)
            } else {
                folder.copyMessages(messages, destination)
                folder.setFlags(messages, Flags(Flags.Flag.DELETED), true)
                folder.expunge(messages)
            }
        }
    }

    /**
     * Deletes the given messages from [mailboxId].
     *
     * Empty [ids] does nothing and does not open a folder.
     */
    fun destroy(mailboxId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        useFolder(mailboxId, Folder.READ_WRITE) { folder ->
            val messages = messagesByUid(folder, ids)
            if (messages.isEmpty()) return@useFolder
            folder.setFlags(messages, Flags(Flags.Flag.DELETED), true)
            // A bare expunge() would drop every deleted message in the folder, including
            // ones another client flagged and the user has not confirmed. The UID form
            // only removes the messages this call just marked.
            folder.expunge(messages)
        }
    }

    /**
     * The readable body of one message, headers included.
     *
     * IMAP has no Email/get to flatten parts, so the MIME walk happens here. Attachments
     * are skipped: they are a different fetch, and treating one as the body is how a
     * PDF or a forwarded message replaces the text the person meant to read.
     */
    fun body(mailboxId: String, id: String): Body = useFolder(mailboxId, Folder.READ_ONLY) { folder ->
        val message = folder.getMessageByUID(id.toLong()) as? MimeMessage
            ?: throw JmapError("That message is not on the server any more.")
        val found = FoundBody()
        walk(message, found)
        val headers = bodyFromHeaders(headerPairs(message))
        // -1 is how Jakarta Mail spells "the server did not say". Missing size is not a
        // reason to hide a message that otherwise opened.
        val size = runCatching { message.size.toLong() }.getOrDefault(-1L)
        headers.copy(
            html = found.html,
            text = found.text,
            size = if (size < 0L) 0L else size,
        )
    }

    override fun close() {
        runCatching { store.close() }
    }

    private inline fun <T> useFolder(mailboxId: String, mode: Int, block: (IMAPFolder) -> T): T {
        val folder = store.getFolder(mailboxId) as IMAPFolder
        folder.open(mode)
        try {
            return block(folder)
        } finally {
            // The work has already happened. A close that fails must not hide that, and
            // must not expunge: close(true) would drop every deleted message in the
            // folder, not only the ones this call meant to remove.
            runCatching { folder.close(false) }
        }
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

    private fun messagesByUid(folder: IMAPFolder, ids: List<String>): Array<Message> {
        val uids = LongArray(ids.size) { ids[it].toLong() }
        // The array has a null slot for a UID the folder no longer holds. Passing those
        // through to STORE or EXPUNGE is an error on some servers.
        return folder.getMessagesByUID(uids).filterNotNull().toTypedArray()
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

/**
 * The IMAP flags a JMAP keyword corresponds to.
 *
 * `$seen` and the other protocol keywords are system flags on IMAP. Sending them as user
 * flags would mark a message with a custom tag named `$seen` and leave it unread.
 */
internal fun flagsFor(keyword: String): Flags = when (keyword.lowercase()) {
    "\$seen" -> Flags(Flags.Flag.SEEN)
    "\$flagged" -> Flags(Flags.Flag.FLAGGED)
    "\$draft" -> Flags(Flags.Flag.DRAFT)
    "\$answered" -> Flags(Flags.Flag.ANSWERED)
    else -> Flags(keyword)
}

/**
 * The header half of a [Body], with no MIME walk.
 *
 * Split out so a folded or repeated header can be tested without an IMAP server. html,
 * text and size stay empty here: those come from the message body, not the headers.
 */
internal fun bodyFromHeaders(headers: List<Pair<String, String>>): Body {
    fun values(name: String): List<String> = headers.mapNotNull { (key, value) ->
        if (!key.equals(name, ignoreCase = true)) return@mapNotNull null
        unfoldHeader(value).trim().takeIf { it.isNotEmpty() }
    }
    fun first(name: String): String? = values(name).firstOrNull()
    return Body(
        html = null,
        text = null,
        messageId = values("Message-ID").flatMap(::messageIdsIn),
        references = values("References").flatMap(::messageIdsIn),
        to = values("To").flatMap(::addressesFrom),
        cc = values("Cc").flatMap(::addressesFrom),
        listUnsubscribe = first("List-Unsubscribe"),
        listUnsubscribePost = first("List-Unsubscribe-Post"),
        authenticationResults = values("Authentication-Results"),
        spamStatus = first("X-Spam-Status"),
        receiptTo = first(MDN_HEADER),
        size = 0L,
        sentAt = first("Date")?.let(::sentAtFrom),
        received = values("Received"),
    )
}

/**
 * The ids in a threading header, in the order they were written, and without their brackets.
 *
 * **Bare, because that is the shape the JMAP backend produces**: RFC 8621's asMessageIds
 * strips the brackets coming in and puts them back going out, and every id inside Rampart
 * is therefore bare. Two backends spelling the same id two ways is not a cosmetic
 * difference. It is a reply that does not thread and a References chain that matches
 * nothing, on one account and not the other, which is the hardest kind of fault to find.
 */
internal fun messageIdsIn(value: String): List<String> {
    val found = MESSAGE_ID.findAll(value).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    // A Message-ID written without brackets is malformed and is still the id the rest of
    // the thread will quote, so it is taken as it stands rather than dropped.
    return found.ifEmpty { listOfNotNull(value.trim().trim('<', '>').takeIf { it.isNotEmpty() }) }
}

private val MESSAGE_ID = Regex("<([^>]+)>")

private fun unfoldHeader(value: String): String = value.replace(Regex("\\r?\\n[ \\t]+"), " ")

private fun addressesFrom(header: String): List<String> =
    // A To line that is not addresses is treated as no recipients rather than refusing
    // to open the message.
    runCatching { InternetAddress.parseHeader(header, false) }
        .getOrDefault(emptyArray())
        .mapNotNull { it.address?.takeIf { address -> address.isNotBlank() } }

private fun sentAtFrom(value: String): String? =
    // A Date line that is not a date is ignored rather than blocking the rest of the
    // message: the body still opens and sentAt is simply missing.
    runCatching { MailDateFormat().parse(value)?.toInstant()?.toString() }.getOrNull()

private class FoundBody(var html: String? = null, var text: String? = null)

private fun headerPairs(message: MimeMessage): List<Pair<String, String>> = buildList {
    val headers = message.allHeaders
    while (headers.hasMoreElements()) {
        val header = headers.nextElement()
        add(header.name to header.value)
    }
}

/**
 * Walks the MIME tree for the html and text the reader should show.
 *
 * multipart/alternative is the same body in several formats, so both are kept and the
 * reader already prefers html when both exist. multipart/mixed is a body plus attachments:
 * keep walking. A part marked as an attachment is skipped so a PDF or a forwarded message
 * cannot replace the text.
 */
private fun walk(part: Part, into: FoundBody) {
    if (isAttachment(part)) return
    when {
        part.isMimeType("multipart/*") -> {
            // A part whose bytes cannot be decoded is skipped so a mangled child cannot
            // hide the parts that did decode.
            val multi = runCatching { part.content as? Multipart }.getOrNull() ?: return
            for (i in 0 until multi.count) walk(multi.getBodyPart(i), into)
        }
        part.isMimeType("text/html") -> if (into.html == null) into.html = textOf(part)
        part.isMimeType("text/plain") -> if (into.text == null) into.text = textOf(part)
    }
}

private fun isAttachment(part: Part): Boolean {
    val disposition = runCatching { part.disposition }.getOrNull()
    if (disposition != null && disposition.equals(Part.ATTACHMENT, ignoreCase = true)) return true
    // A filename with no disposition is how older clients mark an attachment, but a part
    // that is text or multipart is taken as the message anyway. The trade is deliberate:
    // reading a .txt attachment as the body of a message that has no body of its own is a
    // worse-looking message, and skipping it would be an empty one. An empty message is the
    // fault nobody can work around.
    val filename = runCatching { part.fileName }.getOrNull()
    if (filename.isNullOrBlank()) return false
    return !part.isMimeType("text/*") && !part.isMimeType("multipart/*")
}

private fun textOf(part: Part): String? {
    // A charset the JVM does not know, or bytes that are not text, must not hide the
    // rest of the message.
    val content = runCatching { part.content }.getOrNull() ?: return null
    return (content as? String)?.takeIf { it.isNotBlank() }
}
