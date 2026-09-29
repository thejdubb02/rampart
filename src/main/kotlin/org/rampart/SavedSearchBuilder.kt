package org.rampart

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The saved search builder: conditions that nest, and whether the search splits.
 *
 * Drawn the way the filter rule editor is, with values that look like values and open a
 * menu, because a person who has built a filter has already learned how to read this. A
 * search from before the builder opens as the conditions it always meant, so editing one
 * starts from what it did rather than from nothing.
 *
 * It can only change the saved search. Nothing here moves, tags or deletes mail, and the
 * dialog says so, because "folder" suggests otherwise.
 */
@Composable
internal fun SavedSearchBuilder(
    search: SavedSearch,
    onClose: () -> Unit,
    onSave: (name: String, condition: Condition, split: Split) -> Unit,
) {
    var name by remember(search.id) { mutableStateOf(search.name) }
    var root by remember(search.id) {
        mutableStateOf(
            when (val kept = search.condition) {
                is Condition.Group -> kept
                is Condition.Match -> Condition.Group(Joiner.ALL, listOf(kept))
                null -> legacyCondition(search.query, search.filters)
            },
        )
    }
    var split by remember(search.id) { mutableStateOf(search.split) }
    val problems = problemsIn(root)
    val usable = normalized(root) != null

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit ${search.name}") },
        text = {
            Column(
                Modifier.widthIn(min = 460.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                GroupEditor(root, emptyList(), depth = 1, onChange = { root = it }, whole = root)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Child folders", style = MaterialTheme.typography.bodyMedium)
                    Picker(Split.entries.map { it.label }, split.label) { picked ->
                        split = Split.entries.first { it.label == picked }
                    }
                }
                Text(
                    splitNote(split),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    "Nothing moves on the server. Deleting this search, or any folder under it, deletes no mail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                when {
                    problems.isNotEmpty() -> Text(
                        if (problems.size == 1) "One condition needs fixing before this can be saved."
                        else "${problems.size} conditions need fixing before this can be saved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    !usable -> Text(
                        "Add at least one condition, or this search would be every message.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), root, split) },
                enabled = name.isNotBlank() && problems.isEmpty() && usable,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** What a split does, in one sentence, so the choice is not a guess. */
private fun splitNote(split: Split): String = when (split) {
    Split.NONE -> "One folder with everything that matches."
    Split.SENDER -> "A folder under this one for each sender, which appears when they write and goes when their mail does."
    Split.LIST -> "A folder under this one for each mailing list, by its List-Id. Mail from no list stays only here."
    Split.TAG -> "A folder under this one for each tag. A message with two tags is in both."
}

/**
 * One group: how it joins, what is in it, and the buttons that add to it.
 *
 * Nested groups are drawn inside a faint outline and indented, so which "any of" a
 * condition belongs to can be read at a glance rather than worked out.
 */
@Composable
private fun GroupEditor(
    group: Condition.Group,
    path: List<Int>,
    depth: Int,
    whole: Condition.Group,
    onChange: (Condition.Group) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (path.isEmpty()) "Mail matching" else "Matching", style = MaterialTheme.typography.bodyMedium)
            Picker(Joiner.entries.map { it.label }, group.joiner.label) { picked ->
                val joiner = Joiner.entries.first { it.label == picked }
                onChange(whole.changedAt(path) { (it as Condition.Group).copy(joiner = joiner) })
            }
            Text("these", style = MaterialTheme.typography.bodyMedium)
            if (path.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = { onChange(whole.removedAt(path)) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("Remove group") }
            }
        }
        group.items.forEachIndexed { index, item ->
            val at = path + index
            when (item) {
                is Condition.Match -> MatchEditor(item, onChange = { next -> onChange(whole.changedAt(at) { next }) }) {
                    onChange(whole.removedAt(at))
                }
                is Condition.Group -> Column(
                    Modifier.fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                        .padding(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                ) {
                    GroupEditor(item, at, depth + 1, whole, onChange)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { onChange(whole.addedTo(path, Condition.Match(SearchField.FROM))) },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Add condition") }
            // A depth limit rather than none, because three levels is already more than
            // anyone reads back correctly a week later.
            if (depth < MAX_GROUP_DEPTH) {
                TextButton(
                    onClick = {
                        onChange(
                            whole.addedTo(
                                path,
                                Condition.Group(Joiner.ANY, listOf(Condition.Match(SearchField.UNREAD))),
                            ),
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("Add group") }
            }
        }
    }
}

/** One condition: the field as a picker, its value when it takes one, and why it cannot run. */
@Composable
private fun MatchEditor(match: Condition.Match, onChange: (Condition.Match) -> Unit, onRemove: () -> Unit) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Picker(SearchField.entries.map { it.label }, match.field.label) { picked ->
                onChange(match.copy(field = SearchField.entries.first { it.label == picked }))
            }
            if (match.field.valued) {
                OutlinedTextField(
                    value = match.value,
                    onValueChange = { onChange(match.copy(value = it)) },
                    singleLine = true,
                    placeholder = { Text(placeholderFor(match.field)) },
                    modifier = Modifier.width(220.dp),
                )
            }
            TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Remove") }
        }
        problemOf(match)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** An example of what goes in the box, which is quicker to read than a rule about it. */
private fun placeholderFor(field: SearchField): String = when (field) {
    SearchField.TEXT -> "invoice"
    SearchField.FROM, SearchField.TO -> "name or address"
    SearchField.SUBJECT -> "quote"
    SearchField.LIST -> "news.example.org"
    SearchField.TAG -> "work"
    SearchField.AFTER, SearchField.BEFORE -> "2026-09-01"
    SearchField.LARGER, SearchField.SMALLER -> "2 MB"
    else -> ""
}
