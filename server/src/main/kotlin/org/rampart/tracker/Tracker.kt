package org.rampart.tracker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Rampart's companion.
 *
 * It serves a 1x1 GIF at an id the client minted, writes down that somebody fetched it,
 * and answers one authenticated question: what has been fetched since a given moment.
 * It also fetches a sender picture, when asked with the same token, so the app does not
 * have to ask the sender's site itself.
 *
 * The tracking routes never learn anything about the mail. No message, no subject, no
 * recipient, no address, no password. The ids are random and mean nothing without the
 * client that minted them, which is what makes this safe to run on a cheap box with a
 * public hostname. The picture route is the narrow exception: a domain and a hash of an
 * address, not the address, and not the message.
 *
 * Built on the JDK's own HTTP server rather than a framework. A framework would be a
 * larger dependency than the program.
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
    /*
     * A second, optional token that only ever unlocks [diag], never [opens]. [token] above
     * is the one thing this server was built to protect: whose mail was opened, and when.
     * Handing that same value to every install of an app whose source is public would mean
     * anyone who reads the source could read that log too. A build that ships an inbound
     * point baked in for everyone gets this one instead: it can post diagnostics and do
     * nothing else, so the source being public costs this server nothing worse than
     * somebody posting junk numbers.
     */
    val diagToken = System.getenv("RAMPART_DIAG_TOKEN").orEmpty().takeIf { it.length >= 16 }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val log = Log(System.getenv("RAMPART_TRACKER_DB") ?: "/data/tracker.db")
    val keepDays = System.getenv("RAMPART_TRACKER_KEEP_DAYS")?.toIntOrNull() ?: 400
    log.forgetOlderThan(keepDays)
    val pushClients = mutableListOf<PushClient>()
    System.getenv("RAMPART_NTFY_URL")?.trim()?.takeIf { it.isNotEmpty() }?.let {
        pushClients.add(NtfyClient(it, System.getenv("RAMPART_NTFY_TOKEN").orEmpty().takeIf(String::isNotBlank)))
    }
    System.getenv("RAMPART_GOTIFY_URL")?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
        System.getenv("RAMPART_GOTIFY_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
            pushClients.add(GotifyClient(url, token))
        }
    }
    val pushes = Executors.newFixedThreadPool(2)

    val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
    // Icon lookups wait on other servers. A larger pool, and /icon answers 503 when it
    // is already busy, so a pixel is not stuck behind a homepage.
    server.executor = Executors.newFixedThreadPool(16)

    installRoutes(server, log, token, diagToken, pushClients, pushes)
    /*
     * Never behind anything. A health check that answers 200 from a login page says the
     * service is up when it is not, which is the trap the rules file calls out by name.
     */
    server.start()
    println("Rampart tracker listening on $port")
}

internal fun installRoutes(
    server: HttpServer,
    log: Log,
    token: String,
    diagToken: String? = null,
    pushClients: List<PushClient> = emptyList(),
    pushes: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() },
    icons: IconService? = null,
) {
    server.createContext("/o/") { exchange -> pixel(exchange, log, pushClients, pushes) }
    server.createContext("/opens") { exchange -> opens(exchange, log, token, pushClients.isNotEmpty()) }
    server.createContext("/labels") { exchange -> labels(exchange, log, token) }
    server.createContext("/links") { exchange -> links(exchange, log, token) }
    server.createContext("/replied") { exchange -> replied(exchange, log, token) }
    server.createContext("/c/") { exchange -> click(exchange, log, pushClients, pushes) }
    server.createContext("/diag") { exchange -> diag(exchange, log, token, diagToken) }
    // The main token only. The diag token must not unlock a route that fetches URLs.
    val pictures = icons ?: IconService()
    server.createContext("/icon") { exchange -> handleIcon(exchange, token, pictures) }
    server.createContext("/health") { exchange -> reply(exchange, 200, "ok".toByteArray(), "text/plain") }
}

