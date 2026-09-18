package org.rampart.tracker

import java.sql.Connection
import java.sql.DriverManager

/**
 * One fetch of one pixel.
 *
 * **Everything the companion is allowed to know is in this class**, and the list is short
 * on purpose: an opaque id, when it was fetched, what asked for it, and roughly where from.
 * Not the recipient, not the subject, not the message, not the sender. Those live in the
 * client that minted the id, and the id is random, so this database is worth nothing to
 * anyone who takes it.
 */
data class Fetch(
    val id: String,
    /** Milliseconds since the epoch, which is what the client compares against a send. */
    val at: Long,
    val userAgent: String,
    /** The requesting network, never the address. See [network]. */
    val network: String,
)

/**
 * The log, in SQLite.
 *
 * SQLite because the whole service is one table and a few thousand rows a year, and
 * because a single file in a volume is the difference between somebody running this and
 * somebody not bothering. There is nothing here a bigger database would do better.
 */
class Log(path: String) : AutoCloseable {

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path").apply {
        createStatement().use { s ->
            // A pixel is written on every open and read whenever the client asks, which is
            // the shape WAL exists for. Without it a read blocks a write and a slow client
            // poll delays somebody's image loading.
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA busy_timeout=5000")
            s.execute(
                "CREATE TABLE IF NOT EXISTS fetch (" +
                    "id TEXT NOT NULL, at INTEGER NOT NULL, userAgent TEXT NOT NULL, network TEXT NOT NULL)",
            )
            // The only query there is: everything since a time, in order.
            s.execute("CREATE INDEX IF NOT EXISTS fetch_at ON fetch(at)")
        }
    }

    fun record(fetch: Fetch) {
        connection.prepareStatement("INSERT INTO fetch (id, at, userAgent, network) VALUES (?, ?, ?, ?)").use { s ->
            s.setString(1, fetch.id)
            s.setLong(2, fetch.at)
            s.setString(3, fetch.userAgent.take(400))
            s.setString(4, fetch.network)
            s.executeUpdate()
        }
    }

    /**
     * Everything fetched since [since], oldest first.
     *
     * Oldest first so a client that stops halfway can carry on from the last row it read
     * rather than starting again. [limit] is a ceiling on one answer, not on the history:
     * a client that has been shut for a month asks repeatedly, moving `since` forward.
     */
    fun since(since: Long, limit: Int = 1000): List<Fetch> =
        connection.prepareStatement(
            "SELECT id, at, userAgent, network FROM fetch WHERE at > ? ORDER BY at ASC LIMIT ?",
        ).use { s ->
            s.setLong(1, since)
            s.setInt(2, limit)
            s.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(Fetch(rows.getString(1), rows.getLong(2), rows.getString(3), rows.getString(4)))
                    }
                }
            }
        }

    /**
     * Throws away anything older than [days].
     *
     * A log that grows forever is a log somebody eventually has to deal with, and the
     * client keeps its own copy of everything it has read, so the server's copy is a
     * handover buffer rather than the record.
     */
    fun forgetOlderThan(days: Int, now: Long = System.currentTimeMillis()): Int {
        if (days <= 0) return 0
        return connection.prepareStatement("DELETE FROM fetch WHERE at < ?").use { s ->
            s.setLong(1, now - days * 24L * 60 * 60 * 1000)
            s.executeUpdate()
        }
    }

    override fun close() = connection.close()
}

/**
 * An address reduced to the network it is on.
 *
 * The design says this records "the requesting network", and that word is doing real work:
 * a full address is a location and a person, and this service has no use for either. What
 * it is actually for is telling a corporate scanner apart from the recipient, and a /24 or
 * a /48 answers that just as well.
 *
 * Anything unparseable becomes empty rather than being stored as given, so a malformed
 * header cannot smuggle a full address into the log.
 */
fun network(address: String?): String {
    val raw = address?.trim().orEmpty().removePrefix("[").substringBefore("]")
    if (raw.isEmpty()) return ""
    if (":" in raw) {
        val groups = raw.split(":").filter { it.isNotEmpty() }
        if (groups.isEmpty()) return ""
        return groups.take(3).joinToString(":") + "::/48"
    }
    val octets = raw.split(".")
    if (octets.size != 4 || octets.any { it.toIntOrNull() !in 0..255 }) return ""
    return octets.take(3).joinToString(".") + ".0/24"
}
