package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val JMAP_CORE = "urn:ietf:params:jmap:core"
private const val JMAP_MAIL = "urn:ietf:params:jmap:mail"
internal const val STALWART_JMAP = "urn:stalwart:jmap"

/**
 * The object types this build of Rampart will send a request about, and nothing else.
 *
 * This is the second of the two walls between a renderer bug and the directory. The first
 * is that the admin credential is separate from the mail one, so nothing on the mail side
 * can reach the server's management interface at all. This one is that even the admin side
 * can only name these types, whatever a form, a menu entry or a schema says: a method name
 * is never built from server data and then sent unchecked. `x:Directory` is not here and
 * is never to be added without a confirmation screen of its own.
 */
internal val AdminWritableObjects: Set<String> = setOf("x:Domain", "x:Account")

/**
 * Which of the server's menu entries this build can draw and this login can open.
 *
 * Gated three ways: the object must be one [AdminWritableObjects] names, the server must
 * describe it, and `/api/account` must say this login may both query and read it. The last
 * is the one that matters; the schema alone would offer pages that answer 403.
 */
internal fun adminPages(schema: AdminSchema, access: AdminAccess): List<Pair<AdminMenuEntry, AdminObject>> =
    schema.menu().mapNotNull { entry ->
        val obj = schema.objectFor(entry.viewName) ?: return@mapNotNull null
        if (obj.objectName !in AdminWritableObjects) return@mapNotNull null
        if (!access.canList(obj)) return@mapNotNull null
        entry to obj
    }

/** The methods allowed on those types. Destroy is not offered in this first slice. */
private val AdminVerbs = setOf("get", "query", "set")

/**
 * Null when [method] with [args] may be sent, otherwise why not.
 *
 * Pure so it can be tested directly, and checked in [AdminClient.call] on every request
 * rather than at the places that build them, so a new caller cannot forget it.
 */
internal fun adminMethodRefusal(method: String, args: JsonObject): String? {
    val type = method.substringBeforeLast('/', "")
    val verb = method.substringAfterLast('/', "")
    if (type !in AdminWritableObjects) return "Rampart does not manage $type from here."
    if (verb !in AdminVerbs) return "Rampart does not send $method."
    if (verb == "set" && args["destroy"] != null) return "Rampart does not delete ${type.removePrefix("x:")} objects yet."
    return null
}

/**
 * Where to reach the server and with what. [user] blank means [secret] is an API key sent
 * as a bearer token; otherwise the two are a login sent as HTTP Basic, which is what a
 * Stalwart administrator account without an API key has.
 */
internal data class AdminCredential(val server: String, val user: String, val secret: String) {
    val header: String
        get() = if (user.isBlank()) "Bearer ${secret.trim()}"
        else "Basic " + Base64.getEncoder().encodeToString("${user.trim()}:$secret".toByteArray(Charsets.UTF_8))

    /** Never the secret, in case this ends up in a log or a bug report. */
    override fun toString(): String = "AdminCredential(server=$server, user=$user)"
}

/**
 * Where the admin login is kept: the server and user name in their own small file, the
 * secret in the operating system's credential store under its own name.
 *
 * Its own file and its own store entry, never the mail account's, because the point of two
 * credentials is that nothing reading one can find the other.
 */
internal object AdminLogin {
    private val store = JsonStore("admin.json")
    private const val SECRET = "stalwart-admin"

