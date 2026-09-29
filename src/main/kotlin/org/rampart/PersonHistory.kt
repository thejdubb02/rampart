package org.rampart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/*
 * A person's history: every message from or to any address they have used, in every
 * signed-in account and every folder, newest first. Kaneo RAM-108.
 *
 * The server is asked first, because it holds everything and the copy on this computer
 * holds only what has been read. One JMAP request per account carries the first page and
 * the counts together, and the accounts are asked at the same time. An IMAP account, or a
 * JMAP one that cannot be reached, is answered from the copy instead, and the view says
 * which answered, because "nothing found" means something different in each case.
 *
 * Paged like a folder: a hundred at a time, and each account is only asked for its next
 * hundred when the merged list actually needs them. Nothing here holds more than a page
 * or so per account, which is what keeps a 50,000 message mailbox cheap.
 */

/** Who a history is about: a name to show and every address to look for. */
internal data class Correspondent(val name: String, val addresses: List<String>) {
    val normalised: List<String> = addresses.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
}

/**
 * The header's numbers. Sent is mail to them, received is mail from them, and the dates are
 * the oldest and newest message either way. ISO instants, so they compare as strings.
 */
internal data class PersonStats(
    val received: Int = 0,
    val sent: Int = 0,
    val first: String? = null,
    val last: String? = null,
) {
    operator fun plus(other: PersonStats) = PersonStats(
        received = received + other.received,
        sent = sent + other.sent,
        first = listOfNotNull(first, other.first).filter { it.isNotBlank() }.minOrNull(),
        last = listOfNotNull(last, other.last).filter { it.isNotBlank() }.maxOrNull(),
    )
}

/** Who answered for one account. */
internal enum class Answered { SERVER, COPY, NOBODY }

/**
 * One account's page as the server gave it.
 *
 * [consumed] is how many positions the server stepped over, which can be more than [rows]:
 * JMAP's `from` and `to` conditions match text in the header, so `bob@example.org` also
 * finds `jimbob@example.org`, and those are dropped here after the fact. Paging moves on
 * by what the server counted, not by what was kept, or the next page would repeat rows.
 */
internal data class PersonPage(val rows: List<Summary>, val consumed: Int, val stats: PersonStats? = null)

/** From, to, cc or bcc on any of the addresses: the whole history. */
internal fun personFilter(addresses: List<String>): JsonObject =
    anyOf(addresses.flatMap { a -> listOf("from" to a, "to" to a, "cc" to a, "bcc" to a) })

/** Mail from them, for the received count. */
internal fun fromFilter(addresses: List<String>): JsonObject = anyOf(addresses.map { "from" to it })

/** Mail to them, in any recipient header, for the sent count. */
internal fun toFilter(addresses: List<String>): JsonObject =
    anyOf(addresses.flatMap { a -> listOf("to" to a, "cc" to a, "bcc" to a) })

/**
 * An OR of single conditions, always as an operator even for one, so every filter this file
 * builds has the same shape and a server that handles one handles them all.
 */
private fun anyOf(conditions: List<Pair<String, String>>): JsonObject = buildJsonObject {
    put("operator", "OR")
    putJsonArray("conditions") {
        conditions.forEach { (field, value) -> add(buildJsonObject { put(field, value) }) }
    }
}

/** Whether a row really involves one of the addresses, exactly, rather than as a substring. */
internal fun involves(fromEmail: String, recipients: Collection<String>, addresses: Collection<String>): Boolean {
    val wanted = addresses.map { it.lowercase() }.toSet()
    return fromEmail.trim().lowercase() in wanted || recipients.any { it.trim().lowercase() in wanted }
}

/**
 * The same message seen twice is shown once.
 *
 * Across accounts that is the same Message-ID, which is what happens when a list writes to
 * two of your addresses or you copy yourself. Within one account it is the id, which is how
 * a message moving between pages while you scroll would otherwise appear twice.
 */
internal fun dedupeKey(row: Summary): String =
    row.messageId.trim().lowercase().ifEmpty { row.account + "\u0000" + row.id }

/** Where one account's pages come from. */
internal interface PersonSource {
    val account: String
    /** Who answered the last page. */
    val answered: Answered
    fun page(position: Int, limit: Int, withStats: Boolean): PersonPage
}

/**
 * One account: the server while it answers, the copy when it cannot or will not.
 *
 * The switch happens only on the first page. Halfway down, a server that stops answering
 * ends that account's share rather than carrying on from the copy, whose positions are not
 * the server's and would repeat or skip mail at the join.
 */
