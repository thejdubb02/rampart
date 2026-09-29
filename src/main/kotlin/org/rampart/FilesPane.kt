package org.rampart

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser

/*
 * The Files page, and the two places mail reaches into it: Save to Files beside an
 * attachment in the reader, and From Files in the composer. Files.kt is the protocol;
 * this is only what is drawn and when it asks.
 */

/**
 * Whether the page is open, and whether any signed in account could show it.
 *
 * Kept here rather than in the window's own state so the window needs a line to show the
 * page and a line to wire the button, not a new parameter threaded through the sidebar.
 */
internal object FilesPage {
    var open by mutableStateOf(false)

    /** True when at least one account has file storage. The app bar's Files button hides otherwise. */
    var offered by mutableStateOf(false)

    /** The folder the page opens on, or null for the top. See [openAt]. */
    var startAt by mutableStateOf<String?>(null)
        private set

    /**
     * Counts every [openAt], so asking for the same folder twice still moves the page there
     * after somebody has wandered off it. The folder alone would not change and nothing
     * would happen, which reads as the click not landing.
     */
    var asked by mutableStateOf(0)
        private set

    /** Opens the page on [folder], which is how the panel beside the mail hands over to it. */
    fun openAt(folder: String?) {
        startAt = folder
        asked++
        open = true
    }
}

/**
 * Keeps the Files page in step with the other pages the window can show.
 *
 * Opening Files closes Settings, Contacts and the dashboard through [closeOthers]; opening
 * any of those, or picking a folder, a tag or a search ([place] changing), closes Files.
 * One call in the window instead of a line in every handler that opens something.
 */
@Composable
internal fun FilesFollows(
    backends: List<MailBackend>,
    othersOpen: Boolean,
    place: Any?,
    closeOthers: () -> Unit,
) {
    LaunchedEffect(backends) {
        FilesPage.offered = backends.any { (it as? Jmap)?.hasFileStorage == true }
        if (!FilesPage.offered) FilesPage.open = false
    }
    val close by rememberUpdatedState(closeOthers)
    LaunchedEffect(FilesPage.open) {
        if (FilesPage.open) close()
    }
    LaunchedEffect(othersOpen) {
        if (othersOpen) FilesPage.open = false
    }
    val last = remember { arrayOf(place) }
    LaunchedEffect(place) {
        if (last[0] != place) {
            last[0] = place
            FilesPage.open = false
        }
    }
}

