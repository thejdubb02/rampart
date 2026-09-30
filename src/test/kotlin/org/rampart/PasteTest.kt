package org.rampart

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.time.LocalDateTime
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Paste and drop decide what to attach before any window is involved.
 * A test that only checks a function returned something is not one of these:
 * each one pins the file, the picture, or the text.
 */
class PasteTest {

    @Test
    fun `a pasted picture is named with the time it was pasted`() {
        assertEquals(
            "screenshot-20260929-153045.png",
            pastedImageName(LocalDateTime.of(2026, 9, 29, 15, 30, 45)),
        )
        assertEquals(
            "screenshot-20260102-030405.png",
            pastedImageName(LocalDateTime.of(2026, 1, 2, 3, 4, 5)),
        )
    }

    @Test
    fun `a drop keeps real files, in order, and leaves folders and other uris out`() {
        val dir = Files.createTempDirectory("rampart-paste-test")
        try {
            val first = dir.resolve("notes.txt")
            val second = dir.resolve("screen shot.png")
            Files.write(first, "a".toByteArray())
            Files.write(second, "b".toByteArray())
            val folder = Files.createDirectory(dir.resolve("a folder"))
            val dropped = droppedFiles(
                listOf(
                    first.toUri().toString(),
                    folder.toUri().toString(),
                    "https://example.com/a.png",
                    "not a uri",
                    "notes.txt",
                    second.toUri().toString(),
                ),
            )
            assertEquals(
                listOf(first.toAbsolutePath().normalize(), second.toAbsolutePath().normalize()),
                dropped.paths,
            )
            assertFalse(dropped.foldersOnly)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a drop that is only a folder says so`() {
        val dir = Files.createTempDirectory("rampart-paste-folder")
        try {
            val dropped = droppedFiles(listOf(dir.toUri().toString()))
            assertEquals(emptyList(), dropped.paths)
            assertTrue(dropped.foldersOnly)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a missing file is not treated as a folder`() {
        val missing = java.nio.file.Path.of(
            System.getProperty("java.io.tmpdir"),
            "rampart-no-such-file-paste.txt",
        )
        val dropped = droppedFiles(listOf(missing.toUri().toString()))
        assertTrue(dropped.paths.isEmpty())
        assertFalse(dropped.foldersOnly)
    }

    @Test
    fun `a copied file is the file, even when the clipboard also has its name`() {
        val dir = Files.createTempDirectory("rampart-paste-file")
        try {
            val file = dir.resolve("notes.txt")
            Files.write(file, "hello".toByteArray())
            val offer = pasteOffer(
                Clip(
                    mapOf(
                        DataFlavor.javaFileListFlavor to { listOf(file.toFile()) },
                        DataFlavor.stringFlavor to { file.fileName.toString() },
                    ),
                ),
            ) as PasteOffer.Files
            assertEquals(
                listOf(file.toAbsolutePath().normalize()),
                offer.paths.map { it.toAbsolutePath().normalize() },
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a copied file wins over a picture beside it`() {
        val dir = Files.createTempDirectory("rampart-paste-both")
        try {
            val file = dir.resolve("notes.txt")
            Files.write(file, "hello".toByteArray())
            val offer = pasteOffer(
                Clip(
                    mapOf(
                        DataFlavor.javaFileListFlavor to { listOf(file.toFile()) },
                        DataFlavor.imageFlavor to { BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB) },
                    ),
                ),
            )
            assertTrue(offer is PasteOffer.Files)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a copied folder is not a file`() {
        val dir = Files.createTempDirectory("rampart-paste-dir")
        try {
            assertEquals(
                PasteOffer.Directories,
                pasteOffer(Clip(mapOf(DataFlavor.javaFileListFlavor to { listOf(dir.toFile()) }))),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a clipboard with text is text, even beside a picture`() {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        assertEquals(
            PasteOffer.Text("See you Tuesday."),
            pasteOffer(
                Clip(
                    mapOf(
                        DataFlavor.stringFlavor to { "See you Tuesday." },
                        DataFlavor.imageFlavor to { image },
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a clipboard flavor is read off the caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        var seen: Thread? = null
        val offer = offerFromClipboard(
            Clip(mapOf(DataFlavor.stringFlavor to {
                seen = Thread.currentThread()
                "See you Tuesday."
            })),
        )
        assertEquals(PasteOffer.Text("See you Tuesday."), offer)
        assertTrue(seen != null && seen != caller)
    }

    @Test
    fun `pasted text replaces the selection and leaves the caret after it`() {
        assertEquals(PastedText("Hello there.", 11), pastedInto("Hello.", 5, 5, " there"))
        assertEquals(PastedText("Hello.", 5), pastedInto("Hello world.", 5, 11, ""))
        assertEquals(PastedText("Hi", 2), pastedInto("Hello", 5, 1, "i"))
    }

    @Test
    fun `a blank text flavor does not hide a picture`() {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val offer = pasteOffer(
            Clip(
                mapOf(
                    DataFlavor.stringFlavor to { "  " },
                    DataFlavor.imageFlavor to { image },
                ),
            ),
        ) as PasteOffer.Image
        assertEquals(0x89.toByte(), offer.png[0])
        val read = ImageIO.read(offer.png.inputStream())
        assertEquals(2, read.width)
        assertEquals(2, read.height)
    }

    @Test
    fun `png bytes on the clipboard are the picture`() {
        val encoded = pngBytes(BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB))!!
        val flavor = DataFlavor("image/png")
        val offer = pasteOffer(Clip(mapOf(flavor to { encoded.inputStream() }))) as PasteOffer.Image
        assertContentEquals(encoded, offer.png)
    }

    @Test
    fun `bytes that are not a png are not a picture`() {
        val flavor = DataFlavor("image/png")
        assertEquals(
            PasteOffer.Pass,
            pasteOffer(Clip(mapOf(flavor to { "hello".byteInputStream() }))),
        )
    }

    @Test
    fun `a clipboard that cannot be read is left alone`() {
        val offer = pasteOffer(object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
                throw IllegalStateException("busy")
            override fun getTransferData(flavor: DataFlavor): Any = throw UnsupportedFlavorException(flavor)
        })
        assertEquals(PasteOffer.Pass, offer)
    }

    @Test
    fun `a pasted picture is written under its name and only that folder is removed`() {
        val png = pngBytes(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB))!!
        val at = LocalDateTime.of(2026, 9, 29, 15, 30, 45)
        val path = writePastedImage(png, at)
        assertEquals("screenshot-20260929-153045.png", path.fileName.toString())
        assertTrue(path.parent.fileName.toString().startsWith("rampart-paste"))
        assertContentEquals(png, Files.readAllBytes(path))
        deletePastedImage(path)
        assertFalse(Files.exists(path))
        assertFalse(Files.exists(path.parent))
    }

    @Test
    fun `finishing a paste does not delete a file outside a paste folder`() {
        val dir = Files.createTempDirectory("rampart-keep")
        val kept = dir.resolve("keep.txt")
        Files.write(kept, "safe".toByteArray())
        try {
            deletePastedImage(kept)
            assertEquals("safe", Files.readString(kept))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

/** A clipboard with exactly the flavors a test wants to offer. */
private class Clip(private val data: Map<DataFlavor, () -> Any>) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = data.keys.toTypedArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = data.keys.any { it.equals(flavor) }

    override fun getTransferData(flavor: DataFlavor): Any {
        val provide = data.entries.firstOrNull { it.key.equals(flavor) }?.value
            ?: throw UnsupportedFlavorException(flavor)
        return provide()
    }
}
