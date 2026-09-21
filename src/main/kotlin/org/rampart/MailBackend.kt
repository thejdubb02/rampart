package org.rampart

import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/**
 * What Rampart needs a mail server to do, whichever protocol it speaks.
 *
 * Extracted from what the app already asked of [Jmap] rather than designed up front, so it
 * is exactly the surface in use and not one member more. Every call site was written
 * against JMAP and none of them changed: the interface carries the signatures that were
 * already there, which is the whole reason it could be introduced at this size.
 *
 * **Not everything here is possible over IMAP**, and that is the point of naming it. IMAP
 * has no blob store, no Sieve, no vacation, no server-side thread and no cross-folder
 * search, so [Imap] answers those with [Unsupported] carrying the reason from [Lacks], and
 * the UI leaves the feature out with a sentence rather than showing a button that fails.
 * A capability that is simply absent is honest; a button that errors is not.
 */
internal interface MailBackend {

    // ---- reading -------------------------------------------------------------------

    fun mailboxes(): List<Mailbox>

    /**
     * The server's own word for "nothing has changed", or null when it cannot say.
     *
     * Null is a real answer and not a failure: a backend that cannot tell you means every
     * refresh re-reads, which is slower and still correct.
     */
    fun mailState(): String?

    /**
     * One page of a folder, newest first.
     *
     * [filters] is the quick filter row. [unreadOnly] remains so a caller that still
     * passes the old flag keeps meaning "unread", and it is copied into [filters] when
     * the caller does not pass one. The body of every implementation reads [filters]
     * and nothing else: there is not a second path.
     *
     * [knownSenders] and [userKeywords] are the account's book and the user labels the
     * local copy has already seen. They are not toggles. They are what the known-sender
     * and tagged toggles are asked with, and an empty list means there is nothing to match.
     */
    fun emails(
        mailboxId: String,
        limit: Int = 100,
        from: Int = 0,
        unreadOnly: Boolean = false,
        filters: QuickFilters = QuickFilters(unread = unreadOnly),
        knownSenders: Collection<String> = emptyList(),
        userKeywords: Collection<String> = emptyList(),
    ): List<Summary>

    fun thread(threadId: String): List<Summary>

    fun body(id: String): Body

    fun attachments(emailId: String): List<Attachment>

    fun blob(attachment: Attachment, limit: Long = 8L * 1024 * 1024): ByteArray?

    /** The message exactly as it arrived, for saving as .eml or reading the headers raw. */
    fun raw(emailId: String, limit: Long = 4L * 1024 * 1024): String?

    fun download(attachment: Attachment, into: Path): Path

    fun search(text: String, mailboxId: String? = null, limit: Int = 100): List<Summary>

    /**
     * Every message carrying [keyword], newest first, across the whole account.
     *
     * A tag is not a folder and deliberately not asked for one folder at a time: the point
     * of tagging an invoice is to find it again without remembering where it was filed.
     */
    fun withKeyword(keyword: String, limit: Int = 200): List<Summary>

    // ---- changing what is there ----------------------------------------------------

    fun markSeen(id: String)

    fun setKeyword(ids: List<String>, keyword: String, on: Boolean)

    fun move(ids: List<String>, toMailboxId: String)

    fun destroy(ids: List<String>)

    fun createMailbox(name: String, parentId: String? = null): String

    fun updateMailbox(id: String, name: String? = null, parentId: String? = null, reparent: Boolean = false)

    fun destroyMailbox(id: String, withMail: Boolean = false)

    // ---- writing mail --------------------------------------------------------------

    fun identities(): List<Identity>

    fun setSignature(identityId: String, text: String, html: String)

    fun upload(file: Path): Attachment

    fun saveDraft(draft: Draft, identity: Identity, draftsMailboxId: String, replacing: String?): String

    fun send(draft: Draft, identity: Identity, draftsMailboxId: String, sentMailboxId: String?)

    // ---- things a server may simply not have ---------------------------------------

    /**
     * Whether the server can tell us mail arrived, rather than being asked.
     *
     * False is not a fault. The poll underneath push exists for exactly this and is why an
     * IMAP account still shows new mail, just later.
     */
    val hasPush: Boolean

