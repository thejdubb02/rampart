package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A shared mailbox refuses what its rights do not allow before anything is sent, and every
 * request it does send is for the shared account.
 */
class SharedBackendTest {
    /** Records what reached the server side; anything not expected to be reached fails loudly. */
    private class Inner : MailBackend {
        val calls = mutableListOf<String>()
        var page: List<Summary> = emptyList()

        override fun mailboxes(): List<Mailbox> = error("the wrapper reads folders itself")
        override fun mailState(): String? = "s1"
        override fun emails(
            mailboxId: String, limit: Int, from: Int, unreadOnly: Boolean, filters: QuickFilters,
            knownSenders: Collection<String>, userKeywords: Collection<String>,
        ): List<Summary> = page
        override fun thread(threadId: String): List<Summary> = emptyList()
        override fun body(id: String): Body = error("unused")
        override fun attachments(emailId: String): List<Attachment> = emptyList()
        override fun blob(attachment: Attachment, limit: Long): ByteArray? = null
        override fun raw(emailId: String, limit: Long): String? = null
        override fun download(attachment: Attachment, into: Path): Path = into
        override fun search(text: String, mailboxId: String?, limit: Int, except: Collection<String>): List<Summary> = emptyList()
        override fun withKeyword(keyword: String, limit: Int): List<Summary> = emptyList()
        override fun markSeen(id: String): Applied { calls += "seen $id"; return Applied(null) }
        override fun setKeyword(ids: List<String>, keyword: String, on: Boolean): Applied { calls += "keyword $keyword $ids"; return Applied(null) }
        override fun move(ids: List<String>, toMailboxId: String): Applied { calls += "move $ids $toMailboxId"; return Applied(null) }
        override fun moveFrom(ids: List<String>, fromMailboxId: String, toMailboxId: String): Applied {
            calls += "moveFrom $ids $fromMailboxId $toMailboxId"
            return Applied(null)
        }
        override fun conversationIn(threadId: String, mailboxId: String): List<String>? {
            calls += "conversation $threadId $mailboxId"
            return listOf("e1", "e2")
        }
        override fun destroy(ids: List<String>): Applied { calls += "destroy $ids"; return Applied(null) }
        override fun createMailbox(name: String, parentId: String?): String { calls += "create $name $parentId"; return "new" }
        override fun updateMailbox(id: String, name: String?, parentId: String?, reparent: Boolean) { calls += "update $id" }
        override fun destroyMailbox(id: String, withMail: Boolean) { calls += "destroyMailbox $id" }
        override fun identities(): List<Identity> = error("a shared mailbox never asks")
        override fun setSignature(identityId: String, text: String, html: String) = error("unused")
        override fun upload(file: Path): Attachment = error("unused")
        override fun saveDraft(draft: Draft, identity: Identity, draftsMailboxId: String, replacing: String?): String = error("never")
        override fun send(draft: Draft, identity: Identity, draftsMailboxId: String, sentMailboxId: String?): String? = error("never")
        override val hasPush: Boolean get() = true
        override fun watch(onChange: () -> Unit, onGone: () -> Unit): AutoCloseable? = error("never")
        override fun hasSieve(): Boolean = true
        override fun sieveScripts(): List<Jmap.SieveInfo> = error("never")
        override fun sieveText(script: Jmap.SieveInfo): String = error("never")
        override fun saveSieve(name: String, text: String, existing: Jmap.SieveInfo?) = error("never")
        override fun vacation(): Vacation? = error("never")
        override fun setVacation(value: Vacation) = error("never")
        override fun hasContacts(): Boolean = true
        override fun hasCalendars(): Boolean = true
        override fun addressBooks(): List<ContactBook> = error("never")
        override fun quota(): List<MailQuota> = error("never")
        override fun contacts(): List<Pair<Contact, JsonObject>> = error("never")
        override fun saveContact(contact: Contact, original: JsonObject?): String = error("never")
        override fun deleteContact(id: String) = error("never")
    }

    /** Inbox is read and write, Clients is read only; message e2 is known only to the server, in Clients. */
    private val folders = Json.parseToJsonElement(
        """
        ["Mailbox/get", { "list": [
          { "id": "inbox", "name": "Inbox", "role": "inbox",
            "myRights": { "mayReadItems": true, "mayAddItems": true, "mayRemoveItems": true, "maySetSeen": true, "maySetKeywords": true } },
          { "id": "clients", "name": "Clients",
            "myRights": { "mayReadItems": true } }
        ] }, "m"]
        """.trimIndent(),
    ) as JsonArray

    private val where = Json.parseToJsonElement(
        """["Email/get", { "list": [ { "id": "e2", "mailboxIds": { "clients": true } } ] }, "w"]""",
    ) as JsonArray

    private fun mail(id: String) = Summary(id, "A", "a@example.com", id, "2026-09-20T09:00:00Z", "", seen = false)

    private fun setUp(
        owner: Boolean = false,
        folderList: JsonArray = folders,
    ): Triple<SharedBackend, Inner, MutableList<JsonArray>> {
        val inner = Inner()
        val sent = mutableListOf<JsonArray>()
        val backend = SharedBackend(inner, "g", owner) { calls ->
            sent += calls
            calls.map { call ->
                when (call[0].jsonPrimitive.content) {
                    "Mailbox/get" -> folderList
                    "Email/get" -> where
                    else -> error("unexpected ${call[0]}")
                }
            }
        }
        backend.mailboxes()
        return Triple(backend, inner, sent)
    }

