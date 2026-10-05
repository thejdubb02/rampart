package org.rampart

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefenderTest {
    private fun file() = Files.createTempDirectory("defender").resolve("invoice.pdf").also { Files.writeString(it, "x") }

    @Test
    fun `a threat deletes the file and says so`() {
        val f = file()
        val e = assertFailsWith<AttachmentBlocked> { Defender.check(f) { 2 } }
        assertTrue("invoice.pdf" in e.message!!)
        assertFalse(Files.exists(f))
    }

    @Test
    fun `a clean scan, no scanner, or a broken scanner leaves the file`() {
        for (scan in listOf<((java.nio.file.Path) -> Int)?>({ 0 }, null, { throw RuntimeException("no") })) {
            val f = file()
            assertEquals(f, Defender.check(f, scan))
            assertTrue(Files.exists(f))
        }
    }
}
