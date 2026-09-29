package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * The screens for calendar from mail: the "Add to calendar" chip on an open message, the
 * editable event card it opens, the same card for an event Rook proposed, and the "Help me
 * schedule" button in the composer.
 *
 * Everything that can be decided without a window is decided in DateMentions.kt,
 * EventFromMail.kt, FreeSlots.kt and CalendarTools.kt, and tested there. This file is the
 * buttons, the waiting and the sentences, and nothing else.
 *
 * Every model call here is a button press, goes through the same consent, spend ceiling,
 * denied folders and ledger as every other Rook feature, and fails with a sentence.
 */

/** What the message view needs to offer "Add to calendar": whose calendar, and the folder the message is in. */
internal data class MailCalendar(
    val backend: MailBackend?,
    val account: String,
    val accountName: String,
    /** The folder the message sits in, for [Assistant.whyNot]'s never-leaves check. */
    val folder: String?,
)

/**
 * The chip under an open message that mentions a date and a time, and the card it opens.
 *
 * Showing the chip costs nothing: [mentionsDateAndTime] is a local check. Only pressing it
 * asks the model, and only Save on the card that comes back writes to the calendar.
 */
@Composable
internal fun AddToCalendar(context: MailCalendar, summary: Summary, body: Body?) {
    val text = remember(summary.id, body) { rookTextOf(body) }
    val mentions = remember(summary.id, text) { mentionsDateAndTime(summary.subject, text) }
    if (!mentions) return
    val unavailable = remember(context.backend, context.accountName) { noCalendarBecause(context.backend, context.accountName) }
    val scope = rememberCoroutineScope()
    var running by remember(summary.id) { mutableStateOf(false) }
    var fault by remember(summary.id) { mutableStateOf<Pair<String, String?>?>(null) }
    var form by remember(summary.id) { mutableStateOf<EventForm?>(null) }
    var packet by remember(summary.id) { mutableStateOf<String?>(null) }
    var agreed by remember { mutableStateOf(Assistant.agreed(CALENDAR_FEATURE)) }
    val zone = remember { ZoneId.systemDefault() }

    fun ask(sending: String) {
        val config = Assistant.config()
        running = true
        fault = null
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { Llm.ask(config, Secrets.loadNamed(Assistant.KEY), sending) }
                Assistant.record(CALENDAR_FEATURE, reply.tokensIn, reply.tokensOut, config)
                when (val read = extractedEvent(reply.text, zone, fallbackTitle = summary.subject)) {
                    is Extracted.Found -> form = read.form
                    is Extracted.Failed -> fault = read.reason to null
                }
            } catch (e: Exception) {
                val title = "Rook could not read an event out of this message."
                fault = title to faultDetail(e, title)
            } finally {
                running = false
            }
        }
    }

    fun press() {
        val config = Assistant.config()
        Assistant.whyNot(CALENDAR_FEATURE, config, context.account, context.folder)?.let {
            fault = it to null
            return
        }
        val built = EventFromMail.packet(
            config.model,
            LocalDate.now(zone),
            zone,
            summary.subject,
            "${summary.from} <${summary.fromEmail}>",
            summary.receivedAt,
            text,
        )
        if (Assistant.agreed(CALENDAR_FEATURE)) ask(built) else packet = built
    }

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(12.dp))
        if (unavailable != null) {
            // Hidden, with the reason where the chip would have been, so a message about a
            // meeting on an IMAP account says why there is nothing to press.
            Text(unavailable, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            return@Column
        }
        val open = form
        if (open == null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                modifier = Modifier.clip(CircleShape).clickable(enabled = !running) { press() },
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(RampartIcons.Calendar, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (running) "Rook is reading the message" else "Add to calendar",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        } else {
            EventCard(
                initial = open,
                backend = context.backend,
                account = context.account,
                heading = "Add to calendar",
                onClose = { form = null },
            )
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
                Assistant.agree(CALENDAR_FEATURE)
                agreed = true
            },
            onDismiss = { packet = null },
        )
    }
}

/**
 * The editable event, and Save. Every field the model filled in can be changed here, and
 * nothing is written until Save, which checks the whole form first with [formDraft].
 *
 * Only calendars the account may write to are offered, and the default one is picked first.
 */
