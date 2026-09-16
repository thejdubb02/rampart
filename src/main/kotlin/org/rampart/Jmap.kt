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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/** Anything the server said no to, in words a person can read. */
class JmapError(message: String) : Exception(message)

private const val CORE = "urn:ietf:params:jmap:core"
private const val MAIL = "urn:ietf:params:jmap:mail"

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
    val subject: String,
    val receivedAt: String,
    val preview: String,
    val seen: Boolean,
)

/** Only ever one of these is drawn, and html wins when both are present. */
data class Body(val html: String?, val text: String?)

class Jmap private constructor(
    private val credential: String,
    private val apiUrl: String,
    val accountId: String,
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
            return Jmap(credential, session["apiUrl"].require("apiUrl"), account)
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
                putJsonArray("properties") { add("htmlBody"); add("textBody"); add("bodyValues") }
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
        return Body(join("htmlBody"), join("textBody"))
    }

    fun markSeen(id: String) {
        setKeyword(listOf(id), "\$seen", true)
    }

    private fun invoke(name: String, id: String, args: JsonObjectBuilder.() -> Unit): JsonArray =
        buildJsonArray {
            add(name)
            add(buildJsonObject { put("accountId", accountId); args() })
            add(id)
        }

    private fun call(vararg invocations: JsonArray): List<JsonArray> {
        val body = buildJsonObject {
            putJsonArray("using") { add(CORE); add(MAIL) }
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
        a["name"]?.str() ?: a["email"]?.str()
    } ?: "(no sender)",
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
