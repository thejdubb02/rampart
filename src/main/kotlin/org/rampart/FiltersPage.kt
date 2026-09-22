package org.rampart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** Null while the chosen account's script is still being read. */
    script: Script?,
    /** Every signed in account, so a rule can be kept for one of them or for all. */
    accounts: List<AccountMailboxes>,
    /** Which account is being edited, or null for the set that goes to all of them. */
    chosen: String?,
    onChoose: (String?) -> Unit,
    globals: GlobalFilters,
    onGlobals: (GlobalFilters) -> Unit,
    /** Folders to offer for "file into", so nobody types a folder name that does not exist. */
    folders: List<String>,
    saving: Boolean,
    error: String?,
    supported: Boolean,
    onSave: (Script) -> Unit,
) {
    Section("Filters", "Rules run on the server, so they work with Rampart closed.")
    Where(accounts, chosen, onChoose)
    Spacer(Modifier.height(6.dp))
    // Which set is being edited, said in a sentence. The chips alone read as a filter on
    // the list rather than as a choice of where a new rule would go.
    Note(
        if (chosen == null) {
            "Kept for every account below. Each server runs its own copy, and an account " +
                "signed in later gets them too."
        } else {
            "Kept only on " + (accounts.firstOrNull { it.key == chosen }?.email ?: "this account") + "."
        },
    )
    Spacer(Modifier.height(14.dp))

    if (chosen == null) {
        Everywhere(globals, accounts, folders, saving, error, onGlobals)
        return
    }
    OneAccount(script, chosen, globals, folders, saving, error, supported, onSave)
}

/** Which set of rules is on the screen. Always shown, so the global set is one click away. */
@Composable
private fun Where(accounts: List<AccountMailboxes>, chosen: String?, onChoose: (String?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = chosen == null,
            onClick = { onChoose(null) },
            label = { Text("Everywhere") },
        )
        accounts.forEach { account ->
            FilterChip(
                selected = chosen == account.key,
                onClick = { onChoose(account.key) },
                label = { Text(account.email) },
            )
        }
    }
}

/**
 * The rules kept for every account, and which accounts they are kept off.
 *
 * Saved to each account's own server rather than run here, so they work the same way an
 * account's own rules do: at delivery, with Rampart closed.
 */
@Composable
private fun Everywhere(
    globals: GlobalFilters,
    accounts: List<AccountMailboxes>,
    folders: List<String>,
    saving: Boolean,
    error: String?,
    onGlobals: (GlobalFilters) -> Unit,
) {
    RuleList(globals.rules, folders, saving) { onGlobals(globals.copy(rules = it)) }

    if (accounts.size > 1) {
        Spacer(Modifier.height(18.dp))
        Text("Where these go", style = MaterialTheme.typography.titleSmall)
        accounts.forEach { account ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(account.email, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = globals.appliesTo(account.key),
                    enabled = !saving,
                    onCheckedChange = { on ->
                        onGlobals(
                            globals.copy(
                                exceptions = if (on) globals.exceptions - account.key
                                else globals.exceptions + account.key,
                            ),
                        )
                    },
                )
            }
        }
    }
    Problem(error)
}

