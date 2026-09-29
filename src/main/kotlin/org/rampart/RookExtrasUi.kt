package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files

/*
 * The controls for RookExtras.kt and SavedPrompts.kt: the Saved prompts menu, the button
 * that attaches a file from Files as context, the Match my tone switch in Settings, and
 * the row that puts the first two in the Rook panel.
 *
 * None of these calls a model. They choose what goes with the next call, which is still a
 * button the person presses, and which shows its packet the first time it carries a sample
 * or a file under a new agreement.
 */

/** The shelf for a session: its own Files when it has them, this computer otherwise. */
internal fun promptShelfOf(backend: MailBackend?, account: String): PromptShelf =
    promptShelfFor((backend as? Jmap)?.filesTransport(), account)

/**
 * "Saved prompts": pick one to put its words where [onPick] says, save what is typed now
 * under a name, or delete one. The list is read when the menu opens, so a prompt saved on
 * another computer is there without a restart.
 */
@Composable
internal fun SavedPromptsMenu(
    shelf: PromptShelf,
    /** What is typed now, offered as the text of a new saved prompt. */
    current: () -> String,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var prompts by remember(shelf) { mutableStateOf<List<SavedPrompt>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var fault by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var naming by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }

    fun load() {
        busy = true
        fault = null
        scope.launch {
            try {
                prompts = withContext(Dispatchers.IO) { shelf.load() }
            } catch (e: Exception) {
                val title = "Your saved prompts could not be read."
                fault = title to faultDetail(e, title)
            } finally {
                busy = false
            }
        }
    }

    fun store(next: List<SavedPrompt>) {
        busy = true
        fault = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { shelf.save(next) }
                prompts = next
            } catch (e: Exception) {
                val title = "Your saved prompts could not be saved."
                fault = title to faultDetail(e, title)
            } finally {
                busy = false
            }
        }
    }

    Box {
        TextButton(onClick = { open = true; load() }, enabled = enabled) { Text("Saved prompts", maxLines = 1) }
        MenuLayer(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.widthIn(min = 260.dp, max = 380.dp).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(shelf.place.words, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.size(6.dp))
                val list = prompts
                when {
                    list == null && busy -> Spinner(size = 18.dp, thickness = 2.dp)
                    list.isNullOrEmpty() && fault == null -> Text(
                        "None yet. Type an instruction, then save it here to use it again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    list.orEmpty().forEach { prompt ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .clickable { open = false; onPick(prompt.text) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(prompt.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    prompt.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { store(SavedPrompts.remove(list.orEmpty(), prompt.name)) },
                                enabled = !busy,
                                modifier = Modifier.size(26.dp),
                            ) {
                                Icon(RampartIcons.Close, contentDescription = "Delete ${prompt.name}", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
                fault?.let { (title, detail) -> FaultText(title, detail, Modifier.padding(top = 4.dp)) }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val typed = current().trim()
                TextButton(
                    onClick = { naming = typed; name = "" },
                    enabled = typed.isNotEmpty() && !busy && list != null,
                ) { Text(if (typed.isEmpty()) "Type an instruction to save it" else "Save what is typed as a prompt") }
            }
        }
    }

    naming?.let { text ->
        val problem = SavedPrompts.problem(name, text, prompts.orEmpty())
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text("Save this prompt") },
            text = {
                Column {
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(name, { name = it }, label = { Text("Name, like Decline politely") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (name.isNotBlank() && problem != null) {
                        Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store(SavedPrompts.upsert(prompts.orEmpty(), SavedPrompt(name, text)))
                        naming = null
                    },
                    enabled = problem == null,
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
        )
    }
}

/**
 * "Add a file": one document from the account's Files, read on this machine and capped, as
 * context for the next call. Only text-like files and PDFs can be picked; the rest are
 * listed greyed with the reason, so a missing file is explained rather than absent.
 */
@Composable
internal fun AttachFileForRook(
    backend: MailBackend?,
    attached: AttachedFile?,
    enabled: Boolean,
    onChange: (AttachedFile?) -> Unit,
) {
    val store = remember(backend) { filesOf(backend) }
    if (store == null) return
    val scope = rememberCoroutineScope()
    var tree by remember { mutableStateOf<FileTree?>(null) }
    var reading by remember { mutableStateOf(false) }
    var fault by remember { mutableStateOf<Pair<String, String?>?>(null) }

    fun read(node: FileNode) {
        reading = true
        fault = null
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val kind = FileContext.kindOf(node.name, node.type)
                        ?: throw FileContextError("Only text files and PDFs can be sent to Rook.")
                    if (node.size > FileContext.MAX_BYTES) throw FileContextError("${node.name} is too large to read as context.")
                    val dir = Files.createTempDirectory("rampart-context")
                    try {
                        val local = store.download(node, dir)
                        val (text, cut) = FileContext.capped(FileContext.textOf(Files.readAllBytes(local), kind))
                        if (text.isBlank()) throw FileContextError("${node.name} has no text Rampart can read.")
                        AttachedFile(node.name, text, cut)
                    } finally {
                        runCatching { dir.toFile().deleteRecursively() }
                    }
                }
                onChange(file)
            } catch (e: FileContextError) {
                fault = (e.message ?: "That file could not be read.") to null
            } catch (e: Exception) {
                val title = "That file could not be read."
                fault = title to faultDetail(e, title)
            } finally {
                reading = false
            }
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (attached == null) {
                TextButton(
                    onClick = {
                        reading = true
                        fault = null
                        scope.launch {
                            try {
                                tree = withContext(Dispatchers.IO) { store.tree() }
                            } catch (e: Exception) {
                                val title = "Your files could not be read."
                                fault = title to faultDetail(e, title)
                            } finally {
                                reading = false
                            }
                        }
                    },
                    enabled = enabled && !reading,
                ) { Text(if (reading) "Reading the file" else "Add a file", maxLines = 1) }
            } else {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Context: ${attached.label}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 260.dp),
                    )
                    IconButton(onClick = { onChange(null) }, modifier = Modifier.size(26.dp)) {
                        Icon(RampartIcons.Close, contentDescription = "Take the file off", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
        fault?.let { (title, detail) -> FaultText(title, detail) }
    }

    tree?.let { all -> ContextFileDialog(all, onClose = { tree = null }) { node -> tree = null; read(node) } }
}

/** Every file in the account with the folder it is in, the readable ones pickable. */
@Composable
private fun ContextFileDialog(tree: FileTree, onClose: () -> Unit, onPick: (FileNode) -> Unit) {
    val files = remember(tree) {
        tree.nodes.filter { !it.isFolder }
            .map { node -> node to tree.pathTo(node.parentId).joinToString(" / ") { it.name } }
            .sortedWith(
                compareBy<Pair<FileNode, String>> { FileContext.kindOf(it.first.name, it.first.type) == null }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.second }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.name },
            )
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("A file for Rook to read") },
        text = {
            Column(Modifier.width(460.dp)) {
                Text(
                    "Its text goes with your next request, marked as a document and not as instructions: " +
                        "the first ${"%,d".format(java.util.Locale.ENGLISH, FileContext.CAP)} characters of a text file or a PDF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.size(8.dp))
                if (files.isEmpty()) {
                    Text("There are no files in this account yet.", style = MaterialTheme.typography.bodyMedium)
                }
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    files.forEach { (node, folder) ->
                        val readable = FileContext.kindOf(node.name, node.type) != null
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = readable) { onPick(node) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Text(
                                node.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (readable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (readable) {
                                    listOf(folder.ifEmpty { "All files" }, humanSize(node.size)).joinToString(", ")
                                } else {
                                    "Not a text file or a PDF, so Rook cannot read it."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/**
 * Settings, Rook: the Match my tone switch.
 *
 * Says exactly what it sends, and that the first request carrying it shows the packet
 * again, because turning it on is agreeing to more of the person's mail leaving.
 */
@Composable
internal fun ToneMatchingSetting() {
    var on by remember { mutableStateOf(ToneSetting.on()) }
    Section(
        "Match my tone",
        "Help me write and suggested replies can sound like you. With this on, each of those " +
            "requests also carries a short sample of your own writing: your last five sent " +
            "messages, without signatures or quoted mail, cut to 1,500 characters in all. " +
            "The first request that carries it shows you the exact packet before it goes. " +
            "A Sent folder on the never-leave list is never sampled.",
    )
    Row(
        Modifier.fillMaxWidth().clickable { on = !on; ToneSetting.set(on) }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = on, onCheckedChange = { on = it; ToneSetting.set(it) })
        Spacer(Modifier.width(10.dp))
        Text("Send a sample of my writing with Help me write and suggested replies", style = MaterialTheme.typography.bodyMedium)
    }
}

/** The file attached in the Rook panel, kept for the conversation until it is taken off. */
internal object RookAttachment {
    var file by mutableStateOf<AttachedFile?>(null)
}

/**
 * The row above the Rook panel's input: Saved prompts and Add a file. Picking a prompt puts
 * its words in the box through [onInsert]; nothing is asked until the person presses Ask.
 */
@Composable
internal fun RookPanelExtras(backend: MailBackend?, account: String?, typed: () -> String, enabled: Boolean, onInsert: (String) -> Unit) {
    val shelf = remember(backend, account) { account?.let { promptShelfOf(backend, it) } }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (shelf != null) SavedPromptsMenu(shelf, typed, enabled, onInsert)
        AttachFileForRook(backend, RookAttachment.file, enabled) { RookAttachment.file = it }
    }
}
