package org.rampart

/*
 * What a collapsed conversation row says, and which rows are one conversation.
 *
 * Plain functions of their arguments, with no drawing and no network in them, so every
 * rule here is tested directly. ThreadRowsState.kt holds what is known at run time and
 * hands it in; the message list draws whatever comes out, in every layout, because this
 * is the one place a row is decided.
 */

/**
 * Every message of one conversation, as the server last described it.
 *
 * [members] are in the server's own thread order, which is date order. [boxes] is which
 * folders each member is in, by message id, so a row can tell a reply sitting in Sent from
 * one that was deleted and is sitting in Trash.
 *
 * [asOf] is when this was fetched and [row] is the list row it was fetched for, so a
 * newer row (a new reply, a changed count) is noticed and fetched again.
 */
internal data class ThreadGist(
    val members: List<Summary>,
    val boxes: Map<String, Set<String>> = emptyMap(),
    val asOf: Long = 0L,
    val row: String = "",
)

/** A draft is somebody's unfinished reply, not something that arrived. */
internal fun isDraft(message: Summary): Boolean =
    message.keywords.any { it.equals("\$draft", ignoreCase = true) }

/**
 * The newest message of a conversation, which is the one its row describes.
 *
 * Drafts are passed over: a half-written reply is not the latest thing that happened in a
 * conversation, and a row reading "Me, draft" hides the reply that is actually waiting.
 * Only a conversation that is nothing but drafts falls back to its newest draft.
 *
 * A tie on the timestamp goes to the one later in [members]. The server lists a thread in
 * date order and breaks its own ties, so later in the list is its answer to which came
 * second, and a row that swapped between two equal messages on every refresh would look
 * broken.
 */
internal fun latestOf(members: List<Summary>): Summary? {
    fun newest(among: List<Summary>): Summary? {
        var best: Summary? = null
        for (candidate in among) {
            val current = best
            if (current == null || candidate.receivedAt >= current.receivedAt) best = candidate
        }
        return best
    }
    return newest(members.filterNot(::isDraft)) ?: newest(members)
}

/**
 * The row for [raw], a list row standing for the conversation [members].
 *
 * Sender, subject, preview and time are the newest message's. Unread and starred are the
 * whole conversation's: unread when any message in it is unread, starred when any is
 * starred. Drafts count for neither, because a draft is always "read" and says nothing
 * about whether the conversation has been.
 *
 * Everything else is [raw]'s own: its id, account, keywords and the rest. That is what
 * keeps every action, the reader and the selection exactly where they were, because they
 * all go by the id of the message the folder actually holds.
 *
 * A draft row is left as it is. The Drafts folder lists drafts, and a draft row redrawn as
 * the message it answers would put the wrong thing under the pointer.
 *
 * The row's own state is taken from [raw] rather than from [members], because the list
 * row is repainted the moment anything is marked and the conversation may have been
 * fetched a minute earlier.
 */
internal fun threadSummary(raw: Summary, members: List<Summary>, size: Int = members.size): Summary {
    if (members.isEmpty() || isDraft(raw)) return raw
    val current = members.map { if (it.id == raw.id) raw else it }
    val latest = latestOf(current) ?: return raw
    val counted = current.filterNot(::isDraft).ifEmpty { current }
    val shown = raw.copy(
        from = latest.from,
        fromEmail = latest.fromEmail,
        subject = latest.subject,
        receivedAt = latest.receivedAt,
        preview = latest.preview,
        seen = counted.all { it.seen },
        flagged = counted.any { it.flagged },
        threadSize = maxOf(size, 1),
    )
    // The same object when nothing changed, so a row with nothing to add is exactly the
    // row it was and the list does no extra work for it.
    return if (shown == raw) raw else shown
}

