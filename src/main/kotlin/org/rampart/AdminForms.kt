package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The one renderer every admin screen is drawn with.
 *
 * It knows field types, never objects. A domain and an account go through exactly the same
 * code, and so will every page added after them, which is the whole point: see
 * "Settings and admin screens are generated, not written" in `docs/architecture.md`.
 *
 * [value] is the object as it stands (or the draft, while editing), [onValue] replaces it.
 * [problems] marks fields by path ("dkimManagement/selector" for a nested one), from the
 * server or from checks on the whole draft. [onInputError] reports what an input box found
 * wrong with what was typed, or null once it is fixed, so the save button can refuse while
 * any box still holds something that is not a value.
 */
@Composable
internal fun AdminFormBody(
    schema: AdminSchema,
    form: AdminForm,
    value: JsonObject,
    editing: Boolean,
    creating: Boolean,
    enterprise: Boolean,
    problems: Map<String, String>,
    onValue: (JsonObject) -> Unit,
    onInputError: (String, String?) -> Unit,
    onOpenConsole: () -> Unit,
    path: String = "",
) {
    form.sections.forEach { section ->
        section.title?.let {
            Text(
                it,
                style = if (path.isEmpty()) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = if (path.isEmpty()) 18.dp else 8.dp, bottom = 6.dp),
            )
        }
        section.fields.forEach { field ->
            val here = if (path.isEmpty()) field.name else "$path/${field.name}"
            val canChange = editing && (if (creating) field.settableOnCreate else field.editable)
            AdminFieldRow(
                schema = schema,
                field = field,
                value = value[field.name],
                canChange = canChange,
                enterprise = enterprise,
                creating = creating,
                path = here,
                problem = problems[here],
                problems = problems,
                onChange = { next ->
                    onValue(JsonObject(value.toMutableMap().apply { put(field.name, next) }))
                },
                onInputError = onInputError,
                onOpenConsole = onOpenConsole,
            )
        }
    }
}

