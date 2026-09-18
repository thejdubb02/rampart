package org.rampart

import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MailDateFormat
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.SubjectTerm
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import java.time.Instant
import java.util.Properties
import kotlin.io.path.writeBytes

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
    private val user: String,
    private val password: String,
    private val sendHost: String,
    private val sendPort: Int,
) : MailBackend, AutoCloseable {

    companion object {
        /**
         * Signs in and leaves the connection open.
         *
         * Implicit TLS on 993 rather than STARTTLS on 143. A server that offers both is
         * the same server; a server that only offers 143 is one where an interception can
         * strip the upgrade before either end notices, and there is no version of reading
         * somebody's mail where that is an acceptable default.
         */
        fun connect(
            host: String,
            user: String,
            password: String,
            port: Int = 993,
            sendHost: String = host,
            sendPort: Int = 587,
        ): Imap {
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
            return Imap(store, host, user, password, sendHost, sendPort)
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
    override fun mailboxes(): List<Mailbox> {
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
    override fun emails(mailboxId: String, limit: Int, from: Int, unreadOnly: Boolean): List<Summary> {
        val folder = store.getFolder(mailboxId) as IMAPFolder
        if (folder.type and Folder.HOLDS_MESSAGES == 0) return emptyList()
        return useFolder(mailboxId, Folder.READ_ONLY) { open ->
            val count = open.messageCount
            if (count == 0) {
                emptyList()
            } else if (unreadOnly) {
                // A SEARCH rather than a fetch and a filter. Reading a folder of thousands
                // to find the four unread ones is the download this method exists to avoid,
                // and the server can answer it without sending anything.
                open.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
                    .filterIsInstance<MimeMessage>()
                    .asReversed()
                    .drop(from)
                    .take(limit)
                    .map { summaryOf(it, open) }
            } else {
                // Sequence numbers run oldest first, so the newest page is at the end and
                // an offset counts backwards from there. Clamped at 1: asking for a page
                // past the start of the folder is the end of the list, not an error.
                val last = count - from
                if (last < 1) {
                    emptyList()
                } else {
                    open.getMessages(maxOf(1, last - limit + 1), last)
                        .filterIsInstance<MimeMessage>()
                        .map { summaryOf(it, open) }
                        .asReversed()
                }
            }
        }
    }

    /**
     * Sets or clears a keyword on the given messages.
     *
     * IMAP cannot address a message without its folder, so [mailboxId] comes first. An
     * empty [ids] list is a no-op and does not open the folder.
     */
    override fun setKeyword(ids: List<String>, keyword: String, on: Boolean) {
        byFolder(ids).forEach { (mailboxId, uids) ->
            useFolder(mailboxId, Folder.READ_WRITE) { folder ->
                val messages = messagesByUid(folder, uids)
                if (messages.isNotEmpty()) folder.setFlags(messages, flagsFor(keyword), on)
            }
        }
    }

    /**
     * Moves messages from [mailboxId] to [toMailboxId].
     *
     * Empty [ids] does nothing and does not open a folder.
     */
    override fun move(ids: List<String>, toMailboxId: String) {
        byFolder(ids).forEach { (mailboxId, uids) ->
            move(mailboxId, uids, toMailboxId)
        }
    }

    private fun move(mailboxId: String, uids: List<String>, toMailboxId: String) {
        useFolder(mailboxId, Folder.READ_WRITE) { folder ->
            val messages = messagesByUid(folder, uids)
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
    override fun destroy(ids: List<String>) {
        byFolder(ids).forEach { (mailboxId, uids) ->
            useFolder(mailboxId, Folder.READ_WRITE) { folder ->
            val messages = messagesByUid(folder, uids)
            if (messages.isEmpty()) return@useFolder
            folder.setFlags(messages, Flags(Flags.Flag.DELETED), true)
            // A bare expunge() would drop every deleted message in the folder, including
            // ones another client flagged and the user has not confirmed. The UID form
            // only removes the messages this call just marked.
            folder.expunge(messages)
            }
        }
    }

    /**
     * The readable body of one message, headers included.
     *
     * IMAP has no Email/get to flatten parts, so the MIME walk happens here. Attachments
     * are skipped: they are a different fetch, and treating one as the body is how a
     * PDF or a forwarded message replaces the text the person meant to read.
     */
    override fun body(id: String): Body = useFolder(folderOf(id), Folder.READ_ONLY) { folder ->
        val message = folder.getMessageByUID(uidOf(id)) as? MimeMessage
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

    // ---- the rest of the seam ------------------------------------------------------

    /**
     * Null, always. IMAP has UIDVALIDITY and HIGHESTMODSEQ, and neither is one token for
     * the whole account the way JMAP's state string is.
     *
     * A backend that cannot say means every refresh re-reads, which is slower and still
     * correct, and is exactly what the poll underneath push is for.
     */
    override fun mailState(): String? = null

    override fun markSeen(id: String) = setKeyword(listOf(id), "\$seen", true)

    override fun search(text: String, mailboxId: String?, limit: Int): List<Summary> {
        // One folder, because IMAP has no way to ask the question of the account. The
        // caller is told so rather than being given the inbox and left to assume it looked
        // everywhere, which is the answer that quietly loses mail.
        val folder = mailboxId ?: throw Unsupported(Lacks.SERVER_SEARCH_ALL)
        val term = OrTerm(
            arrayOf(
                SubjectTerm(text),
                FromStringTerm(text),
                BodyTerm(text),
            ),
        )
        return useFolder(folder, Folder.READ_ONLY) { open ->
            open.search(term).filterIsInstance<MimeMessage>().asReversed().take(limit).map { summaryOf(it, open) }
        }
    }

    override fun thread(threadId: String): List<Summary> = throw Unsupported(Lacks.SERVER_THREADS)

    // ---- folders --------------------------------------------------------------------

    override fun createMailbox(name: String, parentId: String?): String {
        val full = if (parentId.isNullOrBlank()) name else parentId + separator() + name
        val folder = store.getFolder(full)
        if (!folder.create(Folder.HOLDS_MESSAGES or Folder.HOLDS_FOLDERS)) {
            throw JmapError("The server would not create a folder called \"$name\".")
        }
        return folder.fullName
    }

    override fun updateMailbox(id: String, name: String?, parentId: String?, reparent: Boolean) {
        val folder = store.getFolder(id)
        val leaf = name ?: folder.name
        val parent = if (reparent) parentId.orEmpty() else id.substringBeforeLast(separator(), "")
        val full = if (parent.isBlank()) leaf else parent + separator() + leaf
        if (full == id) return
        // RENAME is how IMAP moves a folder as well as how it renames one: the new name
        // carries the new parent. There is no separate move command to look for.
        if (!folder.renameTo(store.getFolder(full))) {
            throw JmapError("The server would not rename that folder.")
        }
    }

    override fun destroyMailbox(id: String, withMail: Boolean) {
        // false, so a folder with mail in it is refused by the server rather than emptied
        // by us. The same promise the JMAP side makes, and the one people rely on.
        if (!store.getFolder(id).delete(withMail)) {
            throw JmapError("The server would not delete that folder. Empty it first.")
        }
    }

    private fun separator(): Char = runCatching { store.defaultFolder.separator }.getOrDefault('/')

    // ---- writing mail ---------------------------------------------------------------

    /**
     * The one identity there is, made up from the address that signed in.
     *
     * IMAP has no identity store, so there is nothing to read and nothing to choose between.
     * A made-up single identity is honest here in a way a made-up signature would not be:
     * the address really is the only one this connection can send as.
     */
    override fun identities(): List<Identity> = listOf(Identity(id = "imap", name = "", email = user))

    override fun setSignature(identityId: String, text: String, html: String) =
        throw Unsupported(Lacks.IDENTITIES)

    override fun upload(file: Path): Attachment = throw Unsupported(Lacks.BLOB_UPLOAD)

    override fun saveDraft(
        draft: Draft,
        identity: Identity,
        draftsMailboxId: String,
        replacing: String?,
    ): String {
        val message = buildMessage(Session.getInstance(Properties()), draft, identity, emptyList())
        message.setFlag(Flags.Flag.DRAFT, true)
        val id = append(draftsMailboxId, message)
        // After the new one is safely there. A draft saved twice is an annoyance; a draft
        // deleted before its replacement lands is somebody's unsent message.
        replacing?.let { destroy(listOf(it)) }
        return id
    }

    override fun send(draft: Draft, identity: Identity, draftsMailboxId: String, sentMailboxId: String?) {
        // Opened per send rather than held. A submission connection sitting idle for hours
        // is one the server will drop without telling us, and the failure then lands on
        // whoever pressed Send rather than on the connection that went stale.
        val sent = Smtp.connect(sendHost, user, password, sendPort).use {
            it.send(draft, identity, emptyList())
        }
        // The bytes that went out, not a second rendering of the draft: IMAP has no
        // EmailSubmission to file the copy, so the client does it, and two renderings of one
        // draft differ in their Message-ID.
        sentMailboxId?.let { runCatching { append(it, MimeMessage(Session.getInstance(Properties()), sent.inputStream())) } }
    }

    /** APPEND, with the server's new UID when it offers UIDPLUS and a re-read when it does not. */
    private fun append(mailboxId: String, message: MimeMessage): String {
        val folder = store.getFolder(mailboxId) as IMAPFolder
        if (!folder.exists()) throw JmapError("There is no folder called \"$mailboxId\" on this server.")
        folder.open(Folder.READ_WRITE)
        try {
            val appended = runCatching { folder.appendUIDMessages(arrayOf(message)) }.getOrNull()
            val uid = appended?.firstOrNull()?.uid
            if (uid != null) return imapId(uid, folder.fullName)
            // No UIDPLUS. The message is there and we cannot be told its number, so the
            // newest one is the best available answer and is right unless something else
            // appended in between.
            folder.appendMessages(arrayOf(message))
            val last = folder.getMessage(folder.messageCount)
            return imapId(folder.getUID(last), folder.fullName)
        } finally {
            runCatching { folder.close(false) }
        }
    }

    // ---- attachments ----------------------------------------------------------------

    override fun attachments(emailId: String): List<Attachment> =
        useFolder(folderOf(emailId), Folder.READ_ONLY) { folder ->
            val message = folder.getMessageByUID(uidOf(emailId)) as? MimeMessage ?: return@useFolder emptyList()
            attachmentsOf(message, emailId)
        }

    override fun blob(attachment: Attachment, limit: Long): ByteArray? {
        val emailId = attachment.blobId.substringBeforeLast('#')
        val at = attachment.blobId.substringAfterLast('#').toIntOrNull() ?: return null
        if (attachment.size > limit) return null
        return useFolder(folderOf(emailId), Folder.READ_ONLY) { folder ->
            val message = folder.getMessageByUID(uidOf(emailId)) as? MimeMessage ?: return@useFolder null
            partAt(message, at)?.inputStream?.use { it.readBytes() }
        }
    }

    override fun download(attachment: Attachment, into: Path): Path {
        val bytes = blob(attachment, Long.MAX_VALUE) ?: throw JmapError("That attachment is no longer on the server.")
        into.writeBytes(bytes)
        return into
    }

    /**
     * The message exactly as it arrived.
     *
     * Read whole and capped afterwards rather than streamed to the cap: IMAP hands over a
     * message in one FETCH, and a half-read message is not a message anybody can use.
     */
    override fun raw(emailId: String, limit: Long): String? =
        useFolder(folderOf(emailId), Folder.READ_ONLY) { folder ->
            val message = folder.getMessageByUID(uidOf(emailId)) as? MimeMessage ?: return@useFolder null
            if (message.size > limit) return@useFolder null
            java.io.ByteArrayOutputStream().also { message.writeTo(it) }.toString(Charsets.UTF_8)
        }

    // ---- what this protocol does not have -------------------------------------------

    /** False. IDLE exists and is one connection per folder, which is its own piece of work. */
    override val hasPush: Boolean get() = false

    override fun watch(onChange: () -> Unit, onGone: () -> Unit): AutoCloseable? = null

    override fun hasSieve(): Boolean = false

    override fun sieveScripts(): List<Jmap.SieveInfo> = throw Unsupported(Lacks.SIEVE)

    override fun sieveText(script: Jmap.SieveInfo): String = throw Unsupported(Lacks.SIEVE)

    override fun saveSieve(name: String, text: String, existing: Jmap.SieveInfo?) = throw Unsupported(Lacks.SIEVE)

    override fun vacation(): Vacation? = null

    override fun setVacation(value: Vacation) = throw Unsupported(Lacks.VACATION)

    override fun hasContacts(): Boolean = false

    override fun addressBooks(): List<ContactBook> = throw Unsupported(Lacks.CONTACTS)

    override fun contacts(): List<Pair<Contact, JsonObject>> = throw Unsupported(Lacks.CONTACTS)

    override fun saveContact(contact: Contact, original: JsonObject?): String = throw Unsupported(Lacks.CONTACTS)

    override fun deleteContact(id: String) = throw Unsupported(Lacks.CONTACTS)

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
            id = runCatching { imapId(folder.getUID(message), folder.fullName) }.getOrDefault(""),
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
        val uids = LongArray(ids.size) { uidOf(ids[it]) }
        // The array has a null slot for a UID the folder no longer holds. Passing those
        // through to STORE or EXPUNGE is an error on some servers.
        return folder.getMessagesByUID(uids).filterNotNull().toTypedArray()
    }
}

/**
 * A message id that says which folder it is in, because on IMAP nothing else does.
 *
 * A UID is unique inside a folder and nowhere else: the inbox and Sent can both hold a
 * message numbered 4231. JMAP ids are unique across the account, so every call site in
 * Rampart passes ids around with no folder beside them, and an id that cannot say where it
 * lives would have meant widening [MailBackend] for the sake of one backend.
 *
 * A space, because a UID is always digits and an IMAP folder name may contain almost
 * anything else, including the slash used for its own hierarchy. That makes the split
 * unambiguous without escaping, and the id stays printable, which matters because it is
 * also the primary key in the local store.
 */
internal fun imapId(uid: Long, mailboxId: String): String = "$uid $mailboxId"

/** The UID half, or -1 when this is not one of ours, which finds no message. */
internal fun uidOf(id: String): Long = id.substringBefore(' ').toLongOrNull() ?: -1L

/** The folder half. Empty when the id carries none, which opens nothing. */
internal fun folderOf(id: String): String = id.substringAfter(' ', "")

/**
 * Ids grouped by the folder they are in, so one call can act on a selection spanning
 * several, which is what a search result or a unified inbox is.
 */
internal fun byFolder(ids: List<String>): Map<String, List<String>> =
    ids.filter { folderOf(it).isNotEmpty() && uidOf(it) >= 0 }.groupBy(::folderOf)

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
    SERVER_THREADS("This server does not group messages into conversations."),
    IDENTITIES("This server has no place to keep a sign-off, so signatures are set in Rampart."),
    CONTACTS("This server has no address book. Contacts come from the people you write to."),
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

/**
 * Every part of a message that is a file rather than the message.
 *
 * The blob id is the message id and the part's position in a depth-first walk, because IMAP
 * has no blob store and therefore no id of its own to borrow. Position is stable for as
 * long as the message exists, which is as long as the id is any use.
 */
private fun attachmentsOf(message: MimeMessage, emailId: String): List<Attachment> = buildList {
    var at = -1
    fun visit(part: Part) {
        at++
        val here = at
        val multi = runCatching { part.content as? Multipart }.getOrNull()
        if (multi != null) {
            for (i in 0 until multi.count) visit(multi.getBodyPart(i))
            return
        }
        val name = runCatching { part.fileName }.getOrNull()
        val disposition = runCatching { part.disposition }.getOrNull()
        val cid = runCatching { (part as? MimeBodyPart)?.contentID }.getOrNull()?.trim('<', '>')
        // A part with no filename and no Content-ID is the body in one of its formats, not
        // something attached. Only files and the pictures the body points at are listed.
        if (name.isNullOrBlank() && cid.isNullOrBlank()) return
        add(
            Attachment(
                blobId = "$emailId#$here",
                name = name.orEmpty().ifBlank { cid.orEmpty() },
                type = runCatching { part.contentType.substringBefore(';').trim() }.getOrDefault(""),
                size = decodedSize(part),
                cid = cid?.takeIf { it.isNotBlank() },
                inline = !cid.isNullOrBlank() || disposition.equals(Part.INLINE, ignoreCase = true),
            ),
        )
    }
    visit(message)
}

/**
 * The size of a part as the file will be once saved, not as it sits on the wire.
 *
 * IMAP reports the encoded length, and base64 is four bytes on the wire for every three in
 * the file. Listing the wire figure overstates every attachment by a third, and the JMAP
 * side reports the real one, so the two backends would disagree about the size of the same
 * message. Worked out rather than measured, because measuring means downloading every
 * attachment to draw a list, which is the fetch this avoids.
 */
private fun decodedSize(part: Part): Long {
    // A message that is itself one attachment reports the size of the whole message,
    // headers included, which on a DMARC report is most of it: 5,464 bytes claimed for a
    // 729 byte file, measured against our own server. There is only one part to read, so
    // reading it is the same fetch opening the message already did.
    if (part is MimeMessage) {
        return runCatching { part.inputStream.use { it.readBytes().size.toLong() } }.getOrDefault(0L)
    }
    val encoded = runCatching { part.size.toLong() }.getOrDefault(-1L)
    if (encoded < 0L) return 0L
    val encoding = runCatching { (part as? MimeBodyPart)?.encoding ?: (part as? MimeMessage)?.encoding }
        .getOrNull().orEmpty().lowercase()
    // quoted-printable is left alone: it is close to its decoded size for ordinary text and
    // nowhere near a fixed ratio for anything else, so a guess would be worse than the
    // honest upper bound.
    return if (encoding == "base64") encoded * 3 / 4 else encoded
}

/** The part at a position in the same depth-first walk [attachmentsOf] numbered. */
private fun partAt(message: MimeMessage, wanted: Int): Part? {
    var at = -1
    fun visit(part: Part): Part? {
        at++
        if (at == wanted) return part
        val multi = runCatching { part.content as? Multipart }.getOrNull() ?: return null
        for (i in 0 until multi.count) visit(multi.getBodyPart(i))?.let { return it }
        return null
    }
    return visit(message)
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
