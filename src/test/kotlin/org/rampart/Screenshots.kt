package org.rampart

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
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

    private fun shoot(
        name: String,
        width: Int,
        height: Int,
        theme: Theme = THEMES.first(),
        content: @Composable () -> Unit,
    ) {
        val image = renderComposeScene(width, height) {
            CompositionLocalProvider(LocalRampartTheme provides theme) {
                MaterialTheme(colorScheme = theme.scheme(), typography = RampartTypography) {
                    Surface(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        File(out, "$name.png").writeBytes(image.encodeToData()!!.bytes)
    }

    @Test
    fun screenshots() {
        val dark = theme("rampart-dark")
        val withArt = theme("aincrad")
        shoot("connect", 900, 760) { Connect(saved = SAVED, canRemember = true) { _, _ -> } }
        shoot("reader", 1400, 900) { Panes() }
        shoot("reader-dark", 1400, 900, dark) { Panes() }
        shoot("sidebar-collapsed", 1400, 900, dark) { Panes(collapsed = true) }
        // A ported theme, and the one thing about it that cannot be checked by reading the
        // palette: whether the character in the corner is faint enough to read mail over.
        shoot("reader-themed", 1400, 900, withArt) { Panes() }
        shoot("settings", 1400, 900, withArt) {
            SettingsPane(
                accounts = ACCOUNTS,
                update = "0.1.25",
                notifyOnArrival = true,
                onNotifyOnArrival = {},
                onTheme = {},
                onAddAccount = {},
                onRestart = {},
                onClose = {},
            )
        }
        shoot("composer", 1000, 640) {
            Composer(
                identities = listOf("you@example.org", "billing@example.org"),
                initial = replyTo(MESSAGES[1], Body(null, "Could you confirm the start time?"), "you@example.org")
                    .copy(attachments = listOf(Attachment("b1", "Revised quote September.pdf", "application/pdf", 214_512))),
                sending = false,
                error = null,
                onDiscard = {},
                onSend = {},
                onAttach = { emptyList() },
            )
        }
    }

    private fun theme(key: String) = THEMES.first { it.key == key }

    /** A real bundled PNG, so the drawing is exercised rather than a blank placeholder. */
    private fun sampleImage() = Screenshots::class.java.classLoader
        .getResourceAsStream("art/chompy.png")!!.use {
            org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()
        }

    @Composable
    private fun Panes(collapsed: Boolean = false) {
        Column(Modifier.fillMaxSize()) {
        SearchBar(
            query = "invoice",
            focusRequester = androidx.compose.ui.focus.FocusRequester(),
            onFocusChanged = {},
            onQueryChange = {},
            onSearch = {},
        )
        Row(Modifier.fillMaxSize()) {
            Sidebar(
                accounts = ACCOUNTS,
                here = ACCOUNTS[0].key to MAILBOXES[0],
                onSettings = {},
                collapsed = collapsed,
                onToggleCollapsed = {},
                onAddAccount = {},
                onWrite = {},
                onSelect = { _, _ -> },
            )
            VerticalDivider()
            MessageList(MESSAGES, MESSAGES[1], loading = false, title = "Inbox") {}
            VerticalDivider()
            Message(
                summary = MESSAGES[1],
                body = Body(SAMPLE_HTML, null),
                replyAll = true,
                thread = listOf(
                    MESSAGES[4].copy(threadId = "t1"),
                    MESSAGES[1],
                    MESSAGES[3].copy(threadId = "t1"),
                ),
                actions = MessageActions(archive = {}, junk = {}, trash = {}),
                attachments = listOf(
                    Attachment("b1", "Revised quote September.pdf", "application/pdf", 214_512),
                    Attachment("b2", "rear-elevation.jpg", "image/jpeg", 1_882_100),
                    Attachment("b3", "signature.png", "image/png", 9_284, cid = "logo", inline = true),
                ),
                // The one the message carries is drawn; the other two stay in the list to
                // save. Getting that backwards would offer a picture twice and show none.
                images = mapOf("b3" to sampleImage()),
                onLink = {},
            )
        }
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
    AccountMailboxes("work", "Willhite Strategy", "justin@willhitestrategy.com", MAILBOXES),
    AccountMailboxes("personal", "Skybox7", "admin@skybox7.com", MAILBOXES.take(3)),
)

private val MESSAGES = listOf(
    Summary("a", "Stalwart", "stalwart@example.org", "Your certificate renews in 7 days", "2026-09-16T09:12:00Z",
        "The certificate for mail.example.org will be renewed automatically on 23 September.", false),
    // A conversation rather than a single message, so the count on the row gets drawn.
    Summary("b", "Dana Whitfield", "dana@example.org", "Re: the quote for the Tuesday job", "2026-09-15T17:40:00Z",
        "That works for us. Tuesday morning is fine, and the crew will be there by eight.", true,
        threadId = "t1", threadSize = 3),
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
