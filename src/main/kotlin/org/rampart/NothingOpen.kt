package org.rampart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * What the reading pane shows when no message is open.
 *
 * Plain data and callbacks only. The window already knows the unread count, what is
 * waiting, and what is left of today. This draws them. It does not read settings or
 * the mailbox, so a machine with no local copy still gets the date, the buttons and,
 * when the caller has them, the calendar lines.
 */

/** How many conversations "Waiting on you" lists. The rest stay on the dashboard. */
internal const val GLANCE_WAITING = 5

/** One thing still on today: when, then what. */
internal data class GlanceLine(val time: String, val title: String)

/**
 * The date as a heading: "Thursday, 1 October".
 *
 * The year is left off. This is today, and the year is not the part anyone is reading
 * for. The day and month follow [region], the same way the rest of the app writes a date.
 */
internal fun glanceHeading(date: LocalDate, region: Region): String {
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, region.locale)
    return weekday + ", " + formatMonthDay(region, date)
}

/** "3 unread in Inbox", or "Inbox is read" when there is nothing unread. */
internal fun inboxUnreadLine(count: Int): String =
    if (count <= 0) "Inbox is read" else "$count unread in Inbox"

/**
 * Unread conversations in Inbox for the account on screen, or the merged total.
 *
 * [unified] is the count the window already keeps for All inboxes. One account uses
 * that account's own Inbox, which is the number beside the folder, not a new query.
 */
internal fun glanceUnread(accountKey: String?, mailboxes: Map<String, List<Mailbox>>, unified: Int): Int =
    if (accountKey == null || accountKey == ALL_ACCOUNTS) {
        unified
    } else {
        folderFor("inbox", mailboxes[accountKey].orEmpty())?.unreadThreads ?: 0
    }

@Composable
internal fun NothingOpen(
    date: LocalDate,
    region: Region,
    unread: Int,
    /** Null when the figures are not there. An empty list is a quiet inbox. Either way the section is absent. */
    waiting: List<Summary>?,
    /** Empty when there is no calendar, or nothing left today. */
    stillToday: List<GlanceLine>,
    onOpen: (Summary) -> Unit,
    onWrite: () -> Unit,
    /** Null hides the button, which is how a switched-off assistant is drawn. */
    onToday: (() -> Unit)?,
    onDashboard: () -> Unit,
) {
    // Left, and only as wide as a column of text needs to be. Centred on a wide
    // window it would be a small block in a large empty field, which is the screen
    // this replaces. The scroll fills the pane; the words stay in the left column.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 36.dp),
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Text(glanceHeading(date, region), style = MaterialTheme.typography.headlineSmall)
            Text(
                inboxUnreadLine(unread),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )

            val due = waiting?.take(GLANCE_WAITING).orEmpty()
            if (due.isNotEmpty()) {
                Heading("Waiting on you", Modifier.padding(top = 26.dp))
                due.forEach { message ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onOpen(message) }
                            .padding(vertical = 7.dp, horizontal = 8.dp),
                    ) {
                        Text(
                            message.from.ifBlank { message.fromEmail },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            message.subject.ifBlank { "No subject" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            if (stillToday.isNotEmpty()) {
                Heading("Still today", Modifier.padding(top = 26.dp))
                stillToday.forEach { line ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            line.time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            line.title.ifBlank { "(no title)" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 26.dp),
            ) {
                OutlinedButton(onClick = onWrite) { Text("Write") }
                onToday?.let { OutlinedButton(onClick = it) { Text("Today, from Rook") } }
                OutlinedButton(onClick = onDashboard) { Text("Dashboard") }
            }
        }
    }
}
