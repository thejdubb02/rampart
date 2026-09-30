package org.rampart

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.skia.Image
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.time.Instant
import java.time.format.TextStyle

internal const val TNEF_UNREADABLE = "This file could not be read, so it was saved as it is."

/**
 * The files Outlook packed into a winmail.dat.
 *
 * The same dimmed overlay as the image preview and the nested message: a click on
 * the mail behind it must not archive or open something else.
 */
@Composable
internal fun TnefOverlay(
    name: String,
    contents: TnefContents,
    savedTo: String?,
    onSave: (String, ByteArray) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Surface(
            Modifier.align(Alignment.Center).padding(32.dp).widthIn(min = 340.dp, max = 520.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                Text(
                    "Inside ${safeFileName(name)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                if (contents.files.isEmpty()) {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            contents.body ?: "There was nothing else inside this file.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        contents.files.forEach { (fileName, bytes) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        safeFileName(fileName),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        humanSize(bytes.size.toLong()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                TextButton(onClick = { onSave(fileName, bytes) }) { Text("Save") }
                            }
                        }
                    }
                }
                if (savedTo != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Saved to $savedTo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

/**
 * The light page of a hybrid theme, around the whole reading pane.
 *
 * Header, sender, subject, toolbar, body and attachments all sit on it. The sidebar,
 * the list and the settings stay on the dark chrome. A pane that is already on this
 * page is left as it is, so the body is not wrapped a second time.
 */
@Composable
internal fun ReadingPage(messageMode: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val theme = LocalRampartTheme.current
    val page = theme.page
    val lightPage = page != null && !pageIsDark(messageMode, theme, MaterialTheme.colorScheme.surface.luminance())
    if (page == null || !lightPage || MaterialTheme.colorScheme.background == page.background) {
        content()
        return
    }
    MaterialTheme(colorScheme = page.scheme(), typography = MaterialTheme.typography) {
        Surface(color = page.background, contentColor = page.text, modifier = modifier) {
            content()
        }
    }
}

@Composable
internal fun Message(
    summary: Summary?,
    body: Body?,
    /** The account that owns [summary], because message ids are only unique inside it. */
    accountKey: String = summary?.account.orEmpty(),
    /** The account that sent this, when it was one of the reader's own. See [DeliveryLine]. */
    sentBy: MailBackend? = null,
    /** Tracking history for this sent message, grouped by recipient. */
    tracking: List<Pair<Tracked, List<Fetch>>> = emptyList(),
    onReply: (all: Boolean) -> Unit = {},
    replyAll: Boolean = false,
    /** Why the message would not open, when it would not. */
    bodyError: String? = null,
    /** Decoded images the message carries, by blob id. */
    images: Map<String, ImageBitmap> = emptyMap(),
    /** The same parts as they arrived, for the engine, which takes bytes rather than a bitmap. */
    imageBytes: Map<String, ByteArray> = emptyMap(),
    /** Pictures fetched from the web once the reader said to, by the address they came from. */
    /**
     * The domains this reader actually deals with, so a lookalike of one is caught as well
     * as a lookalike of a household name.
     */
    knownDomains: Set<String> = emptySet(),
    /** The meeting this message is about, drawn above the body when there is one. */
    invitation: Invitation? = null,
    /** The answer currently being sent, so the buttons say so and cannot be pressed twice. */
    answering: Rsvp? = null,
    onAnswer: (Rsvp) -> Unit = {},
    /** How the invitation card reaches this account's calendar, when it has one. */
    invitationContext: InvitationContext? = null,
    /** Where "Add to calendar" writes. Null hides it. See CalendarFromMailUi.kt. */
    addToCalendar: MailCalendar? = null,
    /**
     * The address this copy was addressed to, which is the one on the guest list.
     *
     * Passed in rather than worked out here: which of several identities a message reached
     * is the composer's question and is already answered there, and asking it twice is how
     * the card and the answer end up disagreeing about who is replying.
     */
    me: String = "",
    /** Whether the reader has agreed to let this message fetch its pictures. */
    showRemote: Boolean = false,
    onShowImages: (always: Boolean) -> Unit = {},
    /** What happened to an unsubscribe that was pressed, when one was. */
    unsubscribed: String? = null,
    onUnsubscribe: (Unsubscribe) -> Unit = {},
    /** Answer the sender's read-receipt request, as a draft for review. */
    onReceipt: (String) -> Unit = {},
    /** Put this sender in the server's address book. Null where there is none. */
    onAddContact: ((Summary) -> Unit)? = null,
    /** Opens the details panel on first draw. For the screenshot harness, which cannot click. */
    showDetails: Boolean = false,
    /** Whether that sender is already there, so the entry is absent rather than a duplicate. */
    inContacts: Boolean = false,
    /** The message as it arrived, while somebody is looking at it. */
    source: String? = null,
    onSource: () -> Unit = {},
    onSaveSource: () -> Unit = {},
    /** Drawing this message on paper rather than in the theme. */
    paper: Boolean = false,
    /** "dark", "light", or empty to follow the window. See [Settings.messageMode]. */
    messageMode: String = "",
    /** How large the message is drawn. See [Settings.messageScale]. */
    messageScale: Float = 1.0f,
    onPaper: (Boolean) -> Unit = {},
    onTag: (keyword: String, on: Boolean) -> Unit = { _, _ -> },
    /** True while a field in here has focus, so a bare letter is not read as a shortcut. */
    onTyping: (Boolean) -> Unit = {},
    onForward: () -> Unit = {},
    /** The original attached as a .eml, rather than quoted. */
    onForwardFile: () -> Unit = {},
    actions: MessageActions = MessageActions(),
    suggest: SuggestRepliesActions? = null,
    attachments: List<Attachment> = emptyList(),
    savedTo: String? = null,
    onDownload: (Attachment) -> Unit = {},
    /**
     * Writes one attachment to a temp file so the row can be dragged out of the
     * window. Absent where there is nothing to fetch, and the row then only
     * clicks, as before.
     */
    onDragFile: ((Attachment) -> java.io.File)? = null,
    /** Shown when that temp file could not be written. */
    onDragFailed: (String) -> Unit = {},
    /** The account's files, for Save to Files beside each attachment. Null hides it. */
    saveToFiles: FileStore? = null,
    /**
     * A forwarded message opened from an attachment.
     *
     * It is not in the mailbox, so Reply, Forward and the rest have nowhere to land.
     * Close is the only action.
     */
    readOnly: Boolean = false,
    onClose: () -> Unit = {},
    /**
     * Opening a message that arrived as a file. Absent on a message that is already
     * one of those, so a forwarded message inside a forwarded message is saved rather
     * than opened again.
     */
    onOpenMessage: ((Attachment) -> Unit)? = null,
    /**
     * Opening a winmail.dat into the files it contains. Absent where there is nowhere
     * to fetch the bytes, and the button then saves like any other file.
     */
    onOpenTnef: ((Attachment) -> Unit)? = null,
    /**
     * Drawn above the avatar row. Off inside a thread stack, where the subject already
     * heads the whole conversation once and would otherwise repeat on every card.
     */
    showSubject: Boolean = true,
    /**
     * Collapses this card, when it is one of several. Null for a message on its own, which
     * has nothing to collapse to: see rule 9, a lone message must not grow a click that does
     * nothing useful.
     */
    onHeaderClick: (() -> Unit)? = null,
    /**
     * A name or address in the header was clicked: open that person's history. Null where
     * there is nowhere to show one, and the names are then plain text.
     */
    onPerson: ((name: String, address: String) -> Unit)? = null,
    /**
     * The scroll this card shares with the rest of its stack, when it has one.
     *
     * Null draws this card on its own scroll, filling the pane, exactly as a single message
     * always has. Given one, this card contributes its height to that scroll instead of
     * starting a scroll of its own: several cards each scrolling independently would mean a
     * reader hunting for which card's scrollbar is under the pointer, and the whole point of
     * the stack is that it reads as one page.
     */
    externalScroll: ScrollState? = null,
    /**
     * The page, already built. Passed in by the open path so this function does not
     * parse the message while it is trying to draw it. Absent for a viewer that has
     * the bytes already, which builds the same thing off to the side.
     */
    reading: Reading? = null,
    /** How tall the pane is, for a message that has never been measured. */
    paneHeight: Int = 0,
    onLink: (String) -> Unit,
    /** Bytes for an image that was not already fetched with the body, for a preview. */
    onLoadImage: (suspend (Attachment) -> ImageBitmap?)? = null,
    /** Raw bytes for an attachment when opening a preview. */
    onLoadBytes: (suspend (Attachment) -> ByteArray?)? = null,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val quoteColor = MaterialTheme.colorScheme.outline
    // What the body sits on, so a colour the sender chose can be checked against it before
    // it is used. Part of the remember key: the same message on a different theme is a
    // different answer about which of its colours can be read.
    val bodyPaper = MaterialTheme.colorScheme.surface
    /*
     * The message is drawn as the sender built it, on its own page, whatever the window
     * around it is doing.
     *
     * Rampart used to invert a message in a dark window, which is the trick webmail uses.
     * It was wrong twice over on real mail. A reply with no colours in it at all came out
     * as white text on a black slab, because inverting an unpainted page inverts the white
     * the engine supplies. And a design that was inverted is a design nobody made: a
     * hotel's dark brown header came out pink. So the page is always light, the sender's
     * own colours are always what you see, and the dark window holds a light message the
     * way a webmail tab does.
     */
    /*
     * A message with no colours of its own follows the window; one with a design does not.
     *
     * This is not the old inversion and must not become it. Nothing is filtered and no
     * pixel is turned inside out: a message that stated no colours is drawn on a dark page
     * instead of a light one, and a message that stated any is drawn exactly as it was
     * built, in either window. The switch on the toolbar puts this one message back on
     * paper when the guess is wrong, which is what it is for.
     */
    /*
     * Which page a message is drawn on: the window's, or the one that was asked for.
     *
     * Following the window is the default and is right for most people. It is not right for
     * everybody: a dark application with mail on paper, and a light application with mail
     * that does not flash white at night, are both things somebody wants, and they were one
     * setting only because the window was the only thing that knew.
     */
    val theme = LocalRampartTheme.current
    val darkWindow = pageIsDark(messageMode, theme, MaterialTheme.colorScheme.surface.luminance())
    val pageBackground = plainPageBackground(theme)
    val pageText = plainPageText(theme)
    /*
     * The open path hands this in already built. A viewer that only has the bytes builds
     * the same page off to the side, so neither path parses HTML while drawing.
     *
     * Light and dark are one document. The attribute is in it from the start, and the
     * toolbar flips that attribute on the page that is already loaded.
     */
    var built by remember { mutableStateOf<Reading?>(null) }
    val who = summary
    val letter = body
    if (reading == null && letter != null && who != null) {
        LaunchedEffect(letter, attachments, imageBytes, showRemote, darkWindow, paper, pageBackground, pageText) {
            built = withContext(Dispatchers.Default) {
                prepareReading(
                    who.fromEmail,
                    who.from,
                    letter,
                    attachments,
                    imageBytes,
                    showRemote,
                    darkWindow && !paper,
                    knownDomains,
                    pageBackground,
                    pageText,
                )
            }
        }
    }
    val active = reading ?: built
    val page = active?.page
    /*
     * The engine had this message and drew nothing, so the block renderer gets it.
     *
     * Reset with the document, because every reason for a blank page so far has been about
     * this particular document reaching this particular engine, not about the engine being
     * broken from then on. Showing the pictures builds a new document and it gets a fresh
     * try.
     *
     * The block renderer is not as good and is not meant to be. It is the difference
     * between a message that looks plainer than the sender intended and a white rectangle,
     * and there is no version of this where the white rectangle is the better answer.
     */
    var engineBlank by remember(page?.document) { mutableStateOf(false) }
    val waitingForPage = body?.html != null && page == null && webEngineWorks
    val engineDraws = page != null && webEngineWorks && !engineBlank
    val rendered = remember(body, linkColor, bodyPaper, engineDraws, waitingForPage) {
        body?.let {
            when {
                // Nothing to build: the engine is drawing this one, or it is about to.
                // Still not null, because null here means the message has no body at all.
                engineDraws || waitingForPage -> HtmlDoc(emptyList(), emptyList())
                it.html != null -> htmlBlocks(it.html, linkColor, quoteColor, bodyPaper, onLink)
                // Plain text has no structure to keep, so it is one block and the same
                // drawing code handles both rather than there being two ways down.
                it.text != null -> HtmlDoc(
                    listOf(Block.Words(renderText(it.text, linkColor, onLink).text)),
                    emptyList(),
                )
                else -> null
            }
        }
    }
    /** The pictures the body puts on screen itself. Everything else the message brought is a file. */
    val shown = active?.cited ?: emptySet()
    // Files the body did not already draw. The invitation card is the .ics, so that
    // part is not listed again underneath it.
    val fileList = attachments
        .filter { cidKey(it.cid) !in shown }
        .filter { invitation == null || !it.type.equals("text/calendar", ignoreCase = true) }
    // The nested viewer keeps the list where it has always been. The setting is for
    // the ordinary reading view only.
    val attachmentsBeside = !readOnly && Settings.attachmentPosition() == "beside"
    val messageScope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<Pair<Attachment, ImageBitmap>?>(null) }
    var pdfPreview by remember { mutableStateOf<Pair<Attachment, ByteArray>?>(null) }
    fun bitmapOf(bytes: ByteArray): ImageBitmap? =
        runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    fun openFile(attachment: Attachment) {
        val nested = onOpenMessage?.takeIf { isAttachedMessage(attachment.type) }
        if (nested != null) {
            nested(attachment)
            return
        }
        val packed = onOpenTnef?.takeIf { isTnef(attachment.type, attachment.name) }
        if (packed != null) {
            packed(attachment)
            return
        }
        val isPdfFile = isPdf(attachment.type, attachment.name)
        val isImageFile = fileGlyph(attachment.type) == FileGlyph.IMAGE
        val wantsPreview = Settings.attachmentClickBehavior() == "preview" && (isImageFile || isPdfFile)
        if (!wantsPreview) {
            onDownload(attachment)
            return
        }
        if (isPdfFile) {
            val ready = imageBytes[attachment.blobId]
            if (ready != null) {
                pdfPreview = attachment to ready
                return
            }
            val load = onLoadBytes
            if (load == null) {
                onDownload(attachment)
                return
            }
            messageScope.launch {
                val bytes = load(attachment)
                if (bytes != null) pdfPreview = attachment to bytes else onDownload(attachment)
            }
            return
        }
        val ready = images[attachment.blobId] ?: imageBytes[attachment.blobId]?.let(::bitmapOf)
        if (ready != null) {
            preview = attachment to ready
            return
        }
        val load = onLoadImage
        if (load == null) {
            onDownload(attachment)
            return
        }
        messageScope.launch {
            val bitmap = load(attachment)
            if (bitmap != null) preview = attachment to bitmap else onDownload(attachment)
        }
    }
    // The body refers to a picture it carries by its Content-ID, not by its blob, so the
    // two have to be joined up before anything can be drawn in place.
    // Lazy, and decoded from the bytes where no bitmap was made: only the plain renderer
    // reads this, and a message the engine draws should not pay to decode its pictures twice.
    val carried = remember(attachments, images, imageBytes) {
        lazy {
            attachments.mapNotNull { part ->
                val cid = cidKey(part.cid) ?: return@mapNotNull null
                (images[part.blobId] ?: imageBytes[part.blobId]?.let(::bitmapOf))?.let { cid to it }
            }.toMap()
        }
    }

    // The background, the watermark and filling the whole pane are the reading pane's own,
    // drawn once around the pane. A card sharing [externalScroll] is one of several inside
    // that pane rather than the pane itself, so it draws only its own content and lets its
    // width, not the window's height, decide how tall it is.
    val ownsPane = externalScroll == null
    ReadingPage(messageMode, if (ownsPane) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
    Box(
        if (ownsPane) Modifier.fillMaxSize().background(
            if (LocalRampartTheme.current.page?.background == MaterialTheme.colorScheme.background) {
                MaterialTheme.colorScheme.background
            } else {
                MaterialTheme.colorScheme.surface
            },
        ) else Modifier.fillMaxWidth(),
    ) {
        if (ownsPane) ThemeArt(Modifier.align(Alignment.BottomEnd))
        Column(if (ownsPane) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            if (summary == null) {
                Box(Modifier.fillMaxSize()) {
                    Text(
                        "Pick a message.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                return@Column
            }

            if (readOnly) {
                Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) { Text("Close", maxLines = 1) }
                }
            } else {
                ReadingToolbar(
                    summary = summary,
                    body = body,
                    bodyReady = body != null,
                    replyAll = replyAll,
                    darkWindow = darkWindow,
                    paper = paper,
                    canPrint = engineDraws && page != null,
                    sourceOpen = source != null,
                    actions = actions,
                    inContacts = inContacts,
                    onAddContact = onAddContact,
                    onReply = onReply,
                    onForward = onForward,
                    onForwardFile = onForwardFile,
                    onPaper = onPaper,
                    onSource = onSource,
                    onPrint = { page?.let { printDocument(it.document) } },
                    onUnsubscribe = onUnsubscribe,
                    rookOpen = AppBar.showing(SideTool.ROOK),
                    onOpenRook = { AppBar.show(SideTool.ROOK) },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // The pane's heading, under the toolbar and outside the scroll. In the
            // scroll it left with the body when a card was brought into view, and it
            // moved when the body below it finished loading.
            if (showSubject) {
                SelectionContainer {
                    Text(
                        summary.subject.ifBlank { "(no subject)" },
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                    )
                }
            }

            // Hoisted above the source branch as well as the body below it, because both
            // want the one scroll: a card on its own scrolls itself, and a card in a stack
            // shares [externalScroll] with the header and every other card, so switching a
            // card to its source and back does not also reset how far down it was.
            val bodyScroll = externalScroll ?: rememberScrollState()
            val bodyScope = rememberCoroutineScope()
            // Filling the pane and scrolling itself is only right for a card on its own.
            // One inside a stack sizes to its own content and lets the stack's shared
            // Column, already scrolling, carry it.
            // weight, not fillMaxSize: the heading above this has to keep its height,
            // and a scroll area that also asks for the whole pane pushes the bottom off.
            val pageModifier = if (ownsPane) {
                Modifier.weight(1f).fillMaxWidth().verticalScroll(bodyScroll)
            } else {
                Modifier.fillMaxWidth()
            }

            if (source != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        savedTo?.let { "Saved to $it" } ?: "As it arrived, headers and all.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSaveSource) { Text("Save as .eml") }
                }
                val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                SelectionContainer {
                    Column(pageModifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        headersOf(source).forEach { (name, value) ->
                            Row(Modifier.padding(bottom = 2.dp)) {
                                Text(
                                    name,
                                    style = mono,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.width(150.dp),
                                )
                                Text(value, style = mono, modifier = Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(14.dp))
                        Text(bodyOf(source), style = mono)
                    }
                }
                return@Column
            }

            Column(
                pageModifier.padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = if (showSubject) 8.dp else 26.dp,
                    bottom = 26.dp,
                ),
            ) {
                // The whole window, not a column down the middle of it. A capped measure is
                // easier to read a paragraph in, and it was capped at 660 for that reason,
                // but it wastes most of a wide window and a designed message brings its own
                // width anyway.
                Paper(paper) {
                Column(Modifier.fillMaxWidth()) {
                    val proof = remember(body) {
                        authenticityOf(body?.authenticationResults?.joinToString("\n"), body?.spamStatus)
                    }
                    /*
                     * What is wrong with the message, above what failed to vouch for it.
                     *
                     * Read from the message's own HTML rather than the cleaned copy, because
                     * the cleaner removes forms and password fields, which is right for
                     * drawing it and would hide exactly what this is looking for.
                     */
                    val warnings = active?.warnings.orEmpty()
                    warnings.forEach { warning ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Text(
                                warning.says,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                warning.because,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (proof.worthShowing) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                proof.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    unsubscribed?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }

                    if (tracking.isNotEmpty()) {
                        TrackingSection(tracking)
                        Spacer(Modifier.height(14.dp))
                    }

                    suggest?.let { SuggestRepliesCard(it) }

                    SelectionContainer {
                        Row(
                            Modifier.fillMaxWidth()
                                .let { row -> onHeaderClick?.let { row.clickable(onClick = it) } ?: row }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val (who, address) = displaySender(summary.from, summary.fromEmail)
                            val off = unsubscribeFrom(body?.listUnsubscribe, body?.listUnsubscribePost)
                            DisableSelection {
                                Avatar(
                                    who,
                                    summary.fromEmail.ifBlank { who },
                                    34.dp,
                                    photo = photoFor(summary.fromEmail),
                                )
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        who,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        // Its own click, inside the row's: the name opens the
                                        // person, anywhere else on the row folds the card.
                                        modifier = Modifier.weight(1f, fill = false).let { name ->
                                            val open = onPerson?.takeIf { summary.fromEmail.isNotBlank() }
                                            if (open == null) name else name.clip(MaterialTheme.shapes.small)
                                                .clickable { open(who, summary.fromEmail) }
                                        },
                                    )
                                    // Only when the message went out through somewhere other
                                    // than the domain it claims, which is the ordinary
                                    // explanation for mail that looks odd and is not.
                                    sentVia(summary.fromEmail, body?.authenticationResults?.joinToString("\n"))
                                        ?.let { host ->
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "via $host",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.clip(MaterialTheme.shapes.small)
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                                            )
                                        }
                                    if (address == null && off != null) {
                                        Spacer(Modifier.width(8.dp))
                                        DisableSelection {
                                            UnsubscribeLink(off, unsubscribed, onUnsubscribe)
                                        }
                                    }
                                }
                                if (address != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            address,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (off != null) {
                                            Spacer(Modifier.width(8.dp))
                                            DisableSelection {
                                                UnsubscribeLink(off, unsubscribed, onUnsubscribe)
                                            }
                                        }
                                    }
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    summary.receivedAt.asLocalTime(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                humanBytes(body?.size ?: 0L).takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }

                    if (attachmentsBeside && fileList.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        FileRows(
                            fileList, ::openFile, images, savedTo, onOpenMessage, onOpenTnef,
                            onDragFile, onDragFailed, saveToFiles,
                        )
                    }

                    /*
                     * Who else got it, and everything else on request.
                     *
                     * Two names and a count rather than all of them: a message to nine
                     * people should not push the body off the screen, and a bare number
                     * with no names is no use either.
                     */
                    var details by remember(summary.id) { mutableStateOf(showDetails) }
                    val everyone = remember(body) {
                        (body?.to.orEmpty() + body?.cc.orEmpty()).filter { it.isNotBlank() }
                    }
                    if (everyone.isNotEmpty()) {
                        val (shown, more) = shownRecipients(everyone)
                        Spacer(Modifier.height(6.dp))
                        SelectionContainer {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "To",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                Spacer(Modifier.width(7.dp))
                                if (onPerson == null) {
                                    Text(
                                        shown.joinToString(", ") + if (more > 0) "  +$more more" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                } else {
                                    // Each one its own click, so the right person opens.
                                    Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
                                        shown.forEachIndexed { i, address ->
                                            if (i > 0) Text(", ", style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                address,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.clip(MaterialTheme.shapes.small)
                                                    .clickable { onPerson(address, address) },
                                            )
                                        }
                                        if (more > 0) Text("  +$more more", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                DisableSelection {
                                    Text(
                                        if (details) "Hide details" else "Show details",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clip(MaterialTheme.shapes.small)
                                            .clickable { details = !details }
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (details) {
                        Spacer(Modifier.height(10.dp))
                        MessageDetails(summary, body, proof.spamScore)
                    }
                    DeliveryLine(sentBy, summary.id)
                    if (!readOnly) {
                    val colours = LocalTagColours.current
                    val tags = remember(summary.keywords, colours) { tagsOf(summary.keywords, colours) }
                    var adding by remember(summary.id) { mutableStateOf<String?>(null) }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tags.forEach { tag ->
                            Row(
                                Modifier.clip(CircleShape).background(Color(tag.color))
                                    .clickable { onTag(tag.keyword, false) }
                                    .padding(start = 9.dp, end = 7.dp, top = 3.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    tag.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                )
                                Spacer(Modifier.width(5.dp))
                                // The whole chip removes the tag; the cross is there to say so.
                                Text("\u00d7", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            }
                        }
                        val typed = adding
                        if (typed == null) {
                            Text(
                                "Add tag",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clip(CircleShape).clickable { adding = "" }
                                    .padding(horizontal = 9.dp, vertical = 3.dp),
                            )
                        } else {
                            val focus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            fun commit() {
                                validKeyword(typed)?.let { onTag(it, true) }
                                adding = null
                            }
                            BasicTextField(
                                value = typed,
                                onValueChange = { adding = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.labelMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.width(140.dp).focusRequester(focus)
                                    .onFocusChanged { onTyping(it.isFocused) }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (event.key) {
                                            Key.Enter, Key.NumPadEnter -> { commit(); true }
                                            Key.Escape -> { adding = null; true }
                                            else -> false
                                        }
                                    }
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.shapes.small,
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    }
                    invitation?.let { meeting ->
                        Spacer(Modifier.height(16.dp))
                        InvitationCard(
                            invitation = meeting,
                            // The address this copy was addressed to, which is the one on
                            // the guest list and the one the answer goes out as.
                            me = me,
                            onAnswer = if (answering == null) onAnswer else null,
                            context = invitationContext,
                        )
                    }
                    // A proper invitation already has its own card, so the chip is for everything else.
                    if (invitation == null) addToCalendar?.let { AddToCalendar(it, summary, body) }
                    // Make a task, on the same account's server. See TasksUi.kt.
                    addToCalendar?.let { MakeTask(it, summary, body) }

                    /*
                     * The sign-in code, in a size somebody can read across a desk.
                     *
                     * Read from the message itself rather than the preview the list had,
                     * because a body is the whole message and a preview is its first two
                     * hundred characters, so this finds the ones that put the code lower
                     * down. A message whose code the list already found shows the same one.
                     */
                    val signInCode = remember(summary.id, body) {
                        body?.let { oneTimeCode(summary.subject, it.text, it.html) }
                    }
                    if (signInCode != null) {
                        val clipboard = LocalClipboardManager.current
                        var copied by remember(signInCode) { mutableStateOf(false) }
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Sign-in code",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    Text(
                                        signInCode,
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(signInCode))
                                    copied = true
                                }) {
                                    Text(if (copied) "Copied" else "Copy")
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    if (rendered == null) {
                        Spacer(Modifier.height(20.dp))
                        when {
                            bodyError != null -> FaultText(
                                "This message would not open.",
                                bodyError,
                            )
                            body == null -> BodySkeleton()
                            else -> Text("This message has no readable body.")
                        }
                    } else {
                    // Asked, never answered on its own. A receipt that sends itself is
                    // read tracking with the sender's name on it, and the whole reason the
                    // header is ignored by default everywhere else.
                    var receiptAnswered by remember(summary.id) { mutableStateOf(false) }
                    val asked = if (receiptAnswered) {
                        null
                    } else {
                        receiptWanted(
                            mapOf(MDN_HEADER to body?.receiptTo.orEmpty()),
                            summary.fromEmail,
                        )
                    }
                    if (asked != null && !readOnly) {
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "The sender asked to be told when this was opened.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { receiptAnswered = true }) {
                                Text("No", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { receiptAnswered = true; onReceipt(asked) }) {
                                Text("Send a receipt", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if ((page?.blocked ?: rendered.blockedImages) > 0 && !showRemote) {
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            /*
                             * Who was asking, not how many were blocked.
                             *
                             * Rampart already knew which pictures were remote, because that
                             * is the decision it makes to block them. Naming the trackers
                             * among them is what turns a count into something worth reading:
                             * "3 held back" says something happened, "2 of them are trackers,
                             * Mailchimp and HubSpot" says who was watching.
                             */
                            val blocked = page?.blocked ?: rendered.blockedImages
                            val trackers = page?.trackers ?: 0
                            Text(
                                buildString {
                                    append(if (blocked == 1) "1 picture is held back." else "$blocked pictures are held back.")
                                    if (trackers > 0) {
                                        append(if (trackers == 1) " It is a tracker" else " $trackers of them are trackers")
                                        trackerLine(page?.held.orEmpty()).takeIf { it.isNotBlank() }
                                            ?.let { append(": $it") }
                                        append(".")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                            // Fetching one tells the sender the message was opened, so it is
                            // the reader's call, and the answer is kept per sender rather
                            // than asked again on the next newsletter from the same place.
                            TextButton(onClick = { onShowImages(false) }) {
                                Text("Show", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onShowImages(true) }) {
                                Text(
                                    "Always from " + imageSenderKey(summary.fromEmail),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    /*
                     * The engine for HTML, the block renderer for everything else.
                     *
                     * Not a fallback: a plain text message has no layout to get right and
                     * drawing it in Compose keeps it selectable, themed and part of the
                     * same scroll as the rest of the pane, with no engine to start.
                     */
                    if (waitingForPage) {
                        Spacer(Modifier.height(20.dp))
                        BodySkeleton()
                    } else if (engineDraws) WebBody(
                        page.document,
                        onLink = onLink,
                        onScroll = { dy -> bodyScope.launch { bodyScroll.scrollBy(dy) } },
                        onBlank = { engineBlank = true },
                        dark = darkWindow && !paper,
                        scale = messageScale,
                        messageId = summary.id,
                        accountKey = accountKey,
                        initialHeight = openingHeight(
                            messageHeights.of(CardKey(accountKey, summary.id)),
                            paneHeight,
                        ),
                    )
                    else {
                        /*
                         * Never silently.
                         *
                         * A message shown plainly because the engine would not draw it
                         * looks like a message the sender wrote plainly, and the difference
                         * matters when somebody is deciding whether to trust what they are
                         * reading. It also stops the next report of this being "it looks
                         * wrong" with nothing to go on.
                         */
                        if (engineBlank) {
                            Text(
                                "This message would not draw, so it is shown as plain text.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        HtmlBody(rendered, carried.value, emptyMap())
                    }
                    }

                    // Under the body, unless the reader asked for the list beside the sender.
                    // A nested message keeps it here either way.
                    if (!attachmentsBeside && fileList.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(14.dp))
                        FileRows(
                            fileList, ::openFile, images, savedTo, onOpenMessage, onOpenTnef,
                            onDragFile, onDragFailed, saveToFiles,
                        )
                    }
                    Spacer(Modifier.height(40.dp))
                }
                }
            }
        }
        pdfPreview?.let { (attachment, bytes) ->
            PdfPreviewModal(
                attachment = attachment,
                bytes = bytes,
                onDownload = onDownload,
                onClose = { pdfPreview = null },
            )
        }
        preview?.let { (attachment, bitmap) ->
            CoverBody(true)
            Box(
                Modifier.matchParentSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Surface(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 12.dp,
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            bitmap,
                            contentDescription = safeFileName(attachment.name),
                            modifier = Modifier.sizeIn(maxWidth = 720.dp, maxHeight = 520.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            TextButton(onClick = { onDownload(attachment) }) { Text("Save") }
                            TextButton(onClick = { preview = null }) { Text("Close") }
                        }
                    }
                }
            }
        }
    }
    }
}

/**
 * One file on a message: what it is, what it is called, and Save, Open, or Preview.
 *
 * The same rows wherever the list is drawn. Open is a forwarded message, or a
 * winmail.dat. Preview is an image or a PDF when that is what a click is set to do.
 * Everything else saves. Dragging the row past a short movement hands the file
 * to the desktop or to another program. The button is unchanged for a click.
 */
@Composable
private fun FileRows(
    files: List<Attachment>,
    onOpen: (Attachment) -> Unit,
    images: Map<String, ImageBitmap>,
    savedTo: String?,
    onOpenMessage: ((Attachment) -> Unit)?,
    onOpenTnef: ((Attachment) -> Unit)?,
    onDragFile: ((Attachment) -> java.io.File)?,
    onDragFailed: (String) -> Unit,
    saveToFiles: FileStore? = null,
) {
    files.forEach { attachment ->
        // The button stays a button. The drag source uses the same movement
        // threshold as any other drag in this window, so a click that does not
        // move still presses it, and a drag does not.
        val dragOut = onDragFile
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).rowHover().padding(vertical = 4.dp).then(
                if (dragOut == null) Modifier
                else Modifier.dragAttachmentOut(
                    prepare = { dragOut(attachment) },
                    onFailed = onDragFailed,
                ),
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /*
             * A picture is shown, not just named. The bytes are already here and already
             * decoded when the body carried them, so this costs a draw call. A screenshot
             * somebody sent is recognisable from it, and a long camera filename is not.
             */
            FileMark(
                name = safeFileName(attachment.name),
                type = attachment.type,
                picture = images[attachment.blobId],
                glyphForImage = false,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    // The name a sender chose is shown as the name it will be saved under,
                    // so the two can never disagree.
                    safeFileName(attachment.name),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    humanSize(attachment.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            val openNested = onOpenMessage?.takeIf { isAttachedMessage(attachment.type) }
            val openPacked = onOpenTnef?.takeIf { isTnef(attachment.type, attachment.name) }
            val previewLabel = Settings.attachmentClickBehavior() == "preview" &&
                (fileGlyph(attachment.type) == FileGlyph.IMAGE || isPdf(attachment.type, attachment.name))
            SaveToFilesButton(attachment, saveToFiles)
            TextButton(onClick = { onOpen(attachment) }) {
                Text(
                    when {
                        openNested != null || openPacked != null -> "Open"
                        previewLabel -> "Preview"
                        else -> "Save"
                    },
                )
            }
        }
    }
    if (savedTo != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Saved to $savedTo",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Where j and k land. Stepping off either end stays put rather than wrapping: a keystroke
 * that silently jumps from the newest message to the oldest is a keystroke people stop
 * trusting.
 */
internal fun nextIndex(current: Int, size: Int, delta: Int): Int {
    if (size == 0) return 0
    if (current < 0) return if (delta > 0) 0 else size - 1
    return (current + delta).coerceIn(0, size - 1)
}

/**
 * Where a saved attachment goes. The platform Downloads folder when there is one, the home
 * directory otherwise, and it is created rather than assumed: a save that fails because a
 * folder is missing is a bad way to learn the folder was missing.
 */
internal fun downloadsFolder(): java.nio.file.Path {
    val home = java.nio.file.Path.of(System.getProperty("user.home"))
    val downloads = home.resolve("Downloads")
    val target = if (java.nio.file.Files.isDirectory(downloads)) downloads else home
    java.nio.file.Files.createDirectories(target)
    return target
}

/** The short stamp on a message row: a time today, a day this week, the date after that. */
internal fun String.asLocalTime(): String =
    runCatching { Regional.listStamp(Instant.parse(this)) }.getOrDefault(this)

/**
 * A date and a time, for a line written into a message someone sends.
 *
 * The row's short stamp would turn today's mail into "On 15:45, Sam wrote", which is
 * not a date the recipient can place. The full form keeps the day.
 */
internal fun String.asWrittenTime(): String =
    runCatching { Regional.dateTime(Instant.parse(this)) }.getOrDefault(this)

/**
 * The same instant written out in full, for the details panel.
 *
 * The short form beside a message is right there, where the year and the seconds are noise.
 * In the panel they are the point: the gap between when a message says it was sent and
 * when it actually landed is how you spot one that sat somewhere for an hour.
 */
internal fun String.asFullLocalTime(): String = runCatching {
    val region = Regional.current()
    val at = Instant.parse(this).atZone(region.zone)
    val weekday = at.dayOfWeek.getDisplayName(TextStyle.FULL, region.locale)
    "$weekday, ${formatFull(region, at.toLocalDate())} at ${formatTime(region, at, withSeconds = true)}"
}.getOrDefault(this)

/**
 * One collapsed message in the thread stack: who, when, and a preview where there is one.
 * Clicking it opens the card in place, exactly where this row was.
 *
 * [Summary.preview] is empty on IMAP, which has no equivalent and no cheap way to build
 * one: getting it would mean fetching a body for every row, which is the download the list
 * exists to avoid. So the preview is left out rather than drawn as a gap, and the row is
 * just the name and the date, which is still a row worth reading.
 */
@Composable
private fun TrackingSection(rows: List<Pair<Tracked, List<Fetch>>>) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
    ) {
        Text("Tracking", style = MaterialTheme.typography.titleSmall)
        rows.forEach { (tracked, events) ->
            val timeline = trackingTimeline(tracked, events)
            Spacer(Modifier.height(8.dp))
            Text(timeline.recipient.ifBlank { "Recipient" }, fontWeight = FontWeight.SemiBold)
            val summary = buildList {
                timeline.firstRead?.let { add("First read ${Regional.dateTime(it)}") }
                timeline.lastRead?.let { add("Last read ${Regional.dateTime(it)}") }
                if (timeline.reads > 0) add("${timeline.reads} read${if (timeline.reads == 1) "" else "s"}")
                if (timeline.clicked) add("Clicked")
                timeline.repliedAt?.let { add("Replied ${Regional.dateTime(it)}") }
            }.ifEmpty { listOf("No activity yet") }
            Text(summary.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            timeline.events.forEach { event ->
                val action = if (event.event == "click") "Clicked ${event.url}" else "Opened"
                Text(
                    "${Regional.dateTime(event.at)}  $action. ${classificationText(event)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
internal fun ThreadRow(message: Summary, onClick: () -> Unit) {
    val (who, _) = displaySender(message.from, message.fromEmail)
    Row(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .rowHover()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            who,
            message.fromEmail.ifBlank { who },
            24.dp,
            photo = photoFor(message.fromEmail),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            who,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (message.seen) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (message.preview.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                message.preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            message.receivedAt.asLocalTime(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * A text link beside the sender that invokes the unsubscribe action or indicates completed unsubscribe.
 */
@Composable
private fun UnsubscribeLink(
    off: Unsubscribe,
    unsubscribed: String?,
    onUnsubscribe: (Unsubscribe) -> Unit,
) {
    val done = isUnsubscribed(unsubscribed)
    val pending = unsubscribed == UNSUBSCRIBE_PENDING
    val canClick = !done && !pending
    Text(
        unsubscribeLabel(unsubscribed),
        style = MaterialTheme.typography.bodySmall,
        color = if (done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
        modifier = Modifier.clip(MaterialTheme.shapes.small)
            .then(
                if (canClick) {
                    Modifier.clickable { onUnsubscribe(off) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
