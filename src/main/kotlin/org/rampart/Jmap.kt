package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

/** Anything the server said no to, in words a person can read. */
class JmapError(message: String) : Exception(message)

private const val CORE = "urn:ietf:params:jmap:core"
private const val MAIL = "urn:ietf:params:jmap:mail"
private const val SUBMISSION = "urn:ietf:params:jmap:submission"

private val json = Json { ignoreUnknownKeys = true }

private val http: HttpClient = HttpClient.newBuilder()
    // Redirects are followed by hand: HttpClient drops the Authorization header across one,
    // and Stalwart answers /.well-known/jmap with a 307 to the real session URL.
    .followRedirects(HttpClient.Redirect.NEVER)
    .connectTimeout(Duration.ofSeconds(15))
    .build()

data class Mailbox(val id: String, val name: String, val role: String?, val unread: Int)

data class Summary(
    val id: String,
    val from: String,
    val fromEmail: String,
    val subject: String,
    val receivedAt: String,
    val preview: String,
    val seen: Boolean,
)

/** An address this account is allowed to send as. */
data class Identity(val id: String, val name: String, val email: String)

data class Attachment(val blobId: String, val name: String, val type: String, val size: Long)

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
    /** Everyone the message was addressed to, which is what Reply all needs. */
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
)

