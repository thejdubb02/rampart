package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import javax.swing.JFileChooser

/** Picks an export destination folder via the system directory chooser. */
internal fun pickExportDirectory(currentPath: Path? = null): Path? {
    val initial = currentPath?.toFile() ?: downloadsFolder().toFile()
    val chooser = JFileChooser(initial).apply {
        dialogTitle = "Choose export destination folder"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showDialog(null, "Select") == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()
    } else {
        null
    }
}

/**
 * Settings page for exporting mail to Maildir.
 */
@Composable
internal fun ExportMailPage(
    accounts: List<AccountMailboxes>,
    chosenAccountKey: String?,
    onChooseAccount: (String) -> Unit,
    backendFor: (String) -> MailBackend?,
) {
    val scope = rememberCoroutineScope()
    var targetPath by remember { mutableStateOf<Path?>(downloadsFolder().resolve("MailExport")) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var progressFraction by remember { mutableStateOf<Float?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isCancelled by remember { mutableStateOf(false) }

    val activeAccount = accounts.firstOrNull { it.key == chosenAccountKey } ?: accounts.firstOrNull()

    Section(
        "Export mail",
        "Export your mail to a Maildir tree of real RFC 5322 .eml files.",
    )

    if (accounts.size > 1) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Account:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            accounts.forEach { account ->
                val selected = account.key == activeAccount?.key
                OutlinedButton(
                    onClick = { onChooseAccount(account.key) },
                    enabled = exportJob == null,
                ) {
                    Text(
                        shortAccountName(account.name, account.email),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    Text("Destination directory", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = targetPath?.toString().orEmpty(),
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Choose a folder...") },
        )
        OutlinedButton(
            onClick = {
                val picked = pickExportDirectory(targetPath)
                if (picked != null) {
                    targetPath = picked
                }
            },
            enabled = exportJob == null,
        ) {
            Text("Choose folder")
        }
    }

    Spacer(Modifier.height(16.dp))

    val isRunning = exportJob?.isActive == true

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                val destination = targetPath ?: return@Button
                val acc = activeAccount ?: return@Button
                val backend = backendFor(acc.key) ?: return@Button
                isCancelled = false
                statusMessage = null
                progressText = "Starting export..."
                progressFraction = 0f

                exportJob = scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            exportAccount(
                                backend = backend,
                                mailboxes = acc.mailboxes,
                                exportRoot = destination,
                                onProgress = { folderName, current, total ->
                                    progressText = formatExportProgress(folderName, current, total)
                                    progressFraction = if (total > 0) current.toFloat() / total.toFloat() else 0f
                                },
                                isCancelled = { isCancelled },
                            )
                        }
                        if (isCancelled) {
                            statusMessage = "Export cancelled."
                        } else {
                            statusMessage = "Export complete."
                        }
                    } catch (e: Exception) {
                        statusMessage = "Export failed: ${e.message ?: e.toString()}"
                    } finally {
                        exportJob = null
                    }
                }
            },
            enabled = !isRunning && targetPath != null && activeAccount != null,
        ) {
            Text("Export account")
        }

        if (isRunning) {
            OutlinedButton(
                onClick = {
                    isCancelled = true
                    exportJob?.cancel()
                    statusMessage = "Cancelling export..."
                },
            ) {
                Text("Cancel")
            }
        }
    }

    if (isRunning || progressText != null) {
        Spacer(Modifier.height(16.dp))
        progressText?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
        }
        progressFraction?.let { frac ->
            LinearProgressIndicator(
                progress = { frac.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    statusMessage?.let { msg ->
        Spacer(Modifier.height(12.dp))
        Text(
            msg,
            style = MaterialTheme.typography.bodyMedium,
            color = if (msg.startsWith("Export failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Modal dialog for exporting a single folder to Maildir.
 */
@Composable
internal fun ExportFolderDialog(
    accountName: String,
    mailbox: Mailbox,
    allMailboxes: List<Mailbox>,
    backend: MailBackend,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var targetPath by remember { mutableStateOf<Path?>(downloadsFolder().resolve("MailExport-${mailbox.name}")) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var progressFraction by remember { mutableStateOf<Float?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isCancelled by remember { mutableStateOf(false) }

    val isRunning = exportJob?.isActive == true

    AlertDialog(
        onDismissRequest = {
            if (!isRunning) {
                onClose()
            }
        },
        title = { Text("Export ${mailbox.name}") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Export ${mailbox.name} to a Maildir directory with raw RFC 5322 .eml files.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))

                Text("Destination folder", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = targetPath?.toString().orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = {
                            val picked = pickExportDirectory(targetPath)
                            if (picked != null) {
                                targetPath = picked
                            }
                        },
                        enabled = !isRunning,
                    ) {
                        Text("Choose")
                    }
                }

                if (isRunning || progressText != null) {
                    Spacer(Modifier.height(14.dp))
                    progressText?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                    }
                    progressFraction?.let { frac ->
                        LinearProgressIndicator(
                            progress = { frac.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                statusMessage?.let { msg ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (msg.startsWith("Export failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            if (isRunning) {
                OutlinedButton(
                    onClick = {
                        isCancelled = true
                        exportJob?.cancel()
                        statusMessage = "Cancelling export..."
                    },
                ) {
                    Text("Cancel")
                }
            } else {
                Button(
                    onClick = {
                        val destination = targetPath ?: return@Button
                        isCancelled = false
                        statusMessage = null
                        progressText = "Starting export..."
                        progressFraction = 0f

                        exportJob = scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    exportMailbox(
                                        backend = backend,
                                        mailbox = mailbox,
                                        allMailboxes = allMailboxes,
                                        exportRoot = destination,
                                        onProgress = { folderName, current, total ->
                                            progressText = formatExportProgress(folderName, current, total)
                                            progressFraction = if (total > 0) current.toFloat() / total.toFloat() else 0f
                                        },
                                        isCancelled = { isCancelled },
                                        singleFolderExport = true,
                                    )
                                }
                                if (isCancelled) {
                                    statusMessage = "Export cancelled."
                                } else {
                                    statusMessage = "Export complete."
                                }
                            } catch (e: Exception) {
                                statusMessage = "Export failed: ${e.message ?: e.toString()}"
                            } finally {
                                exportJob = null
                            }
                        }
                    },
                    enabled = targetPath != null,
                ) {
                    Text("Export")
                }
            }
        },
        dismissButton = {
            if (!isRunning) {
                TextButton(onClick = onClose) {
                    Text("Close")
                }
            }
        },
    )
}
