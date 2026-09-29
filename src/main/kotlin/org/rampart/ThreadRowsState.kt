package org.rampart

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * What the message list knows about conversations at run time, and the list's actions
 * made to act on a conversation rather than on one message of it.
 *
 * The rules are in ThreadRows.kt. This file only holds what they are applied to: the
 * conversations fetched behind each row, and each account's joins and splits.
 */

/**
 * What the list needs to know to collapse its rows, beyond the rows themselves.
 *
 * [account] is the account a row with no account of its own belongs to, which is the
 * folder's account; null in the merged inbox, where every row carries one. [collapse] is
 * off for a search and a saved search: a result is a message that matched, and redrawing it
 * as the newest message of its conversation would hide what was searched for. [leaveOut]
 * is Trash and Junk for each account, see [threadRows].
 *
 * A plain value rather than functions, so the list can remember its rows against it and
 * only work them out again when something in it changed.
 */
internal data class ThreadContext(
    val account: String? = null,
    val collapse: Boolean = false,
    val leaveOut: Map<String, Set<String>> = emptyMap(),
)

/** The list's rows, collapsed under [context]. See [threadRows]. */
internal fun threadRowsFor(emails: List<Summary>, context: ThreadContext): List<ThreadRow> {
    if (!context.collapse) return emails.map { ThreadRow(it, it) }
    return threadRows(
        emails,
        accountOf = { it.account.ifBlank { context.account.orEmpty() } },
        gistOf = { account, thread -> ThreadGists.gist(account, thread) },
        rethreadingOf = { Rethreads.of(it) },
        leaveOut = { context.leaveOut[it].orEmpty() },
    )
}

/**
 * The conversation behind each row, as far as it has been fetched.
 *
 * Held for the whole window rather than per folder, so going back to a folder draws its
 * rows complete at once. Snapshot state only through [version]: the list reads that as a
 * key, so a conversation arriving redraws the rows it changes and nothing else is watched.
 */
internal object ThreadGists {
    var version by mutableIntStateOf(0)
        private set

    private val known = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, ThreadGist>()

    /** Said once rather than on every refresh, since a failure here is not something to fix. */
    private var told = false

    fun gist(account: String, threadId: String): ThreadGist? = known[account to threadId]

    /**
     * Marked here as soon as the list or the reader marks it, so a row changes with the
     * click rather than a minute later when the conversation is next fetched.
     */
    fun paint(account: String, ids: Set<String>, seen: Boolean? = null, flagged: Boolean? = null) {
        if (ids.isEmpty() || account.isBlank()) return
        var changed = false
        known.entries.filter { it.key.first == account }.forEach { entry ->
            val gist = entry.value
            if (gist.members.none { it.id in ids }) return@forEach
            entry.setValue(
                gist.copy(
                    members = gist.members.map { m ->
                        if (m.id !in ids) m else m.copy(seen = seen ?: m.seen, flagged = flagged ?: m.flagged)
                    },
                ),
            )
            changed = true
        }
        if (changed) version++
    }

    /**
     * Fetches the conversations behind [rows] that are not known, or not known lately.
     *
     * Only rows that stand for more than one message: a conversation of one is already all
     * on the row. A conversation is asked about again when its row changes (a new reply, a
     * new count) or after [STALE_MS], which is how a message read on the phone stops
     * counting as unread here.
     *
     * Returns the first failure, once. A row whose conversation could not be read is drawn
     * the way the server gave it, which is how it was drawn before any of this, so the
     * failure is worth one sentence and not one per refresh.
     */
    suspend fun fill(
        rows: List<Summary>,
        accountOf: (Summary) -> String?,
        backendOf: (String) -> MailBackend?,
    ): Throwable? {
        val now = System.currentTimeMillis()
        val wanted = rows.filter { it.threadSize > 1 && it.threadId.isNotBlank() }.mapNotNull { row ->
            val account = accountOf(row) ?: return@mapNotNull null
            val had = known[account to row.threadId]
            if (had != null && had.row == signature(row) && now - had.asOf < STALE_MS) null else account to row
        }
        var failure: Throwable? = null
        wanted.groupBy({ it.first }, { it.second }).forEach { (account, list) ->
            val backend = backendOf(account) ?: return@forEach
            val byThread = list.associateBy { it.threadId }
            gistBatches(list.map { it.threadId to it.threadSize }).forEach { batch ->
                val got = withContext(Dispatchers.IO) { runCatching { backend.threadMembers(batch) } }
                val found = got.getOrElse {
                    if (failure == null) failure = it
                    return@forEach
                }
                found.forEach { (thread, gist) ->
                    val row = byThread[thread] ?: return@forEach
                    known[account to thread] = gist.copy(asOf = now, row = signature(row))
                }
                if (found.isNotEmpty()) version++
            }
        }
        if (failure == null || told) return null
        told = true
        return failure
    }

