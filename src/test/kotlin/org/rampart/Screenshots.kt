package org.rampart

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
        pack: IconPack = LineIcons,
        content: @Composable () -> Unit,
    ) {
        val image = renderComposeScene(width, height) {
            CompositionLocalProvider(
                LocalRampartTheme provides theme,
                LocalIconPack provides pack,
            ) {
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
        // Bulwark's tintListRowsByTag, which is a setting here. Both states side by side,
        // because the whole question is whether the tint reads as information or as a
        // fault, and that cannot be judged from either one alone.
        shoot("tinted-rows", 900, 460) {
            Row(Modifier.fillMaxSize()) {
                listOf(false, true).forEach { tinted ->
                    Box(Modifier.weight(1f)) {
                        CompositionLocalProvider(
                            LocalTintRowsByTag provides tinted,
                            LocalTagColours provides mapOf("invoices" to 0xFF2F5D96L, "groundworks" to 0xFF4F6D3AL),
                        ) {
                            MessageList(MESSAGES, null, loading = false, title = if (tinted) "Tinted" else "Plain") { _, _, _ -> }
                        }
                    }
                    VerticalDivider()
                }
            }
        }
        shoot("reader-dark", 1400, 900, dark) { Panes() }
        shoot("sidebar-collapsed", 1400, 900, dark) { Panes(collapsed = true) }
        // Taller than any window, because the point of this one is the whole body: a
        // heading, a list, a table, a quote, a rule and a picture the message carries.
        shoot("settings-about", 1200, 900, dark) { SettingsScreen(page = "about") }
        // A layout table doing what a layout table does: a banner, six figures across
        // with their labels, then a real table of data underneath.
        val report =
            """<table width="100%" bgcolor="#F4F1EC"><tr><td>""" +
            """<table width="100%" bgcolor="#3B1F0B"><tr><td align="center">""" +
            """<h2>DUCHAMP</h2><div>HEALDSBURG</div></td></tr></table>""" +
            """<div>MORNING REPORT</div><h3>Friday, September 18, 2026</h3>""" +
            """<table width="100%"><tr>""" +
            """<td align="center" bgcolor="#ffffff"><h3>95.0%</h3><div>OCCUPANCY TONIGHT</div></td>""" +
            """<td align="center" bgcolor="#ffffff"><h3>19/20</h3><div>ROOMS TONIGHT</div></td>""" +
            """<td align="center" bgcolor="#ffffff"><h3>37</h3><div>GUEST COUNT</div></td>""" +
            """<td align="center" bgcolor="#ffffff"><h3>9</h3><div>ARRIVALS</div></td>""" +
            """<td align="center" bgcolor="#ffffff"><h3>5</h3><div>DEPARTURES</div></td>""" +
            """<td align="center" bgcolor="#ffffff"><h3>10</h3><div>STAYOVERS</div></td>""" +
            """</tr></table>""" +
            """<table width="100%"><tr><th>Guest</th><th>Room</th><th>What they asked for</th></tr>""" +
            """<tr><td>Brendan Goodwin</td><td>13</td><td>Arriving around 12p, white Subaru.</td></tr>""" +
            """<tr><td>Melissa OBrien</td><td>4</td><td>In town for a wedding, sent winery list.</td></tr>""" +
            """</table></td></tr></table>"""
        // The undo strip, which now drains. Shot at the start of the drain, so what this
        // proves is that the fill sits behind the label rather than over it.
        shoot("undo-bar", 700, 120) {
            Column {
                UndoBar(text = "1 message archived.", seconds = 30, restartOn = "a", onUndo = {}, onDismiss = {})
                UndoBar(text = "Sending.", seconds = 30, restartOn = "b", onUndo = {})
                // Zero seconds is the "until I dismiss it" choice: no fill at all.
                UndoBar(text = "12 messages moved to Archive.", seconds = 0, restartOn = "c", onUndo = {}, onDismiss = {})
            }
        }
        // A reply that sets a background and leaves its text colour to the client, which
        // is the shape that came out as an empty grey box in 0.1.114.
        val plainReply =
            """<div style="width:100%;background:#f4f4f4"><div style="padding:16px">""" +
                "<p>Mike, it has been 10 weeks and no comms from you. Can you give me an update " +
                "on where the ADA and privacy work stands?</p><p>Thanks,<br>Mark</p>" +
                """<blockquote style="border-left:2px solid #ccc;padding-left:10px">""" +
                "<p>On 4 July, Mike wrote:</p><p>We will have the first pass over to you shortly.</p>" +
                "</blockquote></div></div>"
        shoot("plain-reply-dark", 900, 420, dark) {
            Message(summary = MESSAGES[1], body = Body(plainReply, null), onLink = {})
        }
        // All five, on a dark theme and a light one, because the only question about a
        // loader is whether it reads against the surface it is on.
        listOf("loaders" to null, "loaders-dark" to dark).forEach { (name, which) ->
            shoot(name, 700, 110, which ?: theme("rampart-light")) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Loader.entries.forEach { each ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spinner(which = each, size = 34.dp)
                            Text(each.label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        shoot("report", 980, 640) {
            Message(summary = MESSAGES[0], body = Body(report, null), onLink = {})
        }
        // The same message in a dark window. It paints its own page, so it must arrive
        // looking exactly as it did above: inverting it turned the brown header pink.
        shoot("report-dark", 980, 640, dark) {
            Message(summary = MESSAGES[0], body = Body(report, null), onLink = {})
        }
        shoot("contacts", 1000, 760, dark) {
            ContactsPane(
                contacts = listOf(
                    Contact("1", "Dana Reyes", listOf("dana@example.test"), listOf("+1 555 0100"), "Reyes and Co"),
                    Contact("2", "Sam Okafor", listOf("sam@example.test"), organisation = "Okafor Supply"),
                    Contact("3", "", listOf("hello@example.test")),
                ),
                loading = false,
                error = null,
                onSave = {},
                onDelete = {},
                onWrite = {},
            )
        }
        shoot("settings-signatures", 1200, 900, withArt) { SettingsScreen(page = "identities") }
        // The header and the panel behind Show details, on a message that passes every
        // check: a pass has to be as plain to read as a failure.
        shoot("details", 1000, 620, dark) {
            Message(
                summary = MESSAGES[1],
                body = Body(
                    "<p>The report is below.</p>",
                    null,
                    messageId = listOf("010001a0b4d211ba-0171d9a7@email.amazonses.com"),
                    to = listOf("markl@americantank.com", "tom@duchamphotel.com",
                        "michelle@duchamphotel.com", "stay@duchamphotel.com"),
                    authenticationResults = listOf("""mx.blueprint.example; dkim=pass header.d=blueprint.example header.s=s1; spf=pass smtp.mailfrom=cfbounces@amazonses.com; dmarc=pass (p=none) header.from=blueprint.example"""),
                    size = 155_781L,
                    sentAt = "Fri, 18 Sep 2026 07:01:01 -0700",
                    received = listOf("""from a48-96.smtp-out.amazonses.com (a48-96.smtp-out.amazonses.com [54.240.48.96]) by mx.blueprint.example"""),
                ),
                showDetails = true,
                onLink = {},
            )
        }
        shoot("dashboard", 1100, 1000, dark) {
            val today = java.time.LocalDate.of(2026, 9, 18)
            // A month that looks like a month: busier on weekdays, quiet at the weekend.
            val arrivals = (29 downTo 0).map { back ->
                val day = today.minusDays(back.toLong())
                val weekend = day.dayOfWeek.value >= 6
                DayCount(day, if (weekend) 2 + back % 3 else 9 + (back * 7) % 23)
            }
            val answers = arrivals.map { DayCount(it.day, (it.count / 3).coerceAtMost(9)) }
            DashboardPane(
                stats = MailStats(
                    received = arrivals,
                    sent = answers,
                    junk = 41,
                    arrived = arrivals.sumOf { it.count } + 41,
                    topSenders = listOf(
                        Counted("Dana Whitfield", 34, "dana@example.org"),
                        Counted("Alex Moreau", 21, "alex@example.org"),
                        Counted("Cass Nguyen", 14, "cass@example.org"),
                        Counted("billing@supplier.example", 9, "billing@supplier.example"),
                        Counted("Priya Raman", 4, "priya@example.org"),
                    ),
                    unread = listOf(
                        Counted("Today", 12),
                        Counted("This week", 31),
                        Counted("This month", 8),
                        Counted("Older", 63),
                    ),
                    waiting = MESSAGES.take(4),
                    replyMinutes = listOf(45L, 91L, 130L, 240L, 1_400L),
                    kept = 4_812,
                ),
                accountName = "Willhite Strategy",
                onOpen = {},
            )
        }
        shoot("warned", 900, 520, dark) {
            Message(
                summary = MESSAGES[1].copy(
                    from = "security@paypal.com",
                    fromEmail = "billing@paypaI.com",
                    subject = "Your account has been limited",
                ),
                body = Body(
                    """<p>Confirm your details to restore access.</p>""" +
                        """<form action="https://elsewhere.example">""" +
                        """<input type="password" name="p"></form>""",
                    null,
                    authenticationResults = listOf("mx.example.com; dkim=fail; spf=fail; dmarc=fail"),
                    replyTo = listOf("recovery@another-domain.example"),
                ),
                onLink = {},
            )
        }
        shoot("invitation", 900, 620, dark) {
            Message(
                summary = MESSAGES[1].copy(subject = "Invitation: Quarterly review"),
                body = Body("<p>Looking forward to it.</p>", null),
                invitation = invitationIn(
                    listOf(
                        "BEGIN:VCALENDAR",
                        "METHOD:REQUEST",
                        "BEGIN:VEVENT",
                        "UID:q4-review",
                        "SUMMARY:Quarterly review",
                        "LOCATION:Room 4, and on the usual link",
                        "DTSTART;TZID=Europe/London:20260922T140000",
                        "DTEND;TZID=Europe/London:20260922T153000",
                        "RRULE:FREQ=MONTHLY;BYDAY=2TU;COUNT=4",
                        "ORGANIZER;CN=Dana Whitfield:mailto:dana@example.org",
                        "ATTENDEE;CN=Justin Willhite;PARTSTAT=NEEDS-ACTION:mailto:justin@example.com",
                        "ATTENDEE;CN=Sam Okafor;PARTSTAT=ACCEPTED:mailto:sam@example.org",
                        "ATTENDEE;CN=Priya Raman;PARTSTAT=DECLINED:mailto:priya@example.org",
                        "ATTENDEE;CN=Tom Reyes;PARTSTAT=NEEDS-ACTION:mailto:tom@example.org",
                        "END:VEVENT",
                        "END:VCALENDAR",
                    ).joinToString("\r\n"),
                ),
                me = "justin@example.com",
                onLink = {},
            )
        }
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
        // Both states of the row at once: the pointer is over every row in the second,
        // so the two can be laid side by side and nothing should have moved.
        // The composer as it actually opens: a panel over the mail, not a screen.
        /*
         * Both themes, because the panel that had no edge had one picture and it was the
         * dark one. A composer drawn in the same colour as the mail behind it looks fine
         * until you see it next to a light theme where the shadow is doing the work.
         */
        listOf("rampart-light" to "compose-panel-light", "rampart-dark" to "compose-panel").forEach { (key, name) ->
            shoot(name, 1400, 900, theme(key)) {
                Box(Modifier.fillMaxSize()) {
                    Panes()
                    Box(
                        Modifier.align(Alignment.BottomEnd)
                            .padding(16.dp).width(620.dp).heightIn(max = 620.dp).fillMaxHeight(0.8f),
                    ) {
                        // The same frame the app uses, so this picture cannot flatter it.
                        ComposerFrame {
                            Composer(
                                identities = listOf("you@example.org"),
                                initial = replyTo(
                                    MESSAGES[1],
                                    Body(null, "Could you confirm the start time?"),
                                    "you@example.org",
                                ).copy(body = "Yes, **ten past nine** works.\n"),
                                sending = false,
                                error = null,
                                onDiscard = {},
                                onSend = {},
                            )
                        }
                    }
                }
            }
        }
        shoot("paper", 900, 700, dark) {
            Message(
                summary = MESSAGES[1],
                body = Body(SAMPLE_HTML, null),
                actions = MessageActions(archive = {}, junk = {}, trash = {}, star = {}),
                paper = true,
                onLink = {},
            )
        }
        shoot("row-rest", 420, 300) {
            MessageList(MESSAGES, null, loading = false, title = "Inbox", rowActions = ROW_ACTIONS) { _, _, _ -> }
        }
        shoot("row-hover", 420, 300) {
            MessageList(
                MESSAGES,
                null,
                loading = false,
                title = "Inbox",
                rowActions = ROW_ACTIONS,
                showHover = true,
            ) { _, _, _ -> }
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
                    // Merged across both accounts, which is what the sidebar draws: the
                    // second account contributes Receipts and nothing else.
                    tags = mergedTags(
                        mapOf(
                            ACCOUNTS[0].key to mapOf(
                                "invoices" to 31, "clients/acme" to 12, "clients/borde" to 4, "urgent" to 2,
                            ),
                            ACCOUNTS[1].key to mapOf("receipts" to 7, "invoices" to 5),
                        ),
                        mapOf("urgent" to 0xFFB23A48L),
                    ),
                    // One branch open with its counts, one folded, and the second account's
                    // folders folded away: the three things the chevrons do, in one frame.
                    folded = setOf(foldFolders(ACCOUNTS[1].key)),
                    hereTag = null,
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
        // The other icon pack, so a change to either set is visible in a diff.
        shoot("icons-heavy", 1400, 900, dark, HeavyIcons) { Panes() }
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
    AccountMailboxes("personal", "Blueprint", "admin@blueprint.example", MAILBOXES.take(3)),
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

/** The set kept for every account, which an account's own screen shows above its own. */
private val SAMPLE_GLOBALS = GlobalFilters(
    listOf(
        Rule(
            id = "g1",
            name = "Receipts",
            tests = listOf(Test(Field.SUBJECT, Match.CONTAINS, "receipt")),
            acts = listOf(Act.FileInto("Receipts")),
            global = true,
        ),
    ),
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
                    // The shape a real one has: a two cell table with the logo beside the
                    // block, because that is the only layout Outlook and Gmail both keep.
                    htmlSignature = """<div style="font-family:Arial,Helvetica,sans-serif;""" +
                        """font-size:13px;line-height:1.5;color:#555;margin-top:18px;""" +
                        """padding-top:12px;border-top:1px solid #e5e5e5">""" +
                        """<table cellpadding="0" cellspacing="0" border="0"><tr>""" +
                        """<td style="padding-right:14px;vertical-align:middle">""" +
                        """<img src="https://willhitestrategy.com/wsg-logo.png" width="110" """ +
                        """height="56" alt="Willhite Strategy Group" style="display:block"></td>""" +
                        """<td style="vertical-align:middle">""" +
                        """<div style="color:#222;font-weight:600">Justin Willhite</div>""" +
                        """<div>Willhite Strategy Group</div>""" +
                        """<div>Web design and local SEO, Santa Rosa, CA</div>""" +
                        """<a href="https://willhitestrategy.com">willhitestrategy.com</a>""" +
                        """</td></tr></table></div>""",
                ),
                Identity("i2", "Blueprint", "admin@blueprint.example"),
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
        // One account, with a rule it inherits above its own, which is the case that has
        // two kinds of row on the screen at once.
        filterAccount = "work",
        onFilterAccount = {},
        globalFilters = SAMPLE_GLOBALS,
        onGlobalFilters = {},
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