/** One account: the rules it inherits, then its own. */
@Composable
private fun OneAccount(
    script: Script?,
    chosen: String,
    globals: GlobalFilters,
    folders: List<String>,
    saving: Boolean,
    error: String?,
    supported: Boolean,
    onSave: (Script) -> Unit,
) {
    if (!supported) {
        Note("This server does not offer Sieve, so rules cannot be kept on it.")
        return
    }
    if (script == null) {
        Spinner(Modifier.height(20.dp))
        return
    }

    var raw by remember(script) { mutableStateOf<String?>(null) }
    val inherited = globals.forAccount(chosen)

    if (!script.editable) {
        // Being plain about why, rather than showing an empty list that looks like a bug.
        Note(
            "The filter script on this server was written by hand rather than by a rule " +
                "builder, so Rampart shows it as it is instead of rewriting it.",
        )
        Spacer(Modifier.height(10.dp))
        RawScript(script.tail)
        return
    }

    /*
     * The account's own rules are what this screen edits, and the global set is put back
     * on top on the way out. Saving the list as it is drawn would turn every inherited
     * rule into a copy this account owns, and the global set could then never be changed
     * or removed again.
     */
    RuleList(
        rules = ownRules(script),
        folders = folders,
        saving = saving,
        inherited = inherited,
        extra = {
            TextButton(
                onClick = { raw = sieveOf(scriptFor(script, inherited)) },
            ) { Text("Show the script") }
        },
        onChange = { next -> onSave(scriptFor(script.copy(rules = next), inherited)) },
    )

    Problem(error)
    if (script.tail.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Note(
            "There is more in this script that Rampart did not write. It is kept exactly " +
                "as it is and saving a rule here does not touch it.",
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
}

/**
 * A list of rules and the editor over it, used for one account's rules and for the set
 * kept for all of them.
 *
 * [inherited] is drawn above the editable ones and cannot be changed here: a rule that
 * belongs to every account is changed in one place, not in whichever account it was
 * noticed in.
 */
@Composable
private fun RuleList(
    rules: List<Rule>,
    folders: List<String>,
    saving: Boolean,
    inherited: List<Rule> = emptyList(),
    extra: @Composable RowScope.() -> Unit = {},
    onChange: (List<Rule>) -> Unit,
) {
    var editing by remember(rules, inherited) { mutableStateOf<Rule?>(null) }

    DescribeRule(folders) { made -> onChange(rules + made) }
    Spacer(Modifier.height(14.dp))

    inherited.forEach { rule ->
        RuleRow(rule = rule, mine = false, onToggle = {}, onEdit = {}, onDelete = {})
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    rules.forEach { rule ->
        RuleRow(
            rule = rule,
            mine = true,
            onToggle = { onChange(rules.map { if (it.id == rule.id) it.copy(enabled = !it.enabled) else it }) },
            onEdit = { editing = rule },
            onDelete = { onChange(rules.filterNot { it.id == rule.id }) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    if (rules.isEmpty() && inherited.isEmpty()) {
        Note("No rules yet.")
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
        extra()
        if (saving) {
            Spacer(Modifier.width(4.dp))
            Spinner(Modifier.height(18.dp))
        }
    }

    editing?.let { rule ->
        RuleEditor(
            rule = rule,
            folders = folders,
            onClose = { editing = null },
            onDone = { edited ->
                editing = null
                onChange(
                    if (rules.any { it.id == edited.id }) rules.map { if (it.id == edited.id) edited else it }
                    else rules + edited,
                )
            },
        )
    }
}

/**
 * The sentence a right-click starts from.
 *
 * It names this message and stops. The model is told to refuse a sentence with nothing
 * to do, so the box is left for the person to say what should happen to mail like it.
 * The address when the row has one, otherwise the name on the row, which is sometimes
 * only a domain.
 */
/** Folder names every one of [perAccount] has, since a rule kept for all of them can only file into what they share. */
internal fun commonFolders(perAccount: List<Set<String>>): List<String> =
    perAccount.reduceOrNull { all, next -> all intersect next }.orEmpty().sorted()

internal fun filterSeed(message: Summary): String {
    val who = message.fromEmail.trim().ifBlank { message.from.trim() }.ifBlank { "this sender" }
    val subject = message.subject.trim()
    return "Filter emails like this: from $who, subject like \"$subject\". Say what to do with them."
}

/**
 * The same "describe a filter" box, opened on one message.
 *
 * Nothing is saved here. Add it is still [DescribeRule]'s, and the caller writes the
 * rule the same way Settings does.
 */
@Composable
internal fun FilterFromMessageDialog(
    message: Summary,
    folders: List<String>,
    onClose: () -> Unit,
    onMade: (Rule) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("AI Filter") },
        text = {
            Column(
                Modifier
                    .widthIn(min = 360.dp, max = 520.dp)
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                DescribeRule(folders, filterSeed(message)) { rule ->
                    onMade(rule)
                    onClose()
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/**
 * A filter said in a sentence, built into a rule.
 *
 * The first thing the assistant is for, and the shape every later one should copy: the
 * model proposes, nothing is saved without being read, and what it produces is an ordinary
 * Sieve rule that keeps working with the model switched off forever. See [ruleOfAnswer]
 * for why it cannot produce anything this build would not have accepted from a person.
 */
@Composable
private fun DescribeRule(
    folders: List<String>,
    /**
     * What the box starts with. Blank from Settings. A message puts a sentence here so
     * the person only has to say what to do with mail like it.
     */
    seed: String = "",
    onMade: (Rule) -> Unit,
) {
    val config = remember { Assistant.config() }
    if (config.mode == AssistantMode.OFF) {
        Note("Rules can be described in words. Turn the assistant on in Settings to do that.")
        return
    }

    val scope = rememberCoroutineScope()
    var words by remember(seed) { mutableStateOf(seed) }
    var thinking by remember { mutableStateOf(false) }
    var trouble by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Rule?>(null) }
    // The exact packet, held while it is being shown. Being asked the first time is not a
    // dialog about a feature, it is this text, before it goes anywhere.
    var asking by remember { mutableStateOf<String?>(null) }

    fun send(packet: String) {
        thinking = true
        trouble = null
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val key = Secrets.loadNamed(Assistant.KEY)
                    val reply = Llm.ask(config, key, packet)
                    Assistant.record(Assistant.FILTER, reply.tokensIn, reply.tokensOut, config)
                    reply
                }
            }
            thinking = false
            outcome.fold(
                onSuccess = { reply ->
                    ruleOfAnswer(reply.text, folders).fold(
                        onSuccess = { draft = it },
                        onFailure = { trouble = it.message },
                    )
                },
                onFailure = { trouble = it.message ?: "The model could not be reached." },
            )
        }
    }

    fun make() {
        if (words.isBlank()) return
        Assistant.whyNot(Assistant.FILTER, config)?.let {
            trouble = it
            return
        }
        val packet = rulePacket(config, words, folders)
        // Agreed once, on this install, by reading what actually goes. After that it is a
        // decision somebody made and can see in Settings, not one to interrupt them for.
        if (Assistant.agreed(Assistant.FILTER)) send(packet) else asking = packet
    }

    OutlinedTextField(
        value = words,
        onValueChange = { words = it },
        label = { Text("Describe a filter") },
        placeholder = { Text("If I get a DMARC report, mark it read and delete it") },
        enabled = !thinking,
        minLines = if (seed.isBlank()) 1 else 3,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Button(onClick = { make() }, enabled = !thinking && words.isNotBlank()) { Text("Make a rule") }
        if (thinking) Spinner(Modifier.height(18.dp))
        trouble?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    /*
     * Shown, never saved on its own. A rule that deletes mail is not a thing to find out
     * about afterwards, and the sentence under it is the same one the list uses, so what is
     * agreed to here is what appears there.
     */
    draft?.let { rule ->
        Spacer(Modifier.height(10.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                Text(rule.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    summarise(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { draft = null }) { Text("Discard") }
                    TextButton(
                        onClick = {
                            draft = null
                            words = ""
                            onMade(rule)
                        },
                    ) { Text("Add it") }
                }
            }
        }
    }

    asking?.let { packet ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("This is what would be sent") },
            text = {
                Column {
                    Text(
                        "What you typed and the names of your folders. No mail, no addresses, " +
                            "and nothing else from this machine.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(8.dp))
                    RawScript(packet)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        Assistant.agree(Assistant.FILTER)
                        asking = null
                        send(packet)
                    },
                ) { Text("Send it") }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun Problem(error: String?) {
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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

/**
 * One rule in the list: what it does, in a sentence, and the switch that turns it off.
 *
 * [mine] false is a rule this account inherited from the set kept for all of them. It is
 * shown because a screen that hides half of what runs against your mail is worse than one
 * that shows it greyed, and it is not editable here for the same reason.
 */
@Composable
private fun RuleRow(rule: Rule, mine: Boolean, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
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
            if (!mine) {
                Text(
                    "Kept for every account. Change it under Everywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        if (rule.understood && mine) {
            TextButton(onClick = onEdit) { Text("Edit") }
        }
        if (mine) {
            TextButton(onClick = onDelete) { Text("Delete") }
        }
        Switch(checked = rule.enabled, enabled = mine, onCheckedChange = { onToggle() })
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