    fun watch(onChange: () -> Unit, onGone: () -> Unit): AutoCloseable?

    fun hasSieve(): Boolean

    fun sieveScripts(): List<Jmap.SieveInfo>

    fun sieveText(script: Jmap.SieveInfo): String

    fun saveSieve(name: String, text: String, existing: Jmap.SieveInfo? = null)

    fun vacation(): Vacation?

    fun setVacation(value: Vacation)

    fun hasContacts(): Boolean

    fun addressBooks(): List<ContactBook>

    /**
     * How full the mailbox is. Empty where the server keeps no limit, which is an answer
     * and not a failure: both protocols make a quota optional even where they support one.
     */
    fun quota(): List<MailQuota>

    /** The card and the JSON it arrived as, because a save is written on top of the latter. */
    fun contacts(): List<Pair<Contact, JsonObject>>

    fun saveContact(contact: Contact, original: JsonObject? = null): String

    fun deleteContact(id: String)
}

/**
 * What a backend says when the protocol has no such thing.
 *
 * Carries the [Lacks] entry rather than a message written at the call site, so the sentence
 * shown to somebody is the same sentence wherever they run into it, and adding a backend
 * means adding reasons rather than hunting for strings.
 */
internal class Unsupported(val lacks: Lacks) : Exception(lacks.why)

/**
 * The five toggles above a folder.
 *
 * Every one that is on applies, and they apply together: unread and starred means
 * unread messages that are also starred, which is what a row of filters means. OR
 * would be a different control. Not remembered between runs, for the same reason the
 * single unread switch was not: opening the app into a folder that is hiding most of
 * itself reads as lost mail.
 */
internal data class QuickFilters(
    val unread: Boolean = false,
    val starred: Boolean = false,
    val tagged: Boolean = false,
    val attachment: Boolean = false,
    /** From an address already in this account's book. A switch, not a person picker. */
    val knownSender: Boolean = false,
) {
    /** Whether the row should show a count and a way to clear. */
    val active: Boolean
        get() = unread || starred || tagged || attachment || knownSender

    fun cleared(): QuickFilters = QuickFilters()

    /**
     * What to actually ask for.
     *
     * Attachment is dropped, not left on, where nothing can answer it. Left on, it is
     * either ignored, which looks like every message has one, or the list comes back
     * empty, which looks like none of them do. Neither is the truth.
     */
    fun asked(canFilterAttachment: Boolean): QuickFilters =
        if (canFilterAttachment || !attachment) this else copy(attachment = false)
}

/**
 * Why the attachment toggle cannot be pressed, or null when it can.
 *
 * JMAP has `hasAttachment` on the query. IMAP has no such search key, and the local
 * copy has no column for it, so both of those would be a guess. A merged inbox that
 * includes an account in that position cannot ask the question of every row either:
 * half an answer would look like a whole one.
 */
internal fun attachmentReason(imapAccount: Boolean, mergedWithImap: Boolean): String? = when {
    mergedWithImap -> "Not every account can filter by attachment, so a merged inbox cannot either."
    imapAccount -> "This server cannot say which messages have an attachment, and the saved copy does not record it."
    else -> null
}

/**
 * Whether [summary] meets every toggle that is on.
 *
 * AND, not OR. Attachment is not decided here: a [Summary] does not know whether it
 * has one, and there is no honest way to pretend it does. Callers drop that toggle
 * first, via [QuickFilters.asked], wherever the backend cannot answer it.
 *
 * [knownSenders] is this account's book. Matching ignores case, because that is how
 * the book itself treats two spellings of one address.
 */
internal fun matchesQuick(summary: Summary, filters: QuickFilters, knownSenders: Set<String>): Boolean {
    if (filters.unread && summary.seen) return false
    if (filters.starred && !summary.flagged) return false
    if (filters.tagged && tagsOf(summary.keywords).isEmpty()) return false
    if (filters.knownSender && knownSenders.none { it.equals(summary.fromEmail.trim(), ignoreCase = true) }) {
        return false
    }
    return true
}
