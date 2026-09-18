package org.rampart.tracker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Rampart's companion, which is one container and does one thing.
 *
 * It serves a 1x1 GIF at an id the client minted, writes down that somebody fetched it,
 * and answers one authenticated question: what has been fetched since a given moment.
 *
 * **It never learns anything about the mail.** No message, no subject, no recipient, no
 * address, no password. The ids are random and mean nothing without the client that minted
 * them, which is what makes this safe to run on a cheap box with a public hostname.
 *
 * Built on the JDK's own HTTP server rather than a framework. This is three routes; a
 * framework would be a larger dependency than the program.
 */
fun main() {
    val token = System.getenv("RAMPART_TRACKER_TOKEN").orEmpty()
    /*
     * No token, no start. A tracking log with no token on the read-back is readable by
     * anybody who finds the hostname, and the failure is silent: it works perfectly and
     * leaks. Refusing to start is the only version of this that cannot be got wrong by
     * somebody who did not read the README.
     */
    if (token.length < 16) {
        System.err.println(
            "RAMPART_TRACKER_TOKEN is missing or too short. Set it to at least 16 characters,\n" +
                "the same value you put in Rampart. Generate one with:  openssl rand -base64 32",
        )
        kotlin.system.exitProcess(2)
    }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val log = Log(System.getenv("RAMPART_TRACKER_DB") ?: "/data/tracker.db")
    val keepDays = System.getenv("RAMPART_TRACKER_KEEP_DAYS")?.toIntOrNull() ?: 400
    log.forgetOlderThan(keepDays)

    val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
    // A small pool, because every handler is a single SQLite statement. The default is a
    // single thread, which would let one slow client hold up somebody's image loading.
    server.executor = Executors.newFixedThreadPool(8)

    server.createContext("/o/") { exchange -> pixel(exchange, log) }
    server.createContext("/opens") { exchange -> opens(exchange, log, token) }
    /*
     * Never behind anything. A health check that answers 200 from a login page says the
     * service is up when it is not, which is the trap the rules file calls out by name.
     */
    server.createContext("/health") { exchange -> reply(exchange, 200, "ok".toByteArray(), "text/plain") }
    server.start()
    println("Rampart tracker listening on $port")
}

/**
 * The pixel itself.
 *
 * **Always an image, and always a 200**, whatever the id was. A 404 for an unknown id
 * tells anyone who asks which ids exist, and a mail client that gets an error draws a
 * broken-image icon in the middle of somebody's message. An id we have never seen is
 * simply recorded, because it costs nothing and the alternative leaks.
 */
private fun pixel(exchange: HttpExchange, log: Log) {
    val id = exchange.requestURI.path.removePrefix("/o/").removeSuffix(".gif")
    if (id.isNotBlank() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
        runCatching {
            log.record(
                Fetch(
                    id = id,
                    at = System.currentTimeMillis(),
                    userAgent = exchange.requestHeaders.getFirst("User-Agent").orEmpty(),
                    network = network(callerAddress(exchange)),
                ),
            )
        }
    }
    // no-store rather than no-cache: a second open is the interesting one, and a proxy
    // that keeps the first answer means the log records one open for a message read daily.
    exchange.responseHeaders.add("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
    exchange.responseHeaders.add("Pragma", "no-cache")
    reply(exchange, 200, PIXEL, "image/gif")
}

/**
 * What has been fetched since a moment, for the client that minted the ids.
 *
 * Authenticated, because this is the half that is about somebody's mail: which of their
 * messages were opened and when. The pixel above is public by necessity; this never is.
 */
private fun opens(exchange: HttpExchange, log: Log, token: String) {
    val given = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ").trim()
    if (!sameToken(given, token)) {
        // No detail. "Wrong token" and "no token" are the same answer to anyone guessing.
        reply(exchange, 401, """{"error":"unauthorised"}""".toByteArray(), "application/json")
        return
    }
    val since = exchange.requestURI.query.orEmpty()
        .split('&').firstOrNull { it.startsWith("since=") }
        ?.removePrefix("since=")?.toLongOrNull() ?: 0L
    val found = log.since(since)
    val body = buildString {
        append("""{"fetches":[""")
        found.forEachIndexed { at, fetch ->
            if (at > 0) append(',')
            append("{")
            append(""""id":""").append(quoted(fetch.id)).append(',')
            append(""""at":""").append(fetch.at).append(',')
            append(""""userAgent":""").append(quoted(fetch.userAgent)).append(',')
            append(""""network":""").append(quoted(fetch.network))
            append("}")
        }
        append("]}")
    }
    reply(exchange, 200, body.toByteArray(), "application/json")
}

/**
 * A JSON string, escaped.
 *
 * Hand-rolled because this program has no other use for a JSON library, and the one thing
 * that has to be right is that a user agent is attacker-controlled: it arrives from
 * whatever fetched the pixel, so a quote or a backslash in it must not end the string.
 */
internal fun quoted(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
    }
    append('"')
}

/**
 * Whether two tokens match, in a way that does not leak how much of one was right.
 *
 * Both are hashed first so the comparison is over a fixed length whatever was sent, which
 * is what stops the length itself being a signal.
 */
internal fun sameToken(given: String, expected: String): Boolean {
    if (given.isEmpty()) return false
    val digest = MessageDigest.getInstance("SHA-256")
    return MessageDigest.isEqual(
        digest.digest(given.toByteArray()),
        MessageDigest.getInstance("SHA-256").digest(expected.toByteArray()),
    )
}

/**
 * Who asked, as far as it can be told.
 *
 * Behind a reverse proxy, which is how this is meant to run, the socket address is the
 * proxy. `X-Forwarded-For` is the caller, and its first entry is the original client. It
 * is trusted here because the deployment shape says there is a proxy in front; it only
 * ever reaches [network], which throws away everything but the network anyway, so the
 * worst a forged header achieves is a wrong /24 in a log that nobody bills on.
 */
private fun callerAddress(exchange: HttpExchange): String =
    exchange.requestHeaders.getFirst("X-Forwarded-For")?.split(',')?.firstOrNull()?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: exchange.remoteAddress?.address?.hostAddress.orEmpty()

private fun reply(exchange: HttpExchange, code: Int, body: ByteArray, type: String) {
    exchange.responseHeaders.add("Content-Type", type)
    exchange.sendResponseHeaders(code, body.size.toLong())
    exchange.responseBody.use { it.write(body) }
}

/**
 * A 1x1 transparent GIF, 42 bytes, which is about as small as a valid image gets.
 *
 * Transparent rather than white: it lands in the middle of somebody else's design and a
 * white dot is visible on a coloured background.
 */
internal val PIXEL: ByteArray = Base64.getDecoder()
    .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
