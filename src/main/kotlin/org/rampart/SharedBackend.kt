package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Something Rampart did not ask the server for, because the folder's rights already said no. */
internal class NotAllowedHere(why: String) : Exception(why)

/**
 * A mailbox that belongs to somebody else, read and filed as the signed-in person (RAM-45).
 *
 * [inner] is the login's own JMAP session speaking for [accountId] ([Jmap.forAccount]), so
 * every request it makes already names the shared account and nothing it does can reach
 * the login's own mail. This wrapper adds the other half: **an action the folder's
 * `myRights` do not allow is refused here, with a sentence, and never sent.** The server
 * would refuse it too; asking anyway only turns a known answer into an error message.
 *
 * Written out member by member rather than delegated, on purpose. A new member on
 * [MailBackend] then fails to compile here until somebody decides what a shared mailbox
 * should do with it, instead of quietly passing through to another person's account.
 *
 * What is not here at all: sending (mail is written from your own account, see
 * [sendingKey]), filters, an away reply, contacts, calendars and push. Those belong to the
 * mailbox's owner and Rampart does not offer them on somebody else's account.
 */
internal class SharedBackend(
    private val inner: MailBackend,
    val accountId: String,
    /** Whether the signed-in person owns this account, which on Stalwart is group membership. See [SessionAccount.owner]. */
    private val owner: Boolean,
    /** Sends one batch for this account. Only ever given requests built in SharedAccounts.kt. */
    private val call: (Array<JsonArray>) -> List<JsonArray>,
) : MailBackend {

    /** Folder id to what the signed-in person may do in it, from the last [mailboxes]. */
    @Volatile
    var rights: Map<String, MailboxRights> = emptyMap()
        private set

    /** The folders, with rights and grants, from the last [mailboxes]. */
    @Volatile
    var folders: List<RightsFolder> = emptyList()
        private set

    /** Message id to the folders it was last seen in. Filled as lists are read, asked for when missing. */
    private val located = ConcurrentHashMap<String, Set<String>>()

    fun rightsOf(mailboxId: String): MailboxRights = rights[mailboxId] ?: MailboxRights.NONE

    /** Sends [invocations] as this account. Exposed for the share dialog, which builds its own. */
    fun send(vararg invocations: JsonArray): List<JsonArray> = call(arrayOf(*invocations))

    // ---- reading -------------------------------------------------------------------

    override fun mailboxes(): List<Mailbox> {
        val found = rightsFoldersIn(call(arrayOf(mailboxesWithRights(accountId))).first())
        folders = found
        rights = found.associate { it.mailbox.id to it.rights }
        return found.map { it.mailbox }
    }

    override fun mailState(): String? = inner.mailState()

    override fun emails(
        mailboxId: String,
        limit: Int,
        from: Int,
        unreadOnly: Boolean,
        filters: QuickFilters,
        knownSenders: Collection<String>,
        userKeywords: Collection<String>,
    ): List<Summary> =
        inner.emails(mailboxId, limit, from, unreadOnly, filters, knownSenders, userKeywords).also { page ->
            page.forEach { located.merge(it.id, setOf(mailboxId)) { a, b -> a + b } }
        }

    override fun thread(threadId: String): List<Summary> = inner.thread(threadId)
    override fun body(id: String): Body = inner.body(id)
    override fun attachments(emailId: String): List<Attachment> = inner.attachments(emailId)
    override fun open(id: String): OpenedMail = inner.open(id)
    override fun contentStamp(id: String): ContentStamp? = inner.contentStamp(id)
    override fun blob(attachment: Attachment, limit: Long): ByteArray? = inner.blob(attachment, limit)
    override fun raw(emailId: String, limit: Long): String? = inner.raw(emailId, limit)
    override fun download(attachment: Attachment, into: Path): Path = inner.download(attachment, into)
    override fun search(text: String, mailboxId: String?, limit: Int, except: Collection<String>): List<Summary> =
        inner.search(text, mailboxId, limit, except)
    override fun withKeyword(keyword: String, limit: Int): List<Summary> = inner.withKeyword(keyword, limit)

    // ---- changing what is there, each checked against the folder first ---------------

    /**
     * Where each message is, from what has been listed, and from the server for the rest.
     * A message the server no longer lists is left out, and the server will say so itself.
     */
    private fun whereAre(ids: List<String>): Map<String, Set<String>> {
        val missing = ids.filter { located[it].isNullOrEmpty() }
        if (missing.isNotEmpty()) {
            messageFoldersIn(call(arrayOf(whereMessagesAre(accountId, missing))).first()).forEach { (id, boxes) ->
                located[id] = boxes
            }
        }
        return ids.associateWith { located[it].orEmpty() }
    }

    /**
     * The first message none of whose folders allow [action], as its refusal. Stalwart
     * checks keywords and deletion the same way: one folder that allows it is enough.
     */
    private fun anyFolderAllows(ids: List<String>, action: FolderAction): String? =
        whereAre(ids).values.firstNotNullOfOrNull { boxes ->
            if (boxes.isEmpty() || boxes.any { refusal(action, rightsOf(it)) == null }) null else action.refused
        }

    /**
     * Opening a message marks it read. In a folder that does not allow it that is not an
     * error worth a red bar on every message, so it is skipped quietly; the line above the
     * list already says marking read is off there.
     */
    override fun markSeen(id: String): Applied =
        if (anyFolderAllows(listOf(id), FolderAction.MARK_READ) != null) Applied(null) else inner.markSeen(id)

    override fun setKeyword(ids: List<String>, keyword: String, on: Boolean): Applied {
        val action = if (keyword.equals("\$seen", ignoreCase = true)) FolderAction.MARK_READ else FolderAction.TAG
        anyFolderAllows(ids, action)?.let { throw NotAllowedHere(it) }
        return inner.setKeyword(ids, keyword, on)
    }

    /**
     * A move replaces every folder a message is in ([Jmap.move]), so it needs leave to take
     * the message out of all of them, and leave to put it into the one it is going to.
     */
    override fun move(ids: List<String>, toMailboxId: String): Applied {
        refusal(FolderAction.FILE_INTO, rightsOf(toMailboxId))?.let { throw NotAllowedHere(it) }
        whereAre(ids).values.forEach { boxes ->
            boxes.filter { it != toMailboxId }.forEach { box ->
                refusal(FolderAction.TAKE_OUT, rightsOf(box))?.let { throw NotAllowedHere(it) }
            }
        }
        return inner.move(ids, toMailboxId).also { ids.forEach { id -> located[id] = setOf(toMailboxId) } }
    }

    override fun destroy(ids: List<String>): Applied {
        anyFolderAllows(ids, FolderAction.TAKE_OUT)?.let { throw NotAllowedHere(it) }
        return inner.destroy(ids).also { ids.forEach { id -> located.remove(id) } }
    }

    override fun createMailbox(name: String, parentId: String?): String {
        if (parentId == null) {
            if (!owner) throw NotAllowedHere(TOP_LEVEL_REFUSED)
        } else {
            refusal(FolderAction.NEW_FOLDER, rightsOf(parentId))?.let { throw NotAllowedHere(it) }
        }
        return inner.createMailbox(name, parentId)
    }

    override fun updateMailbox(id: String, name: String?, parentId: String?, reparent: Boolean) {
        // Stalwart needs its Modify right, which JMAP calls mayRename, for any change to a
        // folder, moving it included.
        refusal(FolderAction.RENAME, rightsOf(id))?.let { throw NotAllowedHere(it) }
        if (reparent && parentId == null && !owner) throw NotAllowedHere(TOP_LEVEL_REFUSED)
        if (parentId != null) refusal(FolderAction.NEW_FOLDER, rightsOf(parentId))?.let { throw NotAllowedHere(it) }
        inner.updateMailbox(id, name, parentId, reparent)
    }

    override fun destroyMailbox(id: String, withMail: Boolean) {
        refusal(FolderAction.DELETE_FOLDER, rightsOf(id))?.let { throw NotAllowedHere(it) }
        if (withMail) refusal(FolderAction.TAKE_OUT, rightsOf(id))?.let { throw NotAllowedHere(it) }
        inner.destroyMailbox(id, withMail)
    }

    // ---- writing: from your own account, never from this one -------------------------

    override fun identities(): List<Identity> = emptyList()
    override fun setSignature(identityId: String, text: String, html: String) { throw NotAllowedHere(OWNERS_ONLY) }
    override fun upload(file: Path): Attachment = inner.upload(file)
    override val maxSizeUpload: Long get() = inner.maxSizeUpload

    override fun importMessage(file: Path, mailboxId: String, metadata: ImportedMessage): String {
        refusal(FolderAction.FILE_INTO, rightsOf(mailboxId))?.let { throw NotAllowedHere(it) }
        return inner.importMessage(file, mailboxId, metadata)
    }

    override fun saveDraft(draft: Draft, identity: Identity, draftsMailboxId: String, replacing: String?): String =
        throw NotAllowedHere(WRITE_FROM_YOURS)
    override fun send(draft: Draft, identity: Identity, draftsMailboxId: String, sentMailboxId: String?): String? =
        throw NotAllowedHere(WRITE_FROM_YOURS)
    override val maxDelayedSend: Long get() = 0L
    override fun sendDelayed(
        draft: Draft,
        identity: Identity,
        draftsMailboxId: String,
        sentMailboxId: String?,
        holdUntil: Instant,
    ): DelayedSend = throw NotAllowedHere(WRITE_FROM_YOURS)
    override fun cancelDelayed(submissionId: String): String? = throw NotAllowedHere(WRITE_FROM_YOURS)
    override val submissionExtensions: Set<String> get() = emptySet()
    override fun delivery(emailId: String): DeliveryReport? = null

    // ---- the owner's own things ------------------------------------------------------

    override val hasPush: Boolean get() = false
    override fun watch(onChange: () -> Unit, onGone: () -> Unit): AutoCloseable? = null
    override fun hasSieve(): Boolean = false
    override fun sieveScripts(): List<Jmap.SieveInfo> = throw NotAllowedHere(OWNERS_ONLY)
    override fun sieveText(script: Jmap.SieveInfo): String = throw NotAllowedHere(OWNERS_ONLY)
    override fun saveSieve(name: String, text: String, existing: Jmap.SieveInfo?) { throw NotAllowedHere(OWNERS_ONLY) }
    override fun vacation(): Vacation? = null
    override fun setVacation(value: Vacation) { throw NotAllowedHere(OWNERS_ONLY) }
    override fun hasContacts(): Boolean = false
    override fun hasCalendars(): Boolean = false
    override fun addressBooks(): List<ContactBook> = emptyList()
    override fun quota(): List<MailQuota> = emptyList()
    override fun contacts(): List<Pair<Contact, JsonObject>> = emptyList()
    override fun saveContact(contact: Contact, original: JsonObject?): String = throw NotAllowedHere(OWNERS_ONLY)
    override fun deleteContact(id: String) { throw NotAllowedHere(OWNERS_ONLY) }

    companion object {
        const val TOP_LEVEL_REFUSED = "Only the owner of this mailbox can add a folder at its top level."
        const val WRITE_FROM_YOURS = "Mail is written from your own account. A shared mailbox is read and filed here, not sent from."
        const val OWNERS_ONLY = "That belongs to the mailbox's owner, so Rampart does not change it from here."
    }
}

/**
 * The account a message is written from when the one on screen is [key]: your own login
 * for a shared mailbox, so a reply goes out as you, from your Sent, and never tries to send
 * as the mailbox's owner.
 */
internal fun sendingKey(key: String): String = if (isSharedKey(key)) loginKeyOf(key) else key
