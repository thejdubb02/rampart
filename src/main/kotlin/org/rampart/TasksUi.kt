package org.rampart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/*
 * The screens for tasks from mail: the "Make a task" chip under an open message, the
 * editable task card it opens, the same card for a task Rook proposed, and the Tasks panel
 * in the app bar on the right.
 *
 * Everything that can be decided without a window is decided in TaskFromMail.kt and
 * TaskStore.kt, and tested there. This file is the buttons, the waiting and the sentences.
 *
 * A class 3 feature in CLAUDE.md's terms: it needs the account's own server to keep the
 * task, so the chip is absent on an account with nowhere to keep one and the panel says why
 * in one sentence. Every model call is a button press behind the same consent, ceiling,
 * never-leaves folders and ledger as the rest of Rook.
 */

/**
 * Where tasks go for [backend]: JMAP Tasks when the session advertises it, else Stalwart's
 * CalDAV, else nowhere. Stalwart is known by the management account its session names,
 * which is the same test the Your phone page uses before it hands out a CalDAV address.
 */
internal fun taskBackendFor(backend: MailBackend?): TaskBackend? {
    val jmap = backend as? Jmap ?: return null
    if (jmap.advertises(TASKS_CAPABILITY)) {
        return JmapTasks(jmap.accountId) { calls -> jmap.request(TASKS_CAPABILITY, *calls.toTypedArray()) }
    }
    if (jmap.managementAccountId == null) return null
    return jmap.davTransport()?.let { CalDavTasks(it) }
}

/** Why [backend] has nowhere to keep a task, in one sentence, or null when it has. */
internal fun noTasksBecause(backend: MailBackend?, accountName: String): String? {
    val who = accountName.ifBlank { "This account" }
    return when {
        backend == null -> "Sign in to an account to see its tasks."
        backend !is Jmap -> "IMAP carries mail only, so $who has nowhere Rampart can keep a task."
        taskBackendFor(backend) != null -> null
        else -> "$who's server offers neither JMAP Tasks nor Stalwart's own calendars, so there is nowhere to keep a task."
    }
}

/** The link back to [summary]: its Message-ID, subject, sender and date. */
internal fun taskLinkOf(summary: Summary, body: Body?): TaskLink = TaskLink(
    messageId = body?.messageId?.firstOrNull() ?: summary.messageId,
    subject = summary.subject,
    from = summary.from.ifBlank { summary.fromEmail },
    sent = summary.receivedAt.take(10),
)

/** Rook's task tool for one turn, answering with the reason on an account without tasks. */
internal fun taskToolsFor(backend: MailBackend?, accountName: String, openLink: TaskLink?): TaskTools =
    TaskTools(noTasksBecause(backend, accountName), Regional.zone(), openLink)

/**
 * "Make a task", under an open message.
 *
 * Showing it costs nothing. Pressing it asks the model when Rook is on and may read this
 * folder; otherwise the card opens with the subject as the title and a note saying so, so
 * the button is still of use to somebody who never turns Rook on.
 */
