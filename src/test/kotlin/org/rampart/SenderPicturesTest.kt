package org.rampart

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two rules a picture is allowed to get wrong without a window: which address it
 * belongs to, and whether Junk may have one. The bytes are a stand-in for the bitmap,
 * so this does not open a window and does not ask anyone for a picture.
 */
class SenderPicturesTest {
    private val ada = "b5fc85e55755f9e0d030a10ab4429b6b2944855f9a0d60077fe832becbc41d72"
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00,
    )

    @Test
    fun `the cache key is the lowercased address`() {
        assertEquals("ada@example.com", senderPictureKey("  Ada@Example.com "))
        assertEquals(ada, senderPictureHash("Ada@Example.com"))
        assertEquals(senderPictureHash("ada@example.com"), senderPictureHash("Ada@Example.com"))
        assertEquals("example.com", senderDomain("Ada@Example.com"))
        assertEquals("gmail.com", senderDomain("a@gmail.com"))
        assertNull(senderDomain("not-an-address"))
        assertNull(senderDomain("a@127.0.0.1"))
        assertNull(senderDomain("a@10.1.2.3"))
        assertNull(senderDomain("a@localhost"))
        assertNull(senderDomain("a@foo.localhost"))
        assertNull(senderDomain("a@printer.local"))
        assertEquals(
            "http://127.0.0.1:8080/icon?domain=example.com&email=$ada",
            senderPictureUrl("http://127.0.0.1:8080/", "example.com", ada),
        )
    }

    @Test
    fun `junk stays on initials unless it is switched on`() {
        assertFalse(senderPictureAllowed(enabled = true, inJunk = true, showInJunk = false))
        assertTrue(senderPictureAllowed(enabled = true, inJunk = true, showInJunk = true))
        assertTrue(senderPictureAllowed(enabled = true, inJunk = false, showInJunk = false))
        assertFalse(senderPictureAllowed(enabled = false, inJunk = false, showInJunk = true))
        assertFalse(senderPictureAllowed(enabled = false, inJunk = true, showInJunk = true))

        assertTrue(folderIsJunk("junk", "a", null))
        assertTrue(folderIsJunk("inbox", "j", "j"))
        assertFalse(folderIsJunk("inbox", "a", "j"))
        assertFalse(folderIsJunk("inbox", "a", null))
        assertFalse(folderIsJunk(null, null, null))
        assertTrue(folderIsJunk("junk", null, null))
    }

    @Test
    fun `a missing choice follows the companion`() {
        assertFalse(senderPicturesOn(null, false))
        assertTrue(senderPicturesOn(null, true))
        assertFalse(senderPicturesOn(false, true))
        assertTrue(senderPicturesOn(true, false))
    }

    @Test
    fun `a picture is asked for once and a miss is remembered`() = runBlocking {
        val dir = Files.createTempDirectory("sender-pictures")
        val store = SenderPictureStore(dir, maxEntries = 10, ttlMillis = 1_000)
        val memory = PictureMemory(4)
        var fetches = 0
        suspend fun load(now: Long, answer: IconAnswer) = pictureFromCaches("Ada@Example.com", memory, store, now) {
            fetches++
            answer
        }

        val first = load(10, IconAnswer.Image(png))
        assertTrue(first.answer is IconAnswer.Image)
        assertEquals(1, fetches)
        val names = Files.list(dir).use { stream ->
            stream.filter { !it.fileName.toString().endsWith(".tmp") }.map { it.fileName.toString() }.toList()
        }
        assertEquals(listOf(ada), names)
        val again = load(20, IconAnswer.Retry)
        assertTrue(again.answer is IconAnswer.Image)
        assertEquals(1, fetches)

        val other = Files.createTempDirectory("sender-misses")
        val misses = SenderPictureStore(other, maxEntries = 10, ttlMillis = 1_000)
        val cold = PictureMemory(4)
        var missFetches = 0
        pictureFromCaches("ada@example.com", cold, misses, now = 0) {
            missFetches++
            IconAnswer.Miss
        }
        pictureFromCaches("Ada@Example.com", cold, misses, now = 10) {
            missFetches++
            IconAnswer.Image(png)
        }
        assertEquals(1, missFetches)
        assertTrue(misses.read("ada@example.com", now = 10) is StoredPicture.Miss)
        assertNull(misses.read("ada@example.com", now = 1_000))

        val retryDir = Files.createTempDirectory("sender-retry")
        val retries = SenderPictureStore(retryDir, maxEntries = 10, ttlMillis = 1_000)
        val blank = PictureMemory(4)
        var retryFetches = 0
        suspend fun retry() = pictureFromCaches("a@b.co", blank, retries, now = 1) {
            retryFetches++
            IconAnswer.Retry
        }
        assertTrue(retry().answer is IconAnswer.Retry)
        assertTrue(retry().answer is IconAnswer.Retry)
        assertEquals(2, retryFetches)
        assertEquals(0, Files.list(retryDir).use { it.count() })
    }

    @Test
    fun `the memory cache forgets the least recently used`() {
        val memory = PictureMemory(2)
        memory.put("a", byteArrayOf(1))
        memory.put("b", byteArrayOf(2))
        assertTrue(memory.get("a") is MemorySlot.Held)
        val dropped = memory.put("c", byteArrayOf(3))
        assertEquals(listOf("b"), dropped)
        assertTrue(memory.get("b") is MemorySlot.Absent)
        assertTrue(memory.get("a") is MemorySlot.Held)
        assertTrue((memory.get("c") as MemorySlot.Held).bytes!!.contentEquals(byteArrayOf(3)))
    }

    @Test
    fun `old files are dropped and a bad response is not a picture`() {
        val dir = Files.createTempDirectory("sender-evict")
        val store = SenderPictureStore(dir, maxEntries = 4, ttlMillis = 10_000)
        listOf("a@a.co", "b@b.co", "c@c.co", "d@d.co", "e@e.co").forEachIndexed { index, email ->
            store.save(email, byteArrayOf(index.toByte()), now = index.toLong() + 1)
        }
        assertNull(store.read("a@a.co", now = 10))
        assertNull(store.read("b@b.co", now = 10))
        assertTrue(store.read("e@e.co", now = 10) is StoredPicture.Image)
        val left = Files.list(dir).use { stream -> stream.filter { Files.isRegularFile(it) }.count() }
        assertEquals(3, left)

        assertTrue(classifyIconResponse(404, "text/html", "<html>".toByteArray(), false) is IconAnswer.Miss)
        assertTrue(classifyIconResponse(200, "image/png", png, false) is IconAnswer.Image)
        assertTrue(classifyIconResponse(200, "text/html", png, false) is IconAnswer.Retry)
        assertTrue(classifyIconResponse(200, "image/png", "<html>".toByteArray(), false) is IconAnswer.Retry)
        assertTrue(classifyIconResponse(200, "image/svg+xml", png, false) is IconAnswer.Retry)
        assertTrue(classifyIconResponse(500, "image/png", png, false) is IconAnswer.Retry)
        assertTrue(classifyIconResponse(200, "image/png", png, true) is IconAnswer.Retry)
    }
}