/**
 * A conversation moved around by hand, on one account: which conversations were joined
 * into one and which messages were split out into their own.
 *
 * [joins] maps a server thread id to the group it was joined into. [splits] is message
 * ids. Both are only ever read against the account they were made on, because thread and
 * message ids are only unique inside one account.
 *
 * **Kept on this computer, and never sent anywhere.** JMAP has no way to change a message's
 * threadId: the server assigns it from the headers when the message arrives and the
 * protocol offers nothing to set it (RFC 8621 4.1.1, where threadId is immutable and
 * server-set), and IMAP has no thread to change at all. The only way to move a message
 * between conversations on the server would be rewriting its References and saving it as a
 * new message, which is changing somebody's mail to suit a view of it. So this is the one
 * kind of conversation change that lives beside the mail rather than in it.
 */
internal data class Rethreading(
    val joins: Map<String, String> = emptyMap(),
    val splits: Set<String> = emptySet(),
) {
    /** Every thread joined with [threadId], itself included. Just itself when it is not joined. */
    fun joinedWith(threadId: String): Set<String> {
        val group = joins[threadId] ?: return setOf(threadId)
        return joins.filterValues { it == group }.keys + threadId
    }

    /**
     * [threads] made one conversation, with every group any of them was already in.
     *
     * [messages] are the rows that were picked. A picked message that had been split out
     * is taken back into its own conversation, since joining it is asking for it to be in
     * one again.
     *
     * The group is named after its smallest thread id, so the same join made twice comes
     * out the same and a test can say what it expects.
     */
    fun joined(threads: Collection<String>, messages: Collection<String> = emptyList()): Rethreading {
        val wanted = threads.filter { it.isNotBlank() }.flatMap { joinedWith(it) }.toSet()
        if (wanted.size < 2) return copy(splits = splits - messages.toSet())
        val name = wanted.min()
        return Rethreading(
            joins = joins.filterKeys { it !in wanted } + wanted.associateWith { name },
            splits = splits - messages.toSet(),
        )
    }

    /** [messageId] in a conversation of its own, whichever conversation it was in. */
    fun split(messageId: String): Rethreading = copy(splits = splits + messageId)

    /**
     * Which conversation [message] is in, as a key that only means anything compared with
     * another row of the same account.
     *
     * A row that is not joined to anything is keyed by its own id, not its thread. Two rows
     * of one thread only appear in a list that does not collapse threads (a search, a saved
     * search, an IMAP folder), and folding those together is a change nobody asked for.
     */
    fun keyOf(message: Summary): String {
        if (message.id in splits) return "m:" + message.id
        val group = joins[threadKey(message)] ?: return "m:" + message.id
        return "g:$group"
    }

    val empty: Boolean get() = joins.isEmpty() && splits.isEmpty()
}

/**
 * The thread a row belongs to, or its own id on a server that does not thread.
 *
 * So a message from an IMAP account, which has no thread id to give, can still be joined:
 * it stands for a conversation of one.
 */
internal fun threadKey(message: Summary): String = message.threadId.ifBlank { message.id }

/**
 * One row of the list as it is drawn.
 *
 * [shown] is what the row says. [raw] is the list row it stands on, and every action, the
 * selection and the reader use [raw], never [shown]. [rows] is every list row folded into
 * this one, which is more than [raw] only for a join. [members] is every message known to
 * be in the conversation.
 */
internal data class ThreadRow(
    val shown: Summary,
    val raw: Summary,
    val rows: List<Summary> = listOf(raw),
    val members: List<Summary> = listOf(raw),
)

/**
 * The list, as the rows it is drawn with.
 *
 * [rows] are the list rows as the folder gave them. [accountOf] names the account a row
 * belongs to, which for a folder of one account is that account even though the row does
 * not say so. [gistOf] is the conversation behind a row, where it is known.
 * [rethreadingOf] is each account's joins and splits. [leaveOut] is the folders whose
 * messages do not count as the latest (Trash and Junk), unless the row is itself in one.
 *
 * With nothing joined, nothing split and nothing known about any conversation, this gives
 * back the rows exactly as they came, one for one, which is what keeps every layout
 * looking as it did.
 */
