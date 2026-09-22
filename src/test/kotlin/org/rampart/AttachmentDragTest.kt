package org.rampart

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentDragTest {

    @Test
    fun `a drag offers the file as a file list and nothing else`() {
        val file = java.io.File("notes.txt")
        val payload = fileListTransferable(listOf(file))
        assertTrue(payload.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
        assertFalse(payload.isDataFlavorSupported(DataFlavor.stringFlavor))
        @Suppress("UNCHECKED_CAST")
        val listed = payload.getTransferData(DataFlavor.javaFileListFlavor) as List<java.io.File>
        assertEquals(listOf(file), listed)
        assertFailsWith<UnsupportedFlavorException> {
            payload.getTransferData(DataFlavor.stringFlavor)
        }
    }

    @Test
    fun `the temp file keeps a safe name inside its own folder`() {
        val file = materializeAttachment { dir ->
            Files.write(uniqueIn(dir, "../../etc/passwd"), "x".toByteArray())
        }
        try {
            assertEquals("passwd", file.name)
            assertTrue(file.parentFile.name.startsWith("rampart-drag"), file.parentFile.name)
            assertEquals("x", file.readText())
            assertEquals(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(),
                file.parentFile.parentFile.toPath().toAbsolutePath().normalize(),
            )
        } finally {
            deleteDragFile(file)
        }
        assertFalse(file.exists())
        assertFalse(file.parentFile.exists())
    }

    @Test
    fun `a fetch that writes outside the temp folder is refused and removed`() {
        val outside = Files.createTempDirectory("rampart-outside")
        val escaped = outside.resolve("escaped.txt")
        try {
            assertFailsWith<IllegalStateException> {
                materializeAttachment {
                    Files.write(escaped, "no".toByteArray())
                }
            }
            assertFalse(Files.exists(escaped))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `finishing a drag does not delete a file that is not in a drag folder`() {
        val dir = Files.createTempDirectory("rampart-keep")
        val kept = dir.resolve("keep.txt").toFile()
        kept.writeText("safe")
        try {
            deleteDragFile(kept)
            assertTrue(kept.exists())
            assertEquals("safe", kept.readText())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
