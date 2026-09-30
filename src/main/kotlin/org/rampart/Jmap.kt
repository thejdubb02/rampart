package org.rampart

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64

/** Anything the server said no to, in words a person can read. */
class JmapError(message: String) : Exception(message)

private const val CORE = "urn:ietf:params:jmap:core"
private const val MAIL = "urn:ietf:params:jmap:mail"
private const val SUBMISSION = "urn:ietf:params:jmap:submission"
private const val VACATION = "urn:ietf:params:jmap:vacationresponse"
private const val SIEVE = "urn:ietf:params:jmap:sieve"
private const val CONTACTS = "urn:ietf:params:jmap:contacts"
private const val QUOTA = "urn:ietf:params:jmap:quota"

internal fun capabilitiesFor(invocations: Array<out JsonArray>): List<String> = buildList {
    add(CORE)
    add(MAIL)
    val needsSubmission = invocations.any {
        val method = it[0].str().orEmpty()
        method.startsWith("EmailSubmission/") || method.startsWith("Identity/")
    }
    if (needsSubmission) add(SUBMISSION)
}

/**
 * How many seconds into the future this server will hold a submission for, straight from
 * its own session document.
 *
 * Zero, RFC 8621's own default, means the server refuses a future sendAt outright and
 * Rampart has to hold the message itself. Pulled out as its own function, the way
 * [capabilitiesFor] already is, so the parsing can be checked against a session document
 * without a live server to hand one over.
 */
internal fun maxDelayedSendOf(session: JsonObject): Long =
    session["capabilities"]?.jsonObject?.get(SUBMISSION)?.jsonObject
        ?.get("maxDelayedSend")?.jsonPrimitive?.longOrNull ?: 0L

/**
 * The envelope for a delayed send: an ordinary mailFrom and rcptTo, the way the server
 * would have derived them itself, plus the one parameter that is the point of asking,
 * FUTURERELEASE's HOLDUNTIL (RFC 4865), carrying an RFC 3339 time in UTC.
 *
 * Supplying an envelope at all means supplying the whole thing: JMAP does not merge a
 * partial one with what it would otherwise derive, so rcptTo is built from the same
 * addresses [Jmap]'s own `emailObject` already puts in To and Cc.
 *
 * A top-level function rather than a method on [Jmap], the same way [capabilitiesFor] is,
 * so the shape of the request can be checked without a live server to send it to.
 */
internal fun holdEnvelope(identity: Identity, draft: Draft, holdUntil: Instant): JsonObjectBuilder.() -> Unit = {
    putJsonObject("mailFrom") {
        put("email", identity.email)
        putJsonObject("parameters") { put("HOLDUNTIL", holdUntilText(holdUntil)) }
    }
    putJsonArray("rcptTo") {
        draft.recipients.forEach { email -> add(buildJsonObject { put("email", email) }) }
    }
}

/** RFC 3339, in UTC, to the second: what FUTURERELEASE's HOLDUNTIL parameter takes. */
internal fun holdUntilText(at: Instant): String =
    DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.SECONDS))

/**
 * About where a server stops taking an HTML signature.
 *
 * Not advertised anywhere in JMAP, so this is a number to explain a refusal with rather
 * than one to enforce. Measured against Stalwart 0.16, which accepts 2047 characters and
 * refuses 2048 with `invalidProperties` and no description.
 */
internal const val SIGNATURE_LIMIT = 2048

private val json = Json { ignoreUnknownKeys = true }

private const val WEBSOCKET = "urn:ietf:params:jmap:websocket"

private val http: HttpClient = HttpClient.newBuilder()
    // Redirects are followed by hand: HttpClient drops the Authorization header across one,
    // and Stalwart answers /.well-known/jmap with a 307 to the real session URL.
    .followRedirects(HttpClient.Redirect.NEVER)
    .connectTimeout(Duration.ofSeconds(15))
    .build()

data class Mailbox(
    val id: String,
    val name: String,
    val role: String?,
    val unread: Int,
    /** The folder this one sits inside, or null at the top level. */
    val parentId: String? = null,
    /**
     * How many messages the folder holds, or 0 when the server did not say.
     *
     * Already in the listing that produced [unread]. Kept so a filter can say how many
     * matched against the folder itself, rather than asking again just to draw a number.
     */
    val total: Int = 0,
    /**
     * Unread conversations in this folder, which is what the list and the sidebar show.
     *
     * The list marks a conversation unread when any message in it is unread, including
     * one filed in another folder. [unread] counts messages in this folder only, so the
     * two numbers disagree: the Inbox can say 1 while two unread conversations are on
     * screen. JMAP calls the conversation number unreadThreads (RFC 8621). It defaults
     * to [unread] for a server that left it out, and for IMAP, which has no such count.
     */
    val unreadThreads: Int = unread,
)

data class Summary(
    val id: String,
    val from: String,
    val fromEmail: String,
    val subject: String,
    val receivedAt: String,
    val preview: String,
    val seen: Boolean,
    /** Starred. `$flagged` is what every other mail client calls a star too. */
    val flagged: Boolean = false,
    /** Every keyword on the message, protocol ones included. See [tagsOf]. */
    val keywords: Set<String> = emptySet(),
    /**
     * Which signed in account this came from. Blank everywhere except a merged list, where
     * it is the only thing that says which server to talk to about this message.
     */
    val account: String = "",
    /** The conversation this belongs to. Empty on a server that does not thread. */
    val threadId: String = "",
    /** How many messages are in that conversation, counting this one. */
    val threadSize: Int = 1,
    /** The RFC Message-ID, used to join a Sent row to local tracking history. */
    val messageId: String = "",
    /** The whole message in bytes, as the server counts it. Zero where it did not say. */
    val size: Long = 0L,
    /** The List-Id identifier, see [listIdOf]. Empty for mail that did not come from a list. */
    val listId: String = "",
    /**
     * List-Unsubscribe, Precedence and Auto-Submitted from the list fetch.
     *
     * Kept on the row so the focused inbox can sort a newsletter without opening it.
     * Empty when that header was not on the message.
     */
    val listUnsubscribe: String = "",
    val precedence: String = "",
    val autoSubmitted: String = "",
    /**
     * Every address in To, Cc and Bcc, lowercased.
     *
     * Kept so the local copy can say who a message went to, which is what lets a person's
     * history (PersonHistory.kt) be answered from this computer when the server cannot be.
     * Bcc is in here because a person who was only blind-copied otherwise disappears from
     * that history. Empty where a backend did not say, which is not the same as nobody.
     */
    val recipients: List<String> = emptyList(),
)

/**
 * An address this account is allowed to send as, and the sign-off that goes with it.
 *
 * The signature lives on the identity, on the server, rather than in a file beside this
 * app. That is where JMAP puts it and where Bulwark reads it, so one written in either
 * turns up in the other with nothing to sync and nothing to import.
 */
data class Identity(
    val id: String,
    val name: String,
    val email: String,
    val textSignature: String = "",
    val htmlSignature: String = "",
)

@Serializable
data class Attachment(
    val blobId: String,
    val name: String,
    val type: String,
    val size: Long,
    /** The Content-ID an `<img src="cid:...">` in the body points at, when there is one. */
    val cid: String? = null,
    /** Part of the message as written, rather than a file sent along with it. */
    val inline: Boolean = false,
)

/**
 * Only ever one of these is drawn, and html wins when both are present. The two header
 * lists come along because a reply needs them: without them the answer starts a new
 * conversation in every client that threads.
 */
@Serializable
data class Body(
    val html: String?,
    val text: String?,
    val messageId: List<String> = emptyList(),
    val references: List<String> = emptyList(),
    /** In-Reply-To, when this message is itself a reply. Bare ids, same as [messageId]. */
    val inReplyTo: List<String> = emptyList(),
    /** Everyone the message was addressed to, which is what Reply all needs. */
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    /**
     * Where the sender asked to be answered, when that is not where it came from.
     *
     * Ignoring this sends the reply to the wrong place, and the sender is the one who finds
     * out. A mailing list sets it to the list. A ticketing system sets it to the address
     * that files the answer against the ticket. A no-reply sender sets it to the one address
     * that is read. In every one of those the From address is not the answer.
     */
    val replyTo: List<String> = emptyList(),
    /** The raw List-Unsubscribe header, when the sender offered a way off the list. */
    val listUnsubscribe: String? = null,
    val listUnsubscribePost: String? = null,
    /** Every Authentication-Results header, ours first, as the server stacked them. */
    val authenticationResults: List<String> = emptyList(),
    val spamStatus: String? = null,
    /** Raw Disposition-Notification-To, if the sender asked to be told it was opened. */
    val receiptTo: String? = null,
    /** The whole message in bytes, as the server counts it. Zero when it did not say. */
    val size: Long = 0L,
    /** The Date header, which is when the sender says it was sent. */
    val sentAt: String? = null,
    /** Every Received line, newest first. Only the topmost is ever trusted. */
    val received: List<String> = emptyList(),
    /**
     * The topmost copy of each of [VERDICT_HEADERS] the message carries, by header name:
     * the spam filter's findings and any virus scanner's result.
     */
    val serverVerdicts: Map<String, String> = emptyMap(),
    /**
     * Delivered-To, when the server wrote one. The mailbox the message was
     * handed to, which a mailing list does not put on To.
     */
    val deliveredTo: List<String> = emptyList(),
    /**
     * X-Original-To, the same fact under the name some other servers use.
     * Read only when [deliveredTo] did not name one of our addresses.
     */
    val originalTo: List<String> = emptyList(),
    /**
     * Who the message being answered was addressed to, stored on a draft so opening
     * it again can still warn when From is a different address.
     *
     * These are not the draft's own To and Cc. A draft saved before they were kept
     * has them empty, and the warning stays off rather than guessing.
     */
    val answeredTo: List<String> = emptyList(),
    val answeredCc: List<String> = emptyList(),
    val answeredDeliveredTo: List<String> = emptyList(),
    val answeredOriginalTo: List<String> = emptyList(),
)

/**
 * The Email/query filter for one folder under [filters].
 *
 * Null means the conditions match nothing, and the caller must not send the query: an
 * empty OR is not a filter the protocol accepts, and leaving the condition off would
 * match the whole folder instead.
 *
 * Unread, starred and attachment are properties of one FilterCondition, and several
 * properties in one object are already an AND (RFC 8621 4.4.1). That covers those three
 * with nothing extra.
 *
 * Known sender and tagged are not one property. `from` matches a single string, and
 * `hasKeyword` matches a single keyword. Each is asked as an OR of the values we
 * actually hold: the addresses in this account's book, and the user keywords already in
 * the local copy. A FilterOperator is the protocol's own OR, and it is worth building.
 * The alternative is filtering the page already in hand, which would mean "among the
 * last hundred" rather than "in this folder", the same mistake the unread toggle was
 * written to avoid. Falling all the way back to the local copy would also drop
 * attachment, which only this protocol can answer, so a message with a file and a tag
 * would depend on which toggle was asked first.
 *
 * Tagged cannot be said as "any keyword that is not a protocol one". The OR is the
 * keywords [tagsOf] would show, which is the same set the sidebar lists. A label that
 * has never appeared on a fetched message is not in that set yet.
 *
 * `from` is a case-insensitive substring, because that is what the RFC defines, not an
 * exact address. The book is the list, and the book is capped.
 */
internal fun emailQueryFilter(
    mailboxId: String,
    filters: QuickFilters,
    knownSenders: Collection<String> = emptyList(),
    userKeywords: Collection<String> = emptyList(),
): JsonObject? {
    val addresses = knownSenders.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    val tags = userKeywords.filter { tagsOf(listOf(it)).isNotEmpty() }.distinct()
    if (filters.knownSender && addresses.isEmpty()) return null
    if (filters.tagged && tags.isEmpty()) return null
    val narrow = buildJsonObject {
        put("inMailbox", mailboxId)
        if (filters.unread) put("notKeyword", "\$seen")
        if (filters.starred) put("hasKeyword", "\$flagged")
        if (filters.attachment) put("hasAttachment", true)
    }
    val extra = ArrayList<JsonObject>(2)
    if (filters.knownSender) {
        extra += buildJsonObject {
            put("operator", "OR")
            putJsonArray("conditions") {
                addresses.forEach { add(buildJsonObject { put("from", it) }) }
            }
        }
    }
    if (filters.tagged) {
        extra += buildJsonObject {
            put("operator", "OR")
            putJsonArray("conditions") {
                tags.forEach { add(buildJsonObject { put("hasKeyword", it) }) }
            }
        }
    }
    if (extra.isEmpty()) return narrow
    return buildJsonObject {
        put("operator", "AND")
        putJsonArray("conditions") {
            add(narrow)
            extra.forEach { add(it) }
        }
    }
}

