package org.rampart

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
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
        // Taller than any window, because the point of this one is the whole body: a
        // heading, a list, a table, a quote, a rule and a picture the message carries.
        shoot("settings-about", 1200, 900, dark) { SettingsScreen(page = "about") }
        shoot("settings-signatures", 1200, 900, withArt) { SettingsScreen(page = "identities") }
        shoot("body", 820, 1500) {
            Message(
                summary = MESSAGES[1],
                body = Body(SAMPLE_HTML, null),
                attachments = listOf(
                    Attachment("b3", "signature.png", "image/png", 9_284, cid = "logo", inline = true),
                ),
                images = mapOf("b3" to sampleImage()),
                actions = MessageActions(archive = {}, junk = {}, trash = {}, star = {}),
                onLink = {},
            )
        }
        shoot("unread-only", 420, 420) {
            MessageList(
                MESSAGES.filterNot { it.seen },
                null,
                loading = false,
                title = "Inbox",
                rowActions = ROW_ACTIONS,
                unreadOnly = true,
            ) { _, _, _ -> }
        }
        shoot("unified", 1400, 900) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(
                    accounts = ACCOUNTS,
                    here = ALL_ACCOUNTS to allInboxes(6),
                    onSettings = {},
                    collapsed = false,
                    onToggleCollapsed = {},
                    onAddAccount = {},
                    onWrite = {},
                    onSelect = { _, _ -> },
                )
                VerticalDivider()
                MessageList(
                    MESSAGES.mapIndexed { i, m -> m.copy(account = ACCOUNTS[i % 2].key) },
                    null,
                    loading = false,
                    title = "All inboxes",
                    accountLabels = ACCOUNTS.associate { it.key to shortAccountName(it.name, it.email) },
                ) { _, _, _ -> }
                VerticalDivider()
                Message(summary = null, body = null, onLink = {})
            }
        }
        shoot("update", 1000, 700, dark) {
            Box(Modifier.fillMaxSize()) {
                Panes()
                UpdateCard("0.1.44", installing = false, note = null, onRestart = {}, onLater = {})
            }
        }
        shoot("update-installing", 1000, 700, dark) {
            Box(Modifier.fillMaxSize()) {
                Panes()
                UpdateCard("0.1.44", installing = true, note = null, onRestart = {}, onLater = {})
            }
        }
        shoot("palette", 1000, 700, dark) {
            Box(Modifier.fillMaxSize()) {
                Panes()
                CommandPalette(onClose = {}, onRun = {})
            }
        }
        shoot("shortcuts", 1000, 800, dark) {
            Box(Modifier.fillMaxSize()) {
                Panes()
                ShortcutsOverlay {}
            }
        }
        shoot("source", 900, 700, dark) {
            Message(
                summary = MESSAGES[1],
                body = null,
                source = SAMPLE_RAW,
                actions = MessageActions(archive = {}, junk = {}, trash = {}, star = {}),
                onLink = {},
            )
        }
        // A ported theme, and the one thing about it that cannot be checked by reading the
        // palette: whether the character in the corner is faint enough to read mail over.
        shoot("reader-themed", 1400, 900, withArt) { Panes() }
        shoot("settings", 1200, 900, withArt) { SettingsScreen("accounts") }
        shoot("settings-reading", 1200, 900) { SettingsScreen("reading") }
        shoot("settings-filters", 1200, 900) { SettingsScreen("filters") }
        shoot("composer", 1000, 640) {
            Composer(
                identities = listOf("you@example.org", "billing@example.org"),
                initial = replyTo(MESSAGES[1], Body(null, "Could you confirm the start time?"), "you@example.org")
                    .copy(
                        // With markers in it, because the point of this shot is the
                        // formatting bar and whether the marked words really draw bold.
                        body = "Yes, **ten past nine** works, and I will bring:\n" +
                            "- the *revised* quote\n- the signed copy\n" +
                            "Details are on [the page](https://example.org/booking).\n",
                        attachments = listOf(Attachment("b1", "Revised quote September.pdf", "application/pdf", 214_512)),
                    ),
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
            MessageList(
                MESSAGES.filterNot { it.seen },
                null,
                loading = false,
                title = "Inbox",
                rowActions = ROW_ACTIONS,
                unreadOnly = true,
            ) { _, _, _ -> }
            VerticalDivider()
            Message(
                summary = MESSAGES[1].copy(keywords = setOf("\$seen", "invoices", "groundworks")),
                body = Body(
                    SAMPLE_HTML,
                    null,
                    listUnsubscribe = "<https://lists.example.org/off?id=9>",
                    listUnsubscribePost = "List-Unsubscribe=One-Click",
                    authenticationResults = listOf("mx.example.org; spf=fail; dkim=none; dmarc=fail"),
                ),
                replyAll = true,
                thread = listOf(
                    MESSAGES[4].copy(threadId = "t1"),
                    MESSAGES[1],
                    MESSAGES[3].copy(threadId = "t1"),
                ),
                actions = MessageActions(archive = {}, junk = {}, trash = {}, star = {}),
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

/** Every row action present, so a shot shows the whole menu rather than part of it. */
private val ROW_ACTIONS = RowActions(
    reply = { _, _ -> },
    forward = {},
    archive = {},
    junk = {},
    trash = {},
    star = {},
    markRead = { _, _ -> },
)

/** Two rules that between them show every part of the row: a sentence, and a switch. */
private val SAMPLE_FILTERS = Script(
    listOf(
        Rule(
            id = "f1",
            name = "Invoices",
            tests = listOf(Test(Field.FROM, Match.CONTAINS, "billing@")),
            acts = listOf(Act.FileInto("Invoices"), Act.MarkRead),
        ),
        Rule(
            id = "f2",
            name = "Newsletters",
            tests = listOf(Test(Field.SUBJECT, Match.CONTAINS, "newsletter")),
            acts = listOf(Act.FileInto("Reading")),
            enabled = false,
        ),
    ),
    tail = "# Delivery probes, managed outside any builder.\nif address :matches \"to\" \"zz-canary@*\" { discard; stop; }",
)

private val MESSAGES = listOf(
    Summary("a", "Stalwart", "stalwart@example.org", "Your certificate renews in 7 days", "2026-09-16T09:12:00Z",
        "The certificate for mail.example.org will be renewed automatically on 23 September.", false),
    // A conversation rather than a single message, so the count on the row gets drawn.
    Summary("b", "Dana Whitfield", "dana@example.org", "Re: the quote for the Tuesday job", "2026-09-15T17:40:00Z",
        "That works for us. Tuesday morning is fine, and the crew will be there by eight.", true,
        keywords = setOf("\$seen", "groundworks", "invoices"), threadId = "t1", threadSize = 3),
    Summary("c", "Companies House", "companies@example.org", "Confirmation statement filed", "2026-09-15T11:02:00Z",
        "We have accepted your confirmation statement. No further action is needed.", true, flagged = true),
    Summary("d", "Hetzner", "hetzner@example.org", "Invoice 2026-4471", "2026-09-14T06:00:00Z",
        "Your invoice for September is attached and has been paid by direct debit.", true,
        keywords = setOf("\$seen", "invoices")),
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
<h2>What is included</h2>
<ul>
<li>Excavation and spoil removal</li>
<li>Base preparation to 150mm</li>
<li>Reinstatement of the verge</li>
</ul>
<table>
<tr><th>Item</th><th>Old rate</th><th>Current</th></tr>
<tr><td>Excavation, per day</td><td>640.00</td><td>665.00</td></tr>
<tr><td>Spoil removal, per load</td><td>210.00</td><td>218.00</td></tr>
</table>
<blockquote>
<p>Could you confirm the start time and whether we need to leave the gate open?</p>
</blockquote>
<hr>
<p>Best,<br>Dana</p>
<table><tr><td><img src="cid:logo" width="120"><br>Dana Whitfield<br>Whitfield Groundworks</td></tr></table>
</body></html>
""".trimIndent()

/** Enough of a real message to see that headers and body are told apart. */
/** The settings pane with fixtures, opened on one page. */
@Composable
private fun SettingsScreen(page: String) {
    SettingsPane(
            accounts = ACCOUNTS,
            identities = listOf(
                Identity(
                    "i1", "Justin Willhite", "justin@willhitestrategy.com",
                    htmlSignature = """<div style="color:#555"><b>Justin Willhite</b><br>""" +
                        """Willhite Strategy Group<br>""" +
                        """<a href="https://willhitestrategy.com">willhitestrategy.com</a></div>""",
                ),
                Identity("i2", "Skybox7", "admin@skybox7.com"),
            ),
            vacation = Vacation(
                enabled = true,
                from = "2026-12-24T00:00:00Z",
                subject = "Out of office until the 2nd",
                text = "I am away until 2 January and will not be picking up email. " +
                    "For anything urgent, please call the office.",
            ),
            vacationError = null,
            onVacation = {},
            signatureError = null,
            onSignature = { _, _ -> },
            onPickSignatureImage = { null },
            update = "0.1.25",
            notifyOnArrival = true,
            onNotifyOnArrival = {},
            onTheme = {},
            onAddAccount = {},
            filters = SAMPLE_FILTERS,
        filtersSupported = true,
        filtersSaving = false,
        filtersError = null,
        onFilters = {},
        onRestart = {},
            onClose = {},
            initialPage = page,
    )
}

private val SAMPLE_RAW = """
Return-Path: <dana@example.org>
Received: from mx.example.org (mx.example.org [203.0.113.24])
 by mail.example.org with ESMTPS id 4c2f9a
 for <you@example.com>; Tue, 15 Sep 2026 17:40:02 +0000
From: Dana Whitfield <dana@example.org>
To: You <you@example.com>
Subject: Re: the quote for the Tuesday job
Date: Tue, 15 Sep 2026 17:39:58 +0000
Message-ID: <a41f0c8e-2b77-4d31-9a0c-16f2e1b4d0aa@example.org>
Content-Type: text/plain; charset=utf-8

Hi,

That works for us. Tuesday morning is fine, and the crew will be there by
eight. If the gate is locked, the key safe code is the same as last time.

Dana
""".trimIndent()