@Composable
internal fun EventCard(
    initial: EventForm,
    backend: MailBackend?,
    account: String,
    heading: String,
    onClose: () -> Unit,
    /** Drawn inside a dialog, which has its own surface, rather than in the message. */
    bare: Boolean = false,
) {
    val client = remember(backend) { eventCalendarFor(backend) }
    val scope = rememberCoroutineScope()
    var form by remember(initial) { mutableStateOf(initial) }
    var calendars by remember(client) { mutableStateOf<List<CalendarInfo>?>(null) }
    var calendarId by remember(client) { mutableStateOf("") }
    var problem by remember(initial) { mutableStateOf<String?>(null) }
    var fault by remember(initial) { mutableStateOf<Pair<String, String?>?>(null) }
    var saving by remember(initial) { mutableStateOf(false) }
    var saved by remember(initial) { mutableStateOf<CalendarJumpTarget?>(null) }
    var savedIn by remember(initial) { mutableStateOf("") }

    LaunchedEffect(client) {
        val calendar = client ?: return@LaunchedEffect
        try {
            val writable = withContext(Dispatchers.IO) { calendar.calendars() }.filter { it.mayWrite }
            calendars = writable
            calendarId = (writable.firstOrNull { it.isDefault } ?: writable.firstOrNull())?.id.orEmpty()
            if (writable.isEmpty()) fault = "This account has no calendar Rampart may add to." to null
        } catch (e: Exception) {
            val title = "Could not read your calendars."
            fault = title to faultDetail(e, title)
        }
    }

    fun save() {
        val calendar = client ?: return
        when (val check = formDraft(form, calendarId)) {
            is FormCheck.Refused -> problem = check.reason
            is FormCheck.Ready -> {
                problem = null
                fault = null
                saving = true
                scope.launch {
                    try {
                        val id = withContext(Dispatchers.IO) { calendar.create(newEventObject(check.draft)) }
                        savedIn = calendars.orEmpty().firstOrNull { it.id == calendarId }?.name.orEmpty()
                        saved = CalendarJumpTarget(account, id, check.draft.start.toLocalDate())
                    } catch (e: Exception) {
                        val title = "The event was not saved."
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
            val done = saved
            if (done != null) {
                Text(
                    if (savedIn.isBlank()) "Added to your calendar." else "Added to $savedIn.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row {
                    TextButton(onClick = { CalendarJump.target = done }) { Text("Show in calendar") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                return@Column
            }
            if (client == null) {
                Text(
                    "This account keeps no calendar over JMAP, so there is nowhere to add the event.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                TextButton(onClick = onClose) { Text("Close") }
                return@Column
            }
            form.notes.forEach { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            OutlinedTextField(form.title, { form = form.copy(title = it) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { form = form.copy(allDay = !form.allDay) }) {
                Checkbox(checked = form.allDay, onCheckedChange = { form = form.copy(allDay = it) })
                Text("All day", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.startDate, { form = form.copy(startDate = it) }, label = { Text("Starts") }, singleLine = true, modifier = Modifier.weight(1f))
                if (!form.allDay) {
                    OutlinedTextField(form.startTime, { form = form.copy(startTime = it) }, label = { Text("At") }, singleLine = true, modifier = Modifier.width(96.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.endDate,
                    { form = form.copy(endDate = it) },
                    label = { Text(if (form.allDay) "Last day" else "Ends") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (!form.allDay) {
                    OutlinedTextField(form.endTime, { form = form.copy(endTime = it) }, label = { Text("At") }, singleLine = true, modifier = Modifier.width(96.dp))
                }
            }
            if (!form.allDay) {
                OutlinedTextField(form.timeZone, { form = form.copy(timeZone = it) }, label = { Text("Time zone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(form.location, { form = form.copy(location = it) }, label = { Text("Where") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val choices = calendars.orEmpty()
            if (choices.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    choices.forEach { each ->
                        FilterChip(
                            selected = calendarId == each.id,
                            onClick = { calendarId = each.id },
                            label = { Text(each.name, maxLines = 1) },
                            leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(calendarColour(each))) },
                        )
                    }
                }
            }
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            fault?.let { (title, detail) -> FaultText(title, detail) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { save() }, enabled = !saving && calendars != null) {
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

/** An event Rook proposed, waiting for the person to check it, with the account it is for. */
internal data class ProposedEvent(val form: EventForm, val backend: MailBackend?, val account: String)

/**
 * The cards Rook's propose_event made, oldest first, shown one at a time by [ProposedEventDialog].
 *
 * A holder rather than a parameter threaded through the window, the same way [CalendarJump]
 * is: the chat that makes them and the dialog that shows them are far apart.
 */
internal object ProposedEvents {
    var waiting by mutableStateOf<List<ProposedEvent>>(emptyList())

    /** The cards from one turn of the conversation. Call on the window's thread. */
    fun offer(tools: CalendarTools, backend: MailBackend?, account: String) {
        if (tools.proposed.isEmpty()) return
        waiting = waiting + tools.proposed.map { ProposedEvent(it, backend, account) }
    }
}

/** Rook's proposed event, as the same editable card, in front of the window. Drawn once, by the window. */
@Composable
internal fun ProposedEventDialog() {
    val next = ProposedEvents.waiting.firstOrNull() ?: return
    fun done() {
        ProposedEvents.waiting = ProposedEvents.waiting.drop(1)
    }
    AlertDialog(
        onDismissRequest = { done() },
        text = {
            Box(Modifier.widthIn(min = 420.dp, max = 520.dp).verticalScroll(rememberScrollState())) {
                EventCard(
                    initial = next.form,
                    backend = next.backend,
                    account = next.account,
                    heading = "Rook proposed an event",
                    onClose = { done() },
                    bare = true,
                )
            }
        },
        // The card carries its own Save and Discard, so the dialog adds no buttons of its own.
        confirmButton = {},
    )
}

/** What the composer needs for "Help me schedule": the account's session and its name for the reason line. */
internal data class ScheduleSource(val backend: MailBackend?, val accountName: String)

/** The composer's text with [block] added at the end of the person's own words, and the caret after it. */
internal fun proposalValue(current: TextFieldValue, block: String): TextFieldValue {
    val (text, caret) = insertProposal(current.text, block, quoteStart(current.text))
    return TextFieldValue(text, TextRange(caret))
}

/**
 * "Help me schedule", in the composer's toolbar.
 *
 * Reads the thread for how long and when (a model call, behind the same consent as the rest
 * of Rook, and skipped with the defaults when Rook is off or there is nothing to read), then
 * finds free times on this machine and hands them to [onInsert] as plain lines. What it had
 * to assume or could not do is said in a small note under the button.
 */
@Composable
internal fun HelpMeSchedule(
    source: ScheduleSource,
    replyContext: List<Turn>,
    /** The reply as typed so far, read at the moment the button is pressed. */
    typed: () -> String,
    account: String?,
    folder: String?,
    onInsert: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    var running by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var fault by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var packet by remember { mutableStateOf<String?>(null) }
    var agreed by remember { mutableStateOf(Assistant.agreed(CALENDAR_FEATURE)) }

    fun search(ask: ScheduleAsk, calendar: CalendarClient) {
        running = true
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { freeTimes(calendar, ask, zone, Instant.now()) }
                if (found.isNotEmpty()) onInsert(slotLines(found))
                note = listOfNotNull(ask.note, slotsNote(found.size, ask)).joinToString(" ").ifBlank { null }
            } catch (e: Exception) {
                val title = "Could not read your calendar."
                fault = title to faultDetail(e, title)
            } finally {
                running = false
                open = note != null || fault != null
            }
        }
    }

    fun ask(sending: String, calendar: CalendarClient) {
        val config = Assistant.config()
        val today = LocalDate.now(zone)
        running = true
        scope.launch {
            val read = try {
                val reply = withContext(Dispatchers.IO) { Llm.ask(config, Secrets.loadNamed(Assistant.KEY), sending) }
                Assistant.record(CALENDAR_FEATURE, reply.tokensIn, reply.tokensOut, config)
                scheduleAskOf(reply.text, today)
            } catch (e: Exception) {
                val title = "Rook could not read the thread, so these are 30 minute times over the next two weeks."
                fault = title to faultDetail(e, title)
                defaultAsk(today)
            }
            search(read, calendar)
        }
    }

    fun press() {
        note = null
        fault = null
        open = false
        val calendar = eventCalendarFor(source.backend)
        if (calendar == null) {
            note = noCalendarBecause(source.backend, source.accountName) ?: "This account has no calendar to read."
            open = true
            return
        }
        val today = LocalDate.now(zone)
        val words = typed()
        // Nothing to read means nothing to ask about, so the defaults need no model at all.
        if (replyContext.isEmpty() && words.isBlank()) {
            search(defaultAsk(today), calendar)
            return
        }
        val config = Assistant.config()
        val why = Assistant.whyNot(CALENDAR_FEATURE, config, account, folder)
        if (why != null) {
            search(defaultAsk(today, "$why These are 30 minute times over the next two weeks."), calendar)
            return
        }
        val built = ScheduleHelp.packet(config.model, today, replyContext, words)
        if (Assistant.agreed(CALENDAR_FEATURE)) ask(built, calendar) else packet = built
    }

    Box {
        TextButton(onClick = { press() }, enabled = !running) {
            Text(if (running) "Finding times" else "Help me schedule")
        }
        MenuLayer(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.widthIn(max = 340.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
                fault?.let { (title, detail) -> FaultText(title, detail) }
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    packet?.let { waiting ->
        PacketViewer(
            packet = waiting,
            agreed = agreed,
            onSend = {
                packet = null
                eventCalendarFor(source.backend)?.let { ask(waiting, it) }
            },
            onAgree = {
                Assistant.agree(CALENDAR_FEATURE)
                agreed = true
            },
            onDismiss = { packet = null },
        )
    }
}
