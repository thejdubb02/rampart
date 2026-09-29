package org.rampart

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/** How many picture bytes one message may keep. Past this the text is kept and the pictures are not. */
internal const val PICTURE_CACHE_CAP = 5 * 1024 * 1024

/** How many picture bytes one account may keep in total. Past this the least recently opened go. */
internal const val PICTURE_ACCOUNT_CAP = 200L * 1024 * 1024

/** One cached picture, for deciding which to drop when the account is over its cap. */
internal data class PictureUse(val id: String, val blob: String, val bytes: Int, val used: Long)

/**
 * Pictures to drop so the rest fit in [cap], least recently used first.
 *
 * A picture that was opened again is newer than one that was only stored, so
 * opening a message is what keeps its pictures. Under the cap, nothing goes.
 */
internal fun picturesToEvict(rows: List<PictureUse>, cap: Long): List<Pair<String, String>> {
    var total = rows.sumOf { it.bytes.toLong() }
    if (total <= cap) return emptyList()
    val drop = ArrayList<Pair<String, String>>()
    for (row in rows.sortedWith(compareBy({ it.used }, { it.id }, { it.blob }))) {
        if (total <= cap) break
        drop += row.id to row.blob
        total -= row.bytes
    }
    return drop
}

/**
 * Pictures that fit in [cap], in the order they were given.
 *
 * One that does not fit is skipped rather than stopping the rest, so a huge file
 * beside a logo does not push the logo out.
 */
internal fun picturesWithin(pictures: Map<String, ByteArray>, cap: Int = PICTURE_CACHE_CAP): Map<String, ByteArray> {
    if (cap <= 0) return emptyMap()
    var used = 0
    val kept = LinkedHashMap<String, ByteArray>()
    for ((id, bytes) in pictures) {
        if (bytes.size > cap || used + bytes.size > cap) continue
        kept[id] = bytes
        used += bytes.size
    }
    return kept
}

/**
 * A message kept whole: the body with its headers, the files, and the pictures it draws.
 *
 * The old body table stored only the html and the text, so a cached copy was never equal
 * to the one just fetched and the page was built twice. This is the copy that can be shown
 * as it is.
 */
