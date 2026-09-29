package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Follow-up flags on screen: the dialog, the Follow up row in the sidebar, and the check
 * that announces a due flag. The rules and the keywords are in FollowUp.kt.
 *
 * The reminder only fires while Rampart is running, the same ceiling snooze has: nothing
 * on either protocol can wake a client. The flag and the list are on the server, so every
 * device sees them, and a flag that came due while Rampart was shut is announced at the
 * next start.
 */

/** How often each account is asked for its flags. The server is the list, so this is a poll. */
private const val CHECK_EVERY_MS = 5 * 60_000L

/** At most this many "only if no reply" flags are looked at per account per check. */
private const val REPLY_CHECKS_PER_PASS = 25

/**
 * The window's follow-up state.
 *
 * [backend], [accountOf] and [own] are the window's own lookups, passed in so this file does
 * not reach into Main.kt. [repaint] is told the keywords a message has once the server has
 * accepted a change, and only then: a flag the server refused must not look set.
 */
@Stable
internal class FollowUps(
    private val scope: CoroutineScope,
    private val backend: (String) -> MailBackend?,
    private val accountOf: (Summary) -> String?,
    /** The account's own addresses, lowercase. */
    private val own: (String) -> Set<String>,
    private val report: (String, String?) -> Unit,
    private val repaint: (account: String, id: String, keywords: Set<String>) -> Unit,
) {
    /** The message the dialog is open for, or null. */
    var asking by mutableStateOf<Summary?>(null)

    /** Flagged messages across the accounts, and how many of them are due. */
    var flagged by mutableStateOf(0)
        private set
    var due by mutableStateOf(0)
        private set

    /** Why the last check did not finish, as one sentence, or null. */
    var problem by mutableStateOf<String?>(null)
        private set

    private val checkedAt = HashMap<String, Long>()
    private val byAccount = HashMap<String, List<Summary>>()

    fun ask(message: Summary) {
        asking = message
    }

    /** Whether "only if no reply" can be offered for [message], or the sentence saying why not. */
    fun noReplyRefusal(message: Summary): String? {
        val key = accountOf(message) ?: return "Rampart could not tell which account this message is in."
        if (!sentByMe(message, own(key))) return "Only for a message you sent."
        // An IMAP conversation is one folder, and the reply lands in another one.
        if (backend(key) is Imap) return "This server cannot see a reply that lands in another folder, so it cannot cancel the flag."
        return null
    }

    fun set(message: Summary, due: Instant, noReply: Boolean) =
        change(message, setFollowUp(message.keywords, due, noReply), "The follow-up could not be set.")

    fun clear(message: Summary) =
        change(message, clearFollowUp(message.keywords), "The follow-up could not be cleared.")

    private fun change(message: Summary, change: KeywordChange, failure: String) {
        val key = accountOf(message)
        val server = key?.let(backend)
        if (key == null || server == null) {
            report(failure, "Rampart could not tell which account this message is in.")
            return
        }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    Result.success(server.setKeywords(listOf(message.id), change.add, change.remove))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            val refused = outcome.exceptionOrNull()
            if (refused != null) {
                report(failure, whyFailed(refused))
                return@launch
            }
            val after = change.appliedTo(message.keywords)
            repaint(key, message.id, after)
            // The row and the reader are right already; the sidebar count catches up at once
            // rather than at the next poll.
            val list = byAccount[key].orEmpty().filterNot { it.id == message.id }
            byAccount[key] = if (isFollowUp(after)) list + message.copy(keywords = after) else list
            recount()
        }
    }

    private fun recount(now: Instant = Instant.now()) {
        val all = byAccount.values.flatten()
        flagged = all.size
        due = dueFollowUps(all, now).size
    }

    /**
     * Asks each account for its flags, cancels the answered "only if no reply" ones, and
     * announces each newly due flag once. Called from the window's minute loop, and does its
     * real work at most every few minutes per account.
     */
    suspend fun check(accounts: List<Pair<String, MailBackend>>) {
        val started = System.currentTimeMillis()
        var failed: String? = null
        var looked = false
        for ((key, server) in accounts) {
            if (started - (checkedAt[key] ?: 0L) < CHECK_EVERY_MS) continue
            checkedAt[key] = started
            looked = true
            val found = try {
                withContext(Dispatchers.IO) { server.withKeyword(FOLLOW_UP, 500) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = "Follow-ups could not be checked: " + whyFailed(e)
                continue
            }
            val (left, cancelProblem) = cancelAnswered(key, server, found)
            if (cancelProblem != null) failed = cancelProblem
            byAccount[key] = left
            val now = Instant.now()
            val remembered = withContext(Dispatchers.IO) { FollowUpNotices.remembered(key) }
            val (fresh, keep) = toAnnounce(dueFollowUps(left, now), left, remembered)
            if (keep != remembered) withContext(Dispatchers.IO) { FollowUpNotices.remember(key, keep) }
            fresh.forEach { announce(key, it) }
        }
        dropGone(accounts.map { it.first }.toSet())
        if (looked) problem = failed
        recount()
    }

    private fun dropGone(keys: Set<String>) {
        byAccount.keys.retainAll(keys)
    }

    /**
     * Takes the flag off every "only if no reply" message somebody has answered.
     *
     * JMAP only: the thread there spans folders, so the answer in the Inbox is found for a
     * message in Sent. Returns what is still flagged, and a sentence when a clear failed.
     */
    private suspend fun cancelAnswered(key: String, server: MailBackend, found: List<Summary>): Pair<List<Summary>, String?> {
        if (server is Imap) return found to null
        val mine = own(key)
        val waiting = found.filter { m -> followUpIn(m.keywords)?.noReply == true && sentByMe(m, mine) }
            .take(REPLY_CHECKS_PER_PASS)
        if (waiting.isEmpty()) return found to null
        val cancelled = HashSet<String>()
        var problem: String? = null
        for (m in waiting) {
            try {
                val answered = withContext(Dispatchers.IO) {
                    answeredBySomeoneElse(m, server.thread(m.threadId), mine)
                }
                if (!answered) continue
                val change = clearFollowUp(m.keywords)
                withContext(Dispatchers.IO) { server.setKeywords(listOf(m.id), change.add, change.remove) }
                cancelled += m.id
                repaint(key, m.id, change.appliedTo(m.keywords))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = "A follow-up that was answered could not be cleared: " + whyFailed(e)
            }
        }
        return found.filterNot { it.id in cancelled } to problem
    }

    private fun announce(key: String, message: Summary) {
        val subject = message.subject.ifBlank { "(no subject)" }
        val who = message.from.ifBlank { message.fromEmail }
        NewMailNotices.shell.notify("Follow up: $subject", who) {
            NewMailNotices.opens.trySend(MailRef(key, message.copy(account = key)))
        }
    }
}