    fun load(): AdminCredential? {
        val o = store.read()
        val server = (o["server"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (server.isBlank()) return null
        val secret = Secrets.loadNamed(SECRET) ?: return null
        return AdminCredential(server, (o["user"] as? JsonPrimitive)?.contentOrNull.orEmpty(), secret)
    }

    /** The server and user as saved, for filling the form in again. Never the secret. */
    fun saved(): Pair<String, String> {
        val o = store.read()
        return ((o["server"] as? JsonPrimitive)?.contentOrNull.orEmpty()) to
            ((o["user"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    }

    /** Null when all of it was kept, otherwise why the secret was not. */
    fun save(credential: AdminCredential): String? {
        store.write {
            put("server", JsonPrimitive(credential.server.trim()))
            put("user", JsonPrimitive(credential.user.trim()))
        }
        return Secrets.storeNamed(SECRET, credential.secret)
    }

    fun forget() {
        store.write { remove("server"); remove("user") }
        Secrets.storeNamed(SECRET, "")
    }
}

/** One page of a list: the rows as the server sent them and how many there are in all. */
internal data class AdminPage(val rows: List<JsonObject>, val total: Int?)

/**
 * The server's verdict on a save. [problems] is keyed by field name where the server said
 * which field it objected to, which is how a refusal lands beside the right input box.
 */
internal data class AdminSaved(val id: String?, val refusal: String?, val problems: Map<String, String>)

/**
 * Talks to Stalwart's management interface with the admin credential and nothing else.
 *
 * Its own HTTP client and its own session, never the mail [Jmap] object's, so there is no
 * code path along which the mail side and the admin side share a credential.
 */
internal class AdminClient private constructor(
    private val credential: AdminCredential,
    /** Scheme, host and port, for `/api/account` and `/api/schema`. */
    val base: URI,
    private val apiUrl: URI,
    private val accountId: String,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private val http: HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build()

        /** Signs in. Throws [AdminError] with one sentence on any failure, network ones included. */
        fun connect(credential: AdminCredential): AdminClient = try {
            open(credential)
        } catch (e: AdminError) {
            throw e
        } catch (e: JmapError) {
            throw AdminError(e.message ?: "The server could not be reached.")
        } catch (e: Exception) {
            throw AdminError(plainNetworkError(e, credential.server.trim()))
        }

        private fun open(credential: AdminCredential): AdminClient {
            if (credential.secret.isBlank()) throw AdminError("There is no admin password or API key to sign in with.")
            var response = get(Jmap.sessionUrl(credential.server), credential)
            var hops = 0
            while (response.statusCode() in 300..399) {
                if (hops++ >= 3) throw AdminError("The server kept redirecting and never sent a session.")
                val next = followable(response) ?: throw AdminError("The server redirected the admin login somewhere else, and Rampart does not send an admin credential to another host.")
                response = get(next, credential)
            }
            refuseStatus(response.statusCode(), "the admin login")
            val session = runCatching { json.parseToJsonElement(response.body()) as JsonObject }.getOrNull()
                ?: throw AdminError("The server's session was not readable JSON.")
            val capabilities = (session["capabilities"] as? JsonObject)?.keys.orEmpty()
            if (STALWART_JMAP !in capabilities) {
                throw AdminError("This server does not offer Stalwart's management interface, so there is nothing to administer here.")
            }
            val primary = session["primaryAccounts"] as? JsonObject
            val account = primary?.string(STALWART_JMAP)
                ?: primary?.string(JMAP_MAIL)
                ?: (session["accounts"] as? JsonObject)?.keys?.firstOrNull()
                ?: throw AdminError("This login has no account on the server to act as.")
            val api = session.string("apiUrl") ?: throw AdminError("The server's session did not say where to send requests.")
            val sessionUri = response.uri()
            val apiUri = sessionUri.resolve(api)
            if (!sameOrigin(sessionUri, apiUri)) {
                throw AdminError("The server's session points requests at another host, and Rampart does not send an admin credential there.")
            }
            return AdminClient(credential, URI(sessionUri.scheme, sessionUri.authority, null, null, null), apiUri, account)
        }

        private fun get(url: URI, credential: AdminCredential): HttpResponse<String> = http.send(
            HttpRequest.newBuilder(url)
                .header("Authorization", credential.header)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        /** A redirect's target, only when it stays on the same scheme, host and port. */
        private fun followable(response: HttpResponse<*>): URI? {
            val location = response.headers().firstValue("location").orElse("")
            if (location.isBlank()) return null
            val next = response.uri().resolve(location)
            return next.takeIf { sameOrigin(response.uri(), it) }
        }
    }

    /** What this credential may do, from `GET /api/account`. */
    fun access(): AdminAccess = guarded {
        val response = http.send(request(base.resolve("/api/account")).GET().build(), HttpResponse.BodyHandlers.ofString())
        refuseStatus(response.statusCode(), "its permissions")
        AdminAccess.parse(response.body())
    }

    /**
     * The schema, from the cache when the server's copy has not changed.
     *
     * `/api/schema` answers with a redirect to `/api/schema/<hash>`, and the document at
     * that address never changes, which the server says by marking it immutable. So the
     * hash is the cache key: one small request per sign-in, and the near-megabyte document
     * itself only when the server has been upgraded. It arrives gzipped whatever the
     * request asked for, and Java's HTTP client does not undo that on its own.
     */
    fun schema(cacheDir: Path = Accounts.file().resolveSibling("admin-schema")): AdminSchema = guarded {
        val first = http.send(request(base.resolve("/api/schema")).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        if (first.statusCode() == 200) return@guarded AdminSchema.parse(gunzipIfNeeded(first.body()))
        if (first.statusCode() !in 300..399) refuseStatus(first.statusCode(), "its schema")
        val target = followable(first) ?: throw AdminError("The server sent its schema somewhere Rampart will not follow with an admin credential.")
        val hash = schemaHash(target.path)
        val cached = hash?.let { cacheDir.resolve("$it.json") }
        if (cached != null && cached.exists()) {
            runCatching { return@guarded AdminSchema.parse(cached.readText()) }
        }
        val response = http.send(request(target).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        refuseStatus(response.statusCode(), "its schema")
        val text = gunzipIfNeeded(response.body())
        val schema = AdminSchema.parse(text)
        if (cached != null) {
            // A cache that cannot be written costs the next start a download, nothing more.
            runCatching {
                cacheDir.createDirectories()
                val temp = Files.createTempFile(cacheDir, "schema.", ".new")
                temp.writeText(text)
                Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        }
        schema
    }

    /**
     * One page of a list, in one round trip: the query finds the ids and the get reads
     * them by back reference, so the ids never pass through us.
     */
    fun page(obj: AdminObject, list: AdminList?, properties: List<String>, position: Int, limit: Int): AdminPage {
        val responses = call(
            invocation("${obj.objectName}/query", "q") {
                val filter = list?.staticFilter.orEmpty()
                if (filter.isNotEmpty()) put("filter", JsonObject(filter))
                put("position", position)
                put("limit", limit)
                put("calculateTotal", true)
            },
            invocation("${obj.objectName}/get", "g") {
                putJsonObject("#ids") {
                    put("resultOf", "q")
                    put("name", "${obj.objectName}/query")
                    put("path", "/ids")
                }
                putJsonArray("properties") { (listOf("id", "@type") + properties).distinct().forEach { add(JsonPrimitive(it)) } }
            },
        )
        val total = (responses[0].second["total"] as? JsonPrimitive)?.intOrNull
        val rows = (responses[1].second["list"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return AdminPage(rows, total)
    }

    /** One object, every property. Throws when the server says it is not there. */
    fun one(obj: AdminObject, id: String): JsonObject {
        val response = call(invocation("${obj.objectName}/get", "g") {
            putJsonArray("ids") { add(JsonPrimitive(id)) }
        })[0].second
        return (response["list"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw AdminError("The server no longer has that ${obj.objectName.removePrefix("x:").lowercase()}.")
    }

    /** Sends [changes] as an update to [id], or as a create when [id] is null. */
    fun save(obj: AdminObject, id: String?, changes: JsonObject): AdminSaved {
        val key = id ?: "new"
        val response = call(invocation("${obj.objectName}/set", "s") {
            if (id == null) putJsonObject("create") { put(key, changes) }
            else putJsonObject("update") { put(key, changes) }
        })[0].second
        return readSetResult(response, key, creating = id == null)
    }

    private fun invocation(method: String, tag: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Triple<String, JsonObject, String> =
        Triple(method, buildJsonObject { put("accountId", accountId); body() }, tag)

    private fun call(vararg invocations: Triple<String, JsonObject, String>): List<Pair<String, JsonObject>> {
        invocations.forEach { (method, args) -> adminMethodRefusal(method, args)?.let { throw AdminError(it) } }
        val first = invocations.first().first
        return guarded {
            val body = adminRequestBody(invocations.toList())
            val response = http.send(
                request(apiUrl)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(60))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            refuseStatus(response.statusCode(), first)
            readMethodResponses(response.body())
        }
    }

    private fun request(url: URI): HttpRequest.Builder = HttpRequest.newBuilder(url)
        .header("Authorization", credential.header)
        .header("Accept", "application/json")
        .timeout(Duration.ofSeconds(30))

    /** Every failure on the way out becomes one sentence, whatever threw it. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: AdminError) {
        throw e
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        throw AdminError(plainNetworkError(e, base.host ?: "the server"))
    }
}

/** The JMAP request for these invocations, always naming the Stalwart capability and nothing the admin side does not need. */
internal fun adminRequestBody(invocations: List<Triple<String, JsonObject, String>>): JsonObject = buildJsonObject {
    putJsonArray("using") {
        add(JsonPrimitive(JMAP_CORE))
        add(JsonPrimitive(STALWART_JMAP))
    }
    put("methodCalls", buildJsonArray {
        invocations.forEach { (method, args, tag) ->
            add(buildJsonArray { add(JsonPrimitive(method)); add(args); add(JsonPrimitive(tag)) })
        }
    })
}

/** Method responses as name and arguments, with a method-level error turned into a sentence. */
internal fun readMethodResponses(text: String): List<Pair<String, JsonObject>> {
    val tree = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
        ?: throw AdminError("The server's reply was not readable JSON.")
    val responses = tree["methodResponses"] as? JsonArray ?: throw AdminError("The server's reply held no answers.")
    return responses.map { element ->
        val r = element as? JsonArray ?: throw AdminError("The server's reply was not in the shape JMAP uses.")
        val name = (r.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty()
        val args = r.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap())
        if (name == "error") {
            val type = args.string("type") ?: "no reason given"
            val detail = args.string("description")
            throw AdminError(
                when (type) {
                    "forbidden" -> "The server refused: this admin login is not allowed to do that."
                    "unknownMethod" -> "The server does not know that request, so it is probably not Stalwart 0.16 or later."
                    "accountNotFound" -> "The server does not recognise the account this admin login acts as."
                    else -> "The server refused the request ($type)" + (detail?.let { ": $it" } ?: ".")
                },
            )
        }
        name to args
    }
}

/**
 * What a `/set` answer says about the one object sent under [key].
 *
 * A refusal comes back as a SetError with a type, perhaps a description, and for
 * `invalidProperties` the names of the fields at fault. Those names are what put the
 * server's objection next to the right box rather than at the top of the page.
 */
internal fun readSetResult(response: JsonObject, key: String, creating: Boolean): AdminSaved {
    val done = response[if (creating) "created" else "updated"] as? JsonObject
    if (done != null && key in done) {
        val id = if (creating) ((done[key] as? JsonObject)?.string("id")) else key
        return AdminSaved(id, null, emptyMap())
    }
    val failed = (response[if (creating) "notCreated" else "notUpdated"] as? JsonObject)?.get(key) as? JsonObject
        ?: return AdminSaved(null, "The server did not say whether it saved that.", emptyMap())
    val type = failed.string("type") ?: "unknown"
    val description = failed.string("description")
    val fields = (failed["properties"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        // A pointer into a nested value names its top-level field first, which is the box to mark.
        .map { it.removePrefix("/").substringBefore('/') }
    val sentence = when (type) {
        "invalidProperties" -> description?.let { "The server refused a value: $it" }
            ?: "The server refused ${if (fields.isEmpty()) "a value" else fields.joinToString(", ")}."
        "forbidden" -> "This admin login is not allowed to make that change."
        "notFound" -> "The server no longer has this object."
        "alreadyExists" -> "The server already has one of these with that name."
        else -> "The server refused the change ($type)" + (description?.let { ": $it" } ?: ".")
    }.let { if (it.endsWith(".")) it else "$it." }
    return AdminSaved(null, sentence, fields.associateWith { description ?: "The server refused this value." })
}

/**
 * The hash out of `/api/schema/<hash>`, or null for any other path. It becomes a file name
 * in the cache, so it is letters and digits or it is nothing.
 */
internal fun schemaHash(path: String?): String? =
    path?.let { Regex("^/api/schema/([A-Za-z0-9]{1,128})/?$").matchEntire(it) }?.groupValues?.get(1)

internal fun gunzipIfNeeded(bytes: ByteArray): String {
    val gzipped = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    val plain = if (gzipped) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
    return String(plain, Charsets.UTF_8)
}

/** One sentence per status that means something other than "try again". */
private fun refuseStatus(status: Int, what: String) {
    when (status) {
        200 -> return
        401 -> throw AdminError("The server did not accept the admin login.")
        403 -> throw AdminError("The server accepted the admin login but will not show it $what.")
        404 -> throw AdminError("The server has no $what to give, so it is probably not Stalwart 0.16 or later.")
        in 500..599 -> throw AdminError("The server failed while answering for $what (HTTP $status).")
        else -> throw AdminError("The server answered HTTP $status when asked for $what.")
    }
}

private fun sameOrigin(a: URI, b: URI): Boolean =
    a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) && a.port == b.port

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
