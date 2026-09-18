package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuotaTest {

    private fun size(used: Long, limit: Long?) = MailQuota("mail", used, limit, "octets")

    @Test
    fun `a share is of the hard limit, and never past the end of its own bar`() {
        assertEquals(0.5f, quotaShare(size(512, 1024)))
        // A mailbox can be over its limit. The bar stops at full; the words say the truth.
        assertEquals(1f, quotaShare(size(2048, 1024)))
        assertEquals(0f, quotaShare(size(0, 1024)))
    }

    @Test
    fun `no ceiling means no share, rather than a full bar or a crash`() {
        assertNull(quotaShare(size(900, null)))
        // Zero would divide by zero. A server has published one.
        assertNull(quotaShare(size(900, 0)))
    }

    @Test
    fun `a size reads like a size and a count reads like a count`() {
        assertEquals("1.0 KB of 4.0 KB used (25%)", quotaText(size(1024, 4096)))
        assertEquals(
            "40 of 100 messages used (40%)",
            quotaText(MailQuota("mail", 40, 100, "count")),
        )
        // Usage with no ceiling still says something useful.
        assertEquals("1.0 KB used", quotaText(size(1024, null)))
    }

    @Test
    fun `tight means near enough to full to still do something about it`() {
        assertFalse(quotaIsTight(size(89, 100)))
        assertTrue(quotaIsTight(size(90, 100)))
        assertTrue(quotaIsTight(size(200, 100)))
        // Nothing to be full of is not tight.
        assertFalse(quotaIsTight(size(9_000_000, null)))
    }

    @Test
    fun `the size limit is the one shown, because that is what a full mailbox means`() {
        val counted = MailQuota("count", 99, 100, "count")
        val stored = size(1, 1000)
        assertEquals(stored, mainQuota(listOf(counted, stored)))
    }

    @Test
    fun `between two of a kind, the one about to stop delivery wins`() {
        val roomy = MailQuota("a", 1, 100, "octets")
        val nearlyFull = MailQuota("b", 95, 100, "octets")
        assertEquals(nearlyFull, mainQuota(listOf(roomy, nearlyFull)))
    }

    @Test
    fun `usage with no limit is still worth showing when it is all there is`() {
        val open = size(4096, null)
        assertEquals(open, mainQuota(listOf(open)))
    }

    @Test
    fun `no quota at all is an answer, not a failure`() {
        // What this account actually returns: the capability is advertised and the list is
        // empty, because no limit has been set. It must produce a sentence, not a bar.
        assertNull(mainQuota(emptyList()))
    }
}