/** One per window, like the other window-wide state. */
@Composable
internal fun rememberFollowUps(
    scope: CoroutineScope,
    backend: (String) -> MailBackend?,
    accountOf: (Summary) -> String?,
    own: (String) -> Set<String>,
    report: (String, String?) -> Unit,
    repaint: (String, String, Set<String>) -> Unit,
): FollowUps = remember { FollowUps(scope, backend, accountOf, own, report, repaint) }

/** The row menu's and the reader's entry, which says whether a flag is already there. */
internal fun followUpMenuLabel(keywords: Collection<String>): String =
    if (isFollowUp(keywords)) "Change follow-up" else "Follow up"

/** What the Follow up view is called above the list. */
internal const val FOLLOW_UP_VIEW = "Follow up"

private val CHOICE_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.UK)

/**
 * The Follow up row in the sidebar, shown while anything is flagged or the view is open.
 *
 * A row that is there with nothing in it would be one more thing to look at for nothing,
 * so it appears with the first flag. The count is how many are due now.
 */
@Composable
internal fun FollowUpRow(state: FollowUps, collapsed: Boolean, selected: Boolean, onOpen: () -> Unit) {
    if (state.flagged == 0 && !selected && state.problem == null) return
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(32.dp)
                .clip(MaterialTheme.shapes.small)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .rowHover(showWash = !selected)
                .clickable(onClick = onOpen)
                .padding(start = if (collapsed) 0.dp else 10.dp, end = if (collapsed) 0.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
        ) {
            Icon(
                RampartIcons.Calendar,
                contentDescription = if (collapsed) FOLLOW_UP_VIEW else null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            if (!collapsed) {
                Spacer(Modifier.width(10.dp))
                Text(
                    FOLLOW_UP_VIEW,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.due > 0) {
                    Text(
                        state.due.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        val problem = state.problem
        if (!collapsed && problem != null) {
            Text(
                problem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * Tomorrow, in 3 days, next week, or a date and time typed in, and "only if no reply" for
 * a message the person sent.
 *
 * The named times are the snooze's, and the typed date is read by the schedule-send
 * dialog's own parser ([parseSchedule]) into the same field it uses ([ClockField]), so
 * there is one idea of "a time" in the app rather than three.
 */
@Composable
internal fun FollowUpDialog(state: FollowUps) {
    val message = state.asking ?: return
    CoverBody(true)
    val now = remember(message) { ZonedDateTime.now() }
    val current = remember(message) { followUpIn(message.keywords) }
    val refusal = remember(message) { state.noReplyRefusal(message) }
    var noReply by remember(message) { mutableStateOf(current?.noReply == true && refusal == null) }
    var date by remember(message) { mutableStateOf("") }
    var time by remember(message) { mutableStateOf("09:00") }
    var problem by remember(message) { mutableStateOf<String?>(null) }
    fun done() {
        state.asking = null
    }
    AlertDialog(
        onDismissRequest = { done() },
        title = { Text(if (current != null) "Change follow-up" else "Follow up") },
        text = {
            Column(Modifier.widthIn(min = 300.dp, max = 440.dp)) {
                if (current != null) {
                    Text(followUpText(current.due, now), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    "The flag is kept on the server, so every device sees it. Rampart reminds you " +
                        "while it is open, and at the next start if it was closed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
                FollowUpWhen.entries.forEach { choice ->
                    val at = remember(now, choice) { choice.dueAt(now) }
                    TextButton(
                        onClick = {
                            state.set(message, at.toInstant(), noReply)
                            done()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(choice.label)
                            Text(
                                at.format(CHOICE_CLOCK),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Or a date and time",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(6.dp))
                ClockField(date, "yyyy-MM-dd") {
                    date = it
                    problem = null
                }
                Spacer(Modifier.height(6.dp))
                ClockField(time, "HH:mm") {
                    time = it
                    problem = null
                }
                problem?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = refusal == null) { noReply = !noReply },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = noReply, onCheckedChange = { noReply = it }, enabled = refusal == null)
                    Text("Only if no reply", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    refusal ?: "A reply from anyone else takes the flag off by itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (current != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        state.clear(message)
                        done()
                    }) { Text("Clear follow-up") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val parsed = parseSchedule(date, time, ZonedDateTime.now())) {
                    is ScheduleWhen.At -> {
                        state.set(message, Instant.ofEpochMilli(parsed.millis), noReply)
                        done()
                    }
                    is ScheduleWhen.Problem -> problem = parsed.message
                }
            }) { Text("Set date") }
        },
        dismissButton = { TextButton(onClick = { done() }) { Text("Cancel") } },
    )
}
