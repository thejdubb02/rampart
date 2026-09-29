package org.rampart

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A folder job waiting on an answer.
 *
 * [mailbox] is null only for a new top level folder, which is the one job with nothing to
 * act on. Everything else names the folder it was invoked from.
 */
internal data class FolderAsk(val account: String, val mailbox: Mailbox?, val job: FolderJob)

/** What a right-click on a folder asked for. Answered by whoever owns the sidebar. */
internal enum class FolderJob { CreateInside, Rename, ToTop, Delete, Export, Import, Share }

/** A saved search job waiting on an answer. */
internal data class SavedSearchAsk(val search: SavedSearch, val job: SavedSearchJob)

/** What a right-click or save action on a saved search asked for. */
internal enum class SavedSearchJob { SaveCurrent, Rename, EditQuery, Delete }

/**
 * Asks for whatever a saved search job needs before it runs.
 *
 * Creates, renames, edits query, or deletes a saved search folder.
 */
@Composable
internal fun SavedSearchDialog(
    ask: SavedSearchAsk,
    onClose: () -> Unit,
    onConfirm: (name: String, query: String) -> Unit,
) {
    var name by remember(ask) { mutableStateOf(if (ask.job == SavedSearchJob.SaveCurrent) "" else ask.search.name) }
    var queryText by remember(ask) { mutableStateOf(ask.search.query) }
    var busy by remember(ask) { mutableStateOf(false) }
    val title = when (ask.job) {
        SavedSearchJob.SaveCurrent -> "Save as folder"
        SavedSearchJob.Rename -> "Rename ${ask.search.name}"
        SavedSearchJob.EditQuery -> "Edit query for ${ask.search.name}"
        SavedSearchJob.Delete -> "Delete ${ask.search.name}"
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(title) },
        text = {
            Column {
                when (ask.job) {
                    SavedSearchJob.SaveCurrent, SavedSearchJob.Rename -> {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            enabled = !busy,
                            singleLine = true,
                            label = { Text("Name") },
                        )
                    }
                    SavedSearchJob.EditQuery -> {
                        OutlinedTextField(
                            value = queryText,
                            onValueChange = { queryText = it },
                            enabled = !busy,
                            singleLine = true,
                            label = { Text("Query") },
                        )
                    }
                    SavedSearchJob.Delete -> {
                        Text("Delete saved search '${ask.search.name}'? This removes only the saved search, never mail.")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    onConfirm(name.trim(), queryText)
                },
                enabled = !busy && when (ask.job) {
                    SavedSearchJob.SaveCurrent, SavedSearchJob.Rename -> name.isNotBlank()
                    SavedSearchJob.EditQuery -> true
                    SavedSearchJob.Delete -> true
                },
            ) {
                Text(if (ask.job == SavedSearchJob.Delete) "Delete" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !busy) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Asks for whatever a folder job needs before it runs.
 *
 * Two shapes rather than two dialogs: the naming jobs want a name, and the rest want a yes.
 * A delete says what is about to go rather than asking "are you sure", which is a question
 * nobody reads.
 */
@Composable
internal fun FolderDialog(
    ask: FolderAsk,
    error: String?,
    onClose: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val needsName = ask.job == FolderJob.CreateInside || ask.job == FolderJob.Rename
    var name by remember(ask) { mutableStateOf(if (ask.job == FolderJob.Rename) ask.mailbox?.name.orEmpty() else "") }
    var busy by remember(ask) { mutableStateOf(false) }
    val title = when (ask.job) {
        FolderJob.CreateInside -> ask.mailbox?.let { "New folder inside ${it.name}" } ?: "New folder"
        FolderJob.Rename -> "Rename ${ask.mailbox?.name.orEmpty()}"
        FolderJob.ToTop -> "Move ${ask.mailbox?.name.orEmpty()} to the top level"
        FolderJob.Delete -> "Delete ${ask.mailbox?.name.orEmpty()}"
        FolderJob.Export -> "Export ${ask.mailbox?.name.orEmpty()}"
        FolderJob.Import -> "Import into ${ask.mailbox?.name.orEmpty()}"
        FolderJob.Share -> "Share ${ask.mailbox?.name.orEmpty()}"
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(title) },
        text = {
            Column {
                when {
                    needsName -> OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        enabled = !busy,
                        singleLine = true,
                        label = { Text("Name") },
                    )
                    ask.job == FolderJob.Delete -> Text(
                        // The count is what makes this a decision rather than a reflex.
                        if ((ask.mailbox?.unread ?: 0) > 0) {
                            "It has ${ask.mailbox?.unread} unread. The server will refuse if " +
                                "there is anything in it, and nothing is deleted if it does."
                        } else {
                            "The server will refuse if there is anything in it, and nothing " +
                                "is deleted if it does."
                        },
                    )
                    else -> Text("It will sit alongside your other top level folders.")
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { busy = true; onConfirm(name.trim()) },
                enabled = !busy && (!needsName || name.isNotBlank()),
            ) { Text(if (ask.job == FolderJob.Delete) "Delete" else "Save") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") } },
    )
}
