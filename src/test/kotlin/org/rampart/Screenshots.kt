package org.rampart

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.renderComposeScene
import java.io.File
import kotlin.test.Test

/**
 * Renders the real panes to PNG on a box with no screen, which is the only way this app
 * gets looked at before there is a Windows build. Sample data only: never point this at a
 * live mailbox, the output is meant to be safe to hand round.
 */
class Screenshots {
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun shoot(name: String, width: Int, height: Int, dark: Boolean = false, content: @Composable () -> Unit) {
        val image = renderComposeScene(width, height) {
            MaterialTheme(colorScheme = if (dark) RampartDarkColors else RampartColors) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
        File(out, "$name.png").writeBytes(image.encodeToData()!!.bytes)
    }

    @Test
    fun screenshots() {
        shoot("connect", 900, 760) { Connect(saved = SAVED, canRemember = true) { _, _ -> } }
        shoot("reader", 1400, 900) { Panes(dark = false) }
        shoot("reader-dark", 1400, 900, dark = true) { Panes(dark = true) }
        shoot("composer", 1000, 640) {
            Composer(
                identities = listOf("you@example.org", "billing@example.org"),
                initial = replyTo(MESSAGES[1], Body(null, "Could you confirm the start time?"), "you@example.org"),
                sending = false,
                error = null,
                onDiscard = {},
                onSend = {},
            )
        }
    }

    @Composable
    private fun Panes(dark: Boolean) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(
                accounts = ACCOUNTS,
                here = ACCOUNTS[0].key to MAILBOXES[0],
                dark = dark,
                onToggleDark = {},
                onAddAccount = {},
                onWrite = {},
                onSelect = { _, _ -> },
            )
            VerticalDivider()
            MessageList(MESSAGES, MESSAGES[1], loading = false) {}
            VerticalDivider()
            Message(MESSAGES[1], Body(SAMPLE_HTML, null)) {}
        }
    }
}

private val SAVED = listOf(
    SavedAccount("Work", "mail.example.org", "you@example.org"),
    SavedAccount("Personal", "jmap.example.net", "you@example.net"),
)

private val MAILBOXES = listOf(
    Mailbox("1", "Inbox", "inbox", 3),
    Mailbox("2", "Archive", "archive", 0),
    Mailbox("3", "Drafts", "drafts", 1),
    Mailbox("4", "Junk", "junk", 12),
    Mailbox("5", "Sent", "sent", 0),
    Mailbox("6", "Trash", "trash", 0),
)

private val ACCOUNTS = listOf(
    AccountMailboxes("work", "Work", MAILBOXES),
    AccountMailboxes("personal", "Personal", MAILBOXES.take(3)),
)

private val MESSAGES = listOf(
    Summary("a", "Stalwart", "stalwart@example.org", "Your certificate renews in 7 days", "2026-09-16T09:12:00Z",
        "The certificate for mail.example.org will be renewed automatically on 23 September.", false),
    Summary("b", "Dana Whitfield", "dana@example.org", "Re: the quote for the Tuesday job", "2026-09-15T17:40:00Z",
        "That works for us. Tuesday morning is fine, and the crew will be there by eight.", true),
    Summary("c", "Companies House", "companies@example.org", "Confirmation statement filed", "2026-09-15T11:02:00Z",
        "We have accepted your confirmation statement. No further action is needed.", true),
    Summary("d", "Hetzner", "hetzner@example.org", "Invoice 2026-4471", "2026-09-14T06:00:00Z",
        "Your invoice for September is attached and has been paid by direct debit.", true),
    Summary("e", "Alex Moreno", "alex@example.org", "Photos from the site visit", "2026-09-13T20:15:00Z",
        "Eight shots of the rear elevation, and one of the damp patch we talked about.", true),
)

private val SAMPLE_HTML = """
<html><body style="font-family:Helvetica">
<p>Hi Justin,</p>
<p>That works for us. Tuesday morning is fine, and the crew will be there by
<strong>eight</strong>. If the gate is locked, the key safe code is the same as last time.</p>
<p>One thing worth flagging: the quote has the <em>old</em> rate on it. The current
schedule is on our site at <a href="https://example.com/rates">example.com/rates</a>,
and the difference is about four percent.</p>
<img src="https://tracker.example.net/open.gif" width="1" height="1">
<blockquote>
<p>Could you confirm the start time and whether we need to leave the gate open?</p>
</blockquote>
<p>Best,<br>Dana</p>
<table><tr><td>Dana Whitfield</td></tr><tr><td>Whitfield Groundworks</td></tr></table>
</body></html>
""".trimIndent()
