package org.rampart

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.key
import kotlinx.coroutines.flow.first

/**
 * One message being written, and the account it was opened from.
 *
 * Captured when composing starts. The account, the starting draft and the server draft
 * id stay with this session, so clicking another message, or the one being answered
 * leaving the list, does not retarget the composer or replace a draft that belonged
 * to the previous message.
 */
internal data class ComposeSession(
    val account: String?,
    val draft: Draft,
    val saves: DraftSaves,
)

internal data class ListRequest(
    val account: String,
    val mailbox: String,
    val query: String,
    val results: Boolean,
    val tag: String?,
    val filters: QuickFilters,
    val offset: Int,
)

/** One signed in mailbox. Several of these is the point; the password is in none of them. */
internal class Session(val account: SavedAccount, val jmap: MailBackend) {
    val key: String get() = "${account.email}@${account.server}"

    /**
     * This account's local copy, or null when there is nowhere safe to keep the key.
     *
     * Opened once and lazily: a person who never leaves the inbox should not pay for a file
     * being created, and a failure to open one is never a reason not to show mail. Rampart
     * without a local store is Rampart as it was last week, which works.
     */
    val store: Store? by lazy {
        runCatching {
            Secrets.mailKey(account)?.let { mailKey ->
                val legacy = Secrets.legacyMailKey(account)?.let { Store.legacyFile(key) to it }
                Store.open(Store.file(key), mailKey, legacy)
            }
        }.getOrNull()
    }
}

/** What the sidebar needs to draw an account, with no live connection behind it. */
internal data class AccountMailboxes(
    val key: String,
    val name: String,
    val email: String,
    val mailboxes: List<Mailbox>,
)

/**
 * Everything about one message that has to be fetched, or decided, before its card in the
 * thread stack can be drawn.
 *
 * One map, [Reader]'s `cards`, keyed by account and message id, rather than a body map
 * and a bodyError map and an attachments map and so on side by side: those were nine
 * declarations that moved together, were cleared together and were never once independent
 * of each other, so the next field a card needs would otherwise be three edits in three
 * places rather than one field here. The account is part of the key because an id is only
 * unique inside one account.
 */
internal data class Card(
    val body: Body? = null,
    /** Why [body] would not open, when it would not. Set only when there was nothing kept. */
    val bodyError: String? = null,
    val attachments: List<Attachment> = emptyList(),
    /** Pictures this message carries, decoded, by blob id. */
    val images: Map<String, ImageBitmap> = emptyMap(),
    /** The same parts undecoded, for the engine, which wants bytes rather than a bitmap. */
    val imageBytes: Map<String, ByteArray> = emptyMap(),
    /**
     * The finished page, built off the UI thread.
     *
     * Composition reads this and does not build it again. Building it here is what made
     * opening a message parse the HTML on the thread that paints the window.
     */
    val reading: Reading? = null,
    /** Whether [reading] was built with remote pictures allowed. */
    val pageRemote: Boolean = false,
    /** Invitation text fetched with the message, when there was a small one. */
    val calendar: String? = null,
    /** The message's own blob. Unchanged by a flag, so a cached copy can be trusted. */
    val emailBlobId: String? = null,
    /** What happened after pressing Unsubscribe on this message, when it was pressed. */
    val unsubscribed: String? = null,
    /** This message's own raw source, once "View source" has asked for it. */
    val source: String? = null,
    /** Where an attachment, or this message's source, was last saved to. */
    val saved: String? = null,
    /**
     * Whether the fetch for this card ran all the way through.
     *
     * Not the same question as whether [body] is set. The body is filled from disk first
     * and then again from the server, and the parts and pictures follow it, so a load
     * interrupted part way leaves a card with a body and no attachments. Asking "has a
     * body" before deciding to skip meant that card was never completed: it stayed
     * half loaded for as long as the conversation was open, with its attachments missing
     * and no way to ask for them again.
     */
    val loaded: Boolean = false,
)