@Composable
private fun AdminFieldRow(
    schema: AdminSchema,
    field: AdminField,
    value: JsonElement?,
    canChange: Boolean,
    enterprise: Boolean,
    creating: Boolean,
    path: String,
    problem: String?,
    problems: Map<String, String>,
    onChange: (JsonElement) -> Unit,
    onInputError: (String, String?) -> Unit,
    onOpenConsole: () -> Unit,
) {
    val choices = { name: String -> schema.choices(name) }
    val variants = { name: String -> (schema.shapeOf(name) as? AdminShape.Multiple)?.variants.orEmpty() }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            field.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        val type = field.type
        when {
            !canChange && type !is AdminType.Nested -> Text(
                displayValue(type, value, choices, variants),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
            type is AdminType.Flag -> Switch(
                checked = (value as? JsonPrimitive)?.booleanOrNull == true,
                onCheckedChange = { onChange(JsonPrimitive(it)) },
            )
            type is AdminType.Choice -> ChoicePicker(
                options = schema.choices(type.enumName).map { it.name to it.label },
                current = (value as? JsonPrimitive)?.contentOrNull,
                nullable = type.nullable,
                onPick = { onChange(if (it == null) JsonNull else JsonPrimitive(it)) },
            )
            type is AdminType.Many -> SetEditor(
                schema = schema,
                type = type,
                members = setMembers(value),
                path = path,
                onMembers = { onChange(membersValue(it)) },
            )
            type is AdminType.Nested -> NestedEditor(
                schema = schema,
                type = type,
                value = value as? JsonObject,
                editing = canChange,
                creating = creating,
                enterprise = enterprise,
                path = path,
                problems = problems,
                onValue = onChange,
                onInputError = onInputError,
                onOpenConsole = onOpenConsole,
            )
            type is AdminType.Text || type is AdminType.Number || type is AdminType.Reference ||
                type is AdminType.Timestamp -> TypedBox(type, value, path, field.placeholder, onChange, onInputError)
            else -> {
                // Lists of nested objects, maps, blobs and anything newer than this build.
                // Shown, not edited, with the way to edit them one click away. One field
                // the renderer cannot draw costs that field, never the page it sits on.
                Text(
                    displayValue(type, value, choices, variants),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
                TextButton(onClick = onOpenConsole, modifier = Modifier.padding(top = 0.dp)) {
                    Text("Rampart cannot edit this yet. Open the server's web console", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (field.description.isNotBlank() && canChange) {
            Text(
                field.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        problem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * A text box for every type that is typed: strings, numbers, durations, sizes, ids, dates.
 *
 * What is typed is kept as typed, and turned into a value on every keystroke. A box
 * holding something that is not yet a value reports why to [onInputError] and leaves the
 * draft as it was, so "5" on the way to "5m" never reaches the server as five milliseconds.
 */
@Composable
private fun TypedBox(
    type: AdminType,
    value: JsonElement?,
    path: String,
    placeholder: String?,
    onChange: (JsonElement) -> Unit,
    onInputError: (String, String?) -> Unit,
) {
    val secret = type is AdminType.Text && type.secret
    var text by remember(path) { mutableStateOf(editText(type, value)) }
    var wrong by remember(path) { mutableStateOf<String?>(null) }
    // What the field held when the box was drawn, so an emptied secret box can put it back.
    val initial = remember(path) { value }
    val hint = when {
        secret -> if (value == null || value is JsonNull) "Not set" else "Unchanged"
        placeholder != null -> placeholder
        type is AdminType.Number && type.format == "duration" -> "For example 30s, 5m or 1h"
        type is AdminType.Number && type.format == "size" -> "For example 50 MB"
        else -> null
    }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            // An empty secret box means "leave it as it is", not "clear it", even after
            // something was typed into it and taken out again.
            if (secret && typed.isEmpty()) {
                wrong = null
                onInputError(path, null)
                onChange(initial ?: JsonNull)
                return@OutlinedTextField
            }
            when (val parsed = parseInput(type, typed)) {
                is Parsed.Ok -> {
                    wrong = null
                    onInputError(path, null)
                    onChange(parsed.value)
                }
                is Parsed.Bad -> {
                    wrong = parsed.reason
                    onInputError(path, parsed.reason)
                }
            }
        },
        singleLine = !(type is AdminType.Text && type.multiline),
        minLines = if (type is AdminType.Text && type.multiline) 3 else 1,
        isError = wrong != null,
        placeholder = { hint?.let { Text(it) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
    )
    wrong?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ChoicePicker(
    options: List<Pair<String, String>>,
    current: String?,
    nullable: Boolean,
    onPick: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(options.firstOrNull { it.first == current }?.second ?: current ?: "Not set")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            if (nullable) {
                DropdownMenuItem(text = { Text("Not set") }, onClick = { open = false; onPick(null) })
            }
            options.forEach { (name, label) ->
                DropdownMenuItem(
                    text = { Text(label, fontWeight = if (name == current) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { open = false; onPick(name) },
                )
            }
        }
    }
}

/**
 * A set: the members as removable rows, and a way to add one. Enum members are picked from
 * the enum; everything else is typed and checked as the member type before it is added.
 * A half-typed member is never an error for the draft: what is not added is not sent.
 */
@Composable
private fun SetEditor(
    schema: AdminSchema,
    type: AdminType.Many,
    members: List<String>,
    path: String,
    onMembers: (List<String>) -> Unit,
) {
    val item = type.item
    val labels = if (item is AdminType.Choice) schema.choices(item.enumName).associate { it.name to it.label } else emptyMap()
    Column(Modifier.padding(top = 2.dp)) {
        members.forEach { member ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(labels[member] ?: member, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                IconButton(onClick = { onMembers(members - member) }, modifier = Modifier.size(28.dp)) {
                    Icon(RampartIcons.Close, contentDescription = "Remove ${labels[member] ?: member}", modifier = Modifier.size(12.dp))
                }
            }
        }
        if (item is AdminType.Choice) {
            val left = schema.choices(item.enumName).filter { it.name !in members }.map { it.name to it.label }
            if (left.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { open = true }) { Text("Add") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                        left.forEach { (name, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onMembers(members + name) })
                        }
                    }
                }
            }
        } else {
            var typed by remember(path) { mutableStateOf("") }
            var wrong by remember(path) { mutableStateOf<String?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it; wrong = null },
                    singleLine = true,
                    placeholder = { Text("Add one") },
                    isError = wrong != null,
                    modifier = Modifier.widthIn(max = 380.dp).weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    when (val parsed = parseInput(item, typed)) {
                        is Parsed.Ok -> {
                            val member = (parsed.value as? JsonPrimitive)?.contentOrNull
                            if (member != null && member !in members) onMembers(members + member)
                            typed = ""
                            wrong = null
                        }
                        is Parsed.Bad -> wrong = parsed.reason
                    }
                }) { Text("Add") }
            }
            wrong?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * A nested object, drawn with its own form inside a quiet box. When the object is one of
 * several kinds, the kind is chosen first, and choosing another starts that kind from the
 * server's defaults rather than carrying fields across that it does not have.
 */
@Composable
private fun NestedEditor(
    schema: AdminSchema,
    type: AdminType.Nested,
    value: JsonObject?,
    editing: Boolean,
    creating: Boolean,
    enterprise: Boolean,
    path: String,
    problems: Map<String, String>,
    onValue: (JsonElement) -> Unit,
    onInputError: (String, String?) -> Unit,
    onOpenConsole: () -> Unit,
) {
    val shape = schema.shapeOf(type.objectName)
    val current = value ?: JsonObject(emptyMap())
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (shape is AdminShape.Multiple) {
            val chosen = (current["@type"] as? JsonPrimitive)?.contentOrNull
            if (editing) {
                ChoicePicker(
                    options = shape.variants.map { it.name to it.label },
                    current = chosen,
                    nullable = false,
                    onPick = { picked ->
                        if (picked != null && picked != chosen) {
                            onValue(startingValue(schema, type.objectName, picked, enterprise))
                        }
                    },
                )
            } else {
                Text(
                    shape.variants.firstOrNull { it.name == chosen }?.label ?: chosen ?: "Not set",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        val fieldSet = schema.fieldSetFor(type.objectName, current)
        if (value == null && shape !is AdminShape.Multiple) {
            if (editing) {
                TextButton(onClick = { onValue(startingValue(schema, type.objectName, null, enterprise)) }) { Text("Set this") }
            } else {
                Text("Not set", style = MaterialTheme.typography.bodyMedium)
            }
        } else if (fieldSet != null) {
            // Keyed by the kind, so boxes drawn for the previous kind do not keep what was typed in them.
            androidx.compose.runtime.key(fieldSet) {
                AdminFormBody(
                    schema = schema,
                    form = schema.form(fieldSet, enterprise),
                    value = current,
                    editing = editing,
                    creating = creating,
                    enterprise = enterprise,
                    problems = problems,
                    onValue = { onValue(it) },
                    onInputError = onInputError,
                    onOpenConsole = onOpenConsole,
                    path = path,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** The confirm-before-save list: one row per change, the old value above the new one. */
@Composable
internal fun ChangeList(changes: List<AdminChange>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        changes.forEach { change ->
            Column {
                Text(change.label, style = MaterialTheme.typography.labelLarge)
                Text(
                    "Was: ${change.before}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text("Now: ${change.after}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The clickable row a list page is made of. */
@Composable
internal fun AdminRow(cells: List<String>, weights: List<Float>, header: Boolean, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = if (header) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, cell ->
            Text(
                cell,
                style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                color = if (header) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(weights.getOrElse(i) { 1f }).padding(end = 12.dp),
            )
        }
    }
}
