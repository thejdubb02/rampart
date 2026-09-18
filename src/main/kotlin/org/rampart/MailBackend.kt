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

    fun emails(
        mailboxId: String,
        limit: Int = 100,
        from: Int = 0,
        unreadOnly: Boolean = false,
    ): List<Summary>

    fun thread(threadId: String): List<Summary>

    fun body(id: String): Body

    fun attachments(emailId: String): List<Attachment>

    fun blob(attachment: Attachment, limit: Long = 8L * 1024 * 1024): ByteArray?

    /** The message exactly as it arrived, for saving as .eml or reading the headers raw. */
    fun raw(emailId: String, limit: Long = 4L * 1024 * 1024): String?

    fun download(attachment: Attachment, into: Path): Path

    fun search(text: String, mailboxId: String? = null, limit: Int = 100): List<Summary>

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