internal fun threadRows(
    rows: List<Summary>,
    accountOf: (Summary) -> String,
    gistOf: (account: String, threadId: String) -> ThreadGist? = { _, _ -> null },
    rethreadingOf: (account: String) -> Rethreading = { Rethreading() },
    leaveOut: (account: String) -> Set<String> = { emptySet() },
): List<ThreadRow> {
    class Part(val row: Summary, val account: String, val members: List<Summary>, val boxes: Map<String, Set<String>>, val size: Int)

    val present = rows.map { accountOf(it) to it.id }.toMutableSet()
    val units = ArrayList<Part>(rows.size)
    for (row in rows) {
        val account = accountOf(row)
        val re = rethreadingOf(account)
        // A gist that does not include the row it is meant to describe is somebody else's,
        // or out of date, and is not used.
        val gist = row.threadId.takeIf { it.isNotBlank() }?.let { gistOf(account, it) }
            ?.takeIf { g -> g.members.any { it.id == row.id } }
        if (gist == null) {
            units += Part(row, account, listOf(row), emptyMap(), row.threadSize)
            continue
        }
        val ownBoxes = gist.boxes[row.id].orEmpty()
        // Another message counts as in this folder when it shares a folder with the row. The
        // row is in the folder being looked at, so a shared folder is that folder.
        fun inFolder(m: Summary) = m.id == row.id || (ownBoxes.isNotEmpty() && gist.boxes[m.id].orEmpty().any { it in ownBoxes })
        val kept = gist.members.filterNot { it.id in re.splits }
        if (row.id in re.splits) {
            units += Part(row, account, listOf(row), gist.boxes, 1)
            // The rest of the conversation keeps a row of its own. The server folded it into
            // this one, so without this, splitting the newest message out would make the
            // conversation it came from vanish from the folder.
            val rest = latestOf(kept.filter(::inFolder).filterNot(::isDraft))
            if (rest != null && present.add(account to rest.id)) {
                units += Part(rest.copy(account = row.account, threadSize = kept.size), account, kept, gist.boxes, kept.size)
            }
        } else {
            units += Part(row, account, kept, gist.boxes, kept.size)
        }
        // A message split out of this conversation, still in this folder, gets its own row,
        // for the same reason: the server's collapsed list has no row for it.
        gist.members.filter { it.id in re.splits && it.id != row.id && inFolder(it) && !isDraft(it) }.forEach { m ->
            if (present.add(account to m.id)) {
                units += Part(m.copy(account = row.account, threadSize = 1), account, listOf(m), gist.boxes, 1)
            }
        }
    }

    val groups = LinkedHashMap<String, MutableList<Part>>()
    for (unit in units) {
        val key = unit.account + "\u0000" + rethreadingOf(unit.account).keyOf(unit.row)
        groups.getOrPut(key) { ArrayList() } += unit
    }

    return groups.values.map { group ->
        // The row that stays is the folder's newest, so a join is filed, opened and selected
        // as the message the folder shows for it. The first one wins a tie.
        var head = group.first()
        for (unit in group) if (unit.row.receivedAt > head.row.receivedAt) head = unit
        val raw = head.row
        val members = group.flatMap { it.members }.distinctBy { it.id }.sortedBy { it.receivedAt }
        val boxes = group.fold(emptyMap<String, Set<String>>()) { all, unit -> all + unit.boxes }
        val size = group.sumOf { it.size }
        if (group.size == 1 && head.boxes.isEmpty() && members.size == 1 && members.first().id == raw.id) {
            return@map ThreadRow(raw, raw, listOf(raw), members)
        }
        val leave = leaveOut(head.account)
        // Trash and Junk do not count, unless the row is itself in one of them: in Trash,
        // what was deleted is the point.
        val rowLeft = boxes[raw.id].orEmpty().let { it.isNotEmpty() && it.all { box -> box in leave } }
        val counted = if (rowLeft || leave.isEmpty()) members else members.filter { m ->
            val its = boxes[m.id].orEmpty()
            m.id == raw.id || its.isEmpty() || !its.all { it in leave }
        }
        ThreadRow(threadSummary(raw, counted, size), raw, group.map { it.row }, members)
    }
}