/**
 * The pixel itself.
 *
 * **Always an image, and always a 200**, whatever the id was. A 404 for an unknown id
 * tells anyone who asks which ids exist, and a mail client that gets an error draws a
 * broken-image icon in the middle of somebody's message. An id we have never seen is
 * simply recorded, because it costs nothing and the alternative leaks.
 */
private fun pixel(exchange: HttpExchange, log: Log, pushClients: List<PushClient>, pushes: java.util.concurrent.Executor) {
    val id = exchange.requestURI.path.removePrefix("/o/").removeSuffix(".gif")
    if (id.isNotBlank() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
        runCatching {
            val fetch = synchronized(log) {
                val previous = log.fetchesFor(id)
                val candidate = Fetch(
                    id = id,
                    at = System.currentTimeMillis(),
                    userAgent = exchange.requestHeaders.getFirst("User-Agent").orEmpty(),
                    network = network(callerAddress(exchange)),
                )
                candidate.copy(
                    classification = classifyOpen(
                        OpenSignals(candidate.userAgent, candidate.network, candidate.at, previous),
                    ),
                ).also(log::record)
            }
            if (fetch.classification in setOf(OpenClassification.PERSON, OpenClassification.REPEAT) &&
                pushClients.isNotEmpty() && log.alertsEnabled(id)) {
                val count = log.fetchesFor(id).count {
                    it.event == "open" && it.classification in setOf(OpenClassification.PERSON, OpenClassification.REPEAT)
                }
                val label = log.labelFor(id)
                val notification = openNotification(label, count, fetch.at)
                pushClients.forEach { client ->
                    pushes.execute {
                        runCatching { client.send(notification) }
                            .onFailure { System.err.println("${client.name} notification failed: ${it.message ?: it::class.simpleName}") }
                    }
                }
            }
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
private fun opens(exchange: HttpExchange, log: Log, token: String, pushesConfigured: Boolean) {
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
        append("""{"companionPushes":$pushesConfigured,"ntfyConfigured":$pushesConfigured,"fetches":[""")
        found.forEachIndexed { at, fetch ->
            if (at > 0) append(',')
            append("{")
            append(""""id":""").append(quoted(fetch.id)).append(',')
            append(""""at":""").append(fetch.at).append(',')
            append(""""userAgent":""").append(quoted(fetch.userAgent)).append(',')
            append(""""network":""").append(quoted(fetch.network))
            append(',').append(""""classification":""").append(quoted(fetch.classification.wireName))
            append(',').append(""""event":""").append(quoted(fetch.event))
            append(',').append(""""url":""").append(quoted(fetch.url))
            append("}")
        }
        append("]}")
    }
    reply(exchange, 200, body.toByteArray(), "application/json")
}

private fun labels(exchange: HttpExchange, log: Log, token: String) {
    if (exchange.requestMethod != "POST") {
        reply(exchange, 405, """{"error":"use POST"}""".toByteArray(), "application/json")
        return
    }
    val given = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ").trim()
    if (!labelAuthorised(given, token)) {
        reply(exchange, 401, """{"error":"unauthorised"}""".toByteArray(), "application/json")
        return
    }
    val raw = exchange.requestBody.use { it.readNBytes(4097) }
    if (raw.size > 4096) {
        reply(exchange, 413, """{"error":"too large"}""".toByteArray(), "application/json")
        return
    }
    val body = parseJson(String(raw, Charsets.UTF_8)) as? Map<*, *>
    val id = (body?.get("id") as? String)?.takeIf(::validTrackingId)
    val label = (body?.get("label") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 }
    if (id == null || label == null) {
        reply(exchange, 400, """{"error":"invalid label"}""".toByteArray(), "application/json")
        return
    }
    log.setLabel(TrackingLabel(id, label, System.currentTimeMillis()))
    reply(exchange, 200, """{"stored":true}""".toByteArray(), "application/json")
}

private fun links(exchange: HttpExchange, log: Log, token: String) {
    if (exchange.requestMethod != "POST") {
        reply(exchange, 405, """{"error":"use POST"}""".toByteArray(), "application/json")
        return
    }
    val given = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ").trim()
    if (!labelAuthorised(given, token)) {
        reply(exchange, 401, """{"error":"unauthorised"}""".toByteArray(), "application/json")
        return
    }
    val raw = exchange.requestBody.use { it.readNBytes(65_537) }
    if (raw.size > 65_536) {
        reply(exchange, 413, """{"error":"too large"}""".toByteArray(), "application/json")
        return
    }
    val body = parseJson(String(raw, Charsets.UTF_8)) as? Map<*, *>
    val id = (body?.get("id") as? String)?.takeIf(::validTrackingId)
    val sentAt = (body?.get("sentAt") as? Double)?.toLong() ?: System.currentTimeMillis()
    val registered = (body?.get("links") as? List<*>).orEmpty().mapIndexedNotNull { index, value ->
        (value as? String)?.takeIf { url ->
            url.length <= 4096 && runCatching { URI(url).scheme.lowercase() in setOf("http", "https") }.getOrDefault(false)
        }?.let { TrackedLink(id.orEmpty(), index, it, sentAt) }
    }
    if (id == null || registered.isEmpty()) {
        reply(exchange, 400, """{"error":"invalid links"}""".toByteArray(), "application/json")
        return
    }
    log.setLinks(registered)
    reply(exchange, 200, """{"stored":${registered.size}}""".toByteArray(), "application/json")
}

private fun click(exchange: HttpExchange, log: Log, pushClients: List<PushClient>, pushes: java.util.concurrent.Executor) {
    val parts = exchange.requestURI.path.removePrefix("/c/").split('/')
    val id = parts.getOrNull(0)?.takeIf(::validTrackingId)
    val number = parts.getOrNull(1)?.toIntOrNull()
    val link = if (id == null || number == null) null else log.linkFor(id, number)
    if (link == null) {
        reply(exchange, 404, "not found".toByteArray(), "text/plain")
        return
    }
    val at = System.currentTimeMillis()
    val previous = log.fetchesFor(link.id).filter { it.event == "click" && it.url == link.url }
    val candidate = Fetch(
        id = link.id, at = at,
        userAgent = exchange.requestHeaders.getFirst("User-Agent").orEmpty(),
        network = network(callerAddress(exchange)), event = "click", url = link.url,
    )
    val recorded = candidate.copy(
        classification = classifyOpen(OpenSignals(candidate.userAgent, candidate.network, at, previous, link.createdAt)),
    )
    log.record(recorded)
    if (recorded.classification == OpenClassification.PERSON && log.alertsEnabled(link.id)) {
        val label = log.labelFor(link.id)
        val domain = runCatching { URI(link.url).host }.getOrNull().orEmpty()
        val notification = PushNotification(
            "Email tracking",
            trackingNotificationText(label, "clicked $domain", recorded.at),
            link.url,
        )
        pushClients.forEach { client -> pushes.execute { runCatching { client.send(notification) } } }
    }
    exchange.responseHeaders.add("Location", link.url)
    exchange.responseHeaders.add("Cache-Control", "no-store")
    reply(exchange, 302, ByteArray(0), "text/plain")
}

private fun replied(exchange: HttpExchange, log: Log, token: String) {
    if (exchange.requestMethod != "POST") {
        reply(exchange, 405, """{"error":"use POST"}""".toByteArray(), "application/json"); return
    }
    val given = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ").trim()
    if (!labelAuthorised(given, token)) {
        reply(exchange, 401, """{"error":"unauthorised"}""".toByteArray(), "application/json"); return
    }
    val raw = exchange.requestBody.use { it.readNBytes(4097) }
    val body = parseJson(String(raw, Charsets.UTF_8)) as? Map<*, *>
    val id = (body?.get("id") as? String)?.takeIf(::validTrackingId)
    if (id == null) {
        reply(exchange, 400, """{"error":"invalid id"}""".toByteArray(), "application/json"); return
    }
    log.stopAlerts(id, System.currentTimeMillis())
    reply(exchange, 200, """{"stored":true}""".toByteArray(), "application/json")
}

internal fun labelAuthorised(given: String, token: String): Boolean = sameToken(given, token)

private fun validTrackingId(id: String): Boolean =
    id.isNotBlank() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

internal interface PushClient {
    val name: String
    fun send(notification: PushNotification)
}

internal data class PushNotification(val title: String, val message: String, val click: String? = null)
internal typealias NtfyNotification = PushNotification

internal fun openNotification(label: String?, count: Int, at: Long = System.currentTimeMillis()): PushNotification {
    val times = when (count) {
        2 -> "2nd time"
        3 -> "3rd time"
        else -> "${count}th time"
    }
    return PushNotification(
        title = "Email tracking",
        message = when {
            count == 1 -> trackingNotificationText(label, "opened", at)
            else -> trackingNotificationText(label, "opened again, $times", at)
        },
    )
}

/** Gives ntfy and Gotify one wording for opens and clicks. */
internal fun trackingNotificationText(label: String?, action: String, at: Long): String {
    val parts = label.orEmpty().split('\n', limit = 2)
    val who = parts.getOrNull(0)?.ifBlank { null } ?: "Someone"
    val subject = parts.getOrNull(1)?.ifBlank { null }
    val time = DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(at))
    return if (subject == null) "$who $action at $time"
    else "$who $action in \"$subject\" at $time"
}

internal class NtfyClient(
    private val topicUrl: String,
    private val token: String?,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
) : PushClient {
    override val name: String get() = "ntfy"

    override fun send(notification: PushNotification) {
        val builder = HttpRequest.newBuilder(URI.create(topicUrl))
            .timeout(Duration.ofSeconds(4))
            .header("Title", notification.title)
            .header("Tags", "envelope")
            .header("Priority", "default")
            .POST(HttpRequest.BodyPublishers.ofString(notification.message))
        token?.let { builder.header("Authorization", "Bearer $it") }
        notification.click?.let { builder.header("Click", it) }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) error("ntfy returned HTTP ${response.statusCode()}")
    }
}