internal data class RowActions(
    val reply: ((Summary, all: Boolean) -> Unit)? = null,
    val forward: ((Summary) -> Unit)? = null,
    /** The original attached as a file, rather than quoted. */
    val forwardFile: ((Summary) -> Unit)? = null,
    val archive: ((Summary) -> Unit)? = null,
    val junk: ((Summary) -> Unit)? = null,
    /** Out of Junk again, offered on a row that is in it. */
    val notJunk: ((Summary) -> Unit)? = null,
    /** Whether this row is in Junk, which decides which of the two above is shown. */
    val isJunk: ((Summary) -> Boolean)? = null,
    val trash: ((Summary) -> Unit)? = null,
    val star: ((Summary) -> Unit)? = null,
    val markRead: ((Summary, read: Boolean) -> Unit)? = null,
    /** Putting it away until later. Null on an account with nowhere to put it. */
    val snooze: ((Summary, SnoozeUntil) -> Unit)? = null,
    /** A filter described from this message. Null where the row does not offer one. */
    val filter: ((Summary) -> Unit)? = null,
    /** Send a scheduled draft immediately. Shown only on a row that is scheduled. */
    val sendScheduled: ((Summary) -> Unit)? = null,
    /** Leave the draft in Drafts and forget the time. */
    val cancelScheduled: ((Summary) -> Unit)? = null,
    /** Filing it into a folder by id, for the Move hover button. See [HoverButtons]. */
    val moveInto: ((Summary, String) -> Unit)? = null,
    /** The folders Move offers for this row. */
    val folders: ((Summary) -> List<Mailbox>)? = null,
    /**
     * Read or unread, and a star off, for several messages of the row's account at once:
     * the rest of a collapsed conversation. See [threadActions].
     */
    val markIds: ((Summary, Set<String>, Boolean) -> Unit)? = null,
    val starIds: ((Summary, Set<String>, Boolean) -> Unit)? = null,
    /** Set a per-sender override to always treat this sender as Focused. */
    val alwaysFocused: ((Summary) -> Unit)? = null,
    /** Set a per-sender override to always treat this sender as Other. */
    val alwaysOther: ((Summary) -> Unit)? = null,
)

/** Roles that already have a button on the message, so Move does not offer them again. */
internal val MOVE_COVERED = setOf("archive", "junk", "trash", "drafts", "sent")

internal data class MessageActions(
    val archive: (() -> Unit)? = null,
    val snooze: ((SnoozeUntil) -> Unit)? = null,
    val trash: (() -> Unit)? = null,
    val junk: (() -> Unit)? = null,
    /** Out of Junk again. Present exactly when [junk] is not, never both and never neither. */
    val notJunk: (() -> Unit)? = null,
    val star: (() -> Unit)? = null,
    /** Marking it unread again, which is how most people say "come back to this". */
    val markUnread: (() -> Unit)? = null,
    /**
     * Filing it anywhere, by mailbox id.
     *
     * Archive, Spam and Delete are the three folders worth their own button; everything
     * else a mailbox has is this. Null on an account with nowhere else to put it.
     */
    val moveInto: ((String) -> Unit)? = null,
    /** The folders that move can offer, already in the order the sidebar shows them. */
    val folders: List<Mailbox> = emptyList(),
    /**
     * The whole conversation, as one act.
     *
     * Separate from the buttons above, which are all about the one message on screen. A
     * thread is what people think in and the list already collapses one into a single row,
     * so a row standing for twelve messages that archives one of them is the row lying.
     */
    val conversation: ConversationActions? = null,
)

/** What can be done to a conversation. Null where the open message is not in one. */
internal data class ConversationActions(
    val count: Int,
    val unread: Int,
    val muted: Boolean,
    /** One sentence on where the mute lives, from [muteNote]. Null says nothing. */
    val muteNote: String? = null,
    val onRead: (Boolean) -> Unit,
    val onArchive: () -> Unit,
    val onTrash: () -> Unit,
    val onMute: (Boolean) -> Unit,
    /** This one message out into a conversation of its own, on this computer. See [Rethreading]. */
    val onSplit: (() -> Unit)? = null,
)

/**
 * A batch move, and where it came from.
 *
 * Kept so it can be put back. Undo for a move is only ever a move in the other direction,
 * which is the whole reason batch actions are moves and nothing else: filing fifty messages
 * by accident is recoverable, and a client that makes that unrecoverable is one people
 * stop using for anything but reading.
 */
/** One account's share of a batch that was moved, and where to put it back. */
internal data class Move(val accountKey: String, val ids: List<String>, val fromMailboxId: String)

internal data class Undoable(
    val moves: List<Move>,
    /** What to call it on screen, already in the past tense. */
    val what: String,
    /** The whole sentence, for something that was not a move. Null says it with [movedNotice]. */
    val notice: String? = null,
    /** Puts back a change kept on this computer (a join or a split), in place of [moves]. */
    val local: (() -> Unit)? = null,
) {
    val count: Int get() = moves.sumOf { it.ids.size }
}

/** Set by a right-click just before it selects a row, read once by the list's onSelect. */
internal object RowPress { var menu = false }