class Jmap private constructor(
    private val credential: String,
    private val apiUrl: String,
    val accountId: String,
    private val downloadUrl: String,
) {
    companion object {
        fun connect(server: String, user: String, password: String): Jmap {
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
            return Jmap(credential, session["apiUrl"].require("apiUrl"), account, downloadUrl)
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

    fun mailboxes(): List<Mailbox> {
        val list = call(invoke("Mailbox/get", "m") { put("ids", JsonNull) })[0].list()
        return list.map {
            val o = it.jsonObject
            Mailbox(
                id = o["id"].require("id"),
                name = o["name"]?.str() ?: "(no name)",
                role = o["role"]?.str(),
                unread = o["unreadEmails"]?.jsonPrimitive?.intOrNull ?: 0,
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
    fun mailState(): String? =
        call(invoke("Email/get", "s") { putJsonArray("ids") {} })[0][1].jsonObject["state"]?.str()

    fun emails(mailboxId: String, limit: Int = 100): List<Summary> {
        val responses = call(
            invoke("Email/query", "q") {
                putJsonObject("filter") { put("inMailbox", mailboxId) }
                putJsonArray("sort") {
                    add(buildJsonObject { put("property", "receivedAt"); put("isAscending", false) })
                }
                put("limit", limit)
            },
            invoke("Email/get", "g") {
                // A back reference, so the ids never make the round trip through us.
                putJsonObject("#ids") { put("resultOf", "q"); put("name", "Email/query"); put("path", "/ids") }
                putJsonArray("properties") {
                    emailGetProperties.forEach { add(it) }
                }
            },
        )
        return responses[1].list().map { jsonToSummary(it.jsonObject) }
    }

    fun body(id: String): Body {
        val email = call(
            invoke("Email/get", "b") {
                putJsonArray("ids") { add(id) }
                putJsonArray("properties") {
                    add("htmlBody"); add("textBody"); add("bodyValues")
                    add("messageId"); add("references"); add("to"); add("cc")
                }
                put("fetchHTMLBodyValues", true)
                put("fetchTextBodyValues", true)
                put("maxBodyValueBytes", 1024 * 1024)
            },
        )[0].list().firstOrNull()?.jsonObject
            ?: throw JmapError("That message is not on the server any more.")

        val values = email["bodyValues"]?.jsonObject ?: JsonObject(emptyMap())
        fun join(part: String): String? = email[part]?.jsonArray
            ?.mapNotNull { values[it.jsonObject["partId"]?.str() ?: return@mapNotNull null]?.jsonObject?.get("value")?.str() }
            ?.joinToString("\n")
            ?.ifBlank { null }
        fun ids(field: String) = email[field]?.jsonArray?.mapNotNull { it.str() }.orEmpty()
        fun addresses(field: String) = email[field]?.jsonArray
            ?.mapNotNull { it.jsonObject["email"]?.str() }.orEmpty()
        return Body(
            html = join("htmlBody"),
            text = join("textBody"),
            messageId = ids("messageId"),
            references = ids("references"),
            to = addresses("to"),
            cc = addresses("cc"),
        )
    }

    fun attachments(emailId: String): List<Attachment> {
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
            Attachment(blobId = blobId, name = name, type = type, size = size)
        }
    }

    /**
     * Streams the blob into [into]. The body is never held in memory: a mail
     * attachment can be hundreds of megabytes.
     */
    fun download(attachment: Attachment, into: Path): Path {
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

    fun identities(): List<Identity> =
        call(invoke("Identity/get", "i") { put("ids", JsonNull) })[0].list().map {
            val o = it.jsonObject
            Identity(
                id = o["id"].require("id"),
                name = o["name"]?.str()?.ifBlank { null } ?: o["email"]?.str().orEmpty(),
                email = o["email"].require("email"),
            )
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
    fun send(draft: Draft, identity: Identity, draftsMailboxId: String, sentMailboxId: String?) {
        val responses = call(
            invoke("Email/set", "e") {
                putJsonObject("create") {
                    putJsonObject("m") {
                        putJsonObject("mailboxIds") { put(draftsMailboxId, true) }
                        putJsonObject("keywords") { put("\$draft", true) }
                        putJsonArray("from") {
                            add(buildJsonObject { put("name", identity.name); put("email", identity.email) })
                        }
                        addresses("to", draft.to)
                        addresses("cc", draft.cc)
                        put("subject", draft.subject)
                        // Without both of these a reply arrives as a new conversation in
                        // every client that threads, which is most of them.
                        draft.inReplyTo?.let {
                            putJsonArray("header:In-Reply-To:asMessageIds") { add(it) }
                        }
                        if (draft.references.isNotEmpty()) {
                            putJsonArray("header:References:asMessageIds") { draft.references.forEach { add(it) } }
                        }
                        putJsonObject("bodyStructure") { put("partId", "b"); put("type", "text/plain") }
                        putJsonObject("bodyValues") { putJsonObject("b") { put("value", draft.body) } }
                    }
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
    }

    /** JMAP reports a refused create per id, so the reason is inside the response, not the status code. */
    private fun refusal(response: JsonObject, field: String, prefix: String): String {
        val problem = response[field]?.jsonObject?.values?.firstOrNull()?.jsonObject
        val type = problem?.get("type")?.str()
        val description = problem?.get("description")?.str()
        return listOfNotNull(prefix, description ?: type).joinToString(": ") + "."
    }

    fun markSeen(id: String) {
        setKeyword(listOf(id), "\$seen", true)
    }

    /** Splits what someone typed into a comma separated list, and omits the header entirely if empty. */
    private fun JsonObjectBuilder.addresses(field: String, typed: String) {
        val parsed = typed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parsed.isEmpty()) return
        putJsonArray(field) { parsed.forEach { add(buildJsonObject { put("email", it) }) } }
    }

    private fun invoke(name: String, id: String, args: JsonObjectBuilder.() -> Unit): JsonArray =
        buildJsonArray {
            add(name)
            add(buildJsonObject { put("accountId", accountId); args() })
            add(id)
        }

    private fun call(vararg invocations: JsonArray): List<JsonArray> {
        val body = buildJsonObject {
            putJsonArray("using") { add(CORE); add(MAIL); add(SUBMISSION) }
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

    fun move(ids: List<String>, toMailboxId: String) {
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

    fun setKeyword(ids: List<String>, keyword: String, on: Boolean) {
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

    fun destroy(ids: List<String>) {
        if (ids.isEmpty()) return
        call(invoke("Email/set", "d") {
            putJsonArray("destroy") { ids.forEach { add(it) } }
        })
    }

    fun search(text: String, mailboxId: String? = null, limit: Int = 100): List<Summary> {
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

private val emailGetProperties = listOf("id", "from", "subject", "receivedAt", "preview", "keywords")

private fun jsonToSummary(o: JsonObject): Summary = Summary(
    id = o["id"].require("id"),
    from = o["from"]?.jsonArray?.firstOrNull()?.jsonObject?.let { a ->
        a["name"]?.str()?.ifBlank { null } ?: a["email"]?.str()
    } ?: "(no sender)",
    fromEmail = o["from"]?.jsonArray?.firstOrNull()?.jsonObject?.get("email")?.str().orEmpty(),
    subject = o["subject"]?.str()?.ifBlank { null } ?: "(no subject)",
    receivedAt = o["receivedAt"]?.str() ?: "",
    preview = o["preview"]?.str()?.trim() ?: "",
    seen = o["keywords"]?.jsonObject?.containsKey("\$seen") == true,
)

private fun kotlinx.serialization.json.JsonElement.str(): String? = jsonPrimitive.contentOrNull

private fun kotlinx.serialization.json.JsonElement?.require(name: String): String =
    this?.str() ?: throw JmapError("The server's reply has no $name.")

private fun JsonArray.list(): List<kotlinx.serialization.json.JsonElement> =
    this[1].jsonObject["list"]?.jsonArray ?: emptyList()

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
