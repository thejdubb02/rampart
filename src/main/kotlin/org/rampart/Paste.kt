package org.rampart

import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO

/**
 * What a paste is offering the composer.
 *
 * A file list wins over a picture: copying a file in Explorer puts the file on
 * the clipboard, and some systems also put a thumbnail beside it. Treating the
 * thumbnail as a new screenshot would attach the picture twice and lose the
 * file. Text is not taken here. The field pastes that itself, which is how a
 * sentence keeps landing where the caret is.
 */
internal sealed interface PasteOffer {
    data class Files(val paths: List<Path>) : PasteOffer
    class Image(val png: ByteArray) : PasteOffer
    data object Directories : PasteOffer
    data object Pass : PasteOffer
}

/**
 * Files a drop offered, in the shape the Attach button already uploads.
 *
 * A folder is not something that button can pick, so it is left out.
 * [foldersOnly] is how the composer says so instead of doing nothing.
 */
internal data class DroppedFiles(val paths: List<Path>, val foldersOnly: Boolean)

/**
 * The sentence the Files page already uses when a folder is dropped.
 * The attach button cannot pick a folder either, so the composer says the same thing.
 */
internal const val FOLDERS_NOT_ATTACHABLE =
    "Folders cannot be uploaded; drop the files inside them instead."

private val PASTED_IMAGE_STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

/**
 * The file name of a screenshot, which has no name of its own.
 *
 * [at] is passed in so a test can pin the clock. The stamp is local wall time,
 * which is the time the person means by "when I pasted it".
 */
internal fun pastedImageName(at: LocalDateTime): String =
    "screenshot-${at.format(PASTED_IMAGE_STAMP)}.png"

/** [uris] from a drop, kept only when each one is a real file. */
internal fun droppedFiles(uris: List<String>): DroppedFiles {
    var folders = 0
    val paths = ArrayList<Path>(uris.size)
    for (raw in uris) {
        val path = pathOfFileUri(raw)?.toAbsolutePath()?.normalize() ?: continue
        when {
            Files.isRegularFile(path) -> paths.add(path)
            Files.isDirectory(path) -> folders++
        }
    }
    return DroppedFiles(paths, paths.isEmpty() && folders > 0)
}

/**
 * What [transferable] is for the composer: files, a picture, or nothing to take.
 *
 * Text is [PasteOffer.Pass] even when a picture is sitting beside it, so Ctrl+V
 * of a sentence is left for the field. A blank text flavor does not count: a
 * screenshot tool sometimes adds one, and that must not hide the picture.
 */
internal fun pasteOffer(transferable: Transferable): PasteOffer = runCatching {
    val listed = filesOf(transferable)
    if (listed.isNotEmpty()) {
        val paths = listed.map { it.toPath() }.filter { Files.isRegularFile(it) }
        if (paths.isNotEmpty()) return@runCatching PasteOffer.Files(paths)
        if (listed.all { it.isDirectory }) return@runCatching PasteOffer.Directories
    }
    if (hasPlainText(transferable)) return@runCatching PasteOffer.Pass
    val png = clipboardPng(transferable)
    if (png != null) PasteOffer.Image(png) else PasteOffer.Pass
}.getOrDefault(PasteOffer.Pass)

/**
 * Writes [png] under [pastedImageName] so the existing upload, which takes a
 * path, can send it. The folder is this paste's alone. [deletePastedImage]
 * removes it and will not touch a file outside one of these folders.
 */
internal fun writePastedImage(png: ByteArray, at: LocalDateTime): Path {
    val dir = Files.createTempDirectory("rampart-paste")
    return try {
        val file = dir.resolve(pastedImageName(at))
        Files.write(file, png)
        file
    } catch (e: Exception) {
        runCatching { dir.toFile().deleteRecursively() }
        throw e
    }
}

/**
 * Removes the temp folder a paste wrote, once the upload has read it.
 *
 * Only a folder this created. A path that is not one of those is left where
 * it is: finishing a paste must not delete something the person picked.
 */
internal fun deletePastedImage(path: Path) {
    val dir = path.parent ?: return
    val name = dir.fileName?.toString() ?: return
    if (!name.startsWith("rampart-paste")) return
    runCatching { Files.deleteIfExists(path) }
    runCatching { Files.deleteIfExists(dir) }
}

/** PNG bytes for a picture on the clipboard. Null when it is not a picture. */
internal fun pngBytes(image: Image): ByteArray? {
    val width = image.getWidth(null)
    val height = image.getHeight(null)
    if (width <= 0 || height <= 0) return null
    // Drawn into a plain image first. A screenshot's own colour model is often
    // one the PNG writer refuses, and the refusal looks like a failed paste.
    val copy = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = copy.createGraphics()
    try {
        if (!graphics.drawImage(image, 0, 0, null)) return null
    } finally {
        graphics.dispose()
    }
    val out = ByteArrayOutputStream()
    if (!ImageIO.write(copy, "png", out)) return null
    return out.toByteArray().takeIf { it.isNotEmpty() }
}

private fun pathOfFileUri(raw: String): Path? {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (!"file".equals(uri.scheme, ignoreCase = true)) return null
    return runCatching { Path.of(uri) }.getOrNull()
}

internal fun filesOf(transferable: Transferable): List<java.io.File> {
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    val data = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: return emptyList()
    return data.filterIsInstance<java.io.File>()
}

/** True when the clipboard has text the field should paste. Blank does not count. */
internal fun hasPlainText(transferable: Transferable): Boolean {
    if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return false
    val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String ?: return false
    return text.isNotBlank()
}

private fun clipboardPng(transferable: Transferable): ByteArray? {
    // A flavor that throws is skipped. Some clipboards advertise an image and
    // then refuse to hand it over, and the png bytes beside it are still good.
    val drawn = runCatching {
        if (!transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) return@runCatching null
        val image = transferable.getTransferData(DataFlavor.imageFlavor) as? Image ?: return@runCatching null
        pngBytes(image)
    }.getOrNull()
    if (drawn != null) return drawn
    val flavors = runCatching { transferable.transferDataFlavors }.getOrNull() ?: return null
    for (flavor in flavors) {
        if (!flavor.mimeType.lowercase().startsWith("image/png")) continue
        val data = runCatching { transferable.getTransferData(flavor) }.getOrNull() ?: continue
        val bytes = when (data) {
            is ByteArray -> data
            is InputStream -> data.use { it.readBytes() }
            else -> continue
        }
        // The flavor says png. The bytes have to agree, or a mislabelled
        // payload would go out as a picture that nothing can open.
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) return bytes
    }
    return null
}
