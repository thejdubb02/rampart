package org.rampart

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineAttachmentsTest {

    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val mb = 1024L * 1024
    private val temp: Path = Files.createTempDirectory("rampart-offline-test")

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    private fun daysAgo(days: Long) = now.minus(Duration.ofDays(days)).toEpochMilli()

    private fun candidate(blob: String, size: Long, days: Long, email: String = "e-$blob") =
        OfflineCandidate(blob, email, "$blob.pdf", "application/pdf", size, daysAgo(days))

    private fun kept(blob: String, size: Long, days: Long, lastUsed: Long = 0L) =
        KeptAttachment(blob, "e-$blob", "$blob.pdf", "application/pdf", size, daysAgo(days), lastUsed, "$blob.bin")

    // ---- what to keep --------------------------------------------------------------

    @Test
    fun `a file over the per-file cap is skipped, not cut`() {
        val plan = planOffline(
            listOf(candidate("small", 1 * mb, 1), candidate("huge", 30 * mb, 1)),
            emptyList(), OfflinePrefs(on = true), now, complete = true,
        )
        assertEquals(listOf("small"), plan.fetch.map { it.blobId })
        assertEquals(1, plan.tooBig)
    }

    @Test
    fun `past the total the oldest messages are left out, newest fetched first`() {
        val prefs = OfflinePrefs(on = true, perFile = 25 * mb, total = 50 * mb)
        val plan = planOffline(
            listOf(candidate("old", 20 * mb, 20), candidate("new", 20 * mb, 1), candidate("mid", 20 * mb, 5)),
            emptyList(), prefs, now, complete = true,
        )
        assertEquals(listOf("new", "mid"), plan.fetch.map { it.blobId })
    }

    @Test
    fun `a newer file pushes the oldest kept one out`() {
        val prefs = OfflinePrefs(on = true, perFile = 25 * mb, total = 40 * mb)
        val plan = planOffline(
            listOf(candidate("old", 20 * mb, 20), candidate("mid", 20 * mb, 5), candidate("new", 20 * mb, 1)),
            listOf(kept("old", 20 * mb, 20), kept("mid", 20 * mb, 5)),
            prefs, now, complete = true,
        )
        assertEquals(listOf("new"), plan.fetch.map { it.blobId })
        assertEquals(listOf("old"), plan.drop.map { it.blobId })
    }

    @Test
    fun `aged out or gone from the inbox is dropped, but not when the listing broke off`() {
        val keptNow = listOf(kept("aged", 1 * mb, 40), kept("moved", 1 * mb, 3), kept("stays", 1 * mb, 2))
        val candidates = listOf(candidate("stays", 1 * mb, 2))
        val whole = planOffline(candidates, keptNow, OfflinePrefs(on = true), now, complete = true)
        assertEquals(setOf("aged", "moved"), whole.drop.map { it.blobId }.toSet())

        val partial = planOffline(candidates, keptNow, OfflinePrefs(on = true), now, complete = false)
        assertEquals(listOf("aged"), partial.drop.map { it.blobId })
    }

    @Test
    fun `eviction is oldest message first, then least recently opened`() {
        val files = listOf(
            kept("recent", 10 * mb, 1),
            kept("oldOpened", 10 * mb, 10, lastUsed = 500),
            kept("oldUnopened", 10 * mb, 10, lastUsed = 100),
            kept("oldest", 10 * mb, 25),
        )
        assertEquals(listOf("oldest", "oldUnopened"), offlineEvictions(files, 20 * mb).map { it.blobId })
        assertTrue(offlineEvictions(files, 40 * mb).isEmpty())
    }

    @Test
    fun `lowering the per-file cap drops what is now too big even from a broken listing`() {
        val plan = planOffline(emptyList(), listOf(kept("big", 20 * mb, 1)), OfflinePrefs(on = true, perFile = 10 * mb), now, complete = false)
        assertEquals(listOf("big"), plan.drop.map { it.blobId })
    }

    // ---- the sealed files ------------------------------------------------------------

    private val mailKey = "a1b2c3d4".repeat(8)

    @Test
    fun `a file round trips, and no plaintext reaches the disk`() {
        val vault = OfflineVault(temp.resolve("acct"), mailKey)
        val secret = "QUARTERLY-FIGURES-DO-NOT-FORWARD".toByteArray()
        val payload = ByteArray(4096) { (it % 251).toByte() } + secret
        val file = vault.write("blob-1", payload)
        vault.writeIndex(listOf(KeptAttachment("blob-1", "e1", "figures-2026.xlsx", "x", payload.size.toLong(), 1L, 0L, file)))

        assertContentEquals(payload, vault.read(file, "blob-1"))
        assertEquals("figures-2026.xlsx", vault.readIndex().single().name)

        Files.walk(temp).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val bytes = Files.readAllBytes(path)
                assertFalse(contains(bytes, secret), "plaintext in $path")
                assertFalse(contains(bytes, "figures-2026".toByteArray()), "a file name in $path")
                assertFalse(contains(bytes, "blob-1".toByteArray()), "a blob id in $path")
                assertFalse(path.fileName.toString().contains("blob"), "a blob id in the name $path")
            }
        }
        // Nothing left over from the write-then-move.
        Files.list(temp.resolve("acct")).use { list -> assertTrue(list.noneMatch { it.fileName.toString().endsWith(".new") }) }
    }

    @Test
    fun `the wrong key, a swapped file or a damaged one reads as absent`() {
        val vault = OfflineVault(temp.resolve("acct"), mailKey)
        val a = vault.write("blob-a", "first".toByteArray())
        val b = vault.write("blob-b", "second".toByteArray())

        assertNull(OfflineVault(temp.resolve("acct"), "f".repeat(64)).read(a, "blob-a"))
        // A's bytes under B's name: the blob id is bound into the tag.
        Files.copy(temp.resolve("acct").resolve(a), temp.resolve("acct").resolve(b), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertNull(vault.read(b, "blob-b"))

        val bytes = Files.readAllBytes(temp.resolve("acct").resolve(a))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        Files.write(temp.resolve("acct").resolve(a), bytes)
        assertNull(vault.read(a, "blob-a"))
        assertNull(vault.read("missing.bin", "blob-a"))
    }

    @Test
    fun `an unreadable index is an empty one`() {
        val dir = temp.resolve("acct")
        Files.createDirectories(dir)
        Files.write(dir.resolve("index.bin"), "not sealed".toByteArray())
        assertTrue(OfflineVault(dir, mailKey).readIndex().isEmpty())
    }

    @Test
    fun `turning it off deletes everything for the account`() {
        val dir = temp.resolve("acct")
        val vault = OfflineVault(dir, mailKey)
        val file = vault.write("blob-1", ByteArray(100))
        vault.writeIndex(listOf(KeptAttachment("blob-1", "e1", "a.pdf", "x", 100, 1L, 0L, file)))
        assertTrue(Files.exists(dir))

        OfflineAttachments.wipe("someone@example.org@test", dir)
        assertFalse(Files.exists(dir))
        assertEquals(0L, OfflineAttachments.status.value["someone@example.org@test"]?.usedBytes)
    }

    @Test
    fun `the default is off, 25 MB a file and 2 GB in all`() {
        val prefs = OfflinePrefs()
        assertFalse(prefs.on)
        assertEquals(25L * 1024 * 1024, prefs.perFile)
        assertEquals(2L * 1024 * 1024 * 1024, prefs.total)
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