/**
 * The messages of a conversation opened in the reader, after the joins and splits.
 *
 * [found] is every message of every thread in the conversation, in any order and with
 * repeats. The answer is oldest first with each message once, which is how the reader
 * stacks a conversation. One message is no conversation, and comes back empty the way
 * [MailBackend.thread] answers for a thread of one.
 */
internal fun rethreaded(found: List<Summary>, re: Rethreading, opened: Summary): List<Summary> {
    if (opened.id in re.splits) return emptyList()
    val all = found.distinctBy { it.id }.filterNot { it.id in re.splits }.sortedBy { it.receivedAt }
    return if (all.size <= 1) emptyList() else all
}

/**
 * Which threads to ask about in one request, and which not to ask about at all.
 *
 * [threads] is each thread id with how many messages the list says it has. A request is
 * kept to about [cap] messages, because a server limits how many objects one Email/get may
 * return and a whole page of long conversations would be refused outright. A single thread
 * longer than [cap] is left out: a mailing list thread of a thousand is not worth fetching
 * whole to decide one row, and that row is drawn as the server gave it.
 */
internal fun gistBatches(threads: List<Pair<String, Int>>, cap: Int = 200): List<List<String>> {
    val batches = ArrayList<List<String>>()
    var current = ArrayList<String>()
    var total = 0
    for ((id, size) in threads.distinctBy { it.first }) {
        if (size > cap) continue
        if (total + size > cap && current.isNotEmpty()) {
            batches += current
            current = ArrayList()
            total = 0
        }
        current += id
        total += size
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

/**
 * Which messages of a collapsed row to mark, when the row is marked read or unread.
 *
 * All of them that are not already that way, drafts left out. The row says unread when any
 * message behind it is, so marking only the one message the folder holds would leave the
 * row exactly as it was and the button would look broken.
 */
internal fun toMark(row: ThreadRow, read: Boolean): Set<String> =
    row.members.filterNot(::isDraft).filter { m ->
        val seen = if (m.id == row.raw.id) row.raw.seen else m.seen
        seen != read
    }.map { it.id }.toSet()

/**
 * Which messages of a collapsed row lose their star when the row's star is taken off.
 *
 * Every starred one: the row is starred when any is, so taking the star off one would leave
 * it starred. Putting a star on is still the folder's own message, the same as before rows
 * spoke for the whole conversation.
 */
internal fun toUnstar(row: ThreadRow): Set<String> =
    row.members.filter { m -> if (m.id == row.raw.id) row.raw.flagged else m.flagged }.map { it.id }.toSet()

/**
 * [value] as the rows of the store's rethread table: kind, id and group.
 *
 * Sorted, so the same joins are always written the same way and a file compared before and
 * after shows only what changed.
 */
internal fun rethreadRows(value: Rethreading): List<Triple<String, String, String>> =
    value.joins.entries.sortedBy { it.key }.map { Triple("join", it.key, it.value) } +
        value.splits.sorted().map { Triple("split", it, "") }

/**
 * The rows of the rethread table read back. A kind this version does not know is skipped
 * rather than refused, so a newer Rampart's rows never stop an older one opening the file.
 */
internal fun rethreadingOf(rows: List<Triple<String, String, String>>): Rethreading {
    val joins = HashMap<String, String>()
    val splits = HashSet<String>()
    rows.forEach { (kind, id, group) ->
        when (kind) {
            "join" -> if (group.isNotBlank()) joins[id] = group
            "split" -> splits += id
        }
    }
    return Rethreading(joins, splits)
}