internal class AccountSource(
    override val account: String,
    private val backend: MailBackend?,
    private val store: Store?,
    private val addresses: List<String>,
) : PersonSource {
    override var answered: Answered = Answered.NOBODY
        private set

    override fun page(position: Int, limit: Int, withStats: Boolean): PersonPage {
        if (position == 0 || answered == Answered.SERVER) {
            val fromServer = if (answered == Answered.COPY) null else runCatching {
                backend?.personPage(addresses, position, limit, withStats)
            }.getOrElse { if (position > 0) return PersonPage(emptyList(), 0) else null }
            if (fromServer != null) {
                answered = Answered.SERVER
                return fromServer.copy(rows = fromServer.rows.map { it.copy(account = account) })
            }
        }
        val copy = store ?: return PersonPage(emptyList(), 0).also { answered = Answered.NOBODY }
        answered = Answered.COPY
        val rows = copy.personPage(addresses, limit, position)
        return PersonPage(
            rows = rows.map { it.copy(account = account) },
            consumed = rows.size,
            stats = if (withStats) copy.personStats(addresses) else null,
        )
    }
}

/**
 * Every account's pages merged into one list, newest first, a page at a time.
 *
 * Before taking a page, each account still able to give more holds at least a page of its
 * own, fetched only if it did not already. Taking the newest head of all of them a page's
 * worth of times is then exactly what a single merged query would have returned, and no
 * account ever has more than about two pages in memory.
 */
internal class PersonHistory(private val sources: List<PersonSource>, private val pageSize: Int = LIST_PAGE) {
    private class Lane(val source: PersonSource) {
        val waiting = ArrayDeque<Summary>()
        var position = 0
        var done = false
    }

    private val lanes = sources.map { Lane(it) }
    private val shown = HashSet<String>()
    private var asked = false

    var stats = PersonStats()
        private set

    /** Nothing more to give, from any account. */
    val exhausted: Boolean get() = lanes.all { it.done && it.waiting.isEmpty() }

    /** Who answered, by account. */
    fun answered(): Map<String, Answered> = lanes.associate { it.source.account to it.source.answered }

    /** The next page of the merged list. The first call also gathers the counts. */
    suspend fun next(): List<Summary> {
        val first = !asked
        asked = true
        var withStats = first
        while (true) {
            val needy = lanes.filter { !it.done && it.waiting.size < pageSize }
            if (needy.isEmpty()) break
            val pages = coroutineScope {
                needy.map { lane ->
                    async(Dispatchers.IO) {
                        runCatching { lane.source.page(lane.position, pageSize, withStats) }
                            .getOrElse { PersonPage(emptyList(), 0) }
                    }
                }.awaitAll()
            }
            withStats = false
            needy.zip(pages).forEach { (lane, page) ->
                page.stats?.let { stats += it }
                lane.waiting.addAll(page.rows)
                lane.position += page.consumed
                if (page.consumed < pageSize) lane.done = true
            }
        }
        val out = ArrayList<Summary>(pageSize)
        while (out.size < pageSize) {
            val lane = lanes.filter { it.waiting.isNotEmpty() }.maxByOrNull { it.waiting.first().receivedAt } ?: break
            val row = lane.waiting.removeFirst()
            if (shown.add(dedupeKey(row))) out += row
        }
        return out
    }
}

/**
 * One sentence on who answered, for under the header. Silent when every account's server
 * did, which is the ordinary case and needs no comment.
 */
internal fun answeredNote(answered: Map<String, Answered>, names: Map<String, String>): String? {
    fun name(key: String) = names[key]?.ifBlank { null } ?: key
    val copy = answered.filterValues { it == Answered.COPY }.keys.map(::name)
    val nobody = answered.filterValues { it == Answered.NOBODY }.keys.map(::name)
    if (copy.isEmpty() && nobody.isEmpty()) return null
    return buildList {
        if (copy.isNotEmpty()) {
            add(
                "${joinNames(copy)} answered from the copy on this computer, which holds only mail " +
                    "Rampart has already read, because the server could not be asked.",
            )
        }
        if (nobody.isNotEmpty()) add("${joinNames(nobody)} could not be asked, and has no copy on this computer.")
    }.joinToString(" ")
}

private fun joinNames(names: List<String>): String = when (names.size) {
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}
