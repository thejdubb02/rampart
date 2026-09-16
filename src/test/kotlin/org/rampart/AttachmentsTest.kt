package org.rampart

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The file name is chosen by the sender, so the check that has to bite is that
 * a hostile name cannot leave the folder or impersonate a Windows device.
 */
class AttachmentsTest {

    private val reserved = setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).flatMap { listOf("COM$it", "LPT$it") }

    /** The result itself must be a name we would actually write, not merely non-null. */
    private fun assertSafe(result: String) {
        assertTrue(result.isNotEmpty(), "empty name")
        assertFalse('/' in result, "slash survived: $result")
        assertFalse('\\' in result, "backslash survived: $result")
        assertFalse('\u0000' in result, "NUL survived: $result")
        assertTrue(result.none { it.isISOControl() }, "control char survived: $result")
        assertFalse(result.any { it in "<>:\"|?*" }, "windows-forbidden survived: $result")
        assertNotEquals(".", result)
        assertNotEquals("..", result)
        assertFalse(result.endsWith('.'), "trailing dot survived: $result")
        assertFalse(result.endsWith(' '), "trailing space survived: $result")
        assertTrue(result.length <= 120, "longer than 120: ${result.length} ($result)")
        val stem = result.substringBefore('.').uppercase()
        assertFalse(stem in reserved, "reserved device name survived: $result")
    }

    @Test
    fun `a unix path cannot walk out of the save folder`() {
        val result = safeFileName("../../../etc/passwd")
        assertSafe(result)
        assertEquals("passwd", result)
    }

    @Test
    fun `a windows path cannot walk out of the save folder`() {
        val result = safeFileName("..\\..\\windows\\system32\\evil.dll")
        assertSafe(result)
        assertEquals("evil.dll", result)
    }

    @Test
    fun `nested directories collapse to the last component`() {
        val result = safeFileName("a/b/c.pdf")
        assertSafe(result)
        assertEquals("c.pdf", result)
    }

    @Test
    fun `a reserved device name is rejected`() {
        for (input in listOf("CON", "con.txt", "nul.txt", "NUL", "AuX", "COM1", "lpt9.doc")) {
            val result = safeFileName(input)
            assertSafe(result)
            assertEquals("attachment", result, "rejected $input as $result")
        }
    }

    @Test
    fun `trailing dots and spaces are stripped rather than left for Windows to rewrite`() {
        val result = safeFileName("trailing. . ")
        assertSafe(result)
        assertEquals("trailing", result)
        val disguised = safeFileName("evil.exe . . ")
        assertSafe(disguised)
        assertEquals("evil.exe", disguised)
    }

    @Test
    fun `an empty name falls back`() {
        val result = safeFileName("")
        assertSafe(result)
        assertEquals("attachment", result)
        assertEquals("attachment", safeFileName("."))
        assertEquals("attachment", safeFileName(".."))
        assertEquals("attachment", safeFileName("..."))
    }

    @Test
    fun `a very long name is shortened but keeps its extension`() {
        val result = safeFileName("a".repeat(300) + ".pdf")
        assertSafe(result)
        assertTrue(result.endsWith(".pdf"), "lost the extension: $result")
        assertEquals(120, result.length)
        assertEquals("a".repeat(116) + ".pdf", result)
    }

    @Test
    fun `a NUL does not hide the rest of the name`() {
        val truncated = safeFileName("report.pdf\u0000.exe")
        assertSafe(truncated)
        assertFalse('\u0000' in truncated)
        // Truncating at NUL would leave a name that looks like a PDF.
        assertNotEquals("report.pdf", truncated)
        assertTrue(truncated.endsWith(".exe") || truncated.contains("exe"), "real extension lost: $truncated")
        assertEquals("report.pdf.exe", truncated)

        val embedded = safeFileName("hello\u0000world.txt")
        assertSafe(embedded)
        assertEquals("helloworld.txt", embedded)
    }

    @Test
    fun `dot and parent names cannot survive as themselves`() {
        assertEquals("attachment", safeFileName("./."))
        assertEquals("attachment", safeFileName("../.."))
        assertSafe(safeFileName("./."))
        assertSafe(safeFileName("../.."))
    }

    @Test
    fun `windows forbidden characters are removed`() {
        val result = safeFileName("file<>:\"|?*.txt")
        assertSafe(result)
        assertEquals("file.txt", result)
    }

    @Test
    fun `the first free name is used as is`() {
        val dir = Files.createTempDirectory("rampart-att")
        val path = uniqueIn(dir, "photo.jpg")
        assertEquals(dir.resolve("photo.jpg"), path)
        assertTrue(path.normalize().startsWith(dir.normalize()), "escaped to $path")
    }

    @Test
    fun `a colliding name gets a number before the extension`() {
        val dir = Files.createTempDirectory("rampart-att")
        Files.createFile(dir.resolve("photo.jpg"))
        assertEquals(dir.resolve("photo (2).jpg"), uniqueIn(dir, "photo.jpg"))
        Files.createFile(dir.resolve("photo (2).jpg"))
        assertEquals(dir.resolve("photo (3).jpg"), uniqueIn(dir, "photo.jpg"))
    }

    @Test
    fun `uniqueIn will not resolve a name outside the folder`() {
        val dir = Files.createTempDirectory("rampart-att")
        val path = uniqueIn(dir, "../../../etc/passwd")
        assertTrue(path.normalize().startsWith(dir.normalize()), "escaped to $path")
        assertEquals("passwd", path.fileName.toString())
        assertEquals(dir.normalize(), path.normalize().parent)
    }

    @Test
    fun `sizes read in the units a person expects`() {
        assertEquals("412 bytes", humanSize(412))
        assertEquals("0 bytes", humanSize(0))
        assertEquals("1023 bytes", humanSize(1023))
        assertEquals("1.0 KB", humanSize(1024))
        assertEquals("9.4 KB", humanSize(9626))
        assertEquals("2.1 MB", humanSize(2_202_010))
        assertEquals("1.0 GB", humanSize(1024L * 1024 * 1024))
    }
}