@Composable
internal fun MakeTask(context: MailCalendar, summary: Summary, body: Body?) {
    val unavailable = remember(context.backend, context.accountName) { noTasksBecause(context.backend, context.accountName) }
    // Hidden on an account that cannot keep a task: a sentence under every message would be noise.
    if (unavailable != null) return
    val text = remember(summary.id, body) { rookTextOf(body) }
    val link = remember(summary.id, body) { taskLinkOf(summary, body) }
    val scope = rememberCoroutineScope()
    val zone = Regional.zone()
    var running by remember(summary.id) { mutableStateOf(false) }
    var fault by remember(summary.id) { mutableStateOf<Pair<String, String?>?>(null) }
    var form by remember(summary.id) { mutableStateOf<TaskForm?>(null) }
    var packet by remember(summary.id) { mutableStateOf<String?>(null) }
    var agreed by remember { mutableStateOf(Assistant.agreed(TASKS_FEATURE)) }

    fun byHand(reason: String) {
        form = TaskForm(
            title = summary.subject.ifBlank { "Follow up" }.take(TASK_TITLE_MAX),
            timeZone = zone.id,
            link = link,
            checks = listOf("$reason The title is the message's subject."),
        )
    }

    fun ask(sending: String) {
        val config = Assistant.config()
        running = true
        fault = null
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { Llm.ask(config, Secrets.loadNamed(Assistant.KEY), sending) }
                Assistant.record(TASKS_FEATURE, reply.tokensIn, reply.tokensOut, config)
                when (val read = extractedTask(reply.text, zone, link, fallbackTitle = summary.subject)) {
                    is TaskExtracted.Found -> form = read.form
                    is TaskExtracted.Failed -> fault = read.reason to null
                }
            } catch (e: Exception) {
                val title = "Rook could not read a task out of this message."
                fault = title to faultDetail(e, title)
            } finally {
                running = false
            }
        }
    }

    fun press() {
        val config = Assistant.config()
        val why = Assistant.whyNot(TASKS_FEATURE, config, context.account, context.folder)
        if (why != null) {
            byHand(why)
            return
        }
        val built = TaskFromMail.packet(
            config.model,
            LocalDate.now(zone),
            zone,
            summary.subject,
            "${summary.from} <${summary.fromEmail}>",
            summary.receivedAt,
            text,
        )
        if (Assistant.agreed(TASKS_FEATURE)) ask(built) else packet = built
    }

    Column(Modifier.fillMaxWidth()) {
        val open = form
        if (open == null) {
            // A small link like Add tag above it, not a pill: it is an occasional action and
            // should not take a line and a half of the header.
            Row(
                Modifier.clip(CircleShape).clickable(enabled = !running) { press() }
                    .padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(RampartIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (running) "Rook is reading the message" else "Make a task",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            TaskCard(initial = open, backend = context.backend, heading = "Make a task", onClose = { form = null })
        }
        fault?.let { (title, detail) -> FaultText(title, detail, Modifier.padding(top = 6.dp)) }
    }

    packet?.let { waiting ->
        PacketViewer(
            packet = waiting,
            agreed = agreed,
            onSend = {
                packet = null
                ask(waiting)
            },
            onAgree = {
                Assistant.agree(TASKS_FEATURE)
                agreed = true
            },
            onDismiss = { packet = null },
        )
    }
}

/**
 * The editable task, and Save. Every field can be changed, nothing is written until Save,
 * and Save checks the whole form first with [taskDraft]. The uid is made once per card, so
 * pressing Save again after a failure cannot make a second copy of the same task.
 */
@Composable
internal fun TaskCard(
    initial: TaskForm,
    backend: MailBackend?,
    heading: String,
    onClose: () -> Unit,
    /** Drawn inside a dialog, which has its own surface, rather than in the message. */
    bare: Boolean = false,
) {
    val store = remember(backend) { taskBackendFor(backend) }
    val scope = rememberCoroutineScope()
    val uid = remember(initial) { UUID.randomUUID().toString() }
    var form by remember(initial) { mutableStateOf(initial) }
    var lists by remember(store) { mutableStateOf<List<TaskListInfo>?>(null) }
    var listId by remember(store) { mutableStateOf("") }
    var problem by remember(initial) { mutableStateOf<String?>(null) }
    var fault by remember(initial) { mutableStateOf<Pair<String, String?>?>(null) }
    var saving by remember(initial) { mutableStateOf(false) }
    var savedIn by remember(initial) { mutableStateOf<String?>(null) }

    LaunchedEffect(store) {
        val tasks = store ?: return@LaunchedEffect
        try {
            val found = withContext(Dispatchers.IO) { tasks.lists() }
            lists = found
            listId = defaultList(found)?.id.orEmpty()
            if (found.isEmpty()) fault = "This account has no task list or calendar that takes tasks." to null
        } catch (e: Exception) {
            val title = "Could not read your task lists."
            fault = title to faultDetail(e, title)
        }
    }

    fun save() {
        val tasks = store ?: return
        if (listId.isBlank()) {
            problem = "Pick where to keep the task."
            return
        }
        when (val check = taskDraft(form, uid)) {
            is TaskCheck.Refused -> problem = check.reason
            is TaskCheck.Ready -> {
                problem = null
                fault = null
                saving = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { tasks.create(check.draft, listId) }
                        savedIn = lists.orEmpty().firstOrNull { it.id == listId }?.name.orEmpty()
                        TasksPanelState.changed++
                    } catch (e: Exception) {
                        val title = "The task was not saved."
                        fault = title to faultDetail(e, title)
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }

    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(heading, style = MaterialTheme.typography.titleSmall)
            val done = savedIn
            if (done != null) {
                Text(
                    if (done.isBlank()) "Saved. It will show on your phone the next time it syncs." else "Saved in $done. It will show on your phone the next time it syncs.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row {
                    TextButton(onClick = { if (!AppBar.showing(SideTool.TASKS)) AppBar.toggle(SideTool.TASKS) }) { Text("Show tasks") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                return@Column
            }
            if (store == null) {
                Text(
                    noTasksBecause(backend, "") ?: "This account has nowhere to keep a task.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                TextButton(onClick = onClose) { Text("Close") }
                return@Column
            }
            form.checks.forEach { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            OutlinedTextField(form.title, { form = form.copy(title = it) }, label = { Text("To do") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.dueDate,
                    { form = form.copy(dueDate = it) },
                    label = { Text("Due, like 2026-10-03") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(form.dueTime, { form = form.copy(dueTime = it) }, label = { Text("At") }, singleLine = true, modifier = Modifier.width(96.dp))
            }
            if (form.dueTime.isNotBlank()) {
                OutlinedTextField(form.timeZone, { form = form.copy(timeZone = it) }, label = { Text("Time zone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(form.notes, { form = form.copy(notes = it) }, label = { Text("Notes") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
            form.link?.let { link ->
                Text(
                    link.line + if (link.href == null) " The message has no Message-ID, so the task cannot link to it." else " The task links back to it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            val choices = lists.orEmpty()
            if (choices.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    choices.forEach { each ->
                        FilterChip(
                            selected = listId == each.id,
                            onClick = { listId = each.id },
                            label = { Text(each.name, maxLines = 1) },
                        )
                    }
                }
            }
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            fault?.let { (title, detail) -> FaultText(title, detail) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { save() }, enabled = !saving && lists != null) {
                    Text(if (saving) "Saving" else "Save")
                }
                TextButton(onClick = onClose, enabled = !saving) { Text("Discard") }
            }
        }
    }

    if (bare) {
        content()
    } else {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
        }
    }
}

/** A task Rook proposed, waiting for the person to check it, with the account it is for. */
internal data class ProposedTask(val form: TaskForm, val backend: MailBackend?)

/**
 * The cards Rook's propose_task made, oldest first, shown one at a time by [ProposedTaskDialog].
 * A holder for the same reason [ProposedEvents] is one.
 */
internal object ProposedTasks {
    var waiting by mutableStateOf<List<ProposedTask>>(emptyList())

    /** The cards from one turn of the conversation. Call on the window's thread. */
    fun offer(tools: TaskTools, backend: MailBackend?) {
        if (tools.proposed.isEmpty()) return
        waiting = waiting + tools.proposed.map { ProposedTask(it, backend) }
    }
}

/** Rook's proposed task, as the same editable card, in front of the window. Drawn once, by the window. */
@Composable
internal fun ProposedTaskDialog() {
    val next = ProposedTasks.waiting.firstOrNull() ?: return
    fun done() {
        ProposedTasks.waiting = ProposedTasks.waiting.drop(1)
    }
    AlertDialog(
        onDismissRequest = { done() },
        text = {
            Box(Modifier.widthIn(min = 420.dp, max = 520.dp).verticalScroll(rememberScrollState())) {
                TaskCard(initial = next.form, backend = next.backend, heading = "Rook proposed a task", onClose = { done() }, bare = true)
            }
        },
        // The card carries its own Save and Discard, so the dialog adds no buttons of its own.
        confirmButton = {},
    )
}

/** What the Tasks panel and its button in the app bar need from elsewhere in the window. */
internal object TasksPanelState {
    /** Bumped when a card saves, so an open Tasks panel reads the list again without a timer. */
    var changed by mutableIntStateOf(0)

    /** True when at least one signed in account can keep a task. The app bar's Tasks button hides otherwise. */
    var offered by mutableStateOf(false)
}

/** Keeps [TasksPanelState.offered] in step with the accounts signed in. Building a backend reads nothing from the network. */
@Composable
internal fun TasksFollow(backends: List<MailBackend>) {
    LaunchedEffect(backends) {
        TasksPanelState.offered = backends.any { taskBackendFor(it) != null }
    }
}

/**
 * The tasks beside the mail: what is still to do, soonest first.
 *
 * Read only, like the calendar panel. Ticking a task off belongs to whatever the person
 * keeps their tasks in, their phone included, which already does it well.
 */
@Composable
internal fun TasksPanel(backend: MailBackend?, accountName: String) {
    val store = remember(backend) { taskBackendFor(backend) }
    val zone = Regional.zone()
    var reload by remember { mutableIntStateOf(0) }
    var shown by remember(store) { mutableStateOf<List<TaskItem>?>(null) }
    var loading by remember(store) { mutableStateOf(false) }
    var fault by remember(store) { mutableStateOf<Pair<String, String?>?>(null) }

    LaunchedEffect(store, reload, TasksPanelState.changed) {
        val tasks = store ?: return@LaunchedEffect
        loading = true
        try {
            shown = withContext(Dispatchers.IO) { tasks.open(zone) }
            fault = null
        } catch (e: Exception) {
            val title = "Could not read your tasks."
            fault = title to faultDetail(e, title)
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Tasks", style = MaterialTheme.typography.titleSmall)
                if (accountName.isNotBlank()) {
                    Text(
                        accountName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (store != null) {
                IconButton(onClick = { reload++ }, enabled = !loading, modifier = Modifier.size(28.dp)) {
                    Icon(RampartIcons.Refresh, contentDescription = "Read the tasks again", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(15.dp))
                }
            }
            IconButton(onClick = { AppBar.close(SideTool.TASKS) }, modifier = Modifier.size(28.dp)) {
                Icon(RampartIcons.Close, contentDescription = "Close Tasks", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(15.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (store == null) {
            Note(noTasksBecause(backend, accountName) ?: "This account has nowhere to keep a task.")
            return@Column
        }
        Note("Make a task from any open message. They sync to your phone through your own server.")
        fault?.let { (title, detail) -> FaultText(title, detail, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        val list = shown
        when {
            list == null && loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() }
            list == null -> Unit
            list.isEmpty() -> Note("Nothing to do.")
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(list, key = { it.uid.ifBlank { it.title } }) { task ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        if (task.due.isNotBlank()) {
                            Text(
                                "Due ${task.due}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (task.due.take(10) < LocalDate.now(zone).toString()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                            )
                        }
                        Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                item(key = "end") { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(16.dp),
    )
}
