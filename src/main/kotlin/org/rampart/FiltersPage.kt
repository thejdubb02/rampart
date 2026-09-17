package org.rampart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
 * The filters screen: the rules, and the script underneath them.
 *
 * Two things it will not do, both because a person's filters are load-bearing and a builder
 * that quietly changes one is worse than no builder:
 *
 * - A script nobody's builder wrote is shown as text and never rebuilt from our idea of
 *   what it said.
 * - A rule using something this build does not show is listed, marked, and left alone.
 */
@Composable
internal fun FiltersPage(
    /** Null while the server's script is still being fetched. */
    script: Script?,
    /** Folders to offer for "file into", so nobody types a folder name that does not exist. */
    folders: List<String>,
    saving: Boolean,
    error: String?,
    supported: Boolean,
    onSave: (Script) -> Unit,
) {
    if (!supported) {
        Section("Filters", "This server does not offer Sieve, so rules cannot be kept on it.")
        return
    }
    if (script == null) {
        Section("Filters", "Rules run on the server, so they work with Rampart closed.")
        CircularProgressIndicator(Modifier.height(20.dp))
        return
    }

    var editing by remember(script) { mutableStateOf<Rule?>(null) }
    var raw by remember(script) { mutableStateOf<String?>(null) }
    var rules by remember(script) { mutableStateOf(script.rules) }

    fun save(next: List<Rule>) {
        rules = next
        onSave(script.copy(rules = next))
    }

    Section("Filters", "Rules run on the server, so they work with Rampart closed.")

    if (!script.editable) {
        // Being plain about why, rather than showing an empty list that looks like a bug.
        Text(
            "The filter script on this server was written by hand rather than by a rule " +
                "builder, so Rampart shows it as it is instead of rewriting it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(10.dp))
        RawScript(script.tail)
        return
    }

    rules.forEach { rule ->
        RuleRow(
            rule = rule,
            onToggle = { save(rules.map { if (it.id == rule.id) it.copy(enabled = !it.enabled) else it }) },
            onEdit = { editing = rule },
            onDelete = { save(rules.filterNot { it.id == rule.id }) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    if (rules.isEmpty()) {
        Text(
            "No rules yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                editing = Rule(
                    name = "New rule",
                    tests = listOf(Test(Field.FROM, Match.CONTAINS, "")),
                    acts = listOf(Act.FileInto(folders.firstOrNull().orEmpty())),
                )
            },
            enabled = !saving,
        ) { Text("New rule") }
        TextButton(onClick = { raw = sieveOf(script.copy(rules = rules)) }) { Text("Show the script") }
        if (saving) {
            Spacer(Modifier.width(4.dp))
            CircularProgressIndicator(Modifier.height(18.dp))
        }
    }
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (script.tail.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(
            "There is more in this script that Rampart did not write. It is kept exactly " +
                "as it is and saving a rule here does not touch it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    raw?.let { text ->
        AlertDialog(
            onDismissRequest = { raw = null },
            title = { Text("The script as the server sees it") },
            text = { RawScript(text) },
            confirmButton = { TextButton(onClick = { raw = null }) { Text("Close") } },
        )
    }

    editing?.let { rule ->
        RuleEditor(
            rule = rule,
            folders = folders,
            onClose = { editing = null },
            onDone = { edited ->
                editing = null
                save(
                    if (rules.any { it.id == edited.id }) rules.map { if (it.id == edited.id) edited else it }
                    else rules + edited,
                )
            },
        )
    }
}

@Composable
private fun RawScript(text: String) {
    OutlinedTextField(
        value = text,
        onValueChange = {},
        readOnly = true,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp),
    )
}

/** One rule in the list: what it does, in a sentence, and the switch that turns it off. */
@Composable
private fun RuleRow(rule: Rule, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rule.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                summarise(rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (!rule.understood) {
                Text(
                    "This rule uses something Rampart does not show yet, so it is kept as " +
                        "it is rather than edited here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (rule.understood) {
            TextButton(onClick = onEdit) { Text("Edit") }
        }
        TextButton(onClick = onDelete) { Text("Delete") }
        Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
    }
}

/** A rule as one sentence, which is the only form most people ever need to read. */
internal fun summarise(rule: Rule): String {
    if (rule.tests.isEmpty() || rule.acts.isEmpty()) return "Incomplete."
    val join = if (rule.all) " and " else " or "
    val when_ = rule.tests.joinToString(join) {
        "${it.field.label.lowercase()} ${it.match.label} \"${it.value}\""
    }
    val then = rule.acts.joinToString(", ") {
        when (it) {
            is Act.FileInto -> "file into ${it.folder}"
            is Act.Tag -> "tag ${it.keyword}"
            Act.MarkRead -> "mark read"
            Act.Star -> "star"
            Act.Delete -> "delete"
        }
    }
    return "If $when_, $then."
}

/** Editing one rule. Deliberately one condition and one action: the rest is the raw editor. */
@Composable
private fun RuleEditor(rule: Rule, folders: List<String>, onClose: () -> Unit, onDone: (Rule) -> Unit) {
    var draft by remember(rule) { mutableStateOf(rule) }
    val test = draft.tests.firstOrNull() ?: Test(Field.FROM, Match.CONTAINS, "")
    val act = draft.acts.firstOrNull() ?: Act.FileInto(folders.firstOrNull().orEmpty())

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (rule.name == "New rule") "New rule" else "Edit rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Picker(Field.entries.map { it.label }, test.field.label) { picked ->
                        draft = draft.copy(tests = listOf(test.copy(field = Field.entries.first { it.label == picked })))
                    }
                    Picker(Match.entries.map { it.label }, test.match.label) { picked ->
                        draft = draft.copy(tests = listOf(test.copy(match = Match.entries.first { it.label == picked })))
                    }
                }
                OutlinedTextField(
                    value = test.value,
                    onValueChange = { draft = draft.copy(tests = listOf(test.copy(value = it))) },
                    label = { Text("Value") },
                    singleLine = true,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Then", style = MaterialTheme.typography.bodyMedium)
                    val actions = listOf("File into", "Mark read", "Star", "Delete")
                    val current = when (act) {
                        is Act.FileInto -> "File into"
                        Act.MarkRead -> "Mark read"
                        Act.Star -> "Star"
                        Act.Delete -> "Delete"
                        is Act.Tag -> "File into"
                    }
                    Picker(actions, current) { picked ->
                        draft = draft.copy(
                            acts = listOf(
                                when (picked) {
                                    "Mark read" -> Act.MarkRead
                                    "Star" -> Act.Star
                                    "Delete" -> Act.Delete
                                    else -> Act.FileInto(folders.firstOrNull().orEmpty())
                                },
                            ),
                        )
                    }
                    if (act is Act.FileInto) {
                        Picker(folders, act.folder.ifBlank { folders.firstOrNull().orEmpty() }) { picked ->
                            draft = draft.copy(acts = listOf(Act.FileInto(picked)))
                        }
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = draft.stop, onCheckedChange = { draft = draft.copy(stop = it) })
                    Spacer(Modifier.width(8.dp))
                    Text("Stop after this rule", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDone(draft) },
                enabled = draft.name.isNotBlank() && test.value.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** A small menu that looks like a value rather than a control, which is what it is. */
@Composable
private fun Picker(options: List<String>, current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(
        current.ifBlank { "choose" },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { open = true }.padding(horizontal = 4.dp, vertical = 2.dp),
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(option) },
                onClick = {
                    open = false
                    onPick(option)
                },
            )
        }
    }
}