    @Test
    fun `every request the wrapper makes itself names the shared account`() {
        val (backend, _, sent) = setUp()
        // Refused, but only after asking the server where e2 is, which is the request to check.
        runCatching { backend.setKeyword(listOf("e2"), "\$flagged", true) }
        assertEquals(listOf("Mailbox/get", "Email/get"), sent.map { it[0].jsonPrimitive.content })
        sent.forEach { call -> assertEquals("g", call[1].jsonObject["accountId"]!!.jsonPrimitive.content) }
    }

    @Test
    fun `starring in a read-only folder is refused and never sent`() {
        val (backend, inner, _) = setUp()
        val refused = assertFailsWith<NotAllowedHere> { backend.setKeyword(listOf("e2"), "\$flagged", true) }
        assertEquals(FolderAction.TAG.refused, refused.message)
        assertTrue(inner.calls.isEmpty())
    }

    @Test
    fun `starring where the folder allows it goes through`() {
        val (backend, inner, _) = setUp()
        inner.page = listOf(mail("e1"))
        backend.emails("inbox")
        backend.setKeyword(listOf("e1"), "\$flagged", true)
        assertEquals(listOf("keyword \$flagged [e1]"), inner.calls)
    }

    @Test
    fun `opening a message in a read-only folder does not try to mark it read, and does not complain`() {
        val (backend, inner, _) = setUp()
        backend.markSeen("e2")
        assertTrue(inner.calls.isEmpty())
    }

    @Test
    fun `the shared account can say which messages of a thread are in a folder`() {
        val (backend, inner, _) = setUp()
        assertEquals(listOf("e1", "e2"), backend.conversationIn("t1", "inbox"))
        assertEquals(listOf("conversation t1 inbox"), inner.calls)
    }

    @Test
    fun `filing out of one folder does not ask to leave the others`() {
        // A reply that is also in Sent. Taking it out of every folder it is in would
        // need leave from Sent, which this mailbox does not have, and would drop the copy.
        val extra = Json.parseToJsonElement(
            """
            ["Mailbox/get", { "list": [
              { "id": "inbox", "name": "Inbox", "role": "inbox",
                "myRights": { "mayReadItems": true, "mayAddItems": true, "mayRemoveItems": true } },
              { "id": "sent", "name": "Sent", "role": "sent",
                "myRights": { "mayReadItems": true } },
              { "id": "archive", "name": "Archive", "role": "archive",
                "myRights": { "mayReadItems": true, "mayAddItems": true, "mayRemoveItems": true } }
            ] }, "m"]
            """.trimIndent(),
        ) as JsonArray
        val (backend, inner, _) = setUp(folderList = extra)
        inner.page = listOf(mail("e1"))
        backend.emails("inbox")
        backend.emails("sent")
        backend.moveFrom(listOf("e1"), "inbox", "archive")
        assertEquals(listOf("moveFrom [e1] inbox archive"), inner.calls)
    }

    @Test
    fun `filing out of a folder you cannot take mail from is refused`() {
        val (backend, inner, _) = setUp()
        assertFailsWith<NotAllowedHere> { backend.moveFrom(listOf("e2"), "clients", "inbox") }
        assertFailsWith<NotAllowedHere> { backend.moveFrom(listOf("e1"), "inbox", "clients") }
        assertTrue(inner.calls.isEmpty())
    }

    @Test
    fun `moving out of a read-only folder, or into one, is refused`() {
        val (backend, inner, _) = setUp()
        assertFailsWith<NotAllowedHere> { backend.move(listOf("e2"), "inbox") }
        inner.page = listOf(mail("e1"))
        backend.emails("inbox")
        assertFailsWith<NotAllowedHere> { backend.move(listOf("e1"), "clients") }
        assertTrue(inner.calls.isEmpty())
    }

    @Test
    fun `deleting needs the right to take mail out`() {
        val (backend, inner, _) = setUp()
        assertFailsWith<NotAllowedHere> { backend.destroy(listOf("e2")) }
        inner.page = listOf(mail("e1"))
        backend.emails("inbox")
        backend.destroy(listOf("e1"))
        assertEquals(listOf("destroy [e1]"), inner.calls)
    }

    @Test
    fun `folders are refused without their rights, and a top-level folder unless you own the mailbox`() {
        val (backend, inner, _) = setUp()
        assertFailsWith<NotAllowedHere> { backend.createMailbox("New", null) }
        assertFailsWith<NotAllowedHere> { backend.createMailbox("New", "clients") }
        assertFailsWith<NotAllowedHere> { backend.updateMailbox("clients", name = "Renamed") }
        assertFailsWith<NotAllowedHere> { backend.destroyMailbox("clients") }
        assertTrue(inner.calls.isEmpty())

        val (owned, ownedInner, _) = setUp(owner = true)
        owned.createMailbox("New", null)
        assertEquals(listOf("create New null"), ownedInner.calls)
    }

    @Test
    fun `nothing is written, filtered or pushed from somebody else's mailbox`() {
        val (backend, _, _) = setUp()
        assertEquals(emptyList(), backend.identities())
        assertFailsWith<NotAllowedHere> { backend.send(Draft(from = "me@example.com"), Identity("i", "Me", "me@example.com"), "d", null) }
        assertFailsWith<NotAllowedHere> { backend.sendDelayed(Draft(from = "me@example.com"), Identity("i", "Me", "me@example.com"), "d", null, Instant.now()) }
        assertEquals(false, backend.hasPush)
        assertEquals(false, backend.hasSieve())
        assertEquals(null, backend.vacation())
        assertEquals(false, backend.hasContacts())
    }

    @Test
    fun `a reply from a shared mailbox is written from the login it came through`() {
        val shared = sharedKey("me@example.com", "mail.example.com", "g")
        assertEquals("me@example.com@mail.example.com", sendingKey(shared))
        assertEquals("me@example.com@mail.example.com", sendingKey("me@example.com@mail.example.com"))
    }
}
