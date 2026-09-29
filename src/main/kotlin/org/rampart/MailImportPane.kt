package org.rampart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import javax.swing.JFileChooser

internal fun pickImportSources(): List<Path> {
    val chooser = JFileChooser(downloadsFolder().toFile()).apply {
        dialogTitle = "Choose EML files, mbox files, or a Maildir"
        fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
        isMultiSelectionEnabled = true
        isAcceptAllFileFilterUsed = true
    }
    if (chooser.showDialog(null, "Import") != JFileChooser.APPROVE_OPTION) return emptyList()
    return chooser.selectedFiles.map { it.toPath() }.ifEmpty { listOfNotNull(chooser.selectedFile?.toPath()) }
}

private fun importSummary(result: ImportResult): String = buildString {
    append("Imported ${result.imported} message")
    if (result.imported != 1) append('s')
    append(". Skipped ${result.skippedDuplicates} duplicate")
    if (result.skippedDuplicates != 1) append('s')
    append(" and ${result.skippedOversize} oversized message")
    if (result.skippedOversize != 1) append('s')
    if (result.failed > 0) append(". ${result.failed} failed")
    append('.')
}

@Composable
internal fun ImportMailSection(account: AccountMailboxes?, backend: MailBackend?) {
    var sources by remember { mutableStateOf(emptyList<Path>()) }
    var mailbox by remember(account?.key) { mutableStateOf(account?.mailboxes?.firstOrNull { it.role == "inbox" } ?: account?.mailboxes?.firstOrNull()) }
    var folderMenu by remember { mutableStateOf(false) }
    var recreate by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var cancelled by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var fraction by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val running = job?.isActive == true

    Section("Import mail", "Bring EML files, Maildir trees, or mbox archives into this account.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { sources = pickImportSources() }, enabled = !running) { Text("Choose source") }
        Text(if (sources.isEmpty()) "No source selected" else "${sources.size} source${if (sources.size == 1) "" else "s"} selected")
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Destination folder", fontWeight = FontWeight.SemiBold)
        Column {
            OutlinedButton(onClick = { folderMenu = true }, enabled = !running && account != null) {
                Text(mailbox?.name ?: "Choose folder")
            }
            DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                account?.mailboxes?.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.name) }, onClick = { mailbox = choice; folderMenu = false })
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = recreate, onCheckedChange = { recreate = it }, enabled = !running)
        Text("Recreate source folders under the destination")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                val target = mailbox ?: return@Button
                val active = backend ?: return@Button
                cancelled = false
                status = null
                progress = "Starting import..."
                val progressRoot = sources.first().let { if (it.toFile().isDirectory) it else it.parent }
                job = scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            importMail(active, sources, target, recreate, progressRoot, { folder, current, total ->
                                progress = formatImportProgress(folder, current, total)
                                fraction = if (total == 0) 0f else current.toFloat() / total
                            }, { cancelled })
                        }
                        status = if (cancelled) "Import cancelled. Progress was saved." else importSummary(result)
                    } catch (e: Exception) {
                        status = "Import failed: ${e.message ?: e.toString()}"
                    } finally {
                        job = null
                    }
                }
            },
            enabled = !running && sources.isNotEmpty() && mailbox != null && backend != null,
        ) { Text("Import") }
        if (running) OutlinedButton(onClick = { cancelled = true }) { Text("Cancel") }
    }
    progress?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
    status?.let { Text(it, color = if (it.startsWith("Import failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline) }
    Spacer(Modifier.height(22.dp))
}

@Composable
internal fun ImportFolderDialog(mailbox: Mailbox, backend: MailBackend, onClose: () -> Unit) {
    var sources by remember { mutableStateOf(emptyList<Path>()) }
    var job by remember { mutableStateOf<Job?>(null) }
    var cancelled by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val running = job?.isActive == true
    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        title = { Text("Import into ${mailbox.name}") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Import EML files, a Maildir tree, or an mbox archive into this folder.")
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { sources = pickImportSources() }, enabled = !running) { Text("Choose source") }
                if (sources.isNotEmpty()) Text("${sources.size} source${if (sources.size == 1) "" else "s"} selected")
                progress?.let { Spacer(Modifier.height(10.dp)); Text(it, fontWeight = FontWeight.Medium) }
                status?.let { Spacer(Modifier.height(8.dp)); Text(it) }
            }
        },
        confirmButton = {
            if (running) OutlinedButton(onClick = { cancelled = true }) { Text("Cancel") }
            else Button(onClick = {
                cancelled = false
                val root = sources.first().let { if (it.toFile().isDirectory) it else it.parent }
                job = scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            importMail(backend, sources, mailbox, false, root, { folder, current, total ->
                                progress = formatImportProgress(folder, current, total)
                            }, { cancelled })
                        }
                        status = if (cancelled) "Import cancelled. Progress was saved." else importSummary(result)
                    } catch (e: Exception) {
                        status = "Import failed: ${e.message ?: e.toString()}"
                    } finally {
                        job = null
                    }
                }
            }, enabled = sources.isNotEmpty()) { Text("Import") }
        },
        dismissButton = { if (!running) TextButton(onClick = onClose) { Text("Close") } },
    )
}
