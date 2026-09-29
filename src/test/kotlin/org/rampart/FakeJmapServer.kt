package org.rampart

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A JMAP server small enough to read, on this machine, for counting what the client asks.
 *
 * It answers the methods Rampart's sync uses with made up but correctly shaped data, and
 * resolves back references the way RFC 8620 section 3.7 describes, so a batched request is
 * answered exactly as a real server would answer it. Every HTTP request is a round trip and
 * is counted; [latencyMs] is added to each one so that doing things one after another costs
 * what it costs on a real network, and doing them side by side does not.
 *
 * A mailbox of [inboxSize] messages, three to a thread. Message `m<n>` carries [images]
 * inline pictures, each the same real PNG ([picture]), the way a newsletter does.
 */
internal class FakeJmapServer(
    private val latencyMs: Long = 0,
    private val inboxSize: Int = 50_000,
    private val images: Int = 3,
    private val bodyChars: Int = 60_000,
) : AutoCloseable {
    private val server = run {
        // Without this the JDK's little server waits on TCP's small-packet delay whenever
        // several replies are in flight, adding 40 ms to requests made side by side. That is
        // the test server's own quirk and not the client's, and it hid exactly the gain
        // these tests exist to show.
        System.setProperty("sun.net.httpserver.nodelay", "true")
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    }

    /** HTTP requests of any kind: API calls and blob downloads. */
    val trips = AtomicInteger()

    /** API requests only, the ones a batch can fold together. */
    val apiTrips = AtomicInteger()

    /** Method calls inside those requests, so a batch still shows what it asked for. */
    val methods = AtomicInteger()

    /** Blob downloads. */
    val downloads = AtomicInteger()

    val state = "s1"

    /**
     * A real PNG, so decoding it costs what decoding a newsletter's picture costs. A photo
     * shaped picture, 600 by 300, with enough noise that it does not compress to nothing.
     */
    val picture: ByteArray = run {
        val image = java.awt.image.BufferedImage(600, 300, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val random = java.util.Random(44)
        for (y in 0 until 300) for (x in 0 until 600) {
            image.setRGB(x, y, ((x * 255 / 600) shl 16) or ((y * 255 / 300) shl 8) or random.nextInt(64))
        }
        java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
    }

    init {
        server.executor = Executors.newFixedThreadPool(16)
        server.createContext("/api") { exchange -> answer(exchange) { api(it) } }
        server.createContext("/download") { exchange -> answer(exchange) { download(exchange) } }
        server.start()
    }

    private val base get() = "http://127.0.0.1:${server.address.port}"

    fun client(): Jmap = Jmap.forLocalServer(
        apiUrl = "$base/api",
        downloadUrl = "$base/download/{accountId}/{blobId}?type={type}&name={name}",
    )

    fun reset() {
        trips.set(0); apiTrips.set(0); methods.set(0); downloads.set(0)
    }

    override fun close() = server.stop(0)

    private fun answer(exchange: HttpExchange, body: (String) -> ByteArray) {
        trips.incrementAndGet()
        if (latencyMs > 0) Thread.sleep(latencyMs)
        val request = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
        val bytes = runCatching { body(request) }.getOrElse { it.printStackTrace(); ByteArray(0) }
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun download(exchange: HttpExchange): ByteArray {
        downloads.incrementAndGet()
        val blob = exchange.requestURI.path.substringAfterLast('/')
        return when {
            blob.startsWith("img") -> picture
            else -> "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n".toByteArray()
        }
    }

    private fun api(request: String): ByteArray {
        apiTrips.incrementAndGet()
        val calls = Json.parseToJsonElement(request).jsonObject["methodCalls"]!!.jsonArray
        val answered = mutableMapOf<String, Pair<String, JsonObject>>()
        val out = buildJsonArray {
            calls.forEach { element ->
                methods.incrementAndGet()
                val call = element.jsonArray
                val name = call[0].jsonPrimitive.content
                val args = resolve(call[1].jsonObject, answered)
                val tag = call[2].jsonPrimitive.content
                val result = method(name, args)
                answered[tag] = name to result
                add(buildJsonArray { add(name); add(result); add(tag) })
            }
        }
        return buildJsonObject { put("methodResponses", out); put("sessionState", "x") }.toString().toByteArray()
    }

    /** Turns every `#name` argument into the value the earlier call's result holds at that path. */
    private fun resolve(args: JsonObject, answered: Map<String, Pair<String, JsonObject>>): JsonObject =
        JsonObject(
            args.entries.associate { (key, value) ->
                if (!key.startsWith("#")) return@associate key to value
                val ref = value.jsonObject
                val from = answered[ref["resultOf"]!!.jsonPrimitive.content]!!.second
                key.removePrefix("#") to JsonArray(pointer(from, ref["path"]!!.jsonPrimitive.content.trim('/').split('/')))
            },
        )

    private fun pointer(node: JsonElement, path: List<String>): List<JsonElement> {
        if (path.isEmpty()) return if (node is JsonArray) node.toList() else listOf(node)
        val head = path.first()
        return when {
            head == "*" -> (node as JsonArray).flatMap { pointer(it, path.drop(1)) }
            node is JsonObject -> pointer(node[head] ?: JsonNull, path.drop(1))
            else -> emptyList()
        }
    }

    private fun ids(args: JsonObject): List<String>? =
        (args["ids"] as? JsonArray)?.map { it.jsonPrimitive.content }

    private fun method(name: String, args: JsonObject): JsonObject = when (name) {
        "Mailbox/get" -> buildJsonObject {
            put("state", "mb1")
            putJsonArray("list") {
                listOf("inbox" to "Inbox", "archive" to "Archive", "sent" to "Sent", "drafts" to "Drafts", "junk" to "Junk", "trash" to "Trash")
                    .forEachIndexed { i, (role, title) ->
                        add(buildJsonObject {
                            put("id", "box$i"); put("name", title); put("role", role)
                            put("unreadEmails", if (i == 0) 12 else 0); put("totalEmails", if (i == 0) inboxSize else 100)
                        })
                    }
                repeat(20) { i -> add(buildJsonObject { put("id", "folder$i"); put("name", "Project $i"); put("totalEmails", 50) }) }
            }
        }
        "Identity/get" -> buildJsonObject {
            put("state", "i1")
            putJsonArray("list") { add(buildJsonObject { put("id", "id1"); put("name", "Dana"); put("email", "dana@example.org") }) }
        }
        "Email/query" -> {
            val position = (args["position"] as? JsonPrimitive)?.contentOrNull?.toInt() ?: 0
            val limit = (args["limit"] as? JsonPrimitive)?.contentOrNull?.toInt() ?: 100
            buildJsonObject {
                put("queryState", "q1")
                put("total", inboxSize)
                put("position", position)
                putJsonArray("ids") { (position until minOf(inboxSize, position + limit)).forEach { add("m$it") } }
            }
        }
        "Email/get" -> buildJsonObject {
            put("state", state)
            val wanted = ids(args).orEmpty()
            val properties = (args["properties"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()
            putJsonArray("list") { wanted.forEach { add(email(it, properties)) } }
        }
        "Thread/get" -> buildJsonObject {
            put("state", "t1")
            putJsonArray("list") {
                ids(args).orEmpty().distinct().forEach { thread ->
                    val first = thread.removePrefix("t").toInt() * 3
                    add(buildJsonObject {
                        put("id", thread)
                        putJsonArray("emailIds") { (first until first + 3).forEach { add("m$it") } }
                    })
                }
            }
        }
        "AddressBook/get" -> buildJsonObject {
            put("state", "ab1")
            putJsonArray("list") { add(buildJsonObject { put("id", "book1"); put("name", "Contacts"); put("isDefault", true) }) }
        }
        "ContactCard/get" -> buildJsonObject {
            put("state", "cc1")
            putJsonArray("list") {
                repeat(200) { i ->
                    add(buildJsonObject {
                        put("id", "card$i")
                        putJsonObject("addressBookIds") { put("book1", true) }
                        putJsonObject("name") { put("full", "Person $i") }
                        putJsonObject("emails") { putJsonObject("e") { put("address", "person$i@example.org") } }
                    })
                }
            }
        }
        else -> buildJsonObject { put("state", "x"); putJsonArray("list") {} }
    }

    private fun email(id: String, properties: Set<String>?): JsonObject = buildJsonObject {
        val n = id.removePrefix("m").toIntOrNull() ?: 0
        put("id", id)
        fun wants(p: String) = properties == null || p in properties
        if (wants("threadId")) put("threadId", "t${n / 3}")
        if (wants("blobId")) put("blobId", "raw$n")
        if (wants("size")) put("size", 20_000 + n % 5_000)
        if (wants("subject")) put("subject", "Weekly update number $n")
        if (wants("from")) putJsonArray("from") { add(buildJsonObject { put("name", "Sender ${n % 200}"); put("email", "sender${n % 200}@example.org") }) }
        if (wants("receivedAt")) put("receivedAt", "2026-09-29T12:00:00Z")
        if (wants("preview")) put("preview", "A preview of message $n, which is about the weekly update.")
        if (wants("keywords")) putJsonObject("keywords") { if (n % 3 != 0) put("\$seen", true) }
        if (wants("mailboxIds")) putJsonObject("mailboxIds") { put("box0", true) }
        if (properties != null && "htmlBody" in properties) {
            putJsonArray("htmlBody") { add(buildJsonObject { put("partId", "1"); put("type", "text/html"); put("blobId", "html$n") }) }
            putJsonArray("textBody") { add(buildJsonObject { put("partId", "2"); put("type", "text/plain"); put("blobId", "text$n") }) }
            putJsonObject("bodyValues") {
                putJsonObject("1") { put("value", html(n)); put("isTruncated", false) }
                putJsonObject("2") { put("value", "Plain text of message $n."); put("isTruncated", false) }
            }
            putJsonArray("attachments") {
                repeat(images) { i ->
                    add(buildJsonObject {
                        put("blobId", "img$n-$i"); put("type", "image/png"); put("name", "picture$i.png")
                        put("size", picture.size); put("cid", "pic$i@example.org"); put("disposition", "inline")
                    })
                }
            }
            putJsonArray("messageId") { add("m$n@example.org") }
            put("sentAt", "2026-09-29T12:00:00Z")
        }
    }

    /** A newsletter-shaped page: nested tables, inline styles, a few cid pictures. */
    private fun html(n: Int): String = buildString {
        append("<html><head><style>td{font-family:sans-serif}</style></head><body><table width=\"600\">")
        repeat(images) { i -> append("<tr><td><img src=\"cid:pic$i@example.org\" width=\"600\"></td></tr>") }
        var row = 0
        while (length < bodyChars) {
            append("<tr><td style=\"padding:8px;color:#333\"><p>Paragraph $row of message $n. ")
            append("Some words about the weekly update, with <a href=\"https://example.org/$row\">a link</a>.</p></td></tr>")
            row++
        }
        append("</table></body></html>")
    }
}
