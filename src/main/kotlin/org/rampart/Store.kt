package org.rampart

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createDirectories

/**
 * The local copy of a mailbox.
 *
 * Everything Rampart showed used to be fetched live, which meant no offline, no search that
 * answers as you type, nowhere to keep anything, and a refresh that re-asked the server for
 * what it had already said. This is the file that fixes all four.
 *
 * SQLite, one file per account, with FTS5 over the text so search is a query rather than a
 * round trip. Encrypted with a key kept in the operating system's credential store, the
 * same place the passwords go: a full copy of somebody's mail sitting in a plain file on a
 * laptop is exactly the thing a mail client should not leave behind.
 *
 * **It is a cache, never the record.** Anything in here can be deleted and re-fetched, and
 * nothing is only here. That is what lets the sync be simple: when in doubt, throw it away
 * and ask again.
 */
internal class Store(private val connection: Connection) : AutoCloseable {

    companion object {
        /** Where an account's copy lives. Beside the accounts file, which is already ours. */
        fun file(account: String): Path {
            val safe = account.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "default" }
            return Accounts.file().parent.resolve("mail-$safe.db")
        }

        /**
         * Opens, and makes the file if it is not there.
         *
         * [key] null opens it unencrypted, which is only for the tests. Everywhere else the
         * key comes from the OS credential store, and a missing one is a reason to run
         * without a local store rather than to write one in the clear.
         */
        fun open(path: Path, key: String?): Store {
            path.parent?.createDirectories()
            /*
             * The key goes in the URL rather than into a `PRAGMA key` afterwards.
             * Both work on a file being created; only this one works on reopening it,
             * because by the time a statement can be run the driver has already tried to
             * read the header and failed with "file is not a database". Checked against
             * the driver rather than reasoned about: with the key in the URL, the right
             * key opens it, no key fails and a wrong key fails.
             */
            val url = buildString {
                append("jdbc:sqlite:").append(path)
                if (key != null) {
                    append("?cipher=sqlcipher&key=")
                    append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                }
            }
            val connection = DriverManager.getConnection(url)
            return Store(connection).apply { prepare() }
        }
    }

    /**
     * The schema, made on every open.
     *
     * `IF NOT EXISTS` throughout rather than a migration table: this is a cache, so the
     * answer to a schema that has moved on is to delete the file and fetch again, and a
     * migration framework for something disposable is work with no payoff.
     */
    private fun prepare() {
        exec(
            """
            CREATE TABLE IF NOT EXISTS message (
                id TEXT PRIMARY KEY,
                mailbox TEXT NOT NULL,
                thread TEXT NOT NULL DEFAULT '',
                threadSize INTEGER NOT NULL DEFAULT 1,
                sender TEXT NOT NULL DEFAULT '',
                senderEmail TEXT NOT NULL DEFAULT '',
                subject TEXT NOT NULL DEFAULT '',
                receivedAt TEXT NOT NULL DEFAULT '',
                preview TEXT NOT NULL DEFAULT '',
                seen INTEGER NOT NULL DEFAULT 0,
                flagged INTEGER NOT NULL DEFAULT 0,
                keywords TEXT NOT NULL DEFAULT ''
            )
            """,
            "CREATE INDEX IF NOT EXISTS message_mailbox ON message (mailbox, receivedAt DESC)",
            // The bodies are separate: a list needs none of them, and keeping them out of
            // the row the list reads is the difference between a fast query and a slow one.
            "CREATE TABLE IF NOT EXISTS body (id TEXT PRIMARY KEY, html TEXT, text TEXT)",
            // FTS5 over what a person would search for. Not a trigger-maintained mirror of
            // the table: the writer below puts both in, and a cache that loses its index is
            // repaired by deleting the file.
            "CREATE VIRTUAL TABLE IF NOT EXISTS search USING fts5(id UNINDEXED, sender, subject, body)",
            "CREATE TABLE IF NOT EXISTS cursor (mailbox TEXT PRIMARY KEY, state TEXT NOT NULL)",
            // What was sent tracked, and what came back. Two tables because one message
            // gets fetched many times and the interesting question is how many.
            """
            CREATE TABLE IF NOT EXISTS tracked (
                id TEXT PRIMARY KEY, messageId TEXT NOT NULL, recipient TEXT NOT NULL,
                subject TEXT NOT NULL, sentAt INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS fetched (
                id TEXT NOT NULL, at INTEGER NOT NULL, userAgent TEXT NOT NULL, network TEXT NOT NULL,
                PRIMARY KEY (id, at)
            )
            """,
            "CREATE INDEX IF NOT EXISTS tracked_message ON tracked (messageId)",
        )
    }

    /** Remembers that a message went out tracked. */
    fun track(tracked: Tracked) {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO tracked (id, messageId, recipient, subject, sentAt) VALUES (?, ?, ?, ?, ?)",
        ).use { s ->
            s.setString(1, tracked.id)
            s.setString(2, tracked.messageId)
            s.setString(3, tracked.recipient)
            s.setString(4, tracked.subject)
            s.setLong(5, tracked.sentAt.toEpochMilli())
            s.executeUpdate()
        }
    }

    /**
     * Writes down fetches the companion reported, and returns the ones this call actually
     * inserted.
     *
     * `INSERT OR IGNORE` on (id, at), so asking again over an overlapping window cannot
     * count one open twice. The cursor moves forward on its own and the two together mean
     * a poll that is interrupted halfway loses nothing and duplicates nothing.
     *
     * The returned list is that same guarantee for the notification. A popup fired on
     * everything the companion sent would fire again for a fetch the store had already
     * ignored, which is the same open announced twice. One statement per row, rather than
     * a batch, because the count of rows changed is what distinguishes an insert from an
     * ignore, and a batch is not required to report that per row.
     */
    fun recordFetches(fetches: List<Fetch>): List<Fetch> {
        if (fetches.isEmpty()) return emptyList()
        val inserted = ArrayList<Fetch>(fetches.size)
        connection.prepareStatement(
            "INSERT OR IGNORE INTO fetched (id, at, userAgent, network) VALUES (?, ?, ?, ?)",
        ).use { s ->
            fetches.forEach { fetch ->
                s.setString(1, fetch.id)
                s.setLong(2, fetch.at.toEpochMilli())
                s.setString(3, fetch.userAgent)
                s.setString(4, fetch.network)
                if (s.executeUpdate() > 0) inserted.add(fetch)
            }
        }
        return inserted
    }

    /**
     * The tracked rows for these ids, and no others.
     *
     * The poll is handed every fetch since the cursor and does not know which account sent
     * which of them, so it asks each open account. [tracking] would answer by reading
     * every message this account ever tracked, on every poll, to find out about the few
     * ids that just arrived.
     */
    fun trackedByIds(ids: List<String>): Map<String, Tracked> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return connection.prepareStatement(
            "SELECT id, messageId, recipient, subject, sentAt FROM tracked WHERE id IN ($placeholders)",
        ).use { s ->
            ids.forEachIndexed { index, id -> s.setString(index + 1, id) }
            s.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val id = rows.getString(1)
                        put(
                            id,
                            Tracked(
                                id = id,
                                messageId = rows.getString(2),
                                account = "",
                                recipient = rows.getString(3),
                                subject = rows.getString(4),
                                sentAt = java.time.Instant.ofEpochMilli(rows.getLong(5)),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Everything sent tracked, newest first, with what has been fetched for each. */
    fun tracking(limit: Int = 500): List<Pair<Tracked, List<Fetch>>> {
        val sent = connection.prepareStatement(
            "SELECT id, messageId, recipient, subject, sentAt FROM tracked ORDER BY sentAt DESC LIMIT ?",
        ).use { s ->
            s.setInt(1, limit)
            s.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            Tracked(
                                id = rows.getString(1),
                                messageId = rows.getString(2),
                                account = "",
                                recipient = rows.getString(3),
                                subject = rows.getString(4),
                                sentAt = java.time.Instant.ofEpochMilli(rows.getLong(5)),
                            ),
                        )
                    }
                }
            }
        }
        if (sent.isEmpty()) return emptyList()
        val byId = HashMap<String, MutableList<Fetch>>()
        connection.prepareStatement("SELECT id, at, userAgent, network FROM fetched ORDER BY at ASC").use { s ->
            s.executeQuery().use { rows ->
                while (rows.next()) {
                    byId.getOrPut(rows.getString(1)) { ArrayList() }.add(
                        Fetch(
                            rows.getString(1),
                            java.time.Instant.ofEpochMilli(rows.getLong(2)),
                            rows.getString(3),
                            rows.getString(4),
                        ),
                    )
                }
            }
        }
        return sent.map { it to byId[it.id].orEmpty() }
    }

    private fun exec(vararg statements: String) {
        connection.createStatement().use { s -> statements.forEach { s.execute(it.trimIndent()) } }
    }

    /**
     * Writes a page of summaries.
     *
     * One transaction, because a hundred inserts each committing on their own is the
     * difference between a refresh you do not notice and one you do.
     */
    fun put(mailbox: String, messages: List<Summary>) {
        if (messages.isEmpty()) return
        val was = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                """
                INSERT INTO message (id, mailbox, thread, threadSize, sender, senderEmail,
                    subject, receivedAt, preview, seen, flagged, keywords)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    mailbox=excluded.mailbox, thread=excluded.thread, threadSize=excluded.threadSize,
                    sender=excluded.sender, senderEmail=excluded.senderEmail, subject=excluded.subject,
                    receivedAt=excluded.receivedAt, preview=excluded.preview, seen=excluded.seen,
                    flagged=excluded.flagged, keywords=excluded.keywords
                """.trimIndent(),
            ).use { s ->
                messages.forEach { m ->
                    s.setString(1, m.id)
                    s.setString(2, mailbox)
                    s.setString(3, m.threadId)
                    s.setInt(4, m.threadSize)
                    s.setString(5, m.from)
                    s.setString(6, m.fromEmail)
                    s.setString(7, m.subject)
                    s.setString(8, m.receivedAt)
                    s.setString(9, m.preview)
                    s.setInt(10, if (m.seen) 1 else 0)
                    s.setInt(11, if (m.flagged) 1 else 0)
                    s.setString(12, m.keywords.joinToString(" "))
                    s.addBatch()
                }
                s.executeBatch()
            }
            // Replaced rather than updated: FTS5 has no upsert, and a message re-indexed
            // twice would come back twice from one search.
            connection.prepareStatement("DELETE FROM search WHERE id = ?").use { s ->
                messages.forEach { s.setString(1, it.id); s.addBatch() }
                s.executeBatch()
            }
            connection.prepareStatement("INSERT INTO search (id, sender, subject, body) VALUES (?,?,?,?)").use { s ->
                messages.forEach { m ->
                    s.setString(1, m.id)
                    s.setString(2, m.from + " " + m.fromEmail)
                    s.setString(3, m.subject)
                    s.setString(4, m.preview)
                    s.addBatch()
                }
                s.executeBatch()
            }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = was
        }
    }

    /**
     * A folder, newest first, as far as we have it.
     *
     * [unreadOnly] is the old flag. When the caller does not pass [filters], it becomes
     * the unread toggle, so there is still one path through [matchesQuick].
     *
     * Unread, starred and known sender are ordinary columns, so they are part of the
     * WHERE and the LIMIT applies after them. Tagged is not a column: it is "any
     * keyword [tagsOf] would show", including the snooze prefix that is not a fixed
     * word, so those rows are kept with [matchesQuick] and only then paged. Doing the
     * LIMIT first would make tagged mean "tagged among this page".
     *
     * Attachment has no column and is not given one here. A caller that still asks
     * gets nothing back, which is wrong in a way that is obvious, rather than the
     * whole folder, which looks like the toggle worked.
     */
    fun messages(
        mailbox: String,
        limit: Int = 100,
        from: Int = 0,
        unreadOnly: Boolean = false,
        filters: QuickFilters = QuickFilters(unread = unreadOnly),
        knownSenders: Collection<String> = emptyList(),
    ): List<Summary> {
        if (filters.attachment) return emptyList()
        val known = knownSenders.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (filters.knownSender && known.isEmpty()) return emptyList()
        val where = ArrayList<String>()
        where += "mailbox = ?"
        if (filters.unread) where += "seen = 0"
        if (filters.starred) where += "flagged = 1"
        if (filters.knownSender) where += "lower(senderEmail) IN (${holders(known.size)})"
        val sql = buildString {
            append("SELECT * FROM message WHERE ")
            append(where.joinToString(" AND "))
            append(" ORDER BY receivedAt DESC")
            // Tagged is applied after the read, so the page is cut there instead.
            if (!filters.tagged) append(" LIMIT ? OFFSET ?")
        }
        val rows = connection.prepareStatement(sql).use { s ->
            var at = 1
            s.setString(at++, mailbox)
            if (filters.knownSender) known.forEach { s.setString(at++, it) }
            if (!filters.tagged) {
                s.setInt(at++, limit)
                s.setInt(at, from)
            }
            s.executeQuery().use { found ->
                buildList {
                    while (found.next()) add(summaryOf(found))
                }
            }
        }
        if (!filters.tagged) return rows
        return rows.filter { matchesQuick(it, filters, known.toSet()) }.drop(from).take(limit)
    }

    /**
     * Search, over everything kept rather than one folder.
     *
     * The query is passed to FTS5 as a phrase rather than as syntax, because somebody
     * typing `re: invoice` means those words, and unescaped it is a column filter followed
     * by a syntax error.
     */
    fun search(text: String, limit: Int = 100): List<Summary> {
        // A leading Re: or Fwd: comes off first, using the same rule the subject sort
        // uses, because pasting a subject line into search is the commonest way to look
        // for a conversation and every word in it being required would find nothing.
        val phrase = bareSubject(text).split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { "\"" + it.replace("\"", "") + "\"" }
        if (phrase.isBlank()) return emptyList()
        return connection.prepareStatement(
            """
            SELECT m.* FROM search JOIN message m ON m.id = search.id
            WHERE search MATCH ? ORDER BY m.receivedAt DESC LIMIT ?
            """.trimIndent(),
        ).use { s ->
            s.setString(1, phrase)
            s.setInt(2, limit)
            s.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(summaryOf(rows))
                }
            }
        }
    }

    /**
     * Every tag anywhere in this account's local copy.
     *
     * The local store rather than the server, because neither protocol will answer it:
     * JMAP has no method that lists the keywords in use and IMAP only reports the flags of
     * a folder you have already opened. What is here is what has been read, which is what
     * a person has actually seen and tagged.
     */
    fun keywords(): Set<String> = keywordCounts().keys

    /**
     * Every keyword in the local copy and how many messages carry it.
     *
     * A sidebar full of tags that say nothing about how much is behind them is a list of
     * words, so the count is what makes a tag worth clicking. Counted here rather than with
     * a query per tag: there is one `keywords` column and thirty tags, so one pass over the
     * column is thirty round trips saved.
     *
     * Case-insensitive, because another client storing `invoices` where this one wrote
     * `Invoices` is one tag with two spellings, not two tags with half the mail each.
     */
    fun keywordCounts(): Map<String, Int> = connection.prepareStatement(
        "SELECT keywords FROM message WHERE keywords <> ''",
    ).use { s ->
        s.executeQuery().use { rows ->
            val found = java.util.TreeMap<String, Int>(String.CASE_INSENSITIVE_ORDER)
            while (rows.next()) {
                rows.getString(1).split(' ').forEach { keyword ->
                    if (keyword.isNotBlank()) found.merge(keyword, 1, Int::plus)
                }
            }
            found
        }
    }

    /**
     * The local copy of every message carrying [keyword], newest first.
     *
     * Answered from here when the server cannot be reached, the same way search is. The
     * keyword is padded on both sides before matching so `work` does not also find
     * `workshop`.
     */
    fun withKeyword(keyword: String, limit: Int = 200): List<Summary> =
        connection.prepareStatement(
            "SELECT * FROM message WHERE ' ' || keywords || ' ' LIKE ? " +
                "ORDER BY receivedAt DESC LIMIT ?",
        ).use { s ->
            s.setString(1, "% " + keyword + " %")
            s.setInt(2, limit)
            s.executeQuery().use { rows ->
                buildList { while (rows.next()) add(summaryOf(rows)) }
            }
        }

    /**
     * Everything the dashboard counts, in five queries over the local copy.
     *
     * Assembled here rather than by the pane, because these are SQL and the arithmetic on
     * top of them is in `Dashboard.kt`, where it can be tested without a database.
     *
     * [mine] is the addresses that count as you, so a conversation waiting on an answer can
     * be told from one you already answered. [sent] and [junk] are folder ids rather than
     * names, because the role is what finds them and the name is whatever the server calls
     * it in whatever language.
     */
    fun stats(
        inbox: String,
        sent: List<String>,
        junk: List<String>,
        mine: Set<String>,
        days: Int = 30,
        now: java.time.Instant = java.time.Instant.now(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): MailStats {
        val since = now.minus(java.time.Duration.ofDays(days.toLong())).toString()
        val arrived = receivedAtIn(listOf(inbox) + junk, since)
        val junked = receivedAtIn(junk, since)
        return MailStats(
            received = byDay(arrived, days, now.atZone(zone).toLocalDate(), zone),
            sent = byDay(receivedAtIn(sent, since), days, now.atZone(zone).toLocalDate(), zone),
            junk = junked.size,
            arrived = arrived.size,
            topSenders = topSenders(listOf(inbox), since),
            unread = unreadByAge(unreadIn(inbox), now),
            waiting = awaiting(inbox, sent, mine),
            replyMinutes = replyGaps(inbox, sent),
            kept = count("SELECT COUNT(*) FROM message"),
        )
    }

    /** The timestamps of everything in [mailboxes] since [since], which is all a count needs. */
    private fun receivedAtIn(mailboxes: List<String>, since: String): List<String> {
        if (mailboxes.isEmpty()) return emptyList()
        return connection.prepareStatement(
            "SELECT receivedAt FROM message WHERE mailbox IN (${holders(mailboxes.size)}) AND receivedAt >= ?",
        ).use { s ->
            mailboxes.forEachIndexed { at, box -> s.setString(at + 1, box) }
            s.setString(mailboxes.size + 1, since)
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun unreadIn(mailbox: String): List<String> =
        connection.prepareStatement("SELECT receivedAt FROM message WHERE mailbox = ? AND seen = 0").use { s ->
            s.setString(1, mailbox)
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }

    /**
     * Who writes to you most, by address rather than by name.
     *
     * The same person sends as "Dana Whitfield" and as "Dana" and as nothing at all; the
     * address is the only stable part, so the count is on that and the name shown is
     * whichever one they used most recently.
     */
    private fun topSenders(mailboxes: List<String>, since: String, limit: Int = 8): List<Counted> {
        if (mailboxes.isEmpty()) return emptyList()
        return connection.prepareStatement(
            """
            SELECT senderEmail, COUNT(*) n,
                   (SELECT sender FROM message m2 WHERE m2.senderEmail = m.senderEmail
                    ORDER BY m2.receivedAt DESC LIMIT 1) name
            FROM message m
            WHERE mailbox IN (${holders(mailboxes.size)}) AND receivedAt >= ? AND senderEmail <> ''
            GROUP BY senderEmail ORDER BY n DESC LIMIT ?
            """.trimIndent(),
        ).use { s ->
            mailboxes.forEachIndexed { at, box -> s.setString(at + 1, box) }
            s.setString(mailboxes.size + 1, since)
            s.setInt(mailboxes.size + 2, limit)
            s.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val email = rows.getString("senderEmail")
                        add(Counted(rows.getString("name")?.ifBlank { email } ?: email, rows.getInt("n"), email))
                    }
                }
            }
        }
    }

    /**
     * Conversations in the inbox whose last word is somebody else's.
     *
     * The one thing on the dashboard that is about something still to do, so it is the one
     * worth getting right. A thread counts as answered when anything in it sits in Sent, or
     * when its newest message came from one of your own addresses: replying from a phone
     * leaves a copy in Sent, replying from a client that does not leaves the thread with
     * your message newest, and both mean the same thing.
     */
    private fun awaiting(inbox: String, sent: List<String>, mine: Set<String>, limit: Int = 12): List<Summary> {
        val answered = if (sent.isEmpty()) emptySet() else connection.prepareStatement(
            "SELECT DISTINCT thread FROM message WHERE mailbox IN (${holders(sent.size)}) AND thread <> ''",
        ).use { s ->
            sent.forEachIndexed { at, box -> s.setString(at + 1, box) }
            s.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        val lowered = mine.map { it.lowercase() }.toSet()
        return connection.prepareStatement(
            "SELECT * FROM message WHERE mailbox = ? ORDER BY receivedAt DESC LIMIT 400",
        ).use { s ->
            s.setString(1, inbox)
            s.executeQuery().use { rows ->
                val newest = LinkedHashMap<String, Summary>()
                while (rows.next()) {
                    val message = summaryOf(rows)
                    // Ordered newest first, so the first of a thread seen here is its newest.
                    newest.putIfAbsent(message.threadId.ifBlank { message.id }, message)
                }
                newest.values
                    .filter { it.threadId !in answered }
                    .filter { it.fromEmail.lowercase() !in lowered }
                    .sortedBy { it.receivedAt }
                    .take(limit)
            }
        }
    }

    /**
     * How long each answered conversation waited, in minutes.
     *
     * The gap between the newest message that arrived in a thread and the first one sent
     * after it. A reply sent before the message it answers is a clock disagreement between
     * two servers, not a negative reply time, so it is dropped rather than shown.
     */
    private fun replyGaps(inbox: String, sent: List<String>): List<Long> {
        if (sent.isEmpty()) return emptyList()
        val arrived = HashMap<String, String>()
        connection.prepareStatement(
            "SELECT thread, MIN(receivedAt) at FROM message WHERE mailbox = ? AND thread <> '' GROUP BY thread",
        ).use { s ->
            s.setString(1, inbox)
            s.executeQuery().use { rows -> while (rows.next()) arrived[rows.getString(1)] = rows.getString(2) }
        }
        return connection.prepareStatement(
            "SELECT thread, MIN(receivedAt) at FROM message WHERE mailbox IN (${holders(sent.size)}) " +
                "AND thread <> '' GROUP BY thread",
        ).use { s ->
            sent.forEachIndexed { at, box -> s.setString(at + 1, box) }
            s.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val came = arrived[rows.getString(1)]?.let(::instantOf) ?: continue
                        val answered = instantOf(rows.getString(2)) ?: continue
                        val minutes = java.time.Duration.between(came, answered).toMinutes()
                        if (minutes >= 0) add(minutes)
                    }
                }
            }
        }
    }

    private fun count(sql: String): Int = connection.prepareStatement(sql).use { s ->
        s.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
    }

    private fun holders(n: Int) = List(n) { "?" }.joinToString(",")

    fun putBody(id: String, body: Body) {
        connection.prepareStatement(
            "INSERT INTO body (id, html, text) VALUES (?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET html=excluded.html, text=excluded.text",
        ).use { s ->
            s.setString(1, id)
            s.setString(2, body.html)
            s.setString(3, body.text)
            s.executeUpdate()
        }
    }

    fun body(id: String): Body? = connection.prepareStatement("SELECT html, text FROM body WHERE id = ?").use { s ->
        s.setString(1, id)
        s.executeQuery().use { rows ->
            if (!rows.next()) null else Body(rows.getString("html"), rows.getString("text"))
        }
    }

    /** What the server's state was when this folder was last read, so a refresh can skip. */
    fun cursor(mailbox: String): String? =
        connection.prepareStatement("SELECT state FROM cursor WHERE mailbox = ?").use { s ->
            s.setString(1, mailbox)
            s.executeQuery().use { if (it.next()) it.getString("state") else null }
        }

    fun setCursor(mailbox: String, state: String) {
        connection.prepareStatement(
            "INSERT INTO cursor (mailbox, state) VALUES (?,?) ON CONFLICT(mailbox) DO UPDATE SET state=excluded.state",
        ).use { s ->
            s.setString(1, mailbox)
            s.setString(2, state)
            s.executeUpdate()
        }
    }

    /** Takes messages out, for mail that was filed or deleted elsewhere. */
    fun forget(ids: List<String>) {
        if (ids.isEmpty()) return
        val marks = ids.joinToString(",") { "?" }
        listOf("DELETE FROM message WHERE id IN ($marks)", "DELETE FROM body WHERE id IN ($marks)",
               "DELETE FROM search WHERE id IN ($marks)").forEach { sql ->
            connection.prepareStatement(sql).use { s ->
                ids.forEachIndexed { i, id -> s.setString(i + 1, id) }
                s.executeUpdate()
            }
        }
    }

    /** Empties a folder's copy, for when the server says it has moved on more than a page. */
    fun clear(mailbox: String) {
        connection.prepareStatement("SELECT id FROM message WHERE mailbox = ?").use { s ->
            s.setString(1, mailbox)
            val ids = s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString("id")) } }
            forget(ids)
        }
    }

    override fun close() = connection.close()
}

private fun summaryOf(rows: java.sql.ResultSet) = Summary(
    id = rows.getString("id"),
    from = rows.getString("sender"),
    fromEmail = rows.getString("senderEmail"),
    subject = rows.getString("subject"),
    receivedAt = rows.getString("receivedAt"),
    preview = rows.getString("preview"),
    seen = rows.getInt("seen") == 1,
    flagged = rows.getInt("flagged") == 1,
    keywords = rows.getString("keywords").split(' ').filter { it.isNotBlank() }.toSet(),
    threadId = rows.getString("thread"),
    threadSize = rows.getInt("threadSize"),
)