internal data class Kept(
    val body: Body,
    val attachments: List<Attachment>,
    val pictures: Map<String, ByteArray>,
    val emailBlobId: String?,
    val mailState: String?,
    val calendar: String? = null,
)

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
            val normalized = account.trim().lowercase()
            val prefix = normalized.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(24).ifBlank { "account" }
            val hash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return Accounts.file().parent.resolve("mail-$prefix-$hash.db")
        }

        /** The path used before account cache names included a hash. */
        internal fun legacyFile(account: String): Path {
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
        fun open(path: Path, key: String?, legacy: Pair<Path, String?>? = null): Store {
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
            return Store(connection).apply {
                prepare()
                legacy?.let { (legacyPath, legacyKey) -> migrateTracking(legacyPath, legacyKey) }
            }
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
            "CREATE TABLE IF NOT EXISTS mailbox_message (message_id TEXT NOT NULL, mailbox_id TEXT NOT NULL, " +
                "PRIMARY KEY (message_id, mailbox_id))",
            "CREATE INDEX IF NOT EXISTS mailbox_message_mailbox ON mailbox_message (mailbox_id, message_id)",
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
                subject TEXT NOT NULL, sentAt INTEGER NOT NULL, repliedAt INTEGER
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS tracked_link (
                id TEXT NOT NULL, number INTEGER NOT NULL, url TEXT NOT NULL,
                PRIMARY KEY (id, number)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS fetched (
                id TEXT NOT NULL, at INTEGER NOT NULL, userAgent TEXT NOT NULL, network TEXT NOT NULL,
                classification TEXT NOT NULL DEFAULT '', event TEXT NOT NULL DEFAULT 'open', url TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (id, at)
            )
            """,
            "CREATE INDEX IF NOT EXISTS tracked_message ON tracked (messageId)",
            // The whole opened message, separate from the list row. A new table rather than
            // new columns on body: a cache file that already exists never re-runs the old
            // CREATE, so a column added there would not be there.
            """
            CREATE TABLE IF NOT EXISTS kept (
                id TEXT PRIMARY KEY,
                body TEXT NOT NULL,
                attachments TEXT NOT NULL,
                blobId TEXT,
                mailState TEXT,
                calendar TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS kept_picture (
                id TEXT NOT NULL,
                blob TEXT NOT NULL,
                bytes BLOB NOT NULL,
                used INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (id, blob)
            )
            """,
        )
        val messageColumns = connection.prepareStatement("PRAGMA table_info(message)").use { s ->
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString("name")) } }
        }
        if ("messageId" !in messageColumns) connection.createStatement().use {
            it.execute("ALTER TABLE message ADD COLUMN messageId TEXT NOT NULL DEFAULT ''")
        }
        // Size and List-Id arrived with the table view and split saved searches. A row
        // written before has neither, which reads as zero and empty until it is next fetched.
        if ("size" !in messageColumns) connection.createStatement().use {
            it.execute("ALTER TABLE message ADD COLUMN size INTEGER NOT NULL DEFAULT 0")
        }
        if ("listId" !in messageColumns) connection.createStatement().use {
            it.execute("ALTER TABLE message ADD COLUMN listId TEXT NOT NULL DEFAULT ''")
        }
        // A cache file from before the cap has no used column. CREATE does not add
        // one to a table that already exists, and without it there is no order to
        // evict in. The bytes are disposable, so a missing column is just added.
        val columns = connection.prepareStatement("PRAGMA table_info(kept_picture)").use { s ->
            s.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString("name")) }
            }
        }
        if ("used" !in columns) {
            connection.createStatement().use {
                it.execute("ALTER TABLE kept_picture ADD COLUMN used INTEGER NOT NULL DEFAULT 0")
            }
        }
        val fetchedColumns = connection.prepareStatement("PRAGMA table_info(fetched)").use { s ->
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString("name")) } }
        }
        val trackedColumns = connection.prepareStatement("PRAGMA table_info(tracked)").use { s ->
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString("name")) } }
        }
        val trackedLinkColumns = connection.prepareStatement("PRAGMA table_info(tracked_link)").use { s ->
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString("name")) } }
        }
        check(trackedLinkColumns.containsAll(listOf("id", "number", "url"))) {
            "The tracked link table has an unsupported schema."
        }
        if ("repliedAt" !in trackedColumns) connection.createStatement().use {
            it.execute("ALTER TABLE tracked ADD COLUMN repliedAt INTEGER")
        }
        if ("classification" !in fetchedColumns) {
            connection.createStatement().use {
                it.execute("ALTER TABLE fetched ADD COLUMN classification TEXT NOT NULL DEFAULT ''")
            }
        }
        if ("event" !in fetchedColumns) connection.createStatement().use {
            it.execute("ALTER TABLE fetched ADD COLUMN event TEXT NOT NULL DEFAULT 'open'")
        }
        if ("url" !in fetchedColumns) connection.createStatement().use {
            it.execute("ALTER TABLE fetched ADD COLUMN url TEXT NOT NULL DEFAULT ''")
        }
    }

    /** Copies the only non-cache rows from the database used before cache names changed. */
    private fun migrateTracking(path: Path, key: String?) {
        if (!path.exists()) return
        val copied = runCatching {
            open(path, key).use { old ->
                val tracking = old.tracking(Int.MAX_VALUE)
                val links = old.trackedLinks()
                val was = connection.autoCommit
                connection.autoCommit = false
                try {
                    tracking.forEach { (tracked, fetches) ->
                        track(
                            tracked,
                            links.filterKeys { it.first == tracked.id }.toSortedMap(compareBy { it.second }).values.toList(),
                        )
                        recordFetches(fetches)
                    }
                    connection.commit()
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = was
                }
            }
        }.isSuccess
        if (copied) runCatching { path.deleteIfExists() }
    }

    /** Remembers that a message went out tracked. */
    @Synchronized fun track(tracked: Tracked, links: List<String> = emptyList()) {
        val was = connection.autoCommit
        if (was) connection.autoCommit = false
        try {
            connection.prepareStatement(
                "INSERT OR REPLACE INTO tracked (id, messageId, recipient, subject, sentAt, repliedAt) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { s ->
                s.setString(1, tracked.id)
                s.setString(2, tracked.messageId)
                s.setString(3, tracked.recipient)
                s.setString(4, tracked.subject)
                s.setLong(5, tracked.sentAt.toEpochMilli())
                if (tracked.repliedAt == null) {
                    s.setNull(6, java.sql.Types.BIGINT)
                } else {
                    s.setLong(6, tracked.repliedAt.toEpochMilli())
                }
                s.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM tracked_link WHERE id = ?").use { s ->
                s.setString(1, tracked.id)
                s.executeUpdate()
            }
            if (links.isNotEmpty()) connection.prepareStatement(
                "INSERT INTO tracked_link (id, number, url) VALUES (?, ?, ?)",
            ).use { s ->
                links.forEachIndexed { number, url ->
                    s.setString(1, tracked.id)
                    s.setInt(2, number)
                    s.setString(3, url)
                    s.addBatch()
                }
                s.executeBatch()
            }
            if (was) connection.commit()
        } catch (e: Exception) {
            if (was) connection.rollback()
            throw e
        } finally {
            if (was) connection.autoCommit = true
        }
    }

    /** Original destinations for redirects minted by this account. */
    @Synchronized fun trackedLinks(): Map<Pair<String, Int>, String> = connection.prepareStatement(
        "SELECT id, number, url FROM tracked_link",
    ).use { s ->
        s.executeQuery().use { rows ->
            buildMap { while (rows.next()) put(rows.getString(1) to rows.getInt(2), rows.getString(3)) }
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
    @Synchronized fun recordFetches(fetches: List<Fetch>): List<Fetch> {
        if (fetches.isEmpty()) return emptyList()
        val inserted = ArrayList<Fetch>(fetches.size)
        connection.prepareStatement(
            "INSERT OR IGNORE INTO fetched (id, at, userAgent, network, classification, event, url) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { s ->
            fetches.forEach { fetch ->
                s.setString(1, fetch.id)
                s.setLong(2, fetch.at.toEpochMilli())
                s.setString(3, fetch.userAgent)
                s.setString(4, fetch.network)
                s.setString(5, fetch.classification)
                s.setString(6, fetch.event)
                s.setString(7, fetch.url)
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
    @Synchronized fun trackedByIds(ids: List<String>): Map<String, Tracked> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return connection.prepareStatement(
            "SELECT id, messageId, recipient, subject, sentAt, repliedAt FROM tracked WHERE id IN ($placeholders)",
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
                                repliedAt = rows.getLong(6).let { if (rows.wasNull()) null else java.time.Instant.ofEpochMilli(it) },
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Everything sent tracked, newest first, with what has been fetched for each. */
    @Synchronized fun tracking(limit: Int = 500): List<Pair<Tracked, List<Fetch>>> {
        val sent = connection.prepareStatement(
            "SELECT id, messageId, recipient, subject, sentAt, repliedAt FROM tracked ORDER BY sentAt DESC LIMIT ?",
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
                                repliedAt = rows.getLong(6).let { if (rows.wasNull()) null else java.time.Instant.ofEpochMilli(it) },
                            ),
                        )
                    }
                }
            }
        }
        if (sent.isEmpty()) return emptyList()
        val byId = HashMap<String, MutableList<Fetch>>()
        connection.prepareStatement("SELECT id, at, userAgent, network, classification, event, url FROM fetched ORDER BY at ASC").use { s ->
            s.executeQuery().use { rows ->
                while (rows.next()) {
                    byId.getOrPut(rows.getString(1)) { ArrayList() }.add(
                        Fetch(
                            rows.getString(1),
                            java.time.Instant.ofEpochMilli(rows.getLong(2)),
                            rows.getString(3),
                            rows.getString(4),
                            rows.getString(5),
                            rows.getString(6),
                            rows.getString(7),
                        ),
                    )
                }
            }
        }
        return sent.map { it to byId[it.id].orEmpty() }
    }

    /** Marks tracked recipients who answered a Message-ID minted for tracking. */
    @Synchronized fun markReplied(from: String, references: Collection<String>, at: Instant): List<String> {
        if (references.isEmpty()) return emptyList()
        val placeholders = references.joinToString(",") { "?" }
        val normalized = references.map { it.trim('<', '>') }
        val ids = connection.prepareStatement(
            "SELECT id FROM tracked WHERE repliedAt IS NULL AND lower(recipient) = lower(?) AND messageId IN ($placeholders)",
        ).use { s ->
            s.setString(1, from); normalized.forEachIndexed { index, id -> s.setString(index + 2, id) }
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        if (ids.isEmpty()) return emptyList()
        connection.prepareStatement("UPDATE tracked SET repliedAt = ? WHERE id IN (${ids.joinToString(",") { "?" }})").use { s ->
            s.setLong(1, at.toEpochMilli()); ids.forEachIndexed { index, id -> s.setString(index + 2, id) }; s.executeUpdate()
        }
        return ids
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
    @Synchronized fun put(mailbox: String, messages: List<Summary>) {
        if (messages.isEmpty()) return
        val indexedBodies = messages.associate { message ->
            message.id to connection.prepareStatement("SELECT html, text FROM body WHERE id = ?").use { s ->
                s.setString(1, message.id)
                s.executeQuery().use { rows ->
                    if (!rows.next()) null else rows.getString("text").orEmpty() + " " +
                        rows.getString("html")?.let { org.jsoup.Jsoup.parse(it).text() }.orEmpty()
                }
            }
        }
        val was = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                """
                INSERT INTO message (id, mailbox, thread, threadSize, sender, senderEmail,
                    subject, receivedAt, preview, seen, flagged, keywords, messageId, size, listId)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    mailbox=excluded.mailbox, thread=excluded.thread, threadSize=excluded.threadSize,
                    sender=excluded.sender, senderEmail=excluded.senderEmail, subject=excluded.subject,
                    receivedAt=excluded.receivedAt, preview=excluded.preview, seen=excluded.seen,
                    flagged=excluded.flagged, keywords=excluded.keywords, messageId=excluded.messageId,
                    size=excluded.size, listId=excluded.listId
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
                    s.setString(13, m.messageId)
                    s.setLong(14, m.size)
                    s.setString(15, m.listId)
                    s.addBatch()
                }
                s.executeBatch()
            }
            // Replaced rather than updated: FTS5 has no upsert, and a message re-indexed
            // twice would come back twice from one search.
            //
            // One statement per few hundred ids rather than one per id. The id column is
            // not indexed inside FTS5, so each delete reads the whole index: one per row
            // made a page of a hundred cost a second and a half at fifty thousand messages,
            // with the store locked throughout. Measured in ListBenchmarkTest.
            messages.map { it.id }.distinct().chunked(500).forEach { chunk ->
                connection.prepareStatement(
                    "DELETE FROM search WHERE id IN (" + chunk.joinToString(",") { "?" } + ")",
                ).use { s ->
                    chunk.forEachIndexed { i, id -> s.setString(i + 1, id) }
                    s.executeUpdate()
                }
            }
            connection.prepareStatement("INSERT INTO search (id, sender, subject, body) VALUES (?,?,?,?)").use { s ->
                messages.forEach { m ->
                    s.setString(1, m.id)
                    s.setString(2, m.from + " " + m.fromEmail)
                    s.setString(3, m.subject)
                    s.setString(4, indexedBodies[m.id]?.trim()?.takeIf { it.isNotBlank() } ?: m.preview)
                    s.addBatch()
                }
                s.executeBatch()
            }
            connection.prepareStatement(
                "INSERT OR IGNORE INTO mailbox_message (message_id, mailbox_id) VALUES (?,?)",
            ).use { s ->
                messages.forEach { m ->
                    s.setString(1, m.id)
                    s.setString(2, mailbox)
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
    @Synchronized fun messages(
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
        where += "id IN (SELECT message_id FROM mailbox_message WHERE mailbox_id = ?)"
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
    @Synchronized fun search(text: String, limit: Int = 100): List<Summary> {
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
     * The local copy of everything a saved search's conditions match, newest first.
     *
     * [query] comes from [localWhere], which is the only thing that makes one: its SQL is
     * fixed text and every value in it is bound here, never pasted in.
     */
    @Synchronized fun matching(query: LocalQuery.Sql, limit: Int = 200, from: Int = 0): List<Summary> =
        connection.prepareStatement(
            "SELECT * FROM message WHERE ${query.where} ORDER BY receivedAt DESC LIMIT ? OFFSET ?",
        ).use { s ->
            var at = bind(s, query.args)
            s.setInt(at++, limit)
            s.setInt(at, from)
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(summaryOf(rows)) } }
        }

    /**
     * Every match for [query], with only what counting and splitting need.
     *
     * No limit, because a split has to see every sender and not only the newest two
     * hundred. No preview, subject or thread either: over fifty thousand rows those are
     * most of the bytes, and nothing that counts looks at them.
     */
    @Synchronized fun matchingForCount(query: LocalQuery.Sql): List<Summary> =
        connection.prepareStatement(
            "SELECT id, sender, senderEmail, receivedAt, seen, flagged, keywords, listId FROM message " +
                "WHERE ${query.where}",
        ).use { s ->
            bind(s, query.args)
            s.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            Summary(
                                id = rows.getString(1),
                                from = rows.getString(2),
                                fromEmail = rows.getString(3),
                                subject = "",
                                receivedAt = rows.getString(4),
                                preview = "",
                                seen = rows.getInt(5) == 1,
                                flagged = rows.getInt(6) == 1,
                                keywords = rows.getString(7).split(' ').filter { it.isNotBlank() }.toSet(),
                                listId = rows.getString(8),
                            ),
                        )
                    }
                }
            }
        }

    /** Binds [args] from the first parameter on, and says which one is next. */
    private fun bind(statement: java.sql.PreparedStatement, args: List<Any>): Int {
        var at = 1
        for (arg in args) {
            when (arg) {
                is Long -> statement.setLong(at++, arg)
                is Int -> statement.setInt(at++, arg)
                else -> statement.setString(at++, arg.toString())
            }
        }
        return at
    }

    /**
     * Every tag anywhere in this account's local copy.
     *
     * The local store rather than the server, because neither protocol will answer it:
     * JMAP has no method that lists the keywords in use and IMAP only reports the flags of
     * a folder you have already opened. What is here is what has been read, which is what
     * a person has actually seen and tagged.
     */
    @Synchronized fun keywords(): Set<String> = keywordCounts().keys

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
    @Synchronized fun keywordCounts(): Map<String, Int> = connection.prepareStatement(
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
    @Synchronized fun withKeyword(keyword: String, limit: Int = 200): List<Summary> =
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
    @Synchronized fun stats(
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
            "SELECT receivedAt FROM message WHERE id IN (SELECT message_id FROM mailbox_message " +
                "WHERE mailbox_id IN (${holders(mailboxes.size)})) AND receivedAt >= ?",
        ).use { s ->
            mailboxes.forEachIndexed { at, box -> s.setString(at + 1, box) }
            s.setString(mailboxes.size + 1, since)
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun unreadIn(mailbox: String): List<String> =
        connection.prepareStatement("SELECT receivedAt FROM message WHERE id IN " +
            "(SELECT message_id FROM mailbox_message WHERE mailbox_id = ?) AND seen = 0").use { s ->
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
            WHERE id IN (SELECT message_id FROM mailbox_message WHERE mailbox_id IN (${holders(mailboxes.size)}))
                AND receivedAt >= ? AND senderEmail <> ''
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
            "SELECT DISTINCT thread FROM message WHERE id IN (SELECT message_id FROM mailbox_message " +
                "WHERE mailbox_id IN (${holders(sent.size)})) AND thread <> ''",
        ).use { s ->
            sent.forEachIndexed { at, box -> s.setString(at + 1, box) }
            s.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        val lowered = mine.map { it.lowercase() }.toSet()
        return connection.prepareStatement(
            "SELECT * FROM message WHERE id IN (SELECT message_id FROM mailbox_message WHERE mailbox_id = ?) " +
                "ORDER BY receivedAt DESC LIMIT 400",
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
            "SELECT thread, MIN(receivedAt) at FROM message WHERE id IN " +
                "(SELECT message_id FROM mailbox_message WHERE mailbox_id = ?) AND thread <> '' GROUP BY thread",
        ).use { s ->
            s.setString(1, inbox)
            s.executeQuery().use { rows -> while (rows.next()) arrived[rows.getString(1)] = rows.getString(2) }
        }
        return connection.prepareStatement(
            "SELECT thread, MIN(receivedAt) at FROM message WHERE id IN (SELECT message_id FROM mailbox_message " +
                "WHERE mailbox_id IN (${holders(sent.size)})) " +
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

    @Synchronized fun putBody(id: String, body: Body) {
        putKept(id, body, emptyList(), emptyMap(), null, null, null)
    }

    /**
     * The body, with its headers when this file has them.
     *
     * A row written by an older build has only the html and the text. That copy is still
     * readable. It is not treated as a finished cache: [kept] is null for it, so the next
     * open fetches the message once and stores it whole.
     */
    @Synchronized fun body(id: String): Body? = kept(id)?.body ?: connection.prepareStatement(
        "SELECT html, text FROM body WHERE id = ?",
    ).use { s ->
        s.setString(1, id)
        s.executeQuery().use { rows ->
            if (!rows.next()) null else Body(rows.getString("html"), rows.getString("text"))
        }
    }

    @Synchronized fun putKept(
        id: String,
        body: Body,
        attachments: List<Attachment>,
        pictures: Map<String, ByteArray>,
        emailBlobId: String?,
        mailState: String?,
        calendar: String?,
        pictureCap: Int = PICTURE_CACHE_CAP,
        usedAt: Long = System.currentTimeMillis(),
    ) {
        val fitting = picturesWithin(pictures, pictureCap)
        val was = connection.autoCommit
        connection.autoCommit = false
        try {
        connection.prepareStatement(
            "INSERT INTO body (id, html, text) VALUES (?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET html=excluded.html, text=excluded.text",
        ).use { s ->
            s.setString(1, id)
            s.setString(2, body.html)
            s.setString(3, body.text)
            s.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO kept (id, body, attachments, blobId, mailState, calendar) VALUES (?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET body=excluded.body, attachments=excluded.attachments, " +
                "blobId=excluded.blobId, mailState=excluded.mailState, calendar=excluded.calendar",
        ).use { s ->
            s.setString(1, id)
            s.setString(2, keptJson.encodeToString(Body.serializer(), body))
            s.setString(3, keptJson.encodeToString(ListSerializer(Attachment.serializer()), attachments))
            s.setString(4, emailBlobId)
            s.setString(5, mailState)
            s.setString(6, calendar)
            s.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM kept_picture WHERE id = ?").use { s ->
            s.setString(1, id)
            s.executeUpdate()
        }
        if (fitting.isNotEmpty()) {
            connection.prepareStatement(
                "INSERT INTO kept_picture (id, blob, bytes, used) VALUES (?,?,?,?)",
            ).use { s ->
                fitting.forEach { (blob, bytes) ->
                    s.setString(1, id)
                    s.setString(2, blob)
                    s.setBytes(3, bytes)
                    s.setLong(4, usedAt)
                    s.executeUpdate()
                }
            }
        }
        val searchable = body.text.orEmpty() + " " + body.html?.let { org.jsoup.Jsoup.parse(it).text() }.orEmpty()
        connection.prepareStatement("UPDATE search SET body = ? WHERE id = ?").use { s ->
            s.setString(1, searchable.trim())
            s.setString(2, id)
            s.executeUpdate()
        }
        evictPictures()
        connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = was
        }
    }

    @Synchronized fun kept(id: String): Kept? = connection.prepareStatement(
        "SELECT body, attachments, blobId, mailState, calendar FROM kept WHERE id = ?",
    ).use { s ->
        s.setString(1, id)
        s.executeQuery().use { rows ->
            if (!rows.next()) return null
            val body = runCatching { keptJson.decodeFromString(Body.serializer(), rows.getString("body")) }
                .getOrNull() ?: return null
            val attachments = runCatching {
                keptJson.decodeFromString(ListSerializer(Attachment.serializer()), rows.getString("attachments"))
            }.getOrDefault(emptyList())
            Kept(
                body = body,
                attachments = attachments,
                pictures = picturesOf(id),
                emailBlobId = rows.getString("blobId"),
                mailState = rows.getString("mailState"),
                calendar = rows.getString("calendar"),
            )
        }
    }

    /** The account state this copy was saved under, so a later open can see that nothing moved. */
    @Synchronized fun setKeptState(id: String, state: String) {
        connection.prepareStatement("UPDATE kept SET mailState = ? WHERE id = ?").use { s ->
            s.setString(1, state)
            s.setString(2, id)
            s.executeUpdate()
        }
    }

    private fun picturesOf(id: String): Map<String, ByteArray> {
        val found = connection.prepareStatement("SELECT blob, bytes FROM kept_picture WHERE id = ?").use { s ->
            s.setString(1, id)
            s.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val bytes = rows.getBytes("bytes") ?: continue
                        put(rows.getString("blob"), bytes)
                    }
                }
            }
        }
        // Opening the message is what makes these the recently used ones. A later
        // eviction then drops somebody else's pictures, not the ones on screen.
        if (found.isNotEmpty()) {
            connection.prepareStatement("UPDATE kept_picture SET used = ? WHERE id = ?").use { s ->
                s.setLong(1, System.currentTimeMillis())
                s.setString(2, id)
                s.executeUpdate()
            }
        }
        return found
    }

    /**
     * Drops the least recently used pictures until the account is under [cap].
     *
     * The per-message cap stops one letter filling the file. This stops a year of
     * letters doing it. The text of a message is kept either way.
     */
    @Synchronized fun evictPictures(cap: Long = PICTURE_ACCOUNT_CAP) {
        val rows = connection.prepareStatement(
            "SELECT id, blob, LENGTH(bytes) AS n, used FROM kept_picture",
        ).use { s ->
            s.executeQuery().use { found ->
                buildList {
                    while (found.next()) {
                        add(PictureUse(found.getString("id"), found.getString("blob"), found.getInt("n"), found.getLong("used")))
                    }
                }
            }
        }
        val drop = picturesToEvict(rows, cap)
        if (drop.isEmpty()) return
        connection.prepareStatement("DELETE FROM kept_picture WHERE id = ? AND blob = ?").use { s ->
            drop.forEach { (id, blob) ->
                s.setString(1, id)
                s.setString(2, blob)
                s.addBatch()
            }
            s.executeBatch()
        }
    }

    /**
     * Forgets cached rows in [mailbox] that a fresh first page no longer contains,
     * when their date falls inside that page.
     *
     * Rows older than the page stay. They were not fetched, so their absence from
     * this answer says nothing about whether the server still has them.
     */
    @Synchronized fun pruneToPage(mailbox: String, page: List<Summary>) {
        if (page.isEmpty()) return
        val cached = connection.prepareStatement(
            "SELECT id, receivedAt FROM message WHERE id IN " +
                "(SELECT message_id FROM mailbox_message WHERE mailbox_id = ?)",
        ).use { s ->
            s.setString(1, mailbox)
            s.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(DatedId(rows.getString("id"), rows.getString("receivedAt")))
                }
            }
        }
        forgetFrom(mailbox, idsMissingFromPage(cached, page.map { DatedId(it.id, it.receivedAt) }))
    }

    /** What the server's state was when this folder was last read, so a refresh can skip. */
    @Synchronized fun cursor(mailbox: String): String? =
        connection.prepareStatement("SELECT state FROM cursor WHERE mailbox = ?").use { s ->
            s.setString(1, mailbox)
            s.executeQuery().use { if (it.next()) it.getString("state") else null }
        }

    @Synchronized fun setCursor(mailbox: String, state: String) {
        connection.prepareStatement(
            "INSERT INTO cursor (mailbox, state) VALUES (?,?) ON CONFLICT(mailbox) DO UPDATE SET state=excluded.state",
        ).use { s ->
            s.setString(1, mailbox)
            s.setString(2, state)
            s.executeUpdate()
        }
    }

    /** Takes messages out, for mail that was filed or deleted elsewhere. */
    @Synchronized fun forget(ids: List<String>) {
        if (ids.isEmpty()) return
        val marks = ids.joinToString(",") { "?" }
        listOf(
            "DELETE FROM mailbox_message WHERE message_id IN ($marks)",
            "DELETE FROM message WHERE id IN ($marks)",
            "DELETE FROM body WHERE id IN ($marks)",
            "DELETE FROM search WHERE id IN ($marks)",
            "DELETE FROM kept WHERE id IN ($marks)",
            "DELETE FROM kept_picture WHERE id IN ($marks)",
        ).forEach { sql ->
            connection.prepareStatement(sql).use { s ->
                ids.forEachIndexed { i, id -> s.setString(i + 1, id) }
                s.executeUpdate()
            }
        }
    }

    private fun forgetFrom(mailbox: String, ids: List<String>) {
        if (ids.isEmpty()) return
        connection.prepareStatement("DELETE FROM mailbox_message WHERE mailbox_id = ? AND message_id = ?").use { s ->
            ids.forEach { id ->
                s.setString(1, mailbox)
                s.setString(2, id)
                s.addBatch()
            }
            s.executeBatch()
        }
        val orphaned = ids.filter { id ->
            connection.prepareStatement("SELECT 1 FROM mailbox_message WHERE message_id = ? LIMIT 1").use { s ->
                s.setString(1, id)
                !s.executeQuery().next()
            }
        }
        forget(orphaned)
    }

    /** Empties a folder's copy, for when the server says it has moved on more than a page. */
    @Synchronized fun clear(mailbox: String) {
        connection.prepareStatement("SELECT message_id FROM mailbox_message WHERE mailbox_id = ?").use { s ->
            s.setString(1, mailbox)
            val ids = s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString("message_id")) } }
            forgetFrom(mailbox, ids)
        }
    }

    @Synchronized override fun close() = connection.close()
}

private val keptJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
    messageId = rows.getString("messageId"),
    size = rows.getLong("size"),
    listId = rows.getString("listId"),
)
