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
    private val credential: String,
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
) : MailBackend {
    companion object {
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
            val pushUrl = (session["capabilities"]?.jsonObject?.get(WEBSOCKET)?.jsonObject
                ?.get("url") as? JsonPrimitive)?.contentOrNull.orEmpty()
            // Kept so a feature can ask whether this server has it rather than calling and
            // reading the refusal. A server without Sieve should not be offered filters.
            val capabilities = session["capabilities"]?.jsonObject?.keys.orEmpty().toSet()
            return Jmap(
                credential = credential,
                apiUrl = session["apiUrl"].require("apiUrl"),
                accountId = account,
                downloadUrl = downloadUrl,
                uploadUrl = uploadUrl,
                pushUrl = pushUrl,
                maxUpload = maxUpload,
                capabilities = capabilities,
            )
        }

        /** Accepts a bare host, a base URL, a /jmap/ URL, or the well-known URL itself. */
        private fun sessionUrl(server: String): URI {
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

    override fun mailboxes(): List<Mailbox> {
        val list = call(invoke("Mailbox/get", "m") { put("ids", JsonNull) })[0].list()
        return list.map {
            val o = it.jsonObject
            Mailbox(
                id = o["id"].require("id"),
                name = o["name"]?.str() ?: "(no name)",
                role = o["role"]?.str(),
                unread = o["unreadEmails"]?.jsonPrimitive?.intOrNull ?: 0,
                parentId = o["parentId"]?.str(),
                // totalEmails rides in the same Mailbox/get as the unread count. Reading it
                // here is not a second request.
                total = o["totalEmails"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }.sortedWith(compareBy({ if (it.role == "inbox") 0 else 1 }, { it.name.lowercase() }))
    }

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
        val responses = call(
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
        val ids = call(invoke("Thread/get", "t") { putJsonArray("ids") { add(threadId) } })[0]
            .list().firstOrNull()?.jsonObject?.get("emailIds")?.let { it as? JsonArray }?.mapNotNull { it.str() }
            .orEmpty()
        if (ids.size <= 1) return emptyList()
        val found = call(
            invoke("Email/get", "g") {
                putJsonArray("ids") { ids.forEach { add(it) } }
                putJsonArray("properties") { emailGetProperties.forEach { add(it) } }
            },
        )[0].list().associate { it.jsonObject["id"].require("id") to jsonToSummary(it.jsonObject) }
        // Email/get may answer in any order; the thread's own order is the one that matters.
        return ids.mapNotNull { found[it] }
    }

    override fun body(id: String): Body {
        val email = call(
            invoke("Email/get", "b") {
                putJsonArray("ids") { add(id) }
                putJsonArray("properties") {
                    add("htmlBody"); add("textBody"); add("bodyValues")
                    add("messageId"); add("references"); add("header:In-Reply-To:asMessageIds")
                    add("to"); add("cc"); add("replyTo")
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
                }
                put("fetchHTMLBodyValues", true)
                put("fetchTextBodyValues", true)
                put("maxBodyValueBytes", 1024 * 1024)
            },
        )[0].list().firstOrNull()?.jsonObject
            ?: throw JmapError("That message is not on the server any more.")

        val values = email["bodyValues"]?.jsonObject ?: JsonObject(emptyMap())
        fun join(part: String, wantedType: String? = null): String? =
            bodyText(email[part] as? JsonArray, values, wantedType)
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
        return Body(
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
        )
    }

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

    override fun attachments(emailId: String): List<Attachment> {
        val email = call(
            invoke("Email/get", "a") {
                putJsonArray("ids") { add(emailId) }
                putJsonArray("properties") { add("attachments") }
            },
        )[0].list().firstOrNull()?.jsonObject
            ?: throw JmapError("That message is not on the server any more.")

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

    override fun identities(): List<Identity> =
        call(invoke("Identity/get", "i") { put("ids", JsonNull) })[0].list().map {
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

    override fun addressBooks(): List<ContactBook> = call(
        invoke("AddressBook/get", "a") { put("ids", JsonNull) },
        also = CONTACTS,
    )[0].list().map {
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
    override fun contacts(): List<Pair<Contact, JsonObject>> = call(
        invoke("ContactCard/get", "c") { put("ids", JsonNull) },
        also = CONTACTS,
    )[0].list().map { contactOf(it.jsonObject) to it.jsonObject }

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
     * Writes a script and makes it the active one.
     *
     * The text goes up as a blob first, because that is how JMAP moves anything with a body,
     * and the script then points at it. Activating in the same call rather than a second one
     * means a refused script never becomes the active script.
     */
    override fun saveSieve(name: String, text: String, existing: SieveInfo?) {
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
        val blobId = json.parseToJsonElement(upload.body()).jsonObject["blobId"].require("blobId")

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
                putJsonObject("create") { putJsonObject("m") { emailObject(draft, identity, draftsMailboxId) } }
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

    override fun send(draft: Draft, identity: Identity, draftsMailboxId: String, sentMailboxId: String?) {
        // Only on the way out. A draft keeps the base64 in it, which is what makes the
        // picture still visible when the draft is reopened.
        val ready = withInlineSignature(draft)
        val responses = call(
            invoke("Email/set", "e") {
                putJsonObject("create") {
                    putJsonObject("m") { emailObject(ready, identity, draftsMailboxId) }
                }
            },
            invoke("EmailSubmission/set", "s") {
                putJsonObject("create") {
                    putJsonObject("sub") {
                        put("emailId", "#m")
                        put("identityId", identity.id)
                    }
                }
                putJsonObject("onSuccessUpdateEmail") {
                    putJsonObject("#sub") {
                        put("mailboxIds/$draftsMailboxId", JsonNull)
                        if (sentMailboxId != null) put("mailboxIds/$sentMailboxId", JsonPrimitive(true))
                        put("keywords/\$draft", JsonNull)
                    }
                }
            },
        )
        val created = responses[0][1].jsonObject["created"]?.jsonObject?.get("m")
            ?: throw JmapError(refusal(responses[0][1].jsonObject, "notCreated", "The server would not store the message"))
        checkNotNull(created)
        responses[1][1].jsonObject["created"]?.jsonObject?.get("sub")
            ?: throw JmapError(refusal(responses[1][1].jsonObject, "notCreated", "The server would not send the message"))
        if (ready.trackingPixel.isNotEmpty() && sentMailboxId != null) {
            replaceSentCopy(ready, identity, sentMailboxId, created.jsonObject["id"].require("id"))
        }
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
     */
    private fun replaceSentCopy(sent: Draft, identity: Identity, sentMailboxId: String, trackedId: String) {
        runCatching {
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
            made["created"]?.jsonObject?.get("clean") ?: return
            call(invoke("Email/set", "d") { putJsonArray("destroy") { add(trackedId) } })
        }
    }

    /** JMAP reports a refused create per id, so the reason is inside the response, not the status code. */
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

    override fun markSeen(id: String) {
        setKeyword(listOf(id), "\$seen", true)
    }

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
                add(CORE); add(MAIL); add(SUBMISSION)
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

    override fun move(ids: List<String>, toMailboxId: String) {
        if (ids.isEmpty()) return
        call(invoke("Email/set", "m") {
            putJsonObject("update") {
                ids.forEach { id ->
                    putJsonObject(id) {
                        putJsonObject("mailboxIds") { put(toMailboxId, true) }
                    }
                }
            }
        })
    }

    override fun setKeyword(ids: List<String>, keyword: String, on: Boolean) {
        if (ids.isEmpty()) return
        call(invoke("Email/set", "k") {
            putJsonObject("update") {
                ids.forEach { id ->
                    putJsonObject(id) {
                        put("keywords/$keyword", if (on) JsonPrimitive(true) else JsonNull)
                    }
                }
            }
        })
    }

    override fun destroy(ids: List<String>) {
        if (ids.isEmpty()) return
        call(invoke("Email/set", "d") {
            putJsonArray("destroy") { ids.forEach { add(it) } }
        })
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

    override fun search(text: String, mailboxId: String?, limit: Int): List<Summary> {
        val responses = call(
            invoke("Email/query", "q") {
                val filter = buildJsonObject {
                    if (mailboxId == null) {
                        put("text", text)
                    } else {
                        put("operator", "AND")
                        putJsonArray("conditions") {
                            add(buildJsonObject { put("inMailbox", mailboxId) })
                            add(buildJsonObject { put("text", text) })
                        }
                    }
                }
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

private val emailGetProperties =
    listOf("id", "threadId", "from", "subject", "receivedAt", "preview", "keywords")

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
)

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
internal fun bodyText(parts: JsonArray?, values: JsonObject, wantedType: String?): String? = parts
    ?.filter { wantedType == null || it.jsonObject["type"]?.str() == wantedType }
    ?.mapNotNull { part ->
        val id = part.jsonObject["partId"]?.str() ?: return@mapNotNull null
        values[id]?.jsonObject?.get("value")?.str()
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
