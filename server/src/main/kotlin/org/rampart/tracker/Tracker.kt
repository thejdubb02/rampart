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
    server.createContext("/diag") { exchange -> diag(exchange, log, token) }
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
 * Rampart's own diagnostics: how long things took and how often something happened,
 * aggregated by the client over a few minutes before it ever reaches here.
 *
 * Authenticated the same way [opens] is, because this is data about how the install is
 * running rather than about anybody's mail, but it is still nobody's business but the
 * person who runs the client and whoever they choose to run this for them.
 */
private fun diag(exchange: HttpExchange, log: Log, token: String) {
    if (exchange.requestMethod != "POST") {
        reply(exchange, 405, """{"error":"use POST"}""".toByteArray(), "application/json")
        return
    }
    val given = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ").trim()
    if (!sameToken(given, token)) {
        reply(exchange, 401, """{"error":"unauthorised"}""".toByteArray(), "application/json")
        return
    }
    // A batch is a handful of small numbers; anything past this is not the client we wrote,
    // and reading it into memory to find that out is the wrong way to learn it.
    val raw = exchange.requestBody.use { it.readNBytes(MAX_DIAG_BODY_BYTES + 1) }
    if (raw.size > MAX_DIAG_BODY_BYTES) {
        reply(exchange, 413, """{"error":"too large"}""".toByteArray(), "application/json")
        return
    }
    val items = runCatching { parseDiagBatch(String(raw, Charsets.UTF_8)) }.getOrDefault(emptyList())
    val stamped = items.map { it.copy(at = System.currentTimeMillis()) }
    runCatching { log.recordDiagnostics(stamped) }
    reply(exchange, 200, """{"stored":${stamped.size}}""".toByteArray(), "application/json")
}

private const val MAX_DIAG_BODY_BYTES = 64 * 1024

/**
 * The metric names this service will actually keep, mirrored by name from the client's own
 * closed allowlist in `Diagnostics.kt`. The two are two different Gradle modules and cannot
 * share the enum itself, so this is a second copy of the same list rather than a reference
 * to it: a metric added on one side and not the other is caught here as "not stored", never
 * as a crash.
 *
 * This is a second line of defence, not the first. The client cannot construct an event
 * outside its own [Metric][org.rampart.Metric] enum in the first place; this exists for the
 * case that matters more than usual, which is a future bug on that side, not a caller who
 * has the token and wants to send something else. Either way, an unrecognised metric is
 * simply not stored, the same silent refusal [parseDiagBatch] gives anything malformed.
 */
private val KNOWN_METRICS = setOf(
    "message.open.total", "message.open.fetch", "message.open.render",
    "message.open.cache_hit", "message.open.read_ahead_hit",
    "list.load.cold", "list.load.warm", "list.page.load", "list.commands",
    "search.query", "search.fallback",
    "send.compose_to_sent", "send.failure",
    "sync.poll", "sync.push_latency", "sync.push_reconnect",
    "app.startup", "app.crash",
    "update.check", "update.stage", "update.apply",
)

/**
 * The one shape this service reads as input: `{"items":[{"metric":"...", ...}, ...]}`.
 *
 * Hand-rolled for the same reason [quoted] is: this service's only dependency is the
 * SQLite driver, by design, and a JSON library would be a bigger addition than the parser
 * below. It is a general enough reader of JSON values that it does not need to know this
 * service's own shape to parse it, but it is not trying to be a complete one: malformed
 * input becomes an empty result rather than an exception, because a client that sent
 * something broken should get nothing stored, not a stack trace shown to whoever holds
 * the token.
 */
internal fun parseDiagBatch(text: String): List<DiagAggregate> {
    val root = parseJson(text) as? Map<*, *> ?: return emptyList()
    val items = root["items"] as? List<*> ?: return emptyList()
    return items.mapNotNull { raw ->
        val item = raw as? Map<*, *> ?: return@mapNotNull null
        val metric = (item["metric"] as? String)?.takeIf { it.length in 1..80 && it in KNOWN_METRICS }
            ?: return@mapNotNull null
        val category = (item["category"] as? String)?.takeIf { it.isNotBlank() && it.length <= 80 }
        val count = (item["count"] as? Double)?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
        DiagAggregate(
            metric = metric,
            category = category,
            count = count,
            sum = item["sum"] as? Double,
            min = item["min"] as? Double,
            max = item["max"] as? Double,
            at = 0L,
        )
    }.take(200)
}

/** Parses one JSON value from [text], or null on anything that is not well formed. */
internal fun parseJson(text: String): Any? = runCatching {
    val reader = JsonReader(text)
    reader.value()
}.getOrNull()

/**
 * A small recursive-descent reader for the only JSON shapes a diagnostics batch is ever
 * built from: an object holding an array of flat objects, whose own values are only
 * strings and numbers. No boolean, no `null` literal and no escape beyond a literal quote
 * or backslash: none of those appear in a metric name, a category token or a count, so
 * reading them would be generality this shape never asks for. Values come back as
 * `Map<String, Any?>`, `List<Any?>`, `String` or `Double`, which is enough for
 * [parseDiagBatch] to pick apart without this class knowing anything about diagnostics.
 */
private class JsonReader(private val text: String) {
    private var i = 0

    fun value(): Any? {
        skipWs()
        if (i >= text.length) return null
        return when (text[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            else -> num()
        }
    }

    private fun skipWs() {
        while (i < text.length && text[i].isWhitespace()) i++
    }

    private fun obj(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        i++ // {
        skipWs()
        if (i < text.length && text[i] == '}') { i++; return map }
        while (i < text.length) {
            skipWs()
            val key = str()
            skipWs()
            if (i >= text.length || text[i] != ':') break
            i++
            map[key] = value()
            skipWs()
            if (i < text.length && text[i] == ',') { i++; continue }
            if (i < text.length && text[i] == '}') { i++; break }
            break
        }
        return map
    }

    private fun arr(): List<Any?> {
        val list = ArrayList<Any?>()
        i++ // [
        skipWs()
        if (i < text.length && text[i] == ']') { i++; return list }
        while (i < text.length) {
            list.add(value())
            skipWs()
            if (i < text.length && text[i] == ',') { i++; skipWs(); continue }
            if (i < text.length && text[i] == ']') { i++; break }
            break
        }
        return list
    }

    private fun str(): String {
        if (i >= text.length || text[i] != '"') return ""
        i++
        val sb = StringBuilder()
        while (i < text.length && text[i] != '"') {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                i++
                sb.append(text[i]) // a literal quote or backslash; nothing else escapes here
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        if (i < text.length) i++ // closing quote
        return sb.toString()
    }

    private fun num(): Double {
        val start = i
        while (i < text.length && (text[i].isDigit() || text[i] in "+-.eE")) i++
        return text.substring(start, i).toDoubleOrNull() ?: 0.0
    }
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