    private fun signature(row: Summary) = "${row.id}|${row.threadSize}|${row.receivedAt}"

    /** Five minutes: long enough not to ask on every poll, short enough that a phone's reading shows. */
    private const val STALE_MS = 5 * 60 * 1000L
}

/**
 * Each account's joins and splits, read from its local store once and kept in memory.
 *
 * The list is drawn many times a second while scrolling and must not read a file to do it,
 * so the store is read when an account is first seen and written whenever something
 * changes, and the list only ever reads this.
 */
internal object Rethreads {
    var version by mutableIntStateOf(0)
        private set

    /**
     * Moves only when a split, or its undo, changes the conversation open in the reader,
     * never when an account is first read. The reader reopens its conversation on this, and
     * reopening it because a file finished loading would be a second fetch nobody asked
     * for. A join is made from the list, where nothing is open to redraw.
     */
    var changes by mutableIntStateOf(0)
        private set

    private val byAccount = HashMap<String, Rethreading>()

    fun of(account: String): Rethreading = byAccount[account] ?: Rethreading()

    /**
     * Reads [account]'s from its store, the first time only. No store means nothing joined.
     *
     * [store] is a function because opening an account's store the first time touches the
     * disk and the credential store, and that is done off the window's thread, here.
     * Returns why it could not be read, once; the account then shows nothing joined.
     */
    suspend fun load(account: String, store: () -> Store?): String? {
        if (account in byAccount) return null
        val read = withContext(Dispatchers.IO) { runCatching { store()?.rethreading() } }
        if (account in byAccount) return null
        byAccount[account] = read.getOrNull() ?: Rethreading()
        if (read.getOrNull()?.empty == false) version++
        return read.exceptionOrNull()?.let(::whyFailed)
    }

    /**
     * Saves [next] for [account], and only then shows it, so what is on screen is never a
     * join the store did not take. Returns why it could not be saved, or null.
     */
    suspend fun save(account: String, store: () -> Store?, next: Rethreading, reopen: Boolean = false): String? {
        val saved = withContext(Dispatchers.IO) {
            runCatching { store()?.also { it.setRethreading(next) } }
        }
        saved.exceptionOrNull()?.let { return whyFailed(it) }
        if (saved.getOrNull() == null) return NO_STORE
        byAccount[account] = next
        version++
        if (reopen) changes++
        return null
    }

    /** One sentence for an account with no local copy, which is where joins are kept. */
    const val NO_STORE = "Joins and splits are kept in this account's local copy, and it is not open on this computer."
}

/** One sentence for the join that was refused because it crossed accounts. */
internal const val JOIN_ACROSS_ACCOUNTS =
    "Only conversations from the same account can be joined, because each server numbers its own."

/**
 * The conversation [opened] belongs to after the joins and splits on its account.
 *
 * Exactly [MailBackend.thread] when nothing was joined to it and nothing split out of it,
 * so the ordinary case asks the server the same question it always did. [known] is the
 * rows already on screen, for a joined thread the server cannot describe (a conversation
 * of one on IMAP, which has no thread to ask about).
 */
internal fun rethreadedThread(
    backend: MailBackend,
    opened: Summary,
    re: Rethreading,
    known: List<Summary> = emptyList(),
): List<Summary> = rethreadedThread(opened, re, known, backend::thread, backend::threadMembers)

