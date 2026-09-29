package org.rampart

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaildirExportTest {

    @Test
    fun `flag suffix correctly maps standard flags and orders them in ASCII sequence`() {
        assertEquals("", maildirFlags(seen = false, flagged = false))
        assertEquals(":2,", maildirInfoSuffix(seen = false, flagged = false))

        assertEquals("S", maildirFlags(seen = true, flagged = false))
        assertEquals(":2,S", maildirInfoSuffix(seen = true, flagged = false))

        assertEquals("F", maildirFlags(seen = false, flagged = true))
        assertEquals(":2,F", maildirInfoSuffix(seen = false, flagged = true))

        assertEquals("FS", maildirFlags(seen = true, flagged = true))
        assertEquals(":2,FS", maildirInfoSuffix(seen = true, flagged = true))

        assertEquals("R", maildirFlags(seen = false, flagged = false, keywords = setOf("\$answered")))
        assertEquals("D", maildirFlags(seen = false, flagged = false, keywords = setOf("\$draft")))
        assertEquals("T", maildirFlags(seen = false, flagged = false, keywords = setOf("\$deleted")))

        val allFlags = maildirFlags(
            seen = true,
            flagged = true,
            keywords = setOf("\$draft", "\$answered", "\$deleted"),
        )
        assertEquals("DFRST", allFlags)
        assertEquals(":2,DFRST", maildirInfoSuffix(
            seen = true,
            flagged = true,
            keywords = setOf("\$draft", "\$answered", "\$deleted"),
        ))
    }

    @Test
    fun `flag suffix incorporates dovecot user keyword lowercase letters`() {
        val keywordMap = mapOf("invoices" to 'a', "receipts" to 'b')
        val suffix = maildirInfoSuffix(
            seen = true,
            flagged = true,
            keywords = setOf("invoices"),
            keywordMap = keywordMap,
        )
        assertEquals(":2,FSa", suffix)

        val multipleKeywords = maildirInfoSuffix(
            seen = true,
            flagged = false,
            keywords = setOf("receipts", "invoices"),
            keywordMap = keywordMap,
        )
        assertEquals(":2,Sab", multipleKeywords)
    }

    @Test
    fun `dovecot keywords formatting and parsing handles keyword indices`() {
        val keywords = listOf("invoices", "projects", "receipts")
        val formatted = formatDovecotKeywords(keywords)
        val expected = "0 invoices\n1 projects\n2 receipts"
        assertEquals(expected, formatted)

        val parsed = parseDovecotKeywords(formatted)
        assertEquals(keywords, parsed)

        val mapped = buildDovecotKeywordMap(listOf("\$seen", "Invoices", "\$flagged", "Receipts"))
        assertEquals(mapOf("invoices" to 'a', "receipts" to 'b'), mapped)
    }

    @Test
    fun `maildir folder name mapping preserves root inbox and formats subfolders`() {
        assertEquals("", maildirFolderName(listOf("Inbox"), isInbox = true))
        assertEquals(".Archive", maildirFolderName(listOf("Archive")))
        assertEquals(".Sent", maildirFolderName(listOf("Sent")))
        assertEquals(".Trash", maildirFolderName(listOf("Trash")))
    }

    @Test
    fun `maildir folder name mapping formats nested folders with dot separators`() {
        assertEquals(".Work.Projects", maildirFolderName(listOf("Work", "Projects")))
        assertEquals(".Work.Projects.2024", maildirFolderName(listOf("Work", "Projects", "2024")))
        assertEquals(".Personal.Finances.Taxes", maildirFolderName(listOf("Personal", "Finances", "Taxes")))
    }

    @Test
    fun `maildir folder name mapping sanitizes dots inside folder names`() {
        assertEquals(".v1_0", maildirFolderName(listOf("v1.0")))
        assertEquals(".Work_2024.Sub_1", maildirFolderName(listOf("Work.2024", "Sub.1")))
        assertEquals(".domain_com", maildirFolderName(listOf("domain.com")))
        assertEquals(".Work.Projects.Release_2_5", maildirFolderName(listOf("Work", "Projects", "Release.2.5")))
    }

    @Test
    fun `maildirFolderForMailbox resolves mailbox tree hierarchy`() {
        val inbox = Mailbox("id-inbox", "Inbox", role = "inbox", unread = 0)
        val work = Mailbox("id-work", "Work", role = null, unread = 0)
        val projects = Mailbox("id-projects", "Projects.v1", role = null, unread = 0, parentId = "id-work")
        val allBoxes = listOf(inbox, work, projects)

        assertEquals("", maildirFolderForMailbox(inbox, allBoxes))
        assertEquals(".Work", maildirFolderForMailbox(work, allBoxes))
        assertEquals(".Work.Projects_v1", maildirFolderForMailbox(projects, allBoxes))
    }

    @Test
    fun `resume skipping records exported messages and avoids re-exporting`() {
        val tempDir = Files.createTempDirectory("maildir-resume-test")
        try {
            var progress = loadExportProgress(tempDir)
            assertEquals(0, progress.exported.size)

            progress = recordMessageExported(progress, "INBOX", "msg-1")
            progress = recordMessageExported(progress, "INBOX", "msg-2")
            progress = recordMessageExported(progress, ".Work", "msg-3")
            saveExportProgress(tempDir, progress)

            val loaded = loadExportProgress(tempDir)
            assertTrue(isMessageExported(loaded, "INBOX", "msg-1"))
            assertTrue(isMessageExported(loaded, "INBOX", "msg-2"))
            assertFalse(isMessageExported(loaded, "INBOX", "msg-3"))
            assertTrue(isMessageExported(loaded, ".Work", "msg-3"))
            assertFalse(isMessageExported(loaded, ".Work", "msg-4"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `export writes fake messages to temp then moves to cur with correct flags and keywords`() = runBlocking {
        val tempDir = Files.createTempDirectory("maildir-export-test")
        try {
            val rawMsg1 = """
                From: alice@example.com
                To: me@example.org
                Subject: First Message
                Date: Tue, 29 Sep 2026 10:00:00 +0000

                Hello from Alice.
            """.trimIndent()

            val rawMsg2 = """
                From: bob@example.com
                To: me@example.org
                Subject: Second Message
                Date: Tue, 29 Sep 2026 11:00:00 +0000

                Draft note from Bob.
            """.trimIndent()

            val msg1 = Summary(
                id = "msg-1",
                from = "Alice",
                fromEmail = "alice@example.com",
                subject = "First Message",
                receivedAt = "2026-09-29T10:00:00Z",
                preview = "Hello from Alice.",
                seen = true,
                flagged = true,
            )

            val msg2 = Summary(
                id = "msg-2",
                from = "Bob",
                fromEmail = "bob@example.com",
                subject = "Second Message",
                receivedAt = "2026-09-29T11:00:00Z",
                preview = "Draft note from Bob.",
                seen = false,
                flagged = false,
                keywords = setOf("\$draft", "invoices"),
            )

            val inbox = Mailbox("box-inbox", "Inbox", role = "inbox", unread = 0)
            val fakeBackend = FakeExportBackend(
                mailboxes = listOf(inbox),
                messages = mapOf("box-inbox" to listOf(msg1, msg2)),
                raws = mapOf("msg-1" to rawMsg1, "msg-2" to rawMsg2),
            )

            val progress = exportAccount(
                backend = fakeBackend,
                mailboxes = listOf(inbox),
                exportRoot = tempDir,
                onProgress = { _, _, _ -> },
                isCancelled = { false },
            )

            assertTrue(isMessageExported(progress, "INBOX", "msg-1"))
            assertTrue(isMessageExported(progress, "INBOX", "msg-2"))

            val curDir = tempDir.resolve("cur")
            val newDir = tempDir.resolve("new")
            val tmpDir = tempDir.resolve("tmp")

            assertTrue(curDir.exists())
            assertTrue(newDir.exists())
            assertTrue(tmpDir.exists())

            val curFiles = curDir.listDirectoryEntries()
            assertEquals(2, curFiles.size)
            assertEquals(0, tmpDir.listDirectoryEntries().size)
            assertEquals(0, newDir.listDirectoryEntries().size)

            val file1 = curFiles.first { it.name.endsWith(":2,FS") }
            val file2 = curFiles.first { it.name.endsWith(":2,Da") }

            assertEquals(rawMsg1, file1.readText())
            assertEquals(rawMsg2, file2.readText())

            val dovecotFile = tempDir.resolve("dovecot-keywords")
            assertTrue(dovecotFile.exists())
            assertEquals("0 invoices", dovecotFile.readText().trim())

            // Test resume skipping: running again should not fetch raw messages.
            fakeBackend.rawFetchCount = 0
            val rerunProgress = exportAccount(
                backend = fakeBackend,
                mailboxes = listOf(inbox),
                exportRoot = tempDir,
                onProgress = { _, _, _ -> },
                isCancelled = { false },
            )
            assertEquals(0, fakeBackend.rawFetchCount)
            assertEquals(2, rerunProgress.exported["INBOX"]?.size)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private class FakeExportBackend(
        private val mailboxes: List<Mailbox>,
        private val messages: Map<String, List<Summary>>,
        private val raws: Map<String, String>,
    ) : MailBackend {
        var rawFetchCount = 0

        override fun mailboxes(): List<Mailbox> = mailboxes
        override fun mailState(): String? = null
        override fun emails(
            mailboxId: String,
            limit: Int,
            from: Int,
            unreadOnly: Boolean,
            filters: QuickFilters,
            knownSenders: Collection<String>,
            userKeywords: Collection<String>,
        ): List<Summary> {
            val list = messages[mailboxId].orEmpty()
            if (from >= list.size) return emptyList()
            return list.drop(from).take(limit)
        }

        override fun thread(threadId: String): List<Summary> = emptyList()
        override fun body(id: String): Body = Body("text", "")
        override fun attachments(emailId: String): List<Attachment> = emptyList()
        override fun blob(attachment: Attachment, limit: Long): ByteArray? = null
        override fun raw(emailId: String, limit: Long): String? {
            rawFetchCount++
            return raws[emailId]
        }
        override fun download(attachment: Attachment, into: Path): Path = into
        override fun search(text: String, mailboxId: String?, limit: Int, except: Collection<String>): List<Summary> = emptyList()
        override fun withKeyword(keyword: String, limit: Int): List<Summary> = emptyList()
        override fun markSeen(id: String): Applied = Applied(null)
        override fun setKeyword(ids: List<String>, keyword: String, on: Boolean): Applied = Applied(null)
        override fun move(ids: List<String>, toMailboxId: String): Applied = Applied(null)
        override fun destroy(ids: List<String>): Applied = Applied(null)
        override fun createMailbox(name: String, parentId: String?): String = "id"
        override fun updateMailbox(id: String, name: String?, parentId: String?, reparent: Boolean) {}
        override fun destroyMailbox(id: String, withMail: Boolean) {}
        override fun identities(): List<Identity> = emptyList()
        override fun setSignature(identityId: String, text: String, html: String) {}
        override fun upload(file: Path): Attachment = Attachment("b", "file", "text/plain", 0)
        override fun saveDraft(draft: Draft, identity: Identity, draftsMailboxId: String, replacing: String?): String = "id"
        override fun send(draft: Draft, identity: Identity, draftsMailboxId: String, sentMailboxId: String?): String? = null
        override val hasPush: Boolean get() = false
        override fun watch(onChange: () -> Unit, onGone: () -> Unit): AutoCloseable? = null
        override fun hasSieve(): Boolean = false
        override fun sieveScripts(): List<Jmap.SieveInfo> = emptyList()
        override fun sieveText(script: Jmap.SieveInfo): String = ""
        override fun saveSieve(name: String, text: String, existing: Jmap.SieveInfo?) {}
        override fun vacation(): Vacation? = null
        override fun setVacation(value: Vacation) {}
        override fun hasContacts(): Boolean = false
        override fun hasCalendars(): Boolean = false
        override fun addressBooks(): List<ContactBook> = emptyList()
        override fun quota(): List<MailQuota> = emptyList()
        override fun contacts(): List<Pair<Contact, kotlinx.serialization.json.JsonObject>> = emptyList()
        override fun saveContact(contact: Contact, original: kotlinx.serialization.json.JsonObject?): String = "id"
        override fun deleteContact(id: String) {}
    }
}