internal class Jmap private constructor(
    /** Replaced only by [usePassword], when the password this session signs with changes under it. */
    @Volatile private var credential: String,
    private val apiUrl: String,
    val accountId: String,
    private val downloadUrl: String,
    private val uploadUrl: String,
    /** Every capability URI this server declared, for asking before calling. */
    private val capabilities: Set<String> = emptySet(),
    /** Where the server takes a WebSocket for push. Blank when it does not offer one. */
    private val pushUrl: String,
    /** What the server will accept in one upload, in bytes. Zero when it did not say. */
    private val maxUpload: Long,
    /**
     * The account Stalwart's management objects answer for, which is this login's own.
     * Null on any server that is not Stalwart, and that is the test for it: Stalwart names
     * `urn:stalwart:jmap` under `primaryAccounts` and nothing else does.
     */
    val managementAccountId: String? = null,
    /** Seconds of delay the server will hold a submission for. Zero when it will not. */
    override val maxDelayedSend: Long = 0L,
    /** What the server's submission capability says it will take on an envelope. */
    override val submissionExtensions: Set<String> = emptySet(),
    /** Every account the session listed, the login's own included. Shared mailboxes come from these (SharedMailboxes.kt). */
    val sessionAccounts: List<SessionAccount> = emptyList(),
) : MailBackend {
    override val maxSizeUpload: Long get() = maxUpload
    companion object {
        /**
         * A client for an API address already known, skipping discovery and the https rule.
         *
         * Only for the tests and benchmarks that run against a fake server on this machine
         * (see `FakeJmapServer` in the tests). Nothing in the app calls it: a real account
         * always goes through [connect], which refuses plain http.
         */
        internal fun forLocalServer(apiUrl: String, downloadUrl: String, accountId: String = "a"): Jmap = Jmap(
            credential = "Basic " + Base64.getEncoder().encodeToString("test:test".toByteArray()),
            apiUrl = apiUrl,
            accountId = accountId,
            downloadUrl = downloadUrl,
            uploadUrl = "",
            capabilities = setOf(CORE, MAIL, SUBMISSION, CONTACTS),
            pushUrl = "",
            maxUpload = 0,
        )

        fun connect(server: String, user: String, password: String): Jmap = try {
            session(server, user, password)
        } catch (e: JmapError) {
            throw e
        } catch (e: Exception) {
            // Everything below this line is a network fault, and the sign-in screen is the
            // first thing anyone sees. A Java class name there is not an error message.
            throw JmapError(plainNetworkError(e, server))
        }

        private fun session(server: String, user: String, password: String): Jmap {
            val credential = "Basic " + Base64.getEncoder()
                .encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
            var response = get(sessionUrl(server), credential)
            var hops = 0
            while (response.statusCode() in 300..399) {
                if (hops++ >= 3) throw JmapError("The server kept redirecting and never sent a session.")
                val location = response.headers().firstValue("location").orElse("")
                if (location.isBlank()) throw JmapError("The server redirected without saying where to.")
                response = get(response.uri().resolve(location), credential)
            }
            if (response.statusCode() == 401) throw JmapError("The server did not accept that email address and password.")
            if (response.statusCode() != 200) throw JmapError("The server answered HTTP ${response.statusCode()} instead of a session.")

            val session = json.parseToJsonElement(response.body()).jsonObject
            val account = session["primaryAccounts"]?.jsonObject?.get(MAIL)?.str()
                ?: throw JmapError("This login has no mail account on that server.")
            // Blobs are fetched from this template later. It has to be kept from the
            // session; nothing else in the protocol names the download endpoint.
            val downloadUrl = (session["downloadUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val uploadUrl = (session["uploadUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val maxUpload = session["capabilities"]?.jsonObject?.get(CORE)?.jsonObject
                ?.get("maxSizeUpload")?.jsonPrimitive?.longOrNull ?: 0L
            val maxDelayedSend = maxDelayedSendOf(session)
            val pushUrl = (session["capabilities"]?.jsonObject?.get(WEBSOCKET)?.jsonObject
                ?.get("url") as? JsonPrimitive)?.contentOrNull.orEmpty()
            // Kept so a feature can ask whether this server has it rather than calling and
            // reading the refusal. A server without Sieve should not be offered filters.
            val capabilities = session["capabilities"]?.jsonObject?.keys.orEmpty().toSet()
            val management = (session["primaryAccounts"]?.jsonObject?.get(STALWART_CAPABILITY) as? JsonPrimitive)
                ?.contentOrNull
            return Jmap(
                credential = credential,
                apiUrl = session["apiUrl"].require("apiUrl"),
                accountId = account,
                downloadUrl = downloadUrl,
                uploadUrl = uploadUrl,
                pushUrl = pushUrl,
                maxUpload = maxUpload,
                maxDelayedSend = maxDelayedSend,
                capabilities = capabilities,
                managementAccountId = management,
                // RFC 8621 puts the list on the account, not the session; the session's own
                // entry for submission is an empty object. The session is read as well in
                // case a server puts it there instead.
                submissionExtensions = submissionExtensionsIn(
                    ((session["accounts"] as? JsonObject)?.get(account) as? JsonObject)
                        ?.get("accountCapabilities")?.let { it as? JsonObject }?.get(SUBMISSION),
                ).ifEmpty { submissionExtensionsIn((session["capabilities"] as? JsonObject)?.get(SUBMISSION)) },
                sessionAccounts = sessionAccountsIn(session),
            )
        }

        /** Accepts a bare host, a base URL, a /jmap/ URL, or the well-known URL itself. */
        internal fun sessionUrl(server: String): URI {
            var s = server.trim().removeSuffix("/")
            // Basic auth sends the password on every request. Over plain http that is the
            // password in the clear to anyone on the path, so it is refused outright rather
            // than warned about: there is no version of this that is worth allowing.
            if (s.startsWith("http://")) {
                throw JmapError("Rampart will not send a password over an unencrypted connection. Use https.")
            }
            if (!s.startsWith("https://")) s = "https://$s"
            return URI.create(
                when {
                    s.endsWith("/jmap/session") || s.endsWith("/.well-known/jmap") -> s
                    s.endsWith("/jmap") -> s.removeSuffix("/jmap") + "/.well-known/jmap"
                    else -> "$s/.well-known/jmap"
                }
            )
        }

        private fun get(url: URI, credential: String): HttpResponse<String> = http.send(
            HttpRequest.newBuilder(url)
                .header("Authorization", credential)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    override fun mailboxes(): List<Mailbox> = mailboxesIn(call(mailboxCall())[0])

    private fun mailboxCall() = invoke("Mailbox/get", "m") { put("ids", JsonNull) }

    private fun mailboxesIn(response: JsonArray): List<Mailbox> =
        response.list().map { mailboxFrom(it.jsonObject) }
            .sortedWith(compareBy({ if (it.role == "inbox") 0 else 1 }, { it.name.lowercase() }))

    /**
     * A string that changes whenever anything about this account's mail changes.
     *
     * Asking for zero messages still returns the type's state, so this is the cheapest
     * question the protocol has: a few hundred bytes, against the several hundred kilobytes
     * that re-reading the inbox costs. A check every minute is only reasonable because of
     * it, and the inbox is read only on the checks where the answer has moved.
     */
    override fun mailState(): String? =
        call(invoke("Email/get", "s") { putJsonArray("ids") {} })[0][1].jsonObject["state"]?.str()

    /**
     * One row per conversation, newest first.
     *
     * Three calls in one round trip, chained by back reference so no ids come through us:
     * the query collapses each thread to its newest message, the get fills those in, and
     * Thread/get counts what is behind each one. Without that third call the list would
     * quietly hide the rest of a conversation with nothing on screen to say so.
     */
    /**
     * A page of a folder, newest first.
     *
     * [from] is how many to skip, which is what makes a folder with thirty thousand
     * messages in it readable: the list asks for the next hundred when it gets near the
     * bottom rather than trying to hold all of them.
     */
    override fun emails(
        mailboxId: String,
        limit: Int,
        from: Int,
        unreadOnly: Boolean,
        filters: QuickFilters,
        knownSenders: Collection<String>,
        userKeywords: Collection<String>,
    ): List<Summary> {
        // Null is "these conditions match nothing", not "ask for the whole folder".
        // An empty book or a tag list with nothing but protocol keywords must not fall
        // through into an unfiltered query.
        val filter = emailQueryFilter(mailboxId, filters, knownSenders, userKeywords) ?: return emptyList()
        return pageIn(call(*pageCalls(filter, limit, from)))
    }

    /**
     * The folder counts and a page, in one request, for the poll that has just seen the
     * account change. Asked one after the other these were two round trips on every
     * arrival, for two answers the server had ready at the same moment.
     */
    override fun pageAndFolders(mailboxId: String, limit: Int): Pair<List<Summary>, List<Mailbox>> {
        val filter = emailQueryFilter(mailboxId, QuickFilters(), emptyList(), emptyList())
            ?: return emptyList<Summary>() to mailboxes()
        val responses = call(*pageCalls(filter, limit, 0), mailboxCall())
        return pageIn(responses) to mailboxesIn(responses[3])
    }

    /** The three calls a page is, query, get and thread counts, chained by back reference. */
    private fun pageCalls(filter: JsonObject, limit: Int, from: Int): Array<JsonArray> = arrayOf(
            invoke("Email/query", "q") {
                // Filtered on the server, not here. Hiding rows out of the page we happen
                // to hold would mean "among the last hundred", which is a different and
                // much less useful thing than "in this folder".
                put("filter", filter)
                put("collapseThreads", true)
                putJsonArray("sort") {
                    add(buildJsonObject { put("property", "receivedAt"); put("isAscending", false) })
                }
                put("limit", limit)
                if (from > 0) put("position", from)
            },
            invoke("Email/get", "g") {
                // A back reference, so the ids never make the round trip through us.
                putJsonObject("#ids") { put("resultOf", "q"); put("name", "Email/query"); put("path", "/ids") }
                putJsonArray("properties") {
                    emailGetProperties.forEach { add(it) }
                }
            },
            invoke("Thread/get", "t") {
                putJsonObject("#ids") {
                    put("resultOf", "g"); put("name", "Email/get"); put("path", "/list/*/threadId")
                }
            },
        )

    private fun pageIn(responses: List<JsonArray>): List<Summary> {
        val sizes = responses[2].list().associate {
            it.jsonObject["id"].require("id") to ((it.jsonObject["emailIds"] as? JsonArray)?.size ?: 1)
        }
        return responses[1].list().map { element ->
            val summary = jsonToSummary(element.jsonObject)
            summary.copy(threadSize = sizes[summary.threadId] ?: 1)
        }
    }

    /**
     * Every message in a conversation, oldest first.
     *
     * Thread/get already returns the ids in date order, which was checked against the
     * server rather than taken from the specification, so they are not sorted again here.
     */
    override fun thread(threadId: String): List<Summary> {
        if (threadId.isBlank()) return emptyList()
        // One request: the thread's ids go straight into the Email/get by back reference.
        // Asked separately they were two round trips in a row on every message opened.
        val responses = call(
            invoke("Thread/get", "t") { putJsonArray("ids") { add(threadId) } },
            invoke("Email/get", "g") {
                putJsonObject("#ids") { put("resultOf", "t"); put("name", "Thread/get"); put("path", "/list/*/emailIds") }
                putJsonArray("properties") { emailGetProperties.forEach { add(it) } }
            },
        )
        val ids = responses[0].list().firstOrNull()?.jsonObject?.get("emailIds")?.let { it as? JsonArray }
            ?.mapNotNull { it.str() }.orEmpty()
        if (ids.size <= 1) return emptyList()
        val found = responses[1].list().associate { it.jsonObject["id"].require("id") to jsonToSummary(it.jsonObject) }
        // Email/get may answer in any order; the thread's own order is the one that matters.
        return ids.mapNotNull { found[it] }
    }

    /**
     * Every message of several conversations, in one round trip, for the rows that stand
     * for them. See [ThreadGist].
     *
     * Thread/get names the messages and Email/get fills them in by back reference, so no ids
     * come through us. A small set of properties, since this is asked for a page of rows at
     * a time: enough to say who wrote last and whether anything is unread, and which folder
     * each message is in so a deleted reply is not taken for the latest one.
     */
    override fun threadMembers(threadIds: Collection<String>): Map<String, ThreadGist> {
        val ids = threadIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val responses = call(
            invoke("Thread/get", "t") { putJsonArray("ids") { ids.forEach { add(it) } } },
            invoke("Email/get", "g") {
                putJsonObject("#ids") {
                    put("resultOf", "t"); put("name", "Thread/get"); put("path", "/list/*/emailIds")
                }
                putJsonArray("properties") {
                    listOf("id", "threadId", "from", "subject", "receivedAt", "preview", "keywords", "mailboxIds")
                        .forEach { add(it) }
                }
            },
        )
        val found = responses[1].list().associate { element ->
            val o = element.jsonObject
            o["id"].require("id") to (jsonToSummary(o) to (o["mailboxIds"] as? JsonObject)?.keys.orEmpty())
        }
        return responses[0].list().associate { element ->
            val o = element.jsonObject
            val members = (o["emailIds"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty().mapNotNull { found[it] }
            o["id"].require("id") to ThreadGist(members.map { it.first }, members.associate { it.first.id to it.second })
        }
    }

    override fun body(id: String): Body = open(id).body

    override fun attachments(emailId: String): List<Attachment> = open(emailId).attachments

    override fun contentStamp(id: String): ContentStamp? = stateAndStamp(id).second

    /**
     * Both from one Email/get, because every Email/get answer carries the account's state.
     * The kept-copy check asked for them separately, which was two round trips whenever the
     * account had moved.
     */
    override fun stateAndStamp(id: String): Pair<String?, ContentStamp?> {
        val response = call(
            invoke("Email/get", "stamp") {
                putJsonArray("ids") { add(id) }
                putJsonArray("properties") { add("blobId"); add("size") }
            },
        )[0]
        val state = response[1].jsonObject["state"]?.str()
        return state to stampIn(response.list().firstOrNull()?.jsonObject)
    }

    private fun stampIn(email: JsonObject?): ContentStamp? {
        email ?: return null
        val blobId = email["blobId"]?.str()?.ifBlank { null } ?: return null
        val size = email["size"]?.jsonPrimitive?.longOrNull ?: return null
        return ContentStamp(blobId, size)
    }

    /**
     * Body, files and a small invitation, from one Email/get.
     *
     * A part the server cut at [maxBodyValueBytes] is fetched whole, from its own blob
     * when it named one and by asking again with a higher cap when it did not. The first
     * answer is what almost every message is. The second exists so a long one is not
     * shown with the end missing.
     */
    override fun open(id: String): OpenedMail {
        val (first, state) = emailRecord(id, 1024 * 1024)
        val parsed = openedFrom(first)
        // The state rides on the same answer, so the kept copy is stamped without asking.
        if (!parsed.cutLeft) return parsed.mail.copy(state = state)
        val (again, laterState) = emailRecord(id, 32 * 1024 * 1024)
        return openedFrom(again).mail.copy(state = laterState)
    }

    private fun emailRecord(id: String, maxBytes: Int): Pair<JsonObject, String?> = call(
        invoke("Email/get", "b") {
            putJsonArray("ids") { add(id) }
            putJsonArray("properties") {
                add("htmlBody"); add("textBody"); add("bodyValues")
                add("messageId"); add("references"); add("header:In-Reply-To:asMessageIds")
                add("to"); add("cc"); add("replyTo")
                // The mailbox a list or an alias actually handed the message to, when
                // To names the list. A header nobody asks for is not sent.
                add("header:Delivered-To:asText:all")
                add("header:X-Original-To:asText:all")
                // Who the answered message was addressed to, on a draft. Absent on
                // everything else, and a header nobody asks for is not sent.
                answeredGetProperties().forEach { add(it) }
                // Asked for by name. These are not JMAP properties, they are ordinary
                // headers, and a header nobody asks for is not sent.
                add("header:List-Unsubscribe:asText")
                add("header:List-Unsubscribe-Post:asText")
                add("header:Authentication-Results:asText:all")
                add("header:X-Spam-Status:asText")
                // For the details panel. size and sentAt are JMAP's own; Received is
                // the only place the hop that handed it over is written down, and all
                // of them are asked for because a header nobody asks for is not sent.
                add("size"); add("sentAt")
                add("header:Received:asText:all")
                add("header:" + MDN_HEADER + ":asText")
                // Every copy rather than JMAP's default of the last one, because the last
                // one is the copy furthest from our server and the only one a sender can
                // have written themselves.
                VERDICT_HEADERS.forEach { add("header:$it:asText:all") }
                add("attachments")
                add("blobId")
            }
            put("fetchHTMLBodyValues", true)
            put("fetchTextBodyValues", true)
            put("maxBodyValueBytes", maxBytes)
        },
    )[0].let { response ->
        val email = response.list().firstOrNull()?.jsonObject
            ?: throw JmapError("That message is not on the server any more.")
        email to response[1].jsonObject["state"]?.str()
    }

    private fun openedFrom(email: JsonObject): ParsedOpen {
        val values = email["bodyValues"]?.jsonObject ?: JsonObject(emptyMap())
        var cutLeft = false
        fun join(part: String, wantedType: String? = null): String? =
            resolveBody(email[part] as? JsonArray, values, wantedType) { blobId, type, size ->
                val bytes = blob(Attachment(blobId, "part", type, size), limit = 32L * 1024 * 1024)
                if (bytes == null) cutLeft = true
                bytes
            }
        /*
         * `as? JsonArray` rather than `.jsonArray`, and the difference is not stylistic. A
         * header a message does not have comes back as JSON null rather than being left
         * out, and JsonNull is a value, so the null-safe call does not skip it and
         * `.jsonArray` throws. A message with no Cc, or no References, could not be opened
         * at all: "Element class JsonNull is not a JsonArray", on screen, instead of the
         * message.
         */
        fun ids(field: String) = stringsIn(email[field])
        fun addresses(field: String) = addressesIn(email[field])
        fun headerAddresses(field: String) =
            stringsIn(email[field]).flatMap { header -> parseAddressList(header).map { it.email } }
        val body = Body(
            html = join("htmlBody", wantedType = "text/html"),
            text = join("textBody"),
            messageId = ids("messageId"),
            references = ids("references"),
            inReplyTo = ids("header:In-Reply-To:asMessageIds"),
            to = addresses("to"),
            replyTo = addresses("replyTo"),
            cc = addresses("cc"),
            listUnsubscribe = email["header:List-Unsubscribe:asText"]?.str(),
            listUnsubscribePost = email["header:List-Unsubscribe-Post:asText"]?.str(),
            authenticationResults = stringsIn(email["header:Authentication-Results:asText:all"]),
            spamStatus = email["header:X-Spam-Status:asText"]?.str(),
            receiptTo = email["header:" + MDN_HEADER + ":asText"]?.str(),
            size = email["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            sentAt = email["sentAt"]?.str(),
            received = stringsIn(email["header:Received:asText:all"]),
            serverVerdicts = VERDICT_HEADERS.mapNotNull { name ->
                stringsIn(email["header:$name:asText:all"]).firstOrNull()?.let { name to it }
            }.toMap(),
            deliveredTo = headerAddresses("header:Delivered-To:asText:all"),
            originalTo = headerAddresses("header:X-Original-To:asText:all"),
            answeredTo = answeredAddresses(stringsIn(email["header:$ANSWERED_TO:asText:all"])),
            answeredCc = answeredAddresses(stringsIn(email["header:$ANSWERED_CC:asText:all"])),
            answeredDeliveredTo = answeredAddresses(stringsIn(email["header:$ANSWERED_DELIVERED_TO:asText:all"])),
            answeredOriginalTo = answeredAddresses(stringsIn(email["header:$ANSWERED_ORIGINAL_TO:asText:all"])),
        )
        val attachments = attachmentsIn(email)
        val calendar = attachments.firstOrNull {
            it.type.substringBefore(';').equals("text/calendar", ignoreCase = true) &&
                it.size in 1..CHEAP_CALENDAR
        }?.let { part ->
            blob(part, CHEAP_CALENDAR)?.let { String(it, Charsets.UTF_8) }
        }
        // A cut part with no blob of its own cannot be repaired from this answer, so the
        // caller asks again with a higher cap. One that named a blob is repaired above,
        // and cutLeft is set only when that download came back empty.
        val noBlob = listOf("htmlBody" to "text/html", "textBody" to null).any { (name, wanted) ->
            val parts = email[name] as? JsonArray ?: return@any false
            parts.any { element ->
                val part = element.jsonObject
                if (wanted != null && part["type"]?.str() != wanted) return@any false
                val id = part["partId"]?.str() ?: return@any false
                val cut = values[id]?.jsonObject?.get("isTruncated")?.jsonPrimitive?.booleanOrNull == true
                cut && part["blobId"]?.str().isNullOrBlank()
            }
        }
        return ParsedOpen(
            OpenedMail(
                body = body,
                attachments = attachments,
                emailBlobId = email["blobId"]?.str()?.ifBlank { null },
                calendar = calendar,
            ),
            cutLeft = cutLeft || noBlob,
        )
    }

    private data class ParsedOpen(val mail: OpenedMail, val cutLeft: Boolean)

    /**
     * A part's bytes, held in memory.
     *
     * Only for images drawn in the body, which is why it is capped rather than streamed
     * like [download]. A message that claims a 200MB inline image must not be able to take
     * the app down with it.
     */
    override fun blob(attachment: Attachment, limit: Long): ByteArray? {
        if (downloadUrl.isBlank() || attachment.size > limit) return null
        val url = downloadUrl
            .replace("{accountId}", pct(accountId))
            .replace("{blobId}", pct(attachment.blobId))
            .replace("{type}", pct(attachment.type.ifBlank { "application/octet-stream" }))
            .replace("{name}", pct(attachment.name.ifBlank { "attachment" }))
        val response = http.send(
            HttpRequest.newBuilder(runCatching { URI.create(url) }.getOrNull() ?: return null)
                .header("Authorization", credential)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (response.statusCode() != 200) return null
        val bytes = response.body()
        return if (bytes.size > limit) null else bytes
    }

    /**
     * The message exactly as it arrived, headers and all.
     *
     * Every Email has a blob of its own whole self, which is what "view source" and saving
     * a .eml both need. Capped, because this is going into a window rather than onto disk.
     */
    override fun raw(emailId: String, limit: Long): String? {
        val email = call(
            invoke("Email/get", "r") {
                putJsonArray("ids") { add(emailId) }
                putJsonArray("properties") { add("blobId"); add("size") }
            },
        )[0].list().firstOrNull()?.jsonObject ?: return null
        val blobId = email["blobId"]?.str()?.ifBlank { null } ?: return null
        val size = email["size"]?.jsonPrimitive?.longOrNull ?: 0L
        val bytes = blob(
            Attachment(blobId = blobId, name = "message.eml", type = "message/rfc822", size = size),
            limit,
        ) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Calls [onChange] when the server says something in this account moved.
     *
     * The poll below this is what actually re-reads the mailbox, and it stays: a poll works
     * against every server and will work against IMAP later, while push is an extra the
     * server may not offer. This only shortens the wait from the next round to a second or
     * two. What arrives is a StateChange saying which types moved, deliberately ignored:
     * knowing that something changed is the whole signal, and the poll already works out
     * what it was in one small request.
     *
     * [onGone] says the socket is finished, whether it closed cleanly or broke. Whoever
     * asked for the watch is the one that can open another, so reconnecting is left to them.
     *
     * Returns null when the server named no WebSocket, or when the connection did not open.
     * Closing the returned handle closes the socket.
     */
    override val hasPush: Boolean get() = pushUrl.isNotBlank()

    override fun watch(onChange: () -> Unit, onGone: () -> Unit): AutoCloseable? {
        if (pushUrl.isBlank()) return null
        val socket = runCatching {
            http.newWebSocketBuilder()
                .header("Authorization", credential)
                .subprotocols("jmap")
                .buildAsync(URI.create(pushUrl), PushListener(onChange, onGone))
                .get(20, TimeUnit.SECONDS)
        }.getOrNull() ?: return null
        // Without this the socket is open and silent: a server sends nothing until a client
        // has said which types it wants to hear about.
        runCatching { socket.sendText(PUSH_ENABLE, true) }
        return AutoCloseable { runCatching { socket.abort() } }
    }

    /** The out of office reply as the server has it, or null when it does not do them. */
    override fun vacation(): Vacation? = runCatching {
        val list = call(
            invoke("VacationResponse/get", "v") { put("ids", JsonNull) },
            also = VACATION,
        )[0].list()
        list.firstOrNull()?.jsonObject?.let(::vacationOf)
    }.getOrNull()

    override fun setVacation(value: Vacation) {
        call(
            invoke("VacationResponse/set", "v") {
                putJsonObject("update") { put("singleton", vacationPatch(value)) }
            },
            also = VACATION,
        )
    }

    private fun attachmentsIn(email: JsonObject): List<Attachment> {
        val parts = email["attachments"] as? JsonArray ?: return emptyList()
        return parts.mapNotNull { el ->
            val o = el.jsonObject
            // A part with no blob cannot be fetched; listing it would offer a save that
            // always fails.
            val blobId = (o["blobId"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
                ?: return@mapNotNull null
            val type = (o["type"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
                ?: "application/octet-stream"
            val given = (o["name"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
            val name = given ?: nameFromType(type)
            val size = (o["size"] as? JsonPrimitive)?.longOrNull ?: 0L
            val cid = (o["cid"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
            Attachment(
                blobId = blobId,
                name = name,
                type = type,
                size = size,
                cid = cid,
                // A part is part of the body when it says so, or when the body points at
                // it by Content-ID. Some senders set the cid and leave the disposition off.
                inline = (o["disposition"] as? JsonPrimitive)?.contentOrNull == "inline" || cid != null,
            )
        }
    }

    /**
     * Streams the blob into [into]. The body is never held in memory: a mail
     * attachment can be hundreds of megabytes.
     */
    override fun download(attachment: Attachment, into: Path): Path {
        if (downloadUrl.isBlank()) {
            throw JmapError("This server did not say how to download attachments.")
        }
        val url = downloadUrl
            .replace("{accountId}", pct(accountId))
            .replace("{blobId}", pct(attachment.blobId))
            .replace("{type}", pct(attachment.type.ifBlank { "application/octet-stream" }))
            .replace("{name}", pct(attachment.name.ifBlank { "attachment" }))
        val dest = uniqueIn(into, attachment.name)
        val uri = try {
            URI.create(url)
        } catch (_: IllegalArgumentException) {
            throw JmapError("The server gave a download address Rampart could not use.")
        }
        val response = http.send(
            HttpRequest.newBuilder(uri)
                .header("Authorization", credential)
                // A large file on a slow link will not finish in the JSON round-trip timeout.
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofFile(dest),
        )
        if (response.statusCode() != 200) {
            Files.deleteIfExists(dest)
            throw JmapError("The server answered HTTP ${response.statusCode()} instead of the file.")
        }
        return dest
    }

    override fun identities(): List<Identity> = identitiesIn(call(identityCall())[0])

    private fun identityCall() = invoke("Identity/get", "i") { put("ids", JsonNull) }

    /**
     * The folders and the identities in one request. An account used to cost two round
     * trips before its folder list could be drawn, and every account waited for the one
     * before it.
     */
    override fun startup(): Startup {
        val responses = runCatching { call(mailboxCall(), identityCall()) }.getOrNull()
            // A server that refuses Identity/get refuses the whole request, and the
            // folders matter more than sending, so they are asked for again alone.
            ?: return Startup(mailboxes(), null)
        return Startup(mailboxesIn(responses[0]), runCatching { identitiesIn(responses[1]) }.getOrNull())
    }

    private fun identitiesIn(response: JsonArray): List<Identity> =
        response.list().map {
            val o = it.jsonObject
            Identity(
                id = o["id"].require("id"),
                name = o["name"]?.str()?.ifBlank { null } ?: o["email"]?.str().orEmpty(),
                email = o["email"].require("email"),
                textSignature = o["textSignature"]?.str().orEmpty(),
                htmlSignature = o["htmlSignature"]?.str().orEmpty(),
            )
        }

    /**
     * Stores the sign-off on the server, against the identity it belongs to.
     *
     * Checked against the live server, on a spare identity that was put back afterwards:
     * the HTML comes back byte for byte, including a data URI image.
     */
    override fun setSignature(identityId: String, text: String, html: String) {
        val response = call(
            invoke("Identity/set", "u") {
                putJsonObject("update") {
                    putJsonObject(identityId) {
                        put("textSignature", text)
                        put("htmlSignature", html)
                    }
                }
            },
        )[0][1].jsonObject
        if (response["updated"]?.jsonObject?.containsKey(identityId) != true) {
            // A server that refuses the HTML but takes the text is almost always refusing
            // it for being long, and "invalidProperties: Field could not be set" says
            // nothing a person can act on. Stalwart 0.16 stops at 2047 characters, which is
            // roughly a thousandth of the picture its own client will let you insert.
            val why = response["notUpdated"]?.jsonObject?.values?.firstOrNull()?.jsonObject
            val fields = why?.get("properties")?.jsonArray?.map { it.str() }.orEmpty()
            if ("htmlSignature" in fields && html.length > SIGNATURE_LIMIT) {
                throw JmapError(
                    "The server would not store this signature: it is ${html.length} characters " +
                        "and most cap it near $SIGNATURE_LIMIT. A picture carried inside the " +
                        "signature is what pushes it over, every time. Link to one on the web " +
                        "instead of adding it here.",
                )
            }
            throw JmapError(refusal(response, "notUpdated", "The server would not store the signature"))
        }
    }

    /**
     * Writes the message to Drafts and hands it to the server to send, in one request.
     *
     * The submission refers to the email by its creation id, so the message is never
     * round tripped through us between being written and being sent, and there is no
     * window in which a draft exists that nothing will ever send. On success the server
     * itself moves it out of Drafts and into Sent and drops the draft keyword, which is
     * why that move cannot be left half done by us losing the connection.
     */
    /**
     * The message itself, as JMAP wants it. Shared by sending and saving, because a draft
     * that differs from what is eventually sent is a bug waiting for the day someone sends
     * one without reopening it.
     */
    private fun JsonObjectBuilder.emailObject(
        draft: Draft,
        identity: Identity,
        draftsMailboxId: String,
        /** A draft on the way out, and a read message for a copy filed straight into Sent. */
        keywords: Map<String, Boolean> = mapOf("\$draft" to true),
    ) {
        putJsonObject("mailboxIds") { put(draftsMailboxId, true) }
        putJsonObject("keywords") { keywords.forEach { (keyword, on) -> put(keyword, on) } }
        putJsonArray("from") {
            add(buildJsonObject { put("name", identity.name); put("email", identity.email) })
        }
        addresses("to", draft.to)
        addresses("cc", draft.cc)
        put("subject", draft.subject)
        // Without both of these a reply arrives as a new conversation in every client that
        // threads, which is most of them.
        draft.inReplyTo?.let {
            putJsonArray("header:In-Reply-To:asMessageIds") { add(it) }
        }
        if (draft.references.isNotEmpty()) {
            putJsonArray("header:References:asMessageIds") { draft.references.forEach { add(it) } }
        }
        // Addressed to the sender, because a receipt that goes anywhere else is what the
        // header is abused for and what makes clients refuse it outright.
        if (draft.receipt) {
            putJsonArray("header:" + MDN_HEADER + ":asAddresses") {
                add(buildJsonObject { put("name", identity.name); put("email", identity.email) })
            }
        }
        // textBody rather than bodyStructure. The server refuses a message that sets both
        // bodyStructure and attachments ("Cannot set both properties on a same request"),
        // and one path that works with and without attachments is better than two that can
        // drift apart. Checked against the live server both ways.
        // Both parts when there is an HTML sign-off, so the message reads as written in a
        // client that shows HTML and still reads as text in one that does not. Checked
        // against the live server, alongside an attachment, because the two together are
        // what the convenience properties are fussy about.
        // Minted by us only when tracking is on, because the Sent copy is then a second
        // object and the two have to agree or a reply threads against nothing.
        draft.messageId?.let { putJsonArray("messageId") { add(it.trim().removePrefix("<").removeSuffix(">")) } }
        val html = htmlPartOf(draft)
        putJsonArray("textBody") { add(buildJsonObject { put("partId", "b"); put("type", "text/plain") }) }
        if (html != null) {
            putJsonArray("htmlBody") { add(buildJsonObject { put("partId", "h"); put("type", "text/html") }) }
        }
        putJsonObject("bodyValues") {
            // The markers come off the text part. Somebody reading in a client with no
            // HTML should see "the price" rather than "**the price**".
            putJsonObject("b") { put("value", markupToPlain(draft.body)) }
            if (html != null) putJsonObject("h") { put("value", html) }
        }
        if (draft.attachments.isNotEmpty()) {
            putJsonArray("attachments") {
                draft.attachments.forEach { file ->
                    add(
                        buildJsonObject {
                            put("blobId", file.blobId)
                            put("type", file.type.ifBlank { "application/octet-stream" })
                            put("name", file.name)
                            // An inline picture is part of the message rather than a file
                            // sent with it, and the cid is what the img in the body points
                            // at. Sent as cid rather than a data: URI because Gmail and
                            // Outlook both refuse to draw a data: URI in a received message.
                            if (file.inline && file.cid != null) {
                                put("cid", file.cid)
                                put("disposition", "inline")
                            } else {
                                put("disposition", "attachment")
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * Puts a file on the server and returns what to attach.
     *
     * Uploading is separate from sending on purpose: the bytes go up while the message is
     * still being written, so pressing Send is not a wait proportional to the attachment.
     * The file is streamed rather than read into memory.
     */
    /** A Sieve script on the server. Only one is active at a time. */
    data class SieveInfo(val id: String, val name: String, val active: Boolean, val blobId: String)

    /** Whether this server takes Sieve at all, so the UI can say so rather than fail. */
    override fun hasSieve(): Boolean = capabilities.any { it.endsWith(":sieve") }

    override fun sieveScripts(): List<SieveInfo> = call(
        invoke("SieveScript/get", "s") { put("ids", JsonNull) },
        also = SIEVE,
    )[0].list().map {
        val o = it.jsonObject
        SieveInfo(
            id = o["id"].require("id"),
            name = o["name"]?.str() ?: "(no name)",
            active = (o["isActive"] as? JsonPrimitive)?.content == "true",
            blobId = o["blobId"]?.str().orEmpty(),
        )
    }

    /**
     * Whether this server keeps an address book, so the UI can be absent rather than fail.
     *
     * Stalwart 0.16 advertises `urn:ietf:params:jmap:contacts`, which is the JSContact
     * flavour: AddressBook and ContactCard. The older draft's Contact/get answers
     * unknownMethod and are not a fallback worth having, because no server offers one
     * without the other.
     */
    override fun hasContacts(): Boolean = capabilities.any { it.endsWith(":contacts") }

    /** Whether the session named [uri], for a feature that lives in its own file and asks before calling. */
    internal fun advertises(uri: String): Boolean = uri in capabilities

    /**
     * One round trip under [capability], for a feature that builds its own method calls.
     *
     * The same path every call here takes, so the credential, the refusal handling and the
     * round trip count stay in one place rather than being copied into each new feature.
     */
    internal fun request(capability: String, vararg invocations: JsonArray): List<JsonArray> = try {
        call(*invocations, also = capability)
    } catch (e: JmapError) {
        throw e
    } catch (e: Exception) {
        throw JmapError(plainNetworkError(e, URI.create(apiUrl).host ?: apiUrl))
    }

    /**
     * Whether this server keeps calendars, the same way [hasContacts] asks about contacts.
     *
     * Stalwart 0.16 advertises `urn:ietf:params:jmap:calendars`. Rampart has no calendar view
     * of its own yet, so this exists only to gate the phone setup page: a phone syncs against
     * the server's own CalDAV, and there is nothing to point it at without this capability.
     */
    override fun hasCalendars(): Boolean = capabilities.any { it.endsWith(":calendars") }

    /**
     * RFC 9425 `Quota/get`, asked for only where the session says it exists.
     *
     * **An advertised capability and a configured limit are different things.** This
     * account advertises `urn:ietf:params:jmap:quota` and answers with an empty list,
     * because no quota has been set on it. Checked against the live server rather than
     * assumed from the capability, the same way ContactCard/query was.
     */
    override fun quota(): List<MailQuota> {
        if (capabilities.none { it.endsWith(":quota") }) return emptyList()
        return runCatching {
            call(
                invoke("Quota/get", "q") { put("ids", JsonNull) },
                also = QUOTA,
            )[0].list().map {
                val o = it.jsonObject
                MailQuota(
                    name = o["name"]?.str().orEmpty(),
                    used = o["used"]?.num() ?: 0L,
                    // hardLimit is the one that stops delivery. warnLimit and softLimit are
                    // advisory, and showing a bar against an advisory number would say the
                    // mailbox is full while mail is still arriving.
                    limit = o["hardLimit"]?.num(),
                    resourceType = o["resourceType"]?.str().orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun addressBooks(): List<ContactBook> = booksIn(call(bookCall(), also = CONTACTS)[0])

    private fun bookCall() = invoke("AddressBook/get", "a") { put("ids", JsonNull) }

    private fun cardCall() = invoke("ContactCard/get", "c") { put("ids", JsonNull) }

    /**
     * The books and the cards in one request. The contacts page asked for them one after
     * the other, which was two round trips before the list could be drawn.
     */
    override fun booksAndContacts(): Pair<List<ContactBook>, List<Pair<Contact, JsonObject>>> {
        val responses = call(bookCall(), cardCall(), also = CONTACTS)
        return runCatching { booksIn(responses[0]) }.getOrDefault(emptyList()) to cardsIn(responses[1])
    }

    private fun booksIn(response: JsonArray): List<ContactBook> = response.list().map {
        val o = it.jsonObject
        ContactBook(
            id = o["id"].require("id"),
            name = o["name"]?.str().orEmpty().ifBlank { "Contacts" },
            isDefault = (o["isDefault"] as? JsonPrimitive)?.content == "true",
        )
    }

    /**
     * Every card, with the raw JSON beside it.
     *
     * All of them, not a page and not a search: **ContactCard/query is not implemented in
     * Stalwart 0.16**, in any form. Filtered, unfiltered and by address book all answer
     * `serverUnavailable`, which reads as an outage and is not one. Checked against the
     * live server rather than inferred from the capability being advertised. Searching and
     * sorting therefore happen here, which is the right place for a list this size anyway.
     *
     * The raw object is kept so a save can be built on top of it and not destroy the
     * properties this build does not draw.
     */
    override fun contacts(): List<Pair<Contact, JsonObject>> = cardsIn(call(cardCall(), also = CONTACTS)[0])

    private fun cardsIn(response: JsonArray): List<Pair<Contact, JsonObject>> =
        response.list().map { contactOf(it.jsonObject) to it.jsonObject }

    /** Creates or updates one card, and returns its id. */
    override fun saveContact(contact: Contact, original: JsonObject?): String {
        val card = merged(contact, original)
        val response = call(
            invoke("ContactCard/set", "c") {
                if (contact.id.isBlank()) {
                    putJsonObject("create") { put("new", card) }
                } else {
                    // The whole object rather than a patch. A patch would need every
                    // property this build does not show spelled out as a path to leave
                    // alone, and merged() already carries them.
                    putJsonObject("update") { put(contact.id, card) }
                }
            },
            also = CONTACTS,
        )[0][1].jsonObject
        if (contact.id.isBlank()) {
            return response["created"]?.jsonObject?.get("new")?.jsonObject?.get("id")?.str()
                ?: throw JmapError(refusal(response, "notCreated", "The server would not store the contact"))
        }
        if (response["updated"]?.jsonObject?.containsKey(contact.id) != true) {
            throw JmapError(refusal(response, "notUpdated", "The server would not change the contact"))
        }
        return contact.id
    }

    override fun deleteContact(id: String) {
        val response = call(
            invoke("ContactCard/set", "c") { putJsonArray("destroy") { add(id) } },
            also = CONTACTS,
        )[0][1].jsonObject
        if (response["destroyed"]?.jsonArray?.any { it.str() == id } != true) {
            throw JmapError(refusal(response, "notDestroyed", "The server would not delete the contact"))
        }
    }

    /** The script's text. Empty when the server gave it no blob, which means no script yet. */
    override fun sieveText(script: SieveInfo): String {
        if (script.blobId.isBlank() || downloadUrl.isBlank()) return ""
        val url = downloadUrl
            .replace("{accountId}", pct(accountId))
            .replace("{blobId}", pct(script.blobId))
            .replace("{type}", pct("application/sieve"))
            .replace("{name}", pct(script.name))
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).header("Authorization", credential).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() !in 200..299) return ""
        return response.body()
    }

    /**
     * Checks a script with the server without storing it.
     *
     * RFC 9661 `SieveScript/validate`, which is the CHECKSCRIPT command: the same compiler
     * a save goes through, and none of the storing. A card has to know the server will
     * take the script before a person is asked to save it. Null when it is valid, and a
     * sentence when it is not.
     */
    internal fun validateSieve(text: String): String? {
        val blobId = uploadSieve(text)
        val response = call(
            invoke("SieveScript/validate", "v") { put("blobId", blobId) },
            also = SIEVE,
        )[0][1].jsonObject
        val error = response["error"]
        if (error == null || error is JsonNull) return null
        if (error is JsonPrimitive) {
            val sentence = error.contentOrNull?.trim().orEmpty()
            return if (sentence.isEmpty()) null else "The server rejected this filter: $sentence"
        }
        val problem = error as? JsonObject
        val description = problem?.get("description")?.str()?.trim().orEmpty()
        if (description.isNotEmpty()) return "The server rejected this filter: $description"
        val type = problem?.get("type")?.str()?.trim().orEmpty()
        return if (type.isNotEmpty()) "The server rejected this filter ($type)." else "The server rejected this filter."
    }

    /** The blob id of a script upload. Save and validate both send the text this way. */
    private fun uploadSieve(text: String): String {
        if (uploadUrl.isBlank()) throw JmapError("This server did not say where to upload files.")
        val upload = http.send(
            HttpRequest.newBuilder(URI.create(uploadUrl.replace("{accountId}", pct(accountId))))
                .header("Authorization", credential)
                .header("Content-Type", "application/sieve")
                .POST(HttpRequest.BodyPublishers.ofString(text))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (upload.statusCode() !in 200..299) {
            throw JmapError("The server would not take the filter script (HTTP ${upload.statusCode()}).")
        }
        return json.parseToJsonElement(upload.body()).jsonObject["blobId"].require("blobId")
    }

    /**
     * Writes a script and makes it the active one.
     *
     * The text goes up as a blob first, because that is how JMAP moves anything with a body,
     * and the script then points at it. Activating in the same call rather than a second one
     * means a refused script never becomes the active script.
     */
    override fun saveSieve(name: String, text: String, existing: SieveInfo?) {
        val blobId = uploadSieve(text)

        val response = call(
            invoke("SieveScript/set", "w") {
                if (existing == null) {
                    putJsonObject("create") {
                        putJsonObject("new") {
                            put("name", name)
                            put("blobId", blobId)
                        }
                    }
                    put("onSuccessActivateScript", "#new")
                } else {
                    putJsonObject("update") {
                        putJsonObject(existing.id) { put("blobId", blobId) }
                    }
                    put("onSuccessActivateScript", existing.id)
                }
            },
            also = SIEVE,
        )[0][1].jsonObject
        val field = if (existing == null) "notCreated" else "notUpdated"
        val ok = if (existing == null) {
            response["created"]?.jsonObject?.containsKey("new") == true
        } else {
            response["updated"]?.jsonObject?.containsKey(existing.id) == true
        }
        // The server checks Sieve for us, and its complaint names the line. Ours would not.
        if (!ok) throw JmapError(refusal(response, field, "The server rejected these filters"))
    }

    override fun upload(file: Path): Attachment {
        if (uploadUrl.isBlank()) throw JmapError("This server did not say where to upload files.")
        val size = Files.size(file)
        if (maxUpload in 1 until size) {
            throw JmapError(
                "${file.fileName} is ${humanSize(size)}, and this server accepts at most " +
                    "${humanSize(maxUpload)} in one file.",
            )
        }
        val type = runCatching { Files.probeContentType(file) }.getOrNull().orEmpty()
            .ifBlank { "application/octet-stream" }
        val response = http.send(
            HttpRequest.newBuilder(URI.create(uploadUrl.replace("{accountId}", pct(accountId))))
                .header("Authorization", credential)
                .header("Content-Type", type)
                .timeout(Duration.ofMinutes(10))
                .POST(HttpRequest.BodyPublishers.ofFile(file))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() !in 200..299) {
            throw JmapError("The server would not take ${file.fileName} (HTTP ${response.statusCode()}).")
        }
        val blob = json.parseToJsonElement(response.body()).jsonObject
        return Attachment(
            blobId = blob["blobId"].require("blobId"),
            name = file.fileName.toString(),
            type = blob["type"]?.str()?.ifBlank { null } ?: type,
            size = blob["size"]?.jsonPrimitive?.longOrNull ?: size,
        )
    }

    override fun importMessage(file: Path, mailboxId: String, metadata: ImportedMessage): String {
        val uploaded = upload(file)
        val response = call(
            invoke("Email/import", "import") {
                putJsonObject("emails") {
                    putJsonObject("message") {
                        put("blobId", uploaded.blobId)
                        putJsonObject("mailboxIds") { put(mailboxId, true) }
                        putJsonObject("keywords") { metadata.keywords.forEach { put(it, true) } }
                        put("receivedAt", metadata.receivedAt.toString())
                    }
                }
            },
        )[0][1].jsonObject
        return response["created"]?.jsonObject?.get("message")?.jsonObject?.get("id")?.str()
            ?: throw JmapError(refusal(response, "notCreated", "That message could not be imported"))
    }

    /**
     * The same, for bytes already in hand rather than a file on disk.
     *
     * A separate path rather than a temporary file, because the only caller is a signature
     * picture that is already decoded in memory and writing it out to be read straight back
     * would be the long way round to the same request.
     */
    private fun upload(bytes: ByteArray, name: String, type: String): Attachment {
        if (uploadUrl.isBlank()) throw JmapError("This server did not say where to upload files.")
        if (maxUpload in 1 until bytes.size.toLong()) {
            throw JmapError(
                "$name is ${humanSize(bytes.size.toLong())}, and this server accepts at most " +
                    "${humanSize(maxUpload)} in one file.",
            )
        }
        val response = http.send(
            HttpRequest.newBuilder(URI.create(uploadUrl.replace("{accountId}", pct(accountId))))
                .header("Authorization", credential)
                .header("Content-Type", type)
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() !in 200..299) {
            throw JmapError("The server would not take $name (HTTP ${response.statusCode()}).")
        }
        val blob = json.parseToJsonElement(response.body()).jsonObject
        return Attachment(
            blobId = blob["blobId"].require("blobId"),
            name = name,
            type = blob["type"]?.str()?.ifBlank { null } ?: type,
            size = blob["size"]?.jsonPrimitive?.longOrNull ?: bytes.size.toLong(),
        )
    }

    /**
     * The signature's pictures uploaded, and pointed at by Content-ID rather than carried
     * inline as base64.
     *
     * **Gmail and Outlook both refuse to draw a `data:` URI in a received message.** The
     * body already went out as cid for exactly that reason; the signature did not, because
     * it is stored on the identity as finished HTML and nothing rewrote it on the way out.
     * So a logo added in the signature editor arrived as a broken image for most of the
     * people it was sent to, and looked right in every test because it looked right here.
     *
     * Done on send rather than when the signature is saved: a blob expires on its own, so a
     * Content-ID written into the identity would go stale on a timer with nothing watching.
     * The cost is one upload of a picture capped at 96 KB per message.
     */
    private fun withInlineSignature(draft: Draft): Draft {
        val pictures = signaturePictures(draft.htmlSignature)
        if (pictures.isEmpty()) return draft
        val cids = mutableMapOf<String, String>()
        val extra = pictures.map { picture ->
            val uploaded = upload(picture.bytes, signaturePictureName(picture.type), picture.type)
            val cid = cidFor(uploaded.blobId)
            cids[picture.src] = cid
            uploaded.copy(cid = cid, inline = true)
        }
        return draft.copy(
            htmlSignature = withCids(draft.htmlSignature, cids),
            attachments = draft.attachments + extra,
        )
    }

    /**
     * Writes the draft to the Drafts folder and returns the id it was stored under.
     *
     * [replacing] is the id of the previous save, and it is destroyed in the same request
     * that creates the new one. A JMAP message is immutable apart from its keywords and
     * which mailboxes it is in, so editing a draft means replacing it, and doing both in
     * one call is what stops a dropped connection leaving two copies of the same draft.
     * Create is processed before destroy, so the new one exists before the old one goes.
     */
    override fun saveDraft(draft: Draft, identity: Identity, draftsMailboxId: String, replacing: String?): String {
        val response = call(
            invoke("Email/set", "d") {
                putJsonObject("create") {
                    putJsonObject("m") {
                        emailObject(draft, identity, draftsMailboxId)
                        // On the draft only. Sending builds the message through emailObject
                        // and does not add these, so a reply does not grow headers nobody asked for.
                        answeredJmapFields(draft).forEach { (name, value) -> put(name, value) }
                    }
                }
                if (replacing != null) putJsonArray("destroy") { add(replacing) }
            },
        )[0][1].jsonObject
        val id = response["created"]?.jsonObject?.get("m")?.jsonObject?.get("id")?.str()
            ?: throw JmapError(refusal(response, "notCreated", "The server would not store the draft"))
        // The new draft is what matters, so a destroy that did not go through does not fail
        // the save. But silently moving on is how one edited draft turns into several: a
        // failure here is usually the transient kind, so one more attempt on its own, not
        // batched with the create this time, catches most of what the first one missed.
        if (replacing != null && response["destroyed"]?.jsonArray?.any { it.str() == replacing } != true) {
            runCatching { call(invoke("Email/set", "dr") { putJsonArray("destroy") { add(replacing) } }) }
        }
        return id
    }

    override fun send(draft: Draft, identity: Identity, draftsMailboxId: String, sentMailboxId: String?): String? {
        // Only on the way out. A draft keeps the base64 in it, which is what makes the
        // picture still visible when the draft is reopened.
        val ready = withInlineSignature(draft)
        refusedOption(ready, submissionExtensions)?.let { throw JmapError(it) }
        val envelope = submissionEnvelope(
            from = identity.email,
            recipients = ready.recipients,
            requireTls = ready.requireTls,
            confirmDelivery = ready.confirmDelivery,
            extensions = submissionExtensions,
        )
        val (emailId, _) = submit(ready, identity, draftsMailboxId, sentMailboxId, envelope)
        if (ready.trackingPixel.isNotEmpty() && sentMailboxId != null) {
            return replaceSentCopy(ready, identity, sentMailboxId, emailId).second
        }
        return null
    }

    /**
     * Files [raw] in Drafts exactly as built, then submits it, so the server sends and keeps
     * the bytes the signature covers rather than a message it rebuilt from an Email object.
     * Two requests, because a submission has to name an Email id and the import makes it.
     */
    override fun sendRaw(raw: ByteArray, draft: Draft, identity: Identity, draftsMailboxId: String, sentMailboxId: String?): String? {
        refusedOption(draft.copy(sign = false, encrypt = false), submissionExtensions)?.let { throw JmapError(it) }
        val uploaded = upload(raw, "message.eml", "message/rfc822")
        val imported = call(
            invoke("Email/import", "i") {
                putJsonObject("emails") {
                    putJsonObject("m") {
                        put("blobId", uploaded.blobId)
                        putJsonObject("mailboxIds") { put(draftsMailboxId, true) }
                        putJsonObject("keywords") { put("\$draft", true); put("\$seen", true) }
                    }
                }
            },
        )[0][1].jsonObject
        val emailId = imported["created"]?.jsonObject?.get("m")?.jsonObject?.get("id")?.str()
            ?: throw JmapError(refusal(imported, "notCreated", "The server would not store the message"))
        val envelope = submissionEnvelope(
            from = identity.email,
            recipients = draft.recipients,
            requireTls = draft.requireTls,
            confirmDelivery = draft.confirmDelivery,
            extensions = submissionExtensions,
        )
        val submitted = call(
            invoke("EmailSubmission/set", "s") {
                putJsonObject("create") {
                    putJsonObject("sub") {
                        put("emailId", emailId)
                        put("identityId", identity.id)
                        envelope?.let { put("envelope", it) }
                    }
                }
                putJsonObject("onSuccessUpdateEmail") {
                    putJsonObject("#sub") {
                        put("mailboxIds/$draftsMailboxId", JsonNull)
                        if (sentMailboxId != null) put("mailboxIds/$sentMailboxId", JsonPrimitive(true))
                        put("keywords/\$draft", JsonNull)
                        put("keywords/\$seen", JsonPrimitive(true))
                    }
                }
            },
        )[0][1].jsonObject
        if (submitted["created"]?.jsonObject?.get("sub") == null) {
            // Not sent, so the protected copy in Drafts is taken back out rather than left
            // looking like a draft somebody could reopen and edit, which it cannot be.
            runCatching { call(invoke("Email/set", "x") { putJsonArray("destroy") { add(emailId) } }) }
            throw JmapError(refusal(submitted, "notCreated", "The server would not send the message"))
        }
        return null
    }

    override fun sendDelayed(
        draft: Draft,
        identity: Identity,
        draftsMailboxId: String,
        sentMailboxId: String?,
        holdUntil: Instant,
    ): DelayedSend {
        val ready = withInlineSignature(draft)
        // The composer hides secure delivery once a send is scheduled rather than
        // immediate, but a draft saved before that could still carry it, and holding it
        // for later must not quietly send it unencrypted once the wait is over.
        refusedOption(ready, submissionExtensions)?.let { throw JmapError(it) }
        val (emailId, submissionId) = submit(
            ready,
            identity,
            draftsMailboxId,
            sentMailboxId,
            envelope = buildJsonObject(holdEnvelope(identity, ready, holdUntil)),
        )
        val (filedId, notice) = if (ready.trackingPixel.isNotEmpty() && sentMailboxId != null) {
            replaceSentCopy(ready, identity, sentMailboxId, emailId)
        } else {
            emailId to null
        }
        return DelayedSend(submissionId, filedId, notice)
    }

    override fun cancelDelayed(submissionId: String): String? {
        val response = call(
            invoke("EmailSubmission/set", "u") {
                putJsonObject("update") {
                    putJsonObject(submissionId) { put("undoStatus", "canceled") }
                }
            },
        )[0][1].jsonObject
        if (response["updated"]?.jsonObject?.containsKey(submissionId) == true) return null
        return refusal(response, "notUpdated", "That message could no longer be called back")
    }

    /**
     * Creates the Email and its EmailSubmission in one request, so the message is never
     * round tripped through us between being written and being sent, and there is no
     * window in which a draft exists that nothing will ever send. On success the server
     * itself moves it out of Drafts and into Sent and drops the draft keyword, which is
     * why that move cannot be left half done by us losing the connection.
     *
     * [envelope] is left off entirely unless an option needs it, so the server derives
     * mailFrom and rcptTo from the message itself; see [submissionEnvelope]. A delayed
     * send always supplies one, because the FUTURERELEASE parameter that holds it for
     * later has nowhere else to go.
     *
     * Returns the new Email's id and the EmailSubmission's id. [send] only needs the
     * first; [sendDelayed] needs the second too, so a later cancel can name this exact
     * submission.
     */
    private fun submit(
        draft: Draft,
        identity: Identity,
        draftsMailboxId: String,
        sentMailboxId: String?,
        envelope: JsonObject? = null,
    ): Pair<String, String> {
        val responses = call(
            invoke("Email/set", "e") {
                putJsonObject("create") {
                    putJsonObject("m") { emailObject(draft, identity, draftsMailboxId) }
                }
            },
            invoke("EmailSubmission/set", "s") {
                putJsonObject("create") {
                    putJsonObject("sub") {
                        put("emailId", "#m")
                        put("identityId", identity.id)
                        envelope?.let { put("envelope", it) }
                    }
                }
                putJsonObject("onSuccessUpdateEmail") {
                    putJsonObject("#sub") {
                        put("mailboxIds/$draftsMailboxId", JsonNull)
                        if (sentMailboxId != null) put("mailboxIds/$sentMailboxId", JsonPrimitive(true))
                        put("keywords/\$draft", JsonNull)
                        put("keywords/\$seen", JsonPrimitive(true))
                    }
                }
            },
        )
        val created = responses[0][1].jsonObject["created"]?.jsonObject?.get("m")
            ?: throw JmapError(refusal(responses[0][1].jsonObject, "notCreated", "The server would not store the message"))
        val submitted = responses[1][1].jsonObject["created"]?.jsonObject?.get("sub")
            ?: throw JmapError(refusal(responses[1][1].jsonObject, "notCreated", "The server would not send the message"))
        return created.jsonObject["id"].require("id") to submitted.jsonObject["id"].require("id")
    }

    /**
     * Puts a copy without the tracking pixel in Sent, in place of the one that was sent.
     *
     * **Opening your own message must not register as the recipient reading it.** That is
     * the single thing that would make the numbers useless, and it is not hypothetical: the
     * copy in Sent is read on a phone all the time, and a phone loads images.
     *
     * It has to be a replacement rather than an edit, because JMAP makes an Email immutable
     * apart from its keywords and its mailboxes: there is no way to reach into a stored
     * message and take one tag out of its body. And it has to be one object on the way out
     * rather than two, because `EmailSubmission` sends the Email that was created and then
     * relabels that same object into Sent, so there is never a moment where the sent copy
     * and the filed copy are different things to begin with.
     *
     * The new one carries the Message-ID that went out, which is why [Draft.messageId] is
     * minted here rather than left to the server. Without it a reply threads against a
     * message that is no longer in the mailbox.
     *
     * Created before the old one is destroyed, so a failure anywhere leaves a correct copy
     * in Sent that merely still has a pixel in it. Losing the record of what was sent would
     * be a far worse outcome than a self-open.
     *
     * Returns the id now sitting in Sent alongside the usual notice, because a caller that
     * needs to find that row again ([sendDelayed] does, to match it in the scheduled list)
     * cannot use [trackedId] any more: this is the object that replaced it.
     */
    private fun replaceSentCopy(sent: Draft, identity: Identity, sentMailboxId: String, trackedId: String): Pair<String, String?> {
        return runCatching {
            val clean = sent.copy(trackingPixel = "")
            val made = call(
                invoke("Email/set", "c") {
                    putJsonObject("create") {
                        // Not a draft, and already read: it is a record of something that
                        // has gone, not something waiting to be finished or seen.
                        putJsonObject("clean") {
                            emailObject(clean, identity, sentMailboxId, mapOf("\$seen" to true))
                        }
                    }
                },
            )[0][1].jsonObject
            val cleanId = made["created"]?.jsonObject?.get("clean")?.jsonObject?.get("id")?.str()
                ?: throw JmapError(refusal(made, "notCreated", "The server would not clean the Sent copy"))
            val removed = call(invoke("Email/set", "d") { putJsonArray("destroy") { add(trackedId) } })[0][1].jsonObject
            if (removed["destroyed"]?.jsonArray?.any { it.str() == trackedId } != true) {
                throw JmapError(refusal(removed, "notDestroyed", "The server would not replace the Sent copy"))
            }
            cleanId to null
        }.getOrElse { trackedId to "Sent, but the tracking pixel could not be removed from the copy in Sent Items." }
    }

    /**
     * What the server knows about delivering [emailId], from the EmailSubmission that sent it.
     *
     * One round trip: the query finds the submission by the message it sent, and the get
     * reads its per-recipient status. Stalwart answers that status from its live delivery
     * queue, so it changes while a message is still being delivered and is worth asking
     * again rather than caching.
     */
    override fun delivery(emailId: String): DeliveryReport? {
        // A server with no submission would refuse the whole request for naming it.
        if (SUBMISSION !in capabilities) return null
        val responses = call(
            invoke("EmailSubmission/query", "q") {
                putJsonObject("filter") { putJsonArray("emailIds") { add(emailId) } }
            },
            invoke("EmailSubmission/get", "g") {
                putJsonObject("#ids") { put("resultOf", "q"); put("name", "EmailSubmission/query"); put("path", "/ids") }
                putJsonArray("properties") { add("emailId"); add("sendAt"); add("undoStatus"); add("deliveryStatus") }
            },
        )
        return deliveryReport(responses[1].list().mapNotNull { (it as? JsonObject)?.let(::submissionRecordOf) })
    }

    /** JMAP reports a refused create, update, or destroy per id, so the reason is inside the response, not the status code. */
    private fun refusal(response: JsonObject, field: String, prefix: String): String {
        val problem = response[field]?.jsonObject?.values?.firstOrNull()?.jsonObject
        val type = problem?.get("type")?.str()
        val description = problem?.get("description")?.str()
        return listOfNotNull(prefix, description ?: type).joinToString(": ") + "."
    }

    /**
     * Makes a folder, and hands back its id.
     *
     * [parentId] null puts it at the top level. The server owns the id, so a folder is not
     * usable until this returns: creating one and guessing where it went is how the sidebar
     * ends up showing something the server does not have.
     */
    override fun createMailbox(name: String, parentId: String?): String {
        val response = call(
            invoke("Mailbox/set", "c") {
                putJsonObject("create") {
                    putJsonObject("new") {
                        put("name", name)
                        put("parentId", parentId?.let { JsonPrimitive(it) } ?: JsonNull)
                    }
                }
            },
        )[0][1].jsonObject
        return response["created"]?.jsonObject?.get("new")?.jsonObject?.get("id")?.str()
            ?: throw JmapError(refusal(response, "notCreated", "That folder could not be created"))
    }

    /**
     * Renames a folder, moves it under another one, or both.
     *
     * A null [parentId] with [reparent] false means "leave it where it is"; with it true it
     * means "move it to the top level". Two different things that would otherwise be the
     * same argument, which is how a rename quietly moves a folder to the root.
     */
    override fun updateMailbox(id: String, name: String?, parentId: String?, reparent: Boolean) {
        val response = call(
            invoke("Mailbox/set", "u") {
                putJsonObject("update") {
                    putJsonObject(id) {
                        name?.let { put("name", it) }
                        if (reparent) put("parentId", parentId?.let { JsonPrimitive(it) } ?: JsonNull)
                    }
                }
            },
        )[0][1].jsonObject
        if (response["updated"]?.jsonObject?.containsKey(id) != true) {
            throw JmapError(refusal(response, "notUpdated", "That folder could not be changed"))
        }
    }

    /**
     * Deletes a folder.
     *
     * [withMail] false is the safe default and the server refuses if anything is in it,
     * which is the answer we want: the caller can then say how many messages there are and
     * ask, rather than deleting somebody's mail because they clicked Delete on a folder.
     */
    override fun destroyMailbox(id: String, withMail: Boolean) {
        val response = call(
            invoke("Mailbox/set", "d") {
                putJsonArray("destroy") { add(id) }
                put("onDestroyRemoveEmails", withMail)
            },
        )[0][1].jsonObject
        val gone = (response["destroyed"] as? JsonArray)?.any { it.str() == id } == true
        if (!gone) throw JmapError(refusal(response, "notDestroyed", "That folder could not be deleted"))
    }

    override fun markSeen(id: String): Applied = setKeyword(listOf(id), "\$seen", true)

    /** Splits what someone typed, and omits the header entirely if empty. */
    private fun JsonObjectBuilder.addresses(field: String, typed: String) {
        val parsed = parseAddressList(typed)
        if (parsed.isEmpty()) return
        putJsonArray(field) {
            parsed.forEach { address ->
                add(buildJsonObject {
                    if (address.name.isNotBlank()) put("name", address.name)
                    put("email", address.email)
                })
            }
        }
    }

    private fun invoke(name: String, id: String, args: JsonObjectBuilder.() -> Unit): JsonArray =
        buildJsonArray {
            add(name)
            add(buildJsonObject { put("accountId", accountId); args() })
            add(id)
        }

    /**
     * [also] names a capability beyond the three every call needs. It is not simply added to
     * the list for good measure: a server that does not have a capability refuses the whole
     * request when it is named, so asking for one everywhere would break every call on a
     * server that happens not to offer it.
     */
    private fun call(vararg invocations: JsonArray, also: String? = null): List<JsonArray> {
        // One round trip, however many method calls are batched inside it, which is the
        // whole reason this file batches them: counted here, once per call, so a page load
        // that quietly starts asking for more round trips again shows up as a number rather
        // than a feeling.
        Diagnostics.count(Metric.LIST_COMMANDS)
        val body = buildJsonObject {
            putJsonArray("using") {
                capabilitiesFor(invocations).forEach { add(it) }
                also?.let { add(it) }
            }
            put("methodCalls", JsonArray(invocations.toList()))
        }
        val response = http.send(
            HttpRequest.newBuilder(URI.create(apiUrl))
                .header("Authorization", credential)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() != 200) {
            throw JmapError("The server answered HTTP ${response.statusCode()} to ${invocations.first()[0].str()}.")
        }
        val responses = json.parseToJsonElement(response.body()).jsonObject["methodResponses"]?.jsonArray
            ?: throw JmapError("The server's reply held no method responses.")
        responses.forEach {
            val it2 = it.jsonArray
            if (it2[0].str() == "error") {
                throw JmapError("The server refused the request: ${it2[1].jsonObject["type"]?.str() ?: "no reason given"}")
            }
        }
        return responses.map { it.jsonArray }
    }

    /**
     * Stalwart's own `x:` management methods, as the signed-in user. Only the Security
     * page calls this, and only after [managementAccountId] said the server is Stalwart.
     */
    internal fun manage(vararg invocations: JsonArray): List<JsonArray> =
        call(*invocations, also = STALWART_CAPABILITY)

    /**
     * This same signed-in session, speaking for another account in it: a group's mailbox or
     * one shared with this login (SharedMailboxes.kt). Every request it makes names [id],
     * because [invoke] writes in the field this changes. Push, file storage, Stalwart's own
     * objects and every capability but mail are left off, so nothing but mail can be done to
     * somebody else's account through it.
     */
    internal fun forAccount(id: String): Jmap = Jmap(
        credential = credential,
        apiUrl = apiUrl,
        accountId = id,
        downloadUrl = downloadUrl,
        uploadUrl = uploadUrl,
        capabilities = capabilities.filter { it == CORE || it == MAIL }.toSet(),
        pushUrl = "",
        maxUpload = maxUpload,
    )

    /**
     * A batch whose requests already carry their account id, built in SharedAccounts.kt.
     * [also] is the one extra capability they need, `principals` for a Principal lookup.
     */
    internal fun sharingCall(vararg invocations: JsonArray, also: String? = null): List<JsonArray> =
        call(*invocations, also = also)

    // ---- file storage (Files.kt) ------------------------------------------------------

    /** Whether this account has JMAP file storage, which is what shows the Files page. */
    internal val hasFileStorage: Boolean get() = FILENODE in capabilities

    /**
     * The account's files, or null where the server offers no file storage.
     *
     * Files.kt builds every request and reads every answer. This only lends it the signed
     * request path, with the file storage capability named for those calls and no others,
     * for the reason given on [call].
     */
    internal fun fileStore(): FileStore? = filesTransport()?.let(::FileStore)

    /** The signed file storage path on its own, for a feature that keeps one file there (SavedPrompts.kt). */
    internal fun filesTransport(): FilesTransport? {
        if (!hasFileStorage) return null
        val jmap = this
        return object : FilesTransport {
            override val accountId: String get() = jmap.accountId
            override val canSliceBlobs: Boolean get() = BLOB in capabilities
            override fun fileCall(vararg invocations: JsonArray): List<JsonArray> =
                call(*invocations, also = FILENODE)
            override fun blobCall(vararg invocations: JsonArray): List<JsonArray> =
                call(*invocations, also = BLOB)
            override fun upload(file: Path): Attachment = jmap.upload(file)
            override fun download(attachment: Attachment, into: Path): Path = jmap.download(attachment, into)
        }
    }

    /**
     * Requests to this server's own WebDAV tree, signed with this session's credential, for
     * tasks over Stalwart's CalDAV (TaskStore.kt). The same origin the JMAP API is on, so it
     * reaches the server the person signed in to and nowhere else. Null when the session is
     * not signed with a password, since then there is no login name to put in the path.
     */
    internal fun davTransport(): DavTransport? {
        val login = credential.takeIf { it.startsWith("Basic ") }?.let {
            runCatching { String(Base64.getDecoder().decode(it.removePrefix("Basic ")), Charsets.UTF_8).substringBefore(':') }.getOrNull()
        }?.ifBlank { null } ?: return null
        val origin = URI.create(apiUrl).resolve("/")
        return object : DavTransport {
            override val user: String = login

            override fun send(method: String, path: String, body: String?, headers: Map<String, String>): DavReply = try {
                val target = origin.resolve(path)
                if (target.host != origin.host || target.scheme != origin.scheme || target.port != origin.port) {
                    throw JmapError("The calendar address pointed away from this server, so Rampart did not follow it.")
                }
                val request = HttpRequest.newBuilder(target)
                    .header("Authorization", credential)
                    .timeout(Duration.ofSeconds(60))
                    .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                headers.forEach { (name, value) -> request.header(name, value) }
                val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
                DavReply(response.statusCode(), response.body().orEmpty())
            } catch (e: JmapError) {
                throw e
            } catch (e: Exception) {
                throw JmapError(plainNetworkError(e, origin.host ?: apiUrl))
            }
        }
    }

    /**
     * Signs every later request with a new password. Stalwart drops its cached sign-in the
     * moment a credential changes, so after a password change, or a switch to an app
     * password, the old one would fail on the very next call.
     */
    internal fun usePassword(user: String, password: String) {
        credential = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
    }

    override fun move(ids: List<String>, toMailboxId: String): Applied {
        if (ids.isEmpty()) return Applied(null)
        val response = call(invoke("Email/set", "m") {
            putJsonObject("update") {
                ids.forEach { id ->
                    putJsonObject(id) {
                        putJsonObject("mailboxIds") { put(toMailboxId, true) }
                    }
                }
            }
        })[0][1].jsonObject
        return Applied(requireApplied(response, ids, "updated", "notUpdated", "move"))
    }

    override fun setKeyword(ids: List<String>, keyword: String, on: Boolean): Applied {
        if (ids.isEmpty()) return Applied(null)
        val response = call(invoke("Email/set", "k") {
            putJsonObject("update") {
                ids.forEach { id ->
                    putJsonObject(id) {
                        put("keywords/$keyword", if (on) JsonPrimitive(true) else JsonNull)
                    }
                }
            }
        })[0][1].jsonObject
        return Applied(requireApplied(response, ids, "updated", "notUpdated", "update"))
    }

    override fun setKeywords(ids: List<String>, add: Set<String>, remove: Set<String>): Applied {
        if (ids.isEmpty() || (add.isEmpty() && remove.isEmpty())) return Applied(null)
        val response = call(invoke("Email/set", "k") {
            putJsonObject("update") {
                ids.forEach { id ->
                    putJsonObject(id) {
                        add.forEach { put("keywords/$it", JsonPrimitive(true)) }
                        remove.forEach { put("keywords/$it", JsonNull) }
                    }
                }
            }
        })[0][1].jsonObject
        return Applied(requireApplied(response, ids, "updated", "notUpdated", "update"))
    }

    override fun destroy(ids: List<String>): Applied {
        if (ids.isEmpty()) return Applied(null)
        val response = call(invoke("Email/set", "d") {
            putJsonArray("destroy") { ids.forEach { add(it) } }
        })[0][1].jsonObject
        return Applied(requireApplied(response, ids, "destroyed", "notDestroyed", "delete"))
    }

    override fun withKeyword(keyword: String, limit: Int): List<Summary> {
        val responses = call(
            invoke("Email/query", "q") {
                putJsonObject("filter") { put("hasKeyword", keyword) }
                putJsonArray("sort") {
                    add(buildJsonObject { put("property", "receivedAt"); put("isAscending", false) })
                }
                put("limit", limit)
            },
            invoke("Email/get", "g") {
                putJsonObject("#ids") { put("resultOf", "q"); put("name", "Email/query"); put("path", "/ids") }
                putJsonArray("properties") {
                    emailGetProperties.forEach { add(it) }
                }
            },
        )
        return responses[1].list().map { jsonToSummary(it.jsonObject) }
    }

    override fun search(text: String, mailboxId: String?, limit: Int, except: Collection<String>): List<Summary> =
        query(searchFilter(text, mailboxId, except), limit)

    /**
     * Email/query with a filter that is already built, newest first.
     *
     * Search builds its filter from one line of text. A saved search with conditions builds
     * a tree of them in `SearchConditions.kt`, and this is the same request for either.
     */
    /**
     * A person's history, in one request.
     *
     * The page is an Email/query over the whole account with every folder in it, Sent
     * included, and no thread collapsing, because every message counts. With the first page
     * comes one more query, oldest first and capped, whose messages are counted here.
     * The server's own total matches a piece of an address, so it is not used. Four method
     * calls and one round trip when the counts are asked for, two on a later page.
     */
    override fun personPage(addresses: List<String>, position: Int, limit: Int, withStats: Boolean): PersonPage {
        val newestFirst = buildJsonObject { put("property", "receivedAt"); put("isAscending", false) }
        val calls = buildList {
            add(
                invoke("Email/query", "q") {
                    put("filter", personFilter(addresses))
                    putJsonArray("sort") { add(newestFirst) }
                    put("position", position)
                    put("limit", limit)
                },
            )
            add(
                invoke("Email/get", "g") {
                    putJsonObject("#ids") { put("resultOf", "q"); put("name", "Email/query"); put("path", "/ids") }
                    putJsonArray("properties") { emailGetProperties.forEach { add(it) } }
                },
            )
            if (withStats) {
                add(
                    invoke("Email/query", "stat") {
                        put("filter", personFilter(addresses))
                        putJsonArray("sort") {
                            add(buildJsonObject { put("property", "receivedAt"); put("isAscending", true) })
                        }
                        put("limit", PERSON_STAT_LIMIT)
                    },
                )
                add(
                    invoke("Email/get", "statGet") {
                        putJsonObject("#ids") { put("resultOf", "stat"); put("name", "Email/query"); put("path", "/ids") }
                        putJsonArray("properties") {
                            add("from"); add("to"); add("cc"); add("bcc"); add("receivedAt")
                        }
                    },
                )
            }
        }
        val responses = call(*calls.toTypedArray())
        val found = responses[1].list().map { it.jsonObject }
        val consumed = (responses[0][1].jsonObject["ids"] as? JsonArray)?.size ?: found.size
        val rows = found.map(::jsonToSummary).filter { involves(it.fromEmail, it.recipients, addresses) }
        val stats = if (!withStats) {
            null
        } else {
            val hits = responses[3].list().map { element ->
                val email = element.jsonObject
                PersonHit(
                    fromEmail = (email["from"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("email")?.str().orEmpty(),
                    recipients = recipientsIn(email),
                    receivedAt = email["receivedAt"]?.str().orEmpty(),
                )
            }
            val counted = exactPersonStats(hits, addresses, onFirstPage = position == 0)
            // The first page is newest first and already exact, so its top row is the
            // newest message. Falling back to an unfiltered hit would name somebody else.
            val newest = if (position == 0) rows.firstOrNull()?.receivedAt ?: counted.last else null
            counted.copy(last = newest)
        }
        return PersonPage(rows, consumed, stats)
    }

    fun query(filter: JsonObject, limit: Int): List<Summary> {
        val responses = call(
            invoke("Email/query", "q") {
                put("filter", filter)
                putJsonArray("sort") {
                    add(buildJsonObject { put("property", "receivedAt"); put("isAscending", false) })
                }
                put("limit", limit)
            },
            invoke("Email/get", "g") {
                putJsonObject("#ids") { put("resultOf", "q"); put("name", "Email/query"); put("path", "/ids") }
                putJsonArray("properties") {
                    emailGetProperties.forEach { add(it) }
                }
            },
        )
        return responses[1].list().map { jsonToSummary(it.jsonObject) }
    }
}

/**
 * The Email/query filter for a search.
 *
 * One folder when [mailboxId] is set. Otherwise the whole account, minus
 * [except], which is how Junk and Deleted stay out without searching those
 * folders and throwing the hits away after the limit has already been filled
 * with them.
 */
internal fun searchFilter(text: String, mailboxId: String?, except: Collection<String>): JsonObject {
    val skip = except.filter { it.isNotBlank() }.distinct()
    if (mailboxId == null && skip.isEmpty()) return buildJsonObject { put("text", text) }
    return buildJsonObject {
        put("operator", "AND")
        putJsonArray("conditions") {
            mailboxId?.let { add(buildJsonObject { put("inMailbox", it) }) }
            if (skip.isNotEmpty()) {
                add(buildJsonObject {
                    putJsonArray("inMailboxOtherThan") { skip.forEach { add(it) } }
                })
            }
            add(buildJsonObject { put("text", text) })
        }
    }
}

/** Ids a set result lists as done. `updated` is an object, `destroyed` is an array. */
internal fun appliedIds(node: JsonElement?): Set<String> = when (node) {
    is JsonObject -> node.keys
    is JsonArray -> node.mapNotNull { it.str() }.toSet()
    else -> emptySet()
}

/**
 * Refuses the call unless every id landed.
 *
 * Email/set reports success per id. The HTTP status stays 200 when the server
 * refused the messages, and the only place that is written is notUpdated or
 * notDestroyed. The sentence says how many, because "the server refused" with
 * no count is how a partial failure gets undone as if none of it happened.
 *
 * Returns the account state the set produced, so a push of that same state can
 * be recognised as the echo of this call.
 */
internal fun requireApplied(
    response: JsonObject,
    ids: List<String>,
    doneField: String,
    refusedField: String,
    action: String,
): String? {
    val done = appliedIds(response[doneField])
    val refused = response[refusedField]?.jsonObject?.keys ?: emptySet()
    val failed = ids.count { it in refused || it !in done }
    if (failed > 0) {
        val noun = if (failed == 1) "message" else "messages"
        throw JmapError("The server would not $action $failed $noun.")
    }
    return response["newState"]?.str()
}

private const val PUSH_ENABLE =
    """{"@type":"WebSocketPushEnable","dataTypes":["Email","Mailbox"]}"""

/**
 * A text frame can arrive in pieces, so it is collected until the last one before being
 * read. Anything that is not a StateChange is dropped, error frames included: a push that
 * goes wrong is a push that does not arrive, and the poll covers that already.
 */
private class PushListener(
    private val onChange: () -> Unit,
    private val onGone: () -> Unit,
) : WebSocket.Listener {
    private val frame = StringBuilder()

    override fun onError(socket: WebSocket, error: Throwable) = onGone()

    override fun onClose(socket: WebSocket, status: Int, reason: String): CompletionStage<*>? {
        onGone()
        return null
    }

    override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        frame.append(data)
        if (last) {
            val text = frame.toString()
            frame.setLength(0)
            val type = runCatching {
                (json.parseToJsonElement(text).jsonObject["@type"] as? JsonPrimitive)?.contentOrNull
            }.getOrNull()
            if (type == "StateChange") onChange()
        }
        socket.request(1)
        return null
    }
}

internal val emailGetProperties =
    listOf(
        "id", "threadId", "from", "subject", "receivedAt", "preview", "keywords", "messageId",
        // Who it went to, a few dozen bytes a row, so the copy can answer "mail to this
        // person" without the network. Bcc is included: a header nobody asks for is not
        // sent, and a blind copy would then be missing from the copy. See [Summary.recipients].
        "to", "cc", "bcc",
        // For the table's size column and for splitting a saved search by mailing list.
        "size", "header:List-Id:asText",
        // The focused inbox. These three only: the rest of the header block stays unread.
        "header:List-Unsubscribe:asText",
        "header:Precedence:asText",
        "header:Auto-Submitted:asText",
    )

/**
 * One folder from a Mailbox object in a Mailbox/get reply.
 *
 * [Mailbox.unreadThreads] is read here, beside [Mailbox.unread], because the sidebar
 * counts conversations and everything else that already used the message count should
 * keep using it.
 */
internal fun mailboxFrom(o: JsonObject): Mailbox {
    val unread = o["unreadEmails"]?.jsonPrimitive?.intOrNull ?: 0
    return Mailbox(
        id = o["id"].require("id"),
        name = o["name"]?.str() ?: "(no name)",
        role = o["role"]?.str(),
        unread = unread,
        parentId = o["parentId"]?.str(),
        // totalEmails rides in the same Mailbox/get as the unread count. Reading it
        // here is not a second request.
        total = o["totalEmails"]?.jsonPrimitive?.intOrNull ?: 0,
        // Missing means the server did not say. Fall back to the message count rather
        // than showing zero conversations when the mail is still unread.
        unreadThreads = o["unreadThreads"]?.jsonPrimitive?.intOrNull ?: unread,
    )
}

private fun jsonToSummary(o: JsonObject): Summary = Summary(
    id = o["id"].require("id"),
    from = (o["from"] as? JsonArray)?.firstOrNull()?.jsonObject?.let { a ->
        a["name"]?.str()?.ifBlank { null } ?: a["email"]?.str()
    } ?: "(no sender)",
    fromEmail = (o["from"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("email")?.str().orEmpty(),
    subject = o["subject"]?.str()?.ifBlank { null } ?: "(no subject)",
    receivedAt = o["receivedAt"]?.str() ?: "",
    preview = o["preview"]?.str()?.trim() ?: "",
    seen = o["keywords"]?.jsonObject?.containsKey("\$seen") == true,
    flagged = o["keywords"]?.jsonObject?.containsKey("\$flagged") == true,
    keywords = o["keywords"]?.jsonObject?.keys.orEmpty(),
    threadId = o["threadId"]?.str().orEmpty(),
    messageId = (o["messageId"] as? JsonArray)?.firstOrNull()?.str().orEmpty(),
    size = o["size"]?.num() ?: 0L,
    listId = listIdOf((o["header:List-Id:asText"] as? JsonPrimitive)?.contentOrNull),
    recipients = recipientsIn(o),
    listUnsubscribe = headerAsText(o, "List-Unsubscribe"),
    precedence = headerAsText(o, "Precedence"),
    autoSubmitted = headerAsText(o, "Auto-Submitted"),
)

/** One header the list asked for by name. Missing or null is empty, not an error. */
private fun headerAsText(email: JsonObject, name: String): String =
    (email["header:$name:asText"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

/**
 * Every address a message was sent to, for the local copy.
 *
 * Bcc is included because a person who was only blind-copied disappears from history
 * answered on this computer otherwise. Case is not part of an address, and the same
 * address written twice is one person.
 */
internal fun storedRecipients(to: List<String>, cc: List<String>, bcc: List<String> = emptyList()): List<String> =
    (to + cc + bcc).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

/** [storedRecipients] from one Email object. A missing header is nobody, not an error. */
internal fun recipientsIn(email: JsonObject): List<String> =
    storedRecipients(addressesIn(email["to"]), addressesIn(email["cc"]), addressesIn(email["bcc"]))

private fun kotlinx.serialization.json.JsonElement.str(): String? = jsonPrimitive.contentOrNull

/** A number, or null where the server sent null or something that is not one. */
private fun kotlinx.serialization.json.JsonElement.num(): Long? =
    (this as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun kotlinx.serialization.json.JsonElement?.require(name: String): String =
    this?.str() ?: throw JmapError("The server's reply has no $name.")

private fun JsonArray.list(): List<kotlinx.serialization.json.JsonElement> =
    this[1].jsonObject["list"] as? JsonArray ?: emptyList()

/** The subtype is only a hint, and the sender chose it, so it is reduced to letters and digits. */
private fun nameFromType(type: String): String {
    val subtype = type.substringAfterLast('/')
        .substringBefore(';')
        .filter { it.isLetterOrDigit() }
    return if (subtype.isEmpty()) "attachment" else "attachment.$subtype"
}

// Substituting a raw name would let a slash or question mark rewrite the URL we hit.
private fun pct(value: String): String = buildString(value.length * 3) {
    for (byte in value.encodeToByteArray()) {
        val u = byte.toInt() and 0xFF
        val unreserved = u in 'A'.code..'Z'.code ||
            u in 'a'.code..'z'.code ||
            u in '0'.code..'9'.code ||
            u == '-'.code || u == '.'.code || u == '_'.code || u == '~'.code
        if (unreserved) append(u.toChar())
        else {
            append('%')
            append("0123456789ABCDEF"[u shr 4])
            append("0123456789ABCDEF"[u and 0xF])
        }
    }
}

/**
 * A network failure, said the way a person would say it.
 *
 * The reason this exists is that `java.net.http` reports a name that does not resolve as a
 * ConnectException whose own message is null, so the sign-in screen was showing the words
 * "java.net.ConnectException" and nothing else. The cause chain is walked because the fault
 * worth naming is usually two or three levels down from what was thrown.
 */
internal fun plainNetworkError(e: Throwable, server: String): String {
    val chain = generateSequence(e) { it.cause }.take(8).toList()
    fun kind(name: String) = chain.any { it.javaClass.name.endsWith(name) }
    return when {
        // java.net.http reports a name that does not resolve as an UnresolvedAddressException
        // wrapped in two ConnectExceptions, all three with a null message. Checked against a
        // real lookup rather than assumed, because the obvious guess is UnknownHostException
        // and that is not what comes out.
        kind("UnresolvedAddressException") || kind("UnknownHostException") ->
            "There is no server called $server. Check the address for a typo."
        kind("SSLHandshakeException") || kind("CertificateException") ->
            "$server answered, but its security certificate is not one this computer trusts."
        kind("HttpTimeoutException") || kind("SocketTimeoutException") ->
            "$server did not answer in time."
        kind("ConnectException") || kind("NoRouteToHostException") ->
            "Could not reach $server. Either the address is wrong or it is not answering."
        else -> chain.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
            ?: "Could not reach $server."
    }
}

/**
 * The text of the parts the server listed, or null when there are none worth showing.
 *
 * [wantedType] is the point of this existing. A message with no HTML in it is still listed
 * under htmlBody, as the same text/plain part that is in textBody, because that is the best
 * HTML representation the server has. Taking it at its word ran every plain-text message
 * through the HTML parser, which quietly ate anything in angle brackets: a DMARC report's
 * "Report-ID: <secureserver.net!1789516800>" came out with the id missing.
 */
internal fun bodyText(parts: JsonArray?, values: JsonObject, wantedType: String?): String? =
    resolveBody(parts, values, wantedType) { _, _, _ -> null }

/**
 * Whether any part the reader would see was cut off at the server's byte cap.
 *
 * JMAP sets `isTruncated` on a body value it refused to send whole. Ignoring it shows
 * the message with the end missing and nothing saying so.
 */
internal fun bodyCut(parts: JsonArray?, values: JsonObject, wantedType: String?): Boolean =
    parts?.any { element ->
        val part = element.jsonObject
        if (wantedType != null && part["type"]?.str() != wantedType) return@any false
        val id = part["partId"]?.str() ?: return@any false
        values[id]?.jsonObject?.get("isTruncated")?.jsonPrimitive?.booleanOrNull == true
    } == true

/**
 * The text of the parts, with a cut part replaced by [bytes] when that returns the rest.
 *
 * [bytes] is the part's own blob. Null keeps the short copy, so a download that failed
 * still shows what the server did send.
 */
internal fun resolveBody(
    parts: JsonArray?,
    values: JsonObject,
    wantedType: String?,
    bytes: (blobId: String, type: String, size: Long) -> ByteArray?,
): String? = parts
    ?.filter { wantedType == null || it.jsonObject["type"]?.str() == wantedType }
    ?.mapNotNull { element ->
        val part = element.jsonObject
        val id = part["partId"]?.str() ?: return@mapNotNull null
        val record = values[id]?.jsonObject
        val cut = record?.get("isTruncated")?.jsonPrimitive?.booleanOrNull == true
        if (cut) {
            val blobId = part["blobId"]?.str()
            val size = part["size"]?.jsonPrimitive?.longOrNull ?: 0L
            val type = part["type"]?.str() ?: "application/octet-stream"
            val full = blobId?.let { bytes(it, type, size) }?.let { String(it, Charsets.UTF_8) }
            full ?: record?.get("value")?.str()
        } else {
            record?.get("value")?.str()
        }
    }
    ?.joinToString("\n")
    ?.ifBlank { null }

/**
 * The strings in a header that holds a list of them, or none.
 *
 * `as? JsonArray` rather than `.jsonArray`, and the difference is not stylistic. A header a
 * message does not have comes back as JSON null rather than being left out, and JsonNull is
 * a value, so a null-safe call does not skip it and `.jsonArray` throws. Every message in
 * this mailbox answers null for Cc, so every one of them stopped opening: "Element class
 * JsonNull is not a JsonArray", on screen, where the message should have been.
 */
internal fun stringsIn(element: JsonElement?): List<String> =
    (element as? JsonArray)?.mapNotNull { it.str() }.orEmpty()

/** The addresses in a To or Cc header, or none. Same trap as [stringsIn]. */
internal fun addressesIn(element: JsonElement?): List<String> =
    (element as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("email")?.str() }.orEmpty()