/** The same, with the two server questions passed in, which is what the tests use. */
internal fun rethreadedThread(
    opened: Summary,
    re: Rethreading,
    known: List<Summary>,
    thread: (String) -> List<Summary>,
    members: (Collection<String>) -> Map<String, ThreadGist>,
): List<Summary> {
    if (opened.id in re.splits) return emptyList()
    val group = re.joinedWith(threadKey(opened))
    if (group.size <= 1) {
        val server = thread(opened.threadId)
        if (server.none { it.id in re.splits }) return server
        return rethreaded(server, re, opened)
    }
    val found = members(group)
    val all = group.flatMap { id ->
        found[id]?.members?.takeIf { it.isNotEmpty() } ?: known.filter { threadKey(it) == id }
    }
    return rethreaded(all + opened, re, opened)
}

/**
 * [base], acting on the conversation a collapsed row stands for.
 *
 * Nothing changes for a row that is one message, since then the row, its conversation and
 * its one list row are all the same message. For the rest:
 *
 * - **Read** marks every message of the conversation that is unread, since the row is
 *   unread while any of them is. **Unread** marks the folder's own message only, as it
 *   always did: that is enough to make the row unread, and marking a whole conversation
 *   unread would lose which parts of it were actually read.
 * - **Taking a star off** takes it off every starred message, since the row is starred
 *   while any of them is. Putting one on stars the folder's own message, as it always did.
 * - **Filing** (archive, delete, spam, snooze, move) files each list row the row stands
 *   for, which is one for an ordinary conversation, exactly as before, and one per
 *   conversation for a join.
 * - Everything else (reply, forward, a filter, a scheduled send) is about one message and
 *   gets the folder's own, never the newest one the row is showing.
 *
 * [rowOf] finds the collapsed row for whatever the list hands back.
 */
internal fun threadActions(base: RowActions, rowOf: (Summary) -> ThreadRow?): RowActions {
    fun raw(m: Summary) = rowOf(m)?.raw ?: m
    fun each(m: Summary) = rowOf(m)?.rows ?: listOf(m)
    return base.copy(
        reply = base.reply?.let { reply -> { m: Summary, all: Boolean -> reply(raw(m), all) } },
        forward = base.forward?.let { f -> { m: Summary -> f(raw(m)) } },
        forwardFile = base.forwardFile?.let { f -> { m: Summary -> f(raw(m)) } },
        archive = base.archive?.let { f -> { m: Summary -> each(m).forEach(f) } },
        junk = base.junk?.let { f -> { m: Summary -> each(m).forEach(f) } },
        notJunk = base.notJunk?.let { f -> { m: Summary -> each(m).forEach(f) } },
        isJunk = base.isJunk?.let { f -> { m: Summary -> f(raw(m)) } },
        trash = base.trash?.let { f -> { m: Summary -> each(m).forEach(f) } },
        snooze = base.snooze?.let { f -> { m: Summary, until: SnoozeUntil -> each(m).forEach { f(it, until) } } },
        followUp = base.followUp?.let { f -> { m: Summary -> f(raw(m)) } },
        moveInto = base.moveInto?.let { f -> { m: Summary, into: String -> each(m).forEach { f(it, into) } } },
        folders = base.folders?.let { f -> { m: Summary -> f(raw(m)) } },
        filter = base.filter?.let { f -> { m: Summary -> f(raw(m)) } },
        sendScheduled = base.sendScheduled?.let { f -> { m: Summary -> f(raw(m)) } },
        cancelScheduled = base.cancelScheduled?.let { f -> { m: Summary -> f(raw(m)) } },
        markRead = base.markRead?.let { mark ->
            { m: Summary, read: Boolean ->
                val row = rowOf(m)
                val own = raw(m)
                mark(own, read)
                val others = if (!read) emptySet() else row?.let { toMark(it, true) - own.id }.orEmpty()
                if (others.isNotEmpty()) base.markIds?.invoke(own, others, read)
            }
        },
        star = base.star?.let { star ->
            { m: Summary ->
                val row = rowOf(m)
                val own = raw(m)
                val unstar = row?.let(::toUnstar).orEmpty()
                val starIds = base.starIds
                if (m.flagged && unstar.isNotEmpty() && starIds != null) {
                    starIds(own, unstar, false)
                } else {
                    star(own)
                }
            }
        },
    )
}
