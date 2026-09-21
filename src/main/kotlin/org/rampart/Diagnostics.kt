package org.rampart

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * How long things took, how often something happened, and what kind of trouble it ran
 * into, so a slow message or a failed send can be looked at rather than guessed about.
 *
 * **Never a subject, a sender, a recipient, a folder's name or a search term. Not even
 * hashed.** [Metric] is a closed set of names, exactly the ones in `docs/diagnostics.md`'s
 * catalog, and [Category] is a closed set of outcomes: nothing here can carry a string
 * built from a real message, because nothing here accepts one. Adding a new signal later
 * means adding a name to [Metric] in code, reviewed the same way any other change is, not
 * passing a fresh string through at a call site.
 *
 * **Local collection is unconditional.** [time], [count] and [event] always keep the last
 * little while of activity in [recent], with no toggle and no server required, because
 * that is what answers "why did that message feel slow just now" and it can answer it with
 * nothing sent anywhere. The one real toggle, [Settings.diagnosticsReporting], decides only
 * whether the aggregate in [pending] is ever POSTed on. It defaults to on: a build always
 * has somewhere to send it now, [OFFICIAL_SERVER], the same rule `docs/open-tracking.md`
 * already settled for the tracking pixel, that a feature built so the numbers can be seen
 * should default to letting them be seen. Pointing [Settings.diagnosticsServer] at a
 * self-hosted server instead sends there rather than to us; it never adds a second
 * destination.
 *
 * **Two kinds of data live here and they do not mix.** [recent] is for the local
 * Diagnostics settings page and can hold a scrubbed error message, because it never leaves
 * the machine. [pending] is what [flush] can send, and its only shape is
 * metric key, category and numbers: count, sum, min, max. There is no field in that shape
 * capable of holding a sentence, so a message cannot reach the wire by accident, only by
 * someone adding a new field to [Aggregate] and wiring it through on purpose.
 *
 * An error is captured through [error] or [crash] rather than by keeping the exception,
 * because an exception's own message is the dangerous part: it can quote a URL, a header,
 * a path with somebody's name in it. [scrub] removes the two shapes that message actually
 * contains before anything is kept even locally, and [classify] reduces the exception to
 * one of a handful of categories before anything about it is aggregated for upload.
 */
object Diagnostics {

    /** The token this install's diagnostics server was given, kept the same way [Assistant.KEY] is. */
    const val TOKEN_NAME = "diagnostics-token"

    /**
     * Where a build sends diagnostics when [Settings.diagnosticsServer] has not been set to
     * something else.
     *
     * [OFFICIAL_TOKEN] is a public ingest key, not a secret: it ships in the source of a
     * public repository, so anyone can read it. It is scoped server-side to exactly this:
     * the companion's `/diag` route accepts it and only it, never `/opens`, so reading it
     * out of the source gets nobody anything but the ability to post junk numbers, the same
     * shape a Sentry DSN or an analytics write key is exposed to. See `server/README.md`'s
     * "Why there are two tokens." Self-hosting your own copy instead, with your own token
     * entered on this page, is still the whole point of [Settings.setDiagnosticsServer]
     * existing; this pair is only the default for everyone who does not.
     */
    private const val OFFICIAL_SERVER = "https://rampart-relay.willhitestrategy.org"
    private const val OFFICIAL_TOKEN = "U1Bspkc1QxJxvDZE3l50V3ul5DvoM2gTs6zen+s2Rxw=" // gitleaks:allow

    private const val MAX_RECENT = 500
    private val MAX_AGE: Duration = Duration.ofHours(24)
    private val FLUSH_EVERY: Duration = Duration.ofMinutes(3)
    private const val MAX_BATCH_ITEMS = 200

