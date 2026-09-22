package org.rampart

import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.geometry.Offset
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * What the operating system expects to be handed: a list of real files.
 *
 * Bytes cannot be dropped onto a desktop or into another program. The system
 * asks for [DataFlavor.javaFileListFlavor] and then opens those paths itself.
 */
internal fun fileListTransferable(files: List<File>): Transferable = object : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor.isFlavorJavaFileListType

    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return files
    }
}

/**
 * Runs [fetch] into a new temp folder and returns the file it wrote.
 *
 * The folder is this drag's alone, so removing it afterwards cannot touch a
 * file somebody saved. [fetch] is the same download Save uses. A result that
 * is not inside the folder is deleted and refused: the name came from the
 * sender, and a path that walked out would land somewhere we did not choose.
 */
internal fun materializeAttachment(fetch: (Path) -> Path): File {
    val dir = Files.createTempDirectory("rampart-drag")
    try {
        val written = fetch(dir).toAbsolutePath().normalize()
        val root = dir.toAbsolutePath().normalize()
        if (!written.startsWith(root)) {
            runCatching { Files.deleteIfExists(written) }
            throw IllegalStateException("The attachment was written outside its temporary folder.")
        }
        return written.toFile()
    } catch (e: Exception) {
        runCatching { dir.toFile().deleteRecursively() }
        throw e
    }
}

/**
 * Removes the temp folder a drag wrote, once the drag is over.
 *
 * Only a folder this created. A path that is not one of those is left where
 * it is: finishing a drag must not delete something the reader saved.
 */
internal fun deleteDragFile(file: File) {
    val dir = file.parentFile ?: return
    if (!dir.name.startsWith("rampart-drag")) return
    runCatching { dir.deleteRecursively() }
}

/**
 * Dragging a row out of the window, onto the desktop or into another program.
 *
 * [dragAndDropSource] is the drag Compose already runs: it waits until the
 * pointer has moved (a click that stays put still clicks) and then starts
 * Swing's export, which is [java.awt.dnd.DragSource] carrying the file list.
 * A gesture built by hand does not work from this window. `startDrag` refuses
 * a trigger event it never received, and Compose does not hand the original
 * mouse press to application code.
 *
 * The file has to be on disk before that export returns. The system asks for
 * the path as the drag comes up, not for the bytes. [prepare] is that
 * download. It blocks the gesture, on a background thread, only until the
 * file exists. The completion callback is the drag ending, including a
 * cancel, which is when the temp folder can go.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.dragAttachmentOut(
    prepare: () -> File,
    onFailed: (String) -> Unit,
): Modifier = dragAndDropSource { offset ->
    val file = try {
        // The gesture callback is synchronous and runs on the window's thread.
        // The download is the same blocking call Save makes, so it has to move
        // off that thread or the window freezes for the whole transfer, and
        // the drag cannot start until the file is actually there.
        runBlocking(Dispatchers.IO) { prepare() }
    } catch (e: Exception) {
        onFailed(whyFailed(e).ifBlank { "That file could not be dragged out." })
        null
    }
    if (file == null || !file.isFile) {
        return@dragAndDropSource nothingToDrag(offset)
    }
    // If the drag never reports that it ended, the file still goes when the
    // app does. Deleting it sooner than that, on a guess, can remove it while
    // the other program is still reading it. The folder is registered first:
    // exit deletes in reverse order, so the file goes before the folder.
    file.parentFile?.deleteOnExit()
    file.deleteOnExit()
    DragAndDropTransferData(
        transferable = DragAndDropTransferable(fileListTransferable(listOf(file))),
        supportedActions = listOf(DragAndDropTransferAction.Copy),
        dragDecorationOffset = offset,
        onTransferCompleted = { _ -> deleteDragFile(file) },
    )
}

/** A drag the system can start and no drop target will accept. Download failed. */
@OptIn(ExperimentalComposeUiApi::class)
private fun nothingToDrag(offset: Offset): DragAndDropTransferData = DragAndDropTransferData(
    transferable = DragAndDropTransferable(fileListTransferable(emptyList())),
    supportedActions = listOf(DragAndDropTransferAction.Copy),
    dragDecorationOffset = offset,
)