internal class GotifyClient(
    private val serverUrl: String,
    private val token: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
) : PushClient {
    override val name: String get() = "Gotify"

    override fun send(notification: PushNotification) {
        val endpoint = URI.create(serverUrl.trim().trimEnd('/') + "/message")
        val body = """{"title":${quoted(notification.title)},"message":${quoted(notification.message)},"priority":5}"""
        val builder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(4))
            .header("X-Gotify-Key", token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) error("Gotify returned HTTP ${response.statusCode()}")
    }
}

/**
 * Rampart's own diagnostics: how long things took and how often something happened,
 * aggregated by the client over a few minutes before it ever reaches here.
 *
 * Accepts either [token] or [diagToken], unlike [opens] which only ever accepts [token].
 * [diagToken] exists so a value that is public, because it ships in an app's own source,
 * can still write here without being able to read [opens] back: see its own KDoc in
 * [main] for why that split matters.
 */
private fun diag(exchange: HttpExchange, log: Log, token: String, diagToken: String?) {
    if (exchange.requestMethod != "POST") {
        reply(exchange, 405, """{"error":"use POST"}""".toByteArray(), "application/json")
        return
    }
    val given = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ").trim()
    if (!diagAuthorised(given, token, diagToken)) {
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
    "message.open.cache_hit", "message.open.read_ahead_hit", "message.body.clipped",
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
        val category = (item["category"] as? String)?.takeIf { it.isNotBlank() && it.length <= 400 }
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
 * Whether a caller may reach [diag]: either [token], which also reads [opens] back, or
 * [diagToken], which never does. Pulled out of [diag] itself so the two-token rule has one
 * place to be right and one place to be tested, instead of being read back out of an HTTP
 * handler.
 */
internal fun diagAuthorised(given: String, token: String, diagToken: String?): Boolean =
    sameToken(given, token) || (diagToken != null && sameToken(given, diagToken))

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

/** Internal so the icon route can answer with the same headers and the same body write. */
internal fun reply(exchange: HttpExchange, code: Int, body: ByteArray, type: String) {
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
