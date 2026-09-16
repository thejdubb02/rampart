package org.rampart

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An independent check of the one property that matters, written against the behaviour
 * rather than the implementation: whatever a sender calls their attachment, the file lands
 * in the folder the person picked and nowhere else.
 *
 * Enumerating attacks tests the cases someone thought of. This tests the invariant.
 */
class AttachmentEscapeTest {
    private val hostile = listOf(
        "../../../etc/passwd",
        "..\\..\\..\\windows\\system32\\evil.dll",
        "/etc/shadow",
        "C:\\Windows\\System32\\drivers\\etc\\hosts",
        "....//....//escape.txt",
        "a/b/c/../../../../d.pdf",
        "\u0000hidden.exe",
        "report.pdf\u0000.exe",
        "file.txt:alternate-stream.exe",
        "CON", "con.txt", "NUL.TXT", "com1.log", "LPT9",
        "trailing. . . ",
        "   ",
        "...",
        ".",
        "..",
        "",
        "\u202Egpj.exe",
        "x".repeat(300) + ".pdf",
        "no-extension-at-all",
    )

    @Test
    fun `nothing a sender can name escapes the chosen folder`() {
        val folder = Path.of("/home/someone/Downloads").toAbsolutePath().normalize()
        hostile.forEach { proposed ->
            val name = safeFileName(proposed)
            val landed = folder.resolve(name).normalize()
            assertEquals(folder, landed.parent, "escaped the folder: $proposed -> $name")
            assertTrue(name.isNotEmpty(), "empty name from: $proposed")
        }
    }

    @Test
    fun `no result carries a separator, a control character or a Windows reserved name`() {
        hostile.forEach { proposed ->
            val name = safeFileName(proposed)
            assertFalse(name.contains('/'), "slash survived: $proposed -> $name")
            assertFalse(name.contains('\\'), "backslash survived: $proposed -> $name")
            assertFalse(name.any { it.isISOControl() }, "control character survived: $proposed")
            assertFalse(name.any { it in "<>:\"|?*" }, "forbidden character survived: $proposed -> $name")
            assertFalse(name.endsWith('.') || name.endsWith(' '), "trailing junk survived: $proposed -> $name")
            assertFalse(
                name.substringBefore('.').uppercase() in setOf("CON", "PRN", "AUX", "NUL", "COM1", "LPT9"),
                "reserved device name survived: $proposed -> $name",
            )
        }
    }

    @Test
    fun `the result stays short enough for every filesystem we ship on`() {
        hostile.forEach { assertTrue(safeFileName(it).length <= 120, "too long from: $it") }
    }
}
