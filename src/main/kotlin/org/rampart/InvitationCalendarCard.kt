package org.rampart

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * What the invitation card needs to reach the calendar: the account's own session, which
 * account that is, the message's calendar part, and every address that counts as this
 * account on a guest list.
 */
internal data class InvitationContext(
    val backend: MailBackend?,
    val account: String,
    val calendarPart: Attachment?,
    val addresses: List<String>,
)

/** Where "Show in calendar" asked the Calendar page to go. */
internal data class CalendarJumpTarget(
    val account: String,
    val eventId: String,
    val date: LocalDate,
    /** Set once the page has opened the event, so it is not opened again on every reload. */
    val opened: Boolean = false,
)

/**
 * The one request the invitation card makes of the Calendar page.
 *
 * A holder rather than a parameter threaded through the window, the same way [FilesPage]
 * is: the card and the page are far apart, and the window only has to open the page when
 * this is set, which [CalendarJumpFollows] does.
 */
internal object CalendarJump {
    var target by mutableStateOf<CalendarJumpTarget?>(null)
}

/** The calendar client for [backend], or null when it keeps no calendar over JMAP. */
internal fun calendarClientFor(backend: MailBackend?): InvitationCalendarClient? =
    (backend as? Jmap)?.takeIf { it.advertises(CALENDARS) }?.let(::InvitationCalendarClient)

/** The message's calendar part, which is what CalendarEvent/parse is given. */
internal fun calendarPartIn(attachments: List<Attachment>): Attachment? =
    attachments.firstOrNull { it.type.substringBefore(';').trim().equals("text/calendar", ignoreCase = true) }

/**
 * Opens the Calendar page when an invitation asks to show its meeting, and forgets the
 * request when the page closes, so the page goes back to following the sidebar.
 */
@Composable
internal fun CalendarJumpFollows(calendarOpen: Boolean, openCalendar: () -> Unit) {
    val open by rememberUpdatedState(openCalendar)
    LaunchedEffect(CalendarJump.target) {
        if (CalendarJump.target?.opened == false) open()
    }
    LaunchedEffect(calendarOpen) {
        if (!calendarOpen) CalendarJump.target = null
    }
}

/**
 * The line on an invitation card about the calendar: whether the meeting is in it, and what
 * can be done about that.
 *
 * Drawn only for an account whose server keeps a calendar over JMAP. Anywhere else there is
 * nothing to say: the answer goes by email exactly as it always has, and a line saying the
 * calendar is unavailable on every IMAP invitation would be noise about a thing nobody asked.
 */
@Composable
internal fun InvitationCalendarLine(invitation: Invitation, context: InvitationContext?) {
    val client = remember(context?.backend) { calendarClientFor(context?.backend) }
    if (context == null || client == null || invitation.uid.isBlank()) return
    val scope = rememberCoroutineScope()
    var match by remember(invitation, client) { mutableStateOf<CalendarMatch?>(null) }
    var checked by remember(invitation, client) { mutableStateOf(false) }
    var fault by remember(invitation, client) { mutableStateOf<Pair<String, String?>?>(null) }
    var busy by remember(invitation, client) { mutableStateOf(false) }
    var reread by remember { mutableIntStateOf(0) }

    LaunchedEffect(client, invitation, reread) {
        try {
            match = withContext(Dispatchers.IO) { client.find(invitation.uid, context.addresses) }
            fault = null
        } catch (e: Exception) {
            fault = "Could not check your calendar for this meeting." to whyFailed(e)
        } finally {
            checked = true
        }
    }

    /**
     * Runs one of the card's calendar actions off the window's thread.
     *
     * Read again only on success, for the reason CalendarPane.write gives: the read clears
     * [fault] when it succeeds, and would wipe the failure a moment after it appeared.
     */
    fun act(action: CalendarAction) {
        val found = match ?: return
        if (action == CalendarAction.SHOW) {
            CalendarJump.target = CalendarJumpTarget(context.account, found.eventId, jumpDate(invitation, found) ?: LocalDate.now(Regional.zone()))
            return
        }
        busy = true
        scope.launch {
            val failed: String? = withContext(Dispatchers.IO) {
                try {
                    when (action) {
                        CalendarAction.UPDATE -> {
                            val blob = context.calendarPart?.blobId
                            if (blob == null) {
                                "Rampart could not find the invitation's calendar part in this message."
                            } else {
                                val parsed = client.parse(blob, invitation.uid)
                                client.update(found.eventId, organiserUpdatePatch(found.raw, parsed), schedule = false)?.sentence
                            }
                        }
                        CalendarAction.REMOVE -> client.remove(found.eventId)?.sentence
                        CalendarAction.REMOVE_ONE -> {
                            val event = found.event
                            val key = invitation.recurrenceId?.let { at -> event?.let { recurrenceKey(at, it) } }
                            if (event == null || key == null) {
                                "Rampart could not work out which time of the meeting this message is about."
                            } else {
                                client.update(found.eventId, excludedPatch(event, key), schedule = false)?.sentence
                            }
                        }
                        CalendarAction.SHOW -> null
                    }
                } catch (e: JmapError) {
                    e.message ?: "The server gave no reason."
                }
            }
            busy = false
            if (failed == null) {
                reread++
            } else {
                fault = when (action) {
                    CalendarAction.UPDATE -> "Your calendar was not updated."
                    CalendarAction.REMOVE -> "The meeting is still in your calendar."
                    else -> "That time is still in your calendar."
                } to failed
            }
        }
    }

    Column {
        Spacer(Modifier.height(10.dp))
        val problem = fault
        if (problem != null) {
            FaultText(problem.first, problem.second)
        }
        if (!checked) {
            Text(
                "Looking for this meeting in your calendar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            return@Column
        }
        // A failed lookup has said so above, and guessing at the calendar after one would
        // offer to add or remove a meeting on no evidence at all.
        if (problem != null && match == null) return@Column
        val line = calendarLine(invitation, match)
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (line.actions.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                line.actions.forEach { action ->
                    TextButton(onClick = { act(action) }, enabled = !busy) { Text(action.label) }
                }
            }
        }
    }
}