    private val recent = ConcurrentLinkedDeque<RecentEvent>()
    private val pending = ConcurrentHashMap<PendingKey, Aggregate>()

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /*
     * Started the first time anything is actually recorded, not at class load. A test, or a
     * build with nothing wired into it yet, that never calls this object at all should never
     * spin up a thread for it. A daemon thread, so it never keeps the JVM, or a test runner,
     * open on its own.
     */
    private val scheduler: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "rampart-diagnostics").apply { isDaemon = true }
        }.also {
            val minutes = FLUSH_EVERY.toMinutes()
            it.scheduleWithFixedDelay({ runCatching { flush() } }, minutes, minutes, TimeUnit.MINUTES)
        }
    }

    private fun ensureRunning() {
        scheduler
    }

    /**
     * Times [block], on or off. Off costs one clock read either way: every call site runs
     * unconditionally rather than being wrapped in an `if`, so nobody has to remember to
     * guard one.
     *
     * `inline`, so [block] can hold a suspending call (a network round trip, most of the
     * time this exists to measure) without needing its own `suspend` lambda type: inlining
     * splices it into the caller's own coroutine, rather than boxing it as a second one.
     */
    inline fun <T> time(metric: Metric, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            duration(metric, (System.nanoTime() - start) / 1_000_000.0)
        }
    }

    /**
     * A duration already measured elsewhere, in milliseconds.
     *
     * [time] cannot wrap every measurement this catalog needs: a message's render time
     * starts when its body arrives and ends when a WebView engine reports layout finished,
     * two points that are not one call away from each other. This is the primitive both of
     * them are built on, and the one to reach for whenever a start and a stop are on
     * opposite sides of a callback rather than around one block.
     */
    fun duration(metric: Metric, ms: Double) {
        runCatching { record(metric.key, ms, null, null) }
    }

    /** A tally: how many times something happened, or how many of something one load cost. */
    fun count(metric: Metric, amount: Int = 1) {
        runCatching { record(metric.key, amount.toDouble(), null, null) }
    }

    /** Something happened, with one of a fixed set of outcomes. Never a raw string: see [Category]. */
    fun event(metric: Metric, category: Category) {
        runCatching { record(metric.key, 1.0, category.value, null) }
    }

    /**
     * An error, classified before anything about it is kept.
     *
     * [throwable]'s message, if there is one, goes through [scrub] and stays in [recent]
     * only, for the on-screen Diagnostics page. What gets aggregated for [flush] is
     * [category] and a count, nothing else: see this file's header for why that is true
     * structurally rather than by promise.
     */
    fun error(metric: Metric, category: ErrorCategory, throwable: Throwable? = null) {
        runCatching {
            val detail = throwable?.message?.let(::scrub)
            record(metric.key, 1.0, category.value, detail)
        }
    }

    /**
     * `app.crash`. The category is the exception's own class name and nothing else about
     * it: never the message, never a stack trace. [frames], kept locally only, is the one
     * exception to "never a stack trace", and even it is capped to the first few frames
     * that are our own code, by class and method name alone.
     */
    fun crash(throwable: Throwable) {
        runCatching {
            val category = CrashCategory.of(throwable)
            val frames = throwable.stackTrace.asSequence()
                .filter { it.className.startsWith("org.rampart.") }
                .take(5)
                .joinToString(" < ") { it.className.removePrefix("org.rampart.") + "." + it.methodName }
            record(Metric.APP_CRASH.key, 1.0, category.value, frames.ifBlank { null })
        }
    }

    private fun record(metricKey: String, value: Double, category: String?, detail: String?) {
        ensureRunning()
        recent.addLast(RecentEvent(Instant.now(), metricKey, value, category, detail))
        while (recent.size > MAX_RECENT) recent.pollFirst()
        pending.compute(PendingKey(metricKey, category)) { _, existing ->
            if (existing == null) Aggregate(1, value, value, value)
            else Aggregate(
                existing.count + 1,
                existing.sum + value,
                minOf(existing.min, value),
                maxOf(existing.max, value),
            )
        }
    }

    /**
     * The recent raw events, newest first, for the on-screen Diagnostics page.
     *
     * Filtered by age here rather than only when written, so a page opened long after the
     * last activity never shows something [MAX_AGE] has already made stale, whether or not
     * anything has run since to trim it.
     */
    fun recent(limit: Int = MAX_RECENT): List<RecentEvent> {
        val cutoff = Instant.now().minus(MAX_AGE)
        return recent.asSequence().filter { it.at.isAfter(cutoff) }.toList().takeLast(limit).asReversed()
    }

    /**
     * Aggregates whatever has built up since the last call, and if reporting is on and a
     * server is configured, sends it on as one small batch.
     *
     * [pending] is drained unconditionally, whether or not anything is actually sent. That
     * is deliberate: an aggregate belongs to the window it was measured in, and holding a
     * stale one to try again next time would let a server that is down for an evening grow
     * the map without bound. A failed send costs one lost batch, never a leak.
     *
     * Drained key by key rather than a snapshot-then-`clear()`: a `clear()` after `toList()`
     * would wipe out whatever a concurrent [record] added to an existing key in the gap
     * between the two, since `clear()` empties the map as it stands then, not as the earlier
     * snapshot saw it. Removing each key's own value is what [record] can never race with.
     */
    internal fun flush(): FlushOutcome {
        val batch = pending.keys.toList().mapNotNull { key -> pending.remove(key)?.let { key to it } }
        if (batch.isEmpty()) return FlushOutcome.NOTHING_TO_SEND
        if (!Settings.diagnosticsReporting()) return FlushOutcome.REPORTING_OFF
        val (server, token) = targetFor(Settings.diagnosticsServer())
        val items = batch.take(MAX_BATCH_ITEMS).map { (key, aggregate) -> key to aggregate }
        return try {
            send(server, token, items)
            FlushOutcome.SENT
        } catch (e: Exception) {
            runCatching {
                Thread.sleep(1000)
                send(server, token, items)
            }.fold({ FlushOutcome.SENT }, { FlushOutcome.FAILED })
        }
    }

    /**
     * Where a batch actually goes: [customServer] and its saved token when one has been set,
     * [OFFICIAL_SERVER] and [OFFICIAL_TOKEN] when it has not. There is no third state to
     * fail into any more, since [flush] always has somewhere to send a batch once reporting
     * is on.
     */
    internal fun targetFor(customServer: String): Pair<String, String> =
        if (customServer.isBlank()) OFFICIAL_SERVER to OFFICIAL_TOKEN
        else customServer to Secrets.loadNamed(TOKEN_NAME).orEmpty()

    /**
     * Whether the diagnostics server is there and the token is right, for the settings
     * page's Check and save button. Posts an empty batch rather than a real one: this is a
     * connectivity and auth check, not the first upload.
     */
    internal fun checkServer(base: String, token: String): String? {
        trackingProblem(base)?.let { return it }
        if (token.isBlank()) return "Give Rampart the same token the server was started with."
        return try {
            send(base, token, emptyList())
            null
        } catch (e: DiagnosticsError) {
            e.message
        } catch (e: Exception) {
            "Could not reach it: " + whyFailed(e)
        }
    }

    private fun send(base: String, token: String, items: List<Pair<PendingKey, Aggregate>>) {
        val body = buildJsonObject {
            putJsonArray("items") {
                items.forEach { (key, aggregate) ->
                    add(
                        buildJsonObject {
                            put("metric", key.metric)
                            key.category?.let { put("category", it) }
                            put("count", aggregate.count)
                            put("sum", aggregate.sum)
                            put("min", aggregate.min)
                            put("max", aggregate.max)
                        },
                    )
                }
            }
        }
        val response = http.send(
            HttpRequest.newBuilder(URI.create(base.trim().trimEnd('/') + "/diag"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        if (response.statusCode() == 401) {
            throw DiagnosticsError("The diagnostics server did not accept the token.")
        }
        if (response.statusCode() != 200) {
            throw DiagnosticsError("The diagnostics server answered ${response.statusCode()}.")
        }
    }

    /** Clears the in-memory buffers. Test-only: nothing in the running app ever calls this. */
    internal fun resetForTests() {
        recent.clear()
        pending.clear()
    }
}

internal class DiagnosticsError(message: String) : Exception(message)

/** One grouping key for [Diagnostics]'s upload aggregate. Never a message, only the two things that name it. */
internal data class PendingKey(val metric: String, val category: String?)

/** What can leave the machine for one [PendingKey]: a count and the shape of a duration, never text. */
internal data class Aggregate(val count: Long, val sum: Double, val min: Double, val max: Double)

/**
 * One raw event, kept locally only, for the on-screen Diagnostics page.
 *
 * [detail] is the one field with no equivalent in [Aggregate], and it stays that way: it is
 * already [Diagnostics.scrub]bed of anything email- or path-shaped, but that scrubbing is
 * what makes it safe to show on screen, not what would make it safe to upload, so [flush]
 * never reads this class at all.
 */
data class RecentEvent(
    val at: Instant,
    val metric: String,
    /** Milliseconds for a timed metric, the tally for a count, or 1 for a bare event. */
    val value: Double?,
    /** The outcome, for an event or an error. Always one of [Category]'s fixed values. */
    val category: String?,
    /** The scrubbed message or crash frames, for an error or a crash only. Never uploaded. */
    val detail: String?,
)

/**
 * The whole allowlist of what Rampart is allowed to know about itself.
 *
 * A fixed, closed set of names, matched exactly to the catalog this feature was built
 * against. Every one of these carries only numbers or a [Category], never a string built
 * from live data. Adding a metric means adding a name here, reviewed the same way any
 * other change is; it does not mean a new string gets to appear at whichever call site
 * happens to want one.
 */
enum class Metric(val key: String) {
    MESSAGE_OPEN_TOTAL("message.open.total"),
    MESSAGE_OPEN_FETCH("message.open.fetch"),
    MESSAGE_OPEN_RENDER("message.open.render"),
    MESSAGE_OPEN_CACHE_HIT("message.open.cache_hit"),
    MESSAGE_OPEN_READ_AHEAD_HIT("message.open.read_ahead_hit"),
    LIST_LOAD_COLD("list.load.cold"),
    LIST_LOAD_WARM("list.load.warm"),
    LIST_PAGE_LOAD("list.page.load"),
    LIST_COMMANDS("list.commands"),
    SEARCH_QUERY("search.query"),
    SEARCH_FALLBACK("search.fallback"),
    SEND_COMPOSE_TO_SENT("send.compose_to_sent"),
    SEND_FAILURE("send.failure"),
    SYNC_POLL("sync.poll"),
    SYNC_PUSH_LATENCY("sync.push_latency"),
    SYNC_PUSH_RECONNECT("sync.push_reconnect"),
    APP_STARTUP("app.startup"),
    APP_CRASH("app.crash"),
    UPDATE_CHECK("update.check"),
    UPDATE_STAGE("update.stage"),
    UPDATE_APPLY("update.apply"),
}

/**
 * One of a fixed set of outcomes for an [Diagnostics.event], never a raw string.
 *
 * Every implementation is an enum baked into this file, or in [CrashCategory]'s case a
 * class whose only constructor is private. That is the whole allowlist discipline for
 * categories: a call site can only ever hand [Diagnostics.event] a value that already
 * existed in code before the call was written, which is what makes a bad call a compile
 * error rather than something a test has to catch. [Metric] is closed the same way, by
 * being an enum rather than a string; this is that same idea applied to the outcome half
 * of an event.
 */
sealed interface Category {
    val value: String
}

/** The shape every error is reduced to before anything is kept about it at all. See [Diagnostics.classify]. */
enum class ErrorCategory(override val value: String) : Category {
    TIMEOUT("timeout"),
    AUTH("auth"),
    TLS("tls"),
    DNS("dns"),
    SERVER_ERROR("server_error"),
    PARSE_ERROR("parse_error"),
    UNKNOWN("unknown"),
}

/** `send.failure`'s categories. A superset of [ErrorCategory] in spirit; kept separate because "rejected" has no equivalent there. */
enum class SendFailureCategory(override val value: String) : Category {
    AUTH("auth"),
    CONNECTION("connection"),
    REJECTED("rejected"),
    TIMEOUT("timeout"),
    UNKNOWN("unknown"),
}

/** `update.check`'s categories. */
enum class UpdateCheckCategory(override val value: String) : Category {
    NEWER_FOUND("newer-found"),
    CURRENT("current"),
    CHECK_FAILED("check-failed"),
}

/** `update.stage`'s categories. */
enum class UpdateStageCategory(override val value: String) : Category {
    STAGED("staged"),
    NOT_READY("not-ready"),
    FAILED("failed"),
}

/** `update.apply`'s categories. */
enum class UpdateApplyCategory(override val value: String) : Category {
    APPLIED("applied"),
    FAILED("failed"),
}

/**
 * `app.crash`'s category: the crashing exception's class name, and nothing else about it.
 *
 * Not an enum, because the set of exception classes is not ours to close off, but the
 * constructor is private so the only way to make one is [of], which takes the whole
 * exception and keeps only its class's simple name. Never the message, never a stack
 * trace: seeing "NullPointerException" fifty times is useful, seeing what the fiftieth one
 * happened to be pointing at when it was null is not worth what it costs to keep.
 */
class CrashCategory private constructor(override val value: String) : Category {
    companion object {
        fun of(e: Throwable): CrashCategory = CrashCategory((e::class.simpleName ?: "Unknown").take(80))
    }
}

/**
 * Removes the two shapes of personally identifying text an exception message actually
 * contains, for the LOCAL-only diagnostics view: see [Diagnostics] for why nothing this
 * returns is ever uploaded regardless.
 *
 * An email address, anywhere in the text including inside a URL's query string, and a home
 * directory that carries a username on Windows, Linux and macOS alike (`C:\Users\<name>`,
 * `/home/<name>`, `/Users/<name>`). Those are the two shapes an exception's own wording
 * actually contains; a header name or a status code quoted in the same message is not, on
 * its own, something that identifies a person, so this does not try to remove everything
 * that could conceivably be sensitive, only the two things that name somebody.
 */
internal fun scrub(text: String): String {
    val noEmails = EMAIL_LIKE.replace(text, "[address]")
    val noWindowsUser = WINDOWS_USER_PATH.replace(noEmails) { it.groupValues[1] }
    return UNIX_USER_PATH.replace(noWindowsUser) { it.groupValues[1] }
}

private val EMAIL_LIKE = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

// The username segment only, so the rest of the path survives. Capturing the drive and
// "Users" but not the trailing separator is what lets the replacement drop the name while
// leaving exactly one backslash before whatever comes next, rather than adding a second one.
private val WINDOWS_USER_PATH = Regex("""(?i)([A-Za-z]:\\Users)\\[^\\/:*?"<>|\r\n]+""")
private val UNIX_USER_PATH = Regex("""(/(?:home|Users))/[^/\s]+""")

/**
 * The chain-of-causes, match-by-class-name-suffix or match-by-message primitives [classify]
 * and [sendFailureCategoryOf] both reduce an exception with. Shared so there is one
 * definition of "this exception chain is a timeout" for the two call sites to agree on,
 * not two copies that could quietly drift apart.
 */
private class Chain(e: Throwable) {
    private val chain = generateSequence(e as Throwable?) { it.cause }.take(8).toList()
    private val messages = chain.mapNotNull { it.message }
    fun kind(suffix: String) = chain.any { it.javaClass.name.endsWith(suffix) }
    fun says(fragment: String) = messages.any { it.contains(fragment, ignoreCase = true) }
}

/**
 * Reduces an exception to one of a fixed set of categories, for anything that is allowed
 * to leave the machine about it. The same chain-of-causes, match-by-class-name-suffix
 * approach [plainNetworkError] in `Jmap.kt` already uses for the sign-in screen's wording,
 * because it is the right way to tell one kind of network failure from another on the JVM
 * and there is no reason to invent a second one. What differs is the output: that function
 * returns a sentence with the server's hostname in it, which is exactly the kind of string
 * this file exists to keep away from anything uploaded, so it is not reused directly here.
 */
internal fun classify(e: Throwable): ErrorCategory {
    val c = Chain(e)
    fun kind(suffix: String) = c.kind(suffix)
    fun says(fragment: String) = c.says(fragment)
    return when {
        kind("SocketTimeoutException") || kind("HttpTimeoutException") || kind("TimeoutException") ->
            ErrorCategory.TIMEOUT
        kind("SSLHandshakeException") || kind("CertificateException") || kind("SSLException") ->
            ErrorCategory.TLS
        kind("UnresolvedAddressException") || kind("UnknownHostException") ->
            ErrorCategory.DNS
        kind("AuthenticationFailedException") || says("401") || says("unauthoris") || says("unauthoriz") ->
            ErrorCategory.AUTH
        says("http 5") || says("server answered") ->
            ErrorCategory.SERVER_ERROR
        kind("ParseException") || kind("JsonException") || kind("SerializationException") ->
            ErrorCategory.PARSE_ERROR
        else -> ErrorCategory.UNKNOWN
    }
}

/**
 * The same reduction as [classify], for `send.failure`, which distinguishes a server that
 * refused the message from one it could not reach at all: two outcomes a person sending
 * mail wants told apart and [ErrorCategory] has no separate name for.
 */
internal fun sendFailureCategoryOf(e: Throwable): SendFailureCategory {
    val c = Chain(e)
    fun kind(suffix: String) = c.kind(suffix)
    fun says(fragment: String) = c.says(fragment)
    return when {
        kind("SocketTimeoutException") || kind("HttpTimeoutException") || kind("TimeoutException") ->
            SendFailureCategory.TIMEOUT
        kind("ConnectException") || kind("UnresolvedAddressException") ||
            kind("UnknownHostException") || kind("NoRouteToHostException") ->
            SendFailureCategory.CONNECTION
        kind("AuthenticationFailedException") || says("401") || says("unauthoris") || says("unauthoriz") ->
            SendFailureCategory.AUTH
        says("refused") || says("rejected") || says("invalidproperties") ->
            SendFailureCategory.REJECTED
        else -> SendFailureCategory.UNKNOWN
    }
}

/** Outcomes [Diagnostics.flush] can report, for the tests: production never reads this. */
internal enum class FlushOutcome { NOTHING_TO_SEND, REPORTING_OFF, SENT, FAILED }