/** A sheet with its corner turned down, on the same 18 unit grid and 1.4 stroke as Icons.kt. */
internal val FilesGlyph: ImageVector = ImageVector.Builder(
    name = "Files",
    defaultWidth = 18.dp,
    defaultHeight = 18.dp,
    viewportWidth = 18f,
    viewportHeight = 18f,
).apply {
    addPath(
        pathData = PathParser().parsePathString(
            "M4.4 2.6h5.8l3.4 3.4v9.4H4.4Z M10.2 2.6V6h3.4 M6.6 9.4h4.8 M6.6 12h4.8",
        ).toNodes(),
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.4f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()

/** Why [session] has no Files page, in one sentence, or null when it has one. */
internal fun filesUnavailable(session: Session?): String? = when {
    session == null -> "Sign in to an account to see its files."
    session.jmap !is Jmap -> "This account signs in over IMAP, which has no way to keep files, " +
        "so Files is only on accounts that sign in over JMAP."
    !(session.jmap as Jmap).hasFileStorage -> "This server does not offer file storage over JMAP, " +
        "so there are no files to show for this account."
    else -> null
}

/** The account's file storage, or null when it has none. */
internal fun filesOf(backend: MailBackend?): FileStore? = (backend as? Jmap)?.fileStore()

/** A failure, as the sentence and the technical line under it. */
private data class Fault(val title: String, val detail: String?)

private fun faultOf(title: String, e: Exception): Fault = Fault(title, faultDetail(e, title))

/**
 * The page itself: a folder tree down the left, the open folder's contents on the right.
 *
 * [session] is the account whose files are shown, picked the way Contacts picks one.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun FilesPane(session: Session?) {
    val unavailable = filesUnavailable(session)
    val store = remember(session) { filesOf(session?.jmap) }
    val scope = rememberCoroutineScope()
    var tree by remember(store) { mutableStateOf<FileTree?>(null) }
    var here by remember(store, FilesPage.asked) { mutableStateOf(FilesPage.startAt) }
    var loading by remember(store) { mutableStateOf(false) }
    var fault by remember(store) { mutableStateOf<Fault?>(null) }
    var busy by remember(store) { mutableStateOf<String?>(null) }
    var note by remember(store) { mutableStateOf<String?>(null) }
    var reloads by remember(store) { mutableStateOf(0) }
    var naming by remember { mutableStateOf<Pair<String, FileNode?>?>(null) }
    var moving by remember { mutableStateOf<FileNode?>(null) }
    var deleting by remember { mutableStateOf<FileNode?>(null) }
    var dropHover by remember { mutableStateOf(false) }

    LaunchedEffect(store, reloads) {
        val files = store ?: return@LaunchedEffect
        loading = true
        try {
            val read = withContext(Dispatchers.IO) { files.tree() }
            tree = read
            // The folder that was open may have gone, by this window or another client.
            val open = here
            if (open != null && read.byId[open]?.isFolder != true) here = null
        } catch (e: Exception) {
            fault = faultOf("The files could not be read.", e)
        } finally {
            loading = false
        }
    }

    /** Runs one change off the window's thread, says what failed, and reads the tree again. */
    fun change(doing: String, failed: String, job: suspend () -> String?) {
        if (store == null || busy != null) return
        fault = null
        note = null
        busy = doing
        scope.launch {
            try {
                note = withContext(Dispatchers.IO) { job() }
            } catch (e: Exception) {
                fault = faultOf(failed, e)
            } finally {
                busy = null
                reloads++
            }
        }
    }

    fun uploadAll(paths: List<Path>) {
        val files = store ?: return
        val target = here
        val plain = paths.filter { java.nio.file.Files.isRegularFile(it) }
        val skipped = paths.size - plain.size
        if (plain.isEmpty()) {
            if (skipped > 0) fault = Fault("Folders cannot be uploaded; drop the files inside them instead.", null)
            return
        }
        change(
            if (plain.size == 1) "Uploading ${plain[0].fileName}" else "Uploading ${plain.size} files",
            if (plain.size == 1) "${plain[0].fileName} could not be uploaded." else "The files could not all be uploaded.",
        ) {
            // One at a time, the same as the composer's attachments: a mail server is not a CDN.
            plain.forEach { files.upload(it, target) }
            if (skipped > 0) "Folders were left out; drop the files inside them instead." else null
        }
    }

    fun openNode(node: FileNode) {
        val files = store ?: return
        change("Opening ${node.name}", "${node.name} could not be opened.") {
            val dir = java.nio.file.Files.createTempDirectory("rampart-open")
            val local = files.download(node, dir)
            // Removed when Rampart closes, not sooner: the program it was handed to may
            // still be reading it long after this returns.
            dir.toFile().deleteOnExit()
            local.toFile().deleteOnExit()
            val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            if (desktop == null || !desktop.isSupported(Desktop.Action.OPEN)) {
                throw JmapError("This system gives Rampart no way to open a file, so use Download instead.")
            }
            desktop.open(local.toFile())
            null
        }
    }

    fun downloadNode(node: FileNode) {
        val files = store ?: return
        val folder = pickFolder() ?: return
        change("Downloading ${node.name}", "${node.name} could not be downloaded.") {
            "Saved to ${files.download(node, folder)}"
        }
    }

    val drop = remember(store) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dropHover = true }
            override fun onExited(event: DragAndDropEvent) { dropHover = false }
            override fun onEnded(event: DragAndDropEvent) { dropHover = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropHover = false
                val dropped = droppedFiles(event)
                if (dropped.isEmpty()) return false
                uploadAll(dropped.map { it.toPath() })
                return true
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Files", style = MaterialTheme.typography.titleLarge)
                session?.let {
                    Text(
                        shortAccountName(it.account.name, it.account.email),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (store != null) {
                TextButton(
                    onClick = { naming = "New folder" to null },
                    enabled = busy == null && tree != null,
                ) { Text("New folder") }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { pickFiles().takeIf { it.isNotEmpty() }?.let(::uploadAll) },
                    enabled = busy == null && tree != null,
                ) { Text("Upload") }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { reloads++ }, enabled = busy == null && !loading) {
                    Icon(RampartIcons.Refresh, contentDescription = "Read the files again", modifier = Modifier.size(16.dp))
                }
            }
            IconButton(onClick = { FilesPage.open = false }) {
                Icon(RampartIcons.Close, contentDescription = "Close Files", modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            unavailable ?: "Kept on the server, so the same files are on every device that signs in to it. " +
                "Drop files on the list to upload them into the open folder.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        fault?.let {
            Spacer(Modifier.height(10.dp))
            FaultText(it.title, it.detail)
        }
        val status = busy ?: note
        if (status != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy != null) {
                    Spinner(size = 16.dp, thickness = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Spacer(Modifier.height(12.dp))

        val shown = tree
        when {
            unavailable != null || store == null -> Unit
            shown == null && loading -> Spinner()
            shown == null -> Unit
            else -> Row(Modifier.fillMaxSize()) {
                FolderColumn(shown, here, onPick = { here = it }, modifier = Modifier.width(220.dp).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (dropHover) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            } else {
                                Modifier
                            },
                        )
                        .dragAndDropTarget(
                            shouldStartDragAndDrop = { event -> carriesFiles(event) },
                            target = drop,
                        ),
                ) {
                    PathLine(shown, here, onPick = { here = it })
                    Spacer(Modifier.height(6.dp))
                    HeaderLine()
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val inside = shown.childrenOf(here)
                    if (inside.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
                            Text(
                                if (here == null) "No files yet. Upload some, or drop them here." else "This folder is empty.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(inside, key = { it.id }) { node ->
                            NodeRow(
                                node = node,
                                enabled = busy == null,
                                onEnter = { here = node.id },
                                onOpen = { openNode(node) },
                                onDownload = { downloadNode(node) },
                                onRename = { naming = "Rename" to node },
                                onMove = { moving = node },
                                onDelete = { deleting = node },
                            )
                        }
                    }
                }
            }
        }
    }

    naming?.let { (title, node) ->
        NameDialog(
            title = title,
            initial = node?.name.orEmpty(),
            confirm = if (node == null) "Create" else "Rename",
            onClose = { naming = null },
            onDone = { name ->
                naming = null
                val files = store
                val parent = here
                if (files != null) {
                    if (node == null) {
                        change("Creating $name", "The folder $name could not be created.") {
                            files.createFolder(name, parent)
                            null
                        }
                    } else {
                        change("Renaming ${node.name}", "${node.name} could not be renamed.") {
                            files.rename(node, name)
                            null
                        }
                    }
                }
            },
        )
    }

    val movingNode = moving
    val movingTree = tree
    if (movingNode != null && movingTree != null) {
        FolderPickerDialog(
            title = "Move ${movingNode.name}",
            folders = movingTree.moveTargets(movingNode),
            offerTop = movingNode.parentId != null,
            confirm = "Move here",
            onClose = { moving = null },
            onPick = { target ->
                moving = null
                val files = store
                if (files != null) {
                    change("Moving ${movingNode.name}", "${movingNode.name} could not be moved.") {
                        files.move(movingNode, target)
                        null
                    }
                }
            },
        )
    }

    val deletingNode = deleting
    val deletingTree = tree
    if (deletingNode != null && deletingTree != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (deletingNode.isFolder) "Delete folder" else "Delete file") },
            text = { Text(deleteQuestion(deletingNode, deletingTree)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    val files = store
                    if (files != null) {
                        change("Deleting ${deletingNode.name}", "${deletingNode.name} could not be deleted.") {
                            files.delete(deletingNode)
                            null
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

/** The folders down the left, starting from the top level. */
@Composable
private fun FolderColumn(tree: FileTree, here: String?, onPick: (String?) -> Unit, modifier: Modifier) {
    val outline = remember(tree) { tree.folderOutline() }
    LazyColumn(modifier.padding(end = 8.dp)) {
        item(key = "top") { FolderLine("All files", 0, here == null) { onPick(null) } }
        items(outline, key = { it.first.id }) { (folder, depth) ->
            FolderLine(folder.name, depth + 1, here == folder.id) { onPick(folder.id) }
        }
    }
}

@Composable
internal fun FolderLine(name: String, depth: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = (8 + 12 * depth.coerceAtMost(8)).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            RampartIcons.Folder,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Where the list is, each step clickable back up. */
@Composable
private fun PathLine(tree: FileTree, here: String?, onPick: (String?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onPick(null) }) { Text("All files", maxLines = 1) }
        tree.pathTo(here).forEach { step ->
            Text("/", color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = { onPick(step.id) }) {
                Text(step.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HeaderLine() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp)) {
        val style = MaterialTheme.typography.labelSmall
        val colour = MaterialTheme.colorScheme.outline
        Text("Name", style = style, color = colour, modifier = Modifier.weight(1f).padding(start = 28.dp))
        Text("Size", style = style, color = colour, modifier = Modifier.width(90.dp))
        Text("Modified", style = style, color = colour, modifier = Modifier.width(150.dp))
        Spacer(Modifier.width(112.dp))
    }
}

@Composable
private fun NodeRow(
    node: FileNode,
    enabled: Boolean,
    onEnter: () -> Unit,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(6.dp)).rowHover()
            .then(if (node.isFolder) Modifier.clickable(onClick = onEnter) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.isFolder) {
            Icon(
                RampartIcons.Folder,
                contentDescription = "Folder",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(end = 12.dp).size(16.dp),
            )
        } else {
            Icon(
                FilesGlyph,
                contentDescription = "File",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(end = 12.dp).size(16.dp),
            )
        }
        Text(
            node.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            sizeLabel(node),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(90.dp),
        )
        Text(
            if (node.modified.isBlank()) "" else modifiedLabel(node.modified),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            modifier = Modifier.width(150.dp),
        )
        Box(Modifier.width(72.dp)) {
            if (!node.isFolder) {
                TextButton(onClick = onOpen, enabled = enabled) { Text("Open", maxLines = 1) }
            }
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.size(40.dp)) {
                Icon(RampartIcons.More, contentDescription = "More for ${node.name}", modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (node.isFolder) {
                    DropdownMenuItem(text = { Text("Open folder") }, onClick = { menu = false; onEnter() })
                } else {
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; onDownload() })
                }
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Move") }, onClick = { menu = false; onMove() })
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

/** New folder and Rename. The name is checked as it is typed, the way the server will check it. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onClose: () -> Unit,
    onDone: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val problem = nameProblem(name)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (problem != null && name.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDone(name.trim()) },
                enabled = problem == null && name.trim() != initial,
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/**
 * Picks a folder: where Move puts something, and where Save to Files puts an attachment.
 * [offerTop] adds the top level as a choice.
 */
@Composable
private fun FolderPickerDialog(
    title: String,
    folders: List<Pair<FileNode, Int>>,
    offerTop: Boolean,
    confirm: String,
    onClose: () -> Unit,
    onPick: (String?) -> Unit,
) {
    // The top level is its own choice, so "nothing picked yet" needs a value of its own.
    var picked by remember { mutableStateOf<Pair<Boolean, String?>>(false to null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            if (!offerTop && folders.isEmpty()) {
                Text(
                    "There is no other folder to put it in. Make one with New folder first.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // A scrolled column rather than a lazy list: a dialog measures its content in
                // ways a lazy list refuses, and a folder list is short enough not to need one.
                Column(Modifier.heightIn(max = 360.dp).width(360.dp).verticalScroll(rememberScrollState())) {
                    if (offerTop) {
                        FolderLine("All files (top level)", 0, picked == (true to null)) { picked = true to null }
                    }
                    folders.forEach { (folder, depth) ->
                        FolderLine(folder.name, depth + 1, picked == (true to folder.id)) { picked = true to folder.id }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(picked.second) }, enabled = picked.first) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

// ---- the reader: Save to Files -----------------------------------------------------

/**
 * Save to Files, beside an attachment in the reader.
 *
 * Asks which folder, then copies the attachment on the server. Nothing is drawn when
 * [store] is null, which is every account without file storage.
 */
@Composable
internal fun SaveToFilesButton(attachment: Attachment, store: FileStore?) {
    if (store == null) return
    val scope = rememberCoroutineScope()
    var tree by remember { mutableStateOf<FileTree?>(null) }
    var state by remember(attachment.blobId) { mutableStateOf("") }
    var fault by remember { mutableStateOf<Fault?>(null) }
    TextButton(
        onClick = {
            state = "Reading folders"
            scope.launch {
                try {
                    tree = withContext(Dispatchers.IO) { store.tree() }
                    state = ""
                } catch (e: Exception) {
                    state = ""
                    fault = faultOf("Your files could not be read, so nothing was saved.", e)
                }
            }
        },
        enabled = state.isEmpty(),
    ) { Text(state.ifEmpty { "Save to Files" }, maxLines = 1) }

    tree?.let { folders ->
        FolderPickerDialog(
            title = "Save ${safeFileName(attachment.name)} to Files",
            folders = folders.folderOutline(),
            offerTop = true,
            confirm = "Save here",
            onClose = { tree = null },
            onPick = { target ->
                tree = null
                state = "Saving"
                scope.launch {
                    state = try {
                        withContext(Dispatchers.IO) { store.saveAttachment(attachment, target) }
                        "In Files"
                    } catch (e: Exception) {
                        fault = faultOf("${safeFileName(attachment.name)} could not be saved to Files.", e)
                        ""
                    }
                }
            },
        )
    }
    fault?.let { shown ->
        AlertDialog(
            onDismissRequest = { fault = null },
            title = { Text("Save to Files") },
            text = { FaultText(shown.title, shown.detail) },
            confirmButton = { TextButton(onClick = { fault = null }) { Text("OK") } },
        )
    }
}

// ---- the composer: From Files ------------------------------------------------------

/**
 * From Files, beside Attach in the composer.
 *
 * What it hands back points at the file's own blob, so nothing is downloaded or uploaded
 * again: the server copies it into the message when it is saved or sent.
 */
@Composable
internal fun AttachFromFilesButton(store: FileStore?, enabled: Boolean, onAttach: (List<Attachment>) -> Unit) {
    if (store == null) return
    val scope = rememberCoroutineScope()
    var tree by remember { mutableStateOf<FileTree?>(null) }
    var reading by remember { mutableStateOf(false) }
    var fault by remember { mutableStateOf<Fault?>(null) }
    TextButton(
        onClick = {
            reading = true
            scope.launch {
                try {
                    tree = withContext(Dispatchers.IO) { store.tree() }
                } catch (e: Exception) {
                    fault = faultOf("Your files could not be read.", e)
                } finally {
                    reading = false
                }
            }
        },
        enabled = enabled && !reading,
    ) { Text(if (reading) "Reading files" else "From Files", maxLines = 1) }

    tree?.let { all ->
        FilePickerDialog(
            tree = all,
            onClose = { tree = null },
            onPick = { chosen ->
                tree = null
                try {
                    onAttach(chosen.map(::asAttachment))
                } catch (e: Exception) {
                    fault = faultOf("Those files could not be attached.", e)
                }
            },
        )
    }
    fault?.let { shown ->
        AlertDialog(
            onDismissRequest = { fault = null },
            title = { Text("From Files") },
            text = { FaultText(shown.title, shown.detail) },
            confirmButton = { TextButton(onClick = { fault = null }) { Text("OK") } },
        )
    }
}

/** Every file in the account, with the folder it is in, to tick the ones to attach. */
@Composable
private fun FilePickerDialog(tree: FileTree, onClose: () -> Unit, onPick: (List<FileNode>) -> Unit) {
    val files = remember(tree) {
        tree.nodes.filter { !it.isFolder }
            .map { node -> node to tree.pathTo(node.parentId).joinToString(" / ") { it.name } }
            .sortedWith(
                compareBy<Pair<FileNode, String>, String>(String.CASE_INSENSITIVE_ORDER) { it.second }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.name },
            )
    }
    var ticked by remember { mutableStateOf<Set<String>>(emptySet()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Attach from Files") },
        text = {
            if (files.isEmpty()) {
                Text("There are no files in this account yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(Modifier.heightIn(max = 420.dp).width(460.dp).verticalScroll(rememberScrollState())) {
                    files.forEach { (node, folder) ->
                        val on = node.id in ticked
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .clickable { ticked = if (on) ticked - node.id else ticked + node.id }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = { ticked = if (it) ticked + node.id else ticked - node.id })
                            Column(Modifier.weight(1f)) {
                                Text(node.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(folder.ifEmpty { "All files" }, humanSize(node.size)).joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(files.map { it.first }.filter { it.id in ticked }) },
                enabled = ticked.isNotEmpty(),
            ) { Text(if (ticked.size > 1) "Attach ${ticked.size}" else "Attach") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

// ---- the desktop ---------------------------------------------------------------------

/** Where Download puts a file. The system's own folder chooser; null when it was cancelled. */
private fun pickFolder(): Path? {
    val chooser = JFileChooser(downloadsFolder().toFile()).apply {
        dialogTitle = "Download to"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showDialog(null, "Download here") == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()
    } else {
        null
    }
}

/** Whether a drag coming over the list is carrying files from the desktop. */
@OptIn(ExperimentalComposeUiApi::class)
private fun carriesFiles(event: DragAndDropEvent): Boolean =
    runCatching { event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }.getOrDefault(false)

/** The files a drop carried. Empty when it carried something else. */
@OptIn(ExperimentalComposeUiApi::class)
private fun droppedFiles(event: DragAndDropEvent): List<File> = runCatching {
    (event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
        .orEmpty()
        .filterIsInstance<File>()
}.getOrDefault(emptyList())
