package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsSyncTest {
    private var previous: String? = null
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        previous = System.getProperty("rampart.config.dir")
        dir = Files.createTempDirectory("rampart-sync-test")
        System.setProperty("rampart.config.dir", dir.toString())
    }

    @AfterTest
    fun tearDown() {
        if (previous == null) System.clearProperty("rampart.config.dir")
        else System.setProperty("rampart.config.dir", previous!!)
        dir.toFile().deleteRecursively()
    }

    private val allow = mapOf(
        "theme" to SyncShape.TEXT,
        "density" to SyncShape.TEXT,
        "savedSearches" to SyncShape.LIST_OF_OBJECTS,
        "tagColours" to SyncShape.MAP_OF_TEXT,
    )

    /** One computer: its own settings file, stamped the way Settings.write stamps. */
    private inner class Computer(name: String, var clock: Long) {
        val store = JsonStore("$name-settings.json")
        val source = SyncSource("settings", allow, store)
        val syncer = SettingsSyncer(listOf(source)) { clock }

        fun set(key: String, value: JsonElement?) = store.write {
            val before = toMap()
            if (value == null) remove(key) else put(key, value)
            stampChanges(allow, before, this, clock)
        }

        fun get(key: String): JsonElement? = store.read()[key]
    }

    private fun text(value: String) = JsonPrimitive(value)

    // ---- merge rules ----------------------------------------------------------------

    @Test
    fun `newest wins per setting so unrelated changes both survive`() {
        val local = mapOf(
            "settings.theme" to SyncEntry(text("dusk"), 200),
            "settings.density" to SyncEntry(text("compact"), 100),
        )
        val remote = mapOf(
            "settings.theme" to SyncEntry(text("paper"), 150),
            "settings.density" to SyncEntry(text("spacious"), 300),
        )
        val merged = mergeEntries(local, remote)
        assertEquals(text("dusk"), merged["settings.theme"]!!.value)
        assertEquals(text("spacious"), merged["settings.density"]!!.value)
    }

    @Test
    fun `a reset carries its own stamp and beats an older value`() {
        val merged = mergeEntries(
            mapOf("settings.theme" to SyncEntry(null, 500)),
            mapOf("settings.theme" to SyncEntry(text("dusk"), 400)),
        )
        assertNull(merged["settings.theme"]!!.value)
        assertEquals(500L, merged["settings.theme"]!!.updatedAt)
    }

    @Test
    fun `a tie is decided the same way from either side`() {
        val a = SyncEntry(text("dusk"), 100)
        val b = SyncEntry(text("paper"), 100)
        assertEquals(newer(a, b), newer(b, a))
    }

    @Test
    fun `a change made after seeing another beats it even with a slow clock`() {
        val file = mutableMapOf<String, JsonElement>(
            SYNC_STAMPS to buildJsonObject { put("theme", 10_000L) },
            "theme" to text("dusk"),
        )
        val before = file.toMap()
        file["theme"] = text("paper")
        // This computer's clock says 5000, behind the stamp it took in from elsewhere.
        assertTrue(stampChanges(allow, before, file, now = 5_000L))
        assertEquals(10_001L, (file[SYNC_STAMPS] as JsonObject)["theme"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun `a stamp from years ahead is not believed`() {
        val now = 1_000_000L
        val text = encodeSyncDocument(mapOf("settings.theme" to SyncEntry(text("dusk"), now + SYNC_MAX_AHEAD_MS + 1)))
        val read = parseSyncDocument(text, syncedNames(listOf(SyncSource("settings", allow, JsonStore("x.json")))), now)
        assertTrue((read as SyncRead.Read).document.entries.isEmpty())
    }

    @Test
    fun `a local value from before sync existed counts as stamped at zero`() {
        val entries = localEntries("settings", allow, mapOf("theme" to text("dusk")))
        assertEquals(SyncEntry(text("dusk"), 0), entries["settings.theme"])
    }

    // ---- the allowlist --------------------------------------------------------------

    @Test
    fun `keys off the allowlist are never stamped and never leave`() {
        val file = mutableMapOf<String, JsonElement>()
        assertFalse(stampChanges(allow, emptyMap(), file.apply { put("window", text("x")) }, 1))
        val sent = encodeSyncDocument(
            localEntries("settings", allow, mapOf("window" to text("x"), "gotifyServer" to text("y"), "theme" to text("dusk"))),
        )
        assertFalse("window" in sent)
        assertFalse("gotifyServer" in sent)
        assertTrue("settings.theme" in sent)
    }

    @Test
    fun `the real allowlist holds nothing machine specific or secret`() {
        val never = setOf(
            "window", "composeWidth", "composeHeight", "sidePanelWidths", "readingPaneHeight", "sidebarCollapsed",
            "collapsedSections", "notify", "notifyOpen", "closeToTray", "trackingServer",
            "diagnosticsServer", "gotifyServer", "ntfyServer", "phoneAlertProvider",
            "diagnosticsReporting", "trackingCursor", "changelogSeen", "imageSenders",
            // Keyed by account, so syncing it would write each account's address into the others.
            "accountColours",
            SYNC_SWITCH, SYNC_STAMPS,
        )
        assertTrue(never.none { it in SYNCED_SETTINGS }, "A machine-specific key is on the allowlist.")
        assertEquals(setOf("deniedFolders"), SYNCED_ASSISTANT.keys)
    }

    @Test
    fun `an entry from the server off the allowlist is kept aside and never applied`() {
        val computer = Computer("a", 1_000)
        val body = """{"format":1,"settings":{
            "settings.window":{"updatedAt":900,"value":"evil"},
            "settings.theme":{"updatedAt":900,"value":"dusk"}}}"""
        val read = parseSyncDocument(body, syncedNames(listOf(computer.source)), 1_000) as SyncRead.Read
        assertEquals(setOf("settings.window"), read.document.passthrough.keys)
        computer.store.write {
            applyEntries("settings", allow, mergeEntries(localEntries("settings", allow, this), read.document.entries), this)
        }
        assertNull(computer.get("window"))
        assertEquals(text("dusk"), computer.get("theme"))
    }

    @Test
    fun `a value of the wrong shape from the server is dropped on its own`() {
        val body = """{"format":1,"settings":{
            "settings.theme":{"updatedAt":5,"value":["not","text"]},
            "settings.density":{"updatedAt":5,"value":"compact"}}}"""
        val read = parseSyncDocument(body, syncedNames(listOf(SyncSource("settings", allow, JsonStore("x.json")))), 10)
        val entries = (read as SyncRead.Read).document.entries
        assertEquals(setOf("settings.density"), entries.keys)
    }

    // ---- round trip through a server -------------------------------------------------

    @Test
    fun `two computers see each other's changes and neither loses its own`() {
        val server = FakeFiles()
        val a = Computer("a", 1_000)
        val b = Computer("b", 1_000)

        a.set("theme", text("dusk"))
        a.syncer.sync(SettingsFile(server))
        assertEquals(1, server.fileCount())

        b.clock = 2_000
        b.set("density", text("compact"))
        val intoB = b.syncer.sync(SettingsFile(server))
        assertEquals(setOf("settings.theme"), intoB.applied)
        assertEquals(text("dusk"), b.get("theme"))

        a.clock = 3_000
        a.set("theme", null)
        a.syncer.sync(SettingsFile(server))
        assertEquals(text("compact"), a.get("density"))

        b.syncer.sync(SettingsFile(server))
        assertNull(b.get("theme"), "A reset on one computer is a reset on the other.")
        assertEquals(text("compact"), b.get("density"))
    }

    @Test
    fun `nothing is written when the server already has everything`() {
        val server = FakeFiles()
        val a = Computer("a", 1_000)
        a.set("theme", text("dusk"))
        a.syncer.sync(SettingsFile(server))
        val writes = server.writes
        a.syncer.sync(SettingsFile(server))
        assertEquals(writes, server.writes)
    }

    @Test
    fun `a write that loses a race reads again and merges`() {
        val server = FakeFiles()
        val a = Computer("a", 1_000)
        val b = Computer("b", 1_000)
        a.set("theme", text("dusk"))
        a.syncer.sync(SettingsFile(server))

        b.set("density", text("spacious"))
        a.clock = 5_000
        a.set("tagColours", buildJsonObject { put("work", 123L) })
        // Between a's read and a's write, b writes. a must not overwrite b's density.
        server.beforeNextSet = { b.syncer.sync(SettingsFile(server)) }
        a.syncer.sync(SettingsFile(server))

        val c = Computer("c", 9_000)
        c.syncer.sync(SettingsFile(server))
        assertEquals(text("dusk"), c.get("theme"))
        assertEquals(text("spacious"), c.get("density"))
        assertEquals(buildJsonObject { put("work", 123L) }, c.get("tagColours"))
    }

    @Test
    fun `a corrupt file on the server is ignored and replaced, and local settings are kept`() {
        val server = FakeFiles()
        server.seed("{ this is not json")
        val a = Computer("a", 1_000)
        a.set("theme", text("dusk"))
        val result = a.syncer.sync(SettingsFile(server))
        assertTrue(result.note!!.contains("not JSON"))
        assertEquals(text("dusk"), a.get("theme"))
        val read = parseSyncDocument(server.contents()!!, syncedNames(listOf(a.source)), 1_000)
        assertEquals(text("dusk"), (read as SyncRead.Read).document.entries["settings.theme"]!!.value)
    }

    @Test
    fun `a file from a newer Rampart is left alone`() {
        val server = FakeFiles()
        val newer = """{"format":99,"settings":{}}"""
        server.seed(newer)
        val a = Computer("a", 1_000)
        a.set("theme", text("dusk"))
        assertFailsWith<JmapError> { a.syncer.sync(SettingsFile(server)) }
        assertEquals(newer, server.contents())
        assertEquals(text("dusk"), a.get("theme"))
    }

    @Test
    fun `a server that fails leaves local settings exactly as they were`() {
        val a = Computer("a", 1_000)
        a.set("theme", text("dusk"))
        val before = a.store.read()
        val broken = FakeFiles().apply { fail = true }
        assertFailsWith<JmapError> { a.syncer.sync(SettingsFile(broken)) }
        assertEquals(before, a.store.read())
    }

    @Test
    fun `the status line says when, or why not`() {
        val at = java.time.LocalDateTime.of(2026, 9, 29, 15, 42).toInstant(ZoneOffset.UTC).toEpochMilli()
        val line = syncStatusLine(SyncStatus.Synced(at), ZoneOffset.UTC, Locale.US)
        assertEquals("Synced with the server at 3:42 PM.".replace(" ", " "), line.replace(" ", " "))
        assertTrue(syncStatusLine(SyncStatus.LocalOnly("This account is IMAP.")).endsWith("stay in the file on this computer."))
    }
}

/**
 * A small FileNode server: queries by name, parent and top level, gets by back-reference,
 * sets with ifInState and creation references, and an in-memory blob store.
 */
private class FakeFiles : FilesTransport {
    private data class Node(val id: String, val parentId: String?, val name: String, val blobId: String?)

    private val nodes = LinkedHashMap<String, Node>()
    private val blobs = HashMap<String, ByteArray>()
    private var state = 0
    private var next = 0
    var writes = 0
    var fail = false
    var beforeNextSet: (() -> Unit)? = null

    override val accountId = "acc"
    override val canSliceBlobs = false

    fun fileCount() = nodes.values.count { it.blobId != null }

    fun contents(): String? = nodes.values.filter { it.blobId != null }.firstOrNull()?.blobId?.let { String(blobs.getValue(it)) }

    fun seed(text: String) {
        val folder = Node("n${next++}", null, SYNC_FOLDER, null)
        nodes[folder.id] = folder
        val blob = "b${next++}"
        blobs[blob] = text.toByteArray()
        val file = Node("n${next++}", folder.id, SYNC_FILE, blob)
        nodes[file.id] = file
        state++
    }

    override fun fileCall(vararg invocations: JsonArray): List<JsonArray> {
        if (fail) throw JmapError("The server answered HTTP 503 to FileNode/query.")
        if (invocations.any { it[0].jsonPrimitive.content == "FileNode/set" }) {
            beforeNextSet?.let { beforeNextSet = null; it() }
        }
        val results = HashMap<String, List<String>>()
        val created = HashMap<String, String>()
        return invocations.map { call ->
            val name = call[0].jsonPrimitive.content
            val args = call[1].jsonObject
            val id = call[2].jsonPrimitive.content
            when (name) {
                "FileNode/query" -> {
                    val f = args["filter"]?.jsonObject ?: JsonObject(emptyMap())
                    val ids = nodes.values.filter { n ->
                        (f["name"]?.jsonPrimitive?.content?.let { it == n.name } ?: true) &&
                            (f["isTopLevel"]?.jsonPrimitive?.content?.let { (it == "true") == (n.parentId == null) } ?: true)
                    }.map { it.id }
                    results[id] = ids
                    reply(name, id) { putJsonArray("ids") { ids.forEach { add(it) } } }
                }
                "FileNode/get" -> {
                    val from = args["#ids"]!!.jsonObject["resultOf"]!!.jsonPrimitive.content
                    reply(name, id) {
                        put("state", state.toString())
                        putJsonArray("list") {
                            results.getValue(from).map { nodes.getValue(it) }.forEach { n ->
                                addJsonObject {
                                    put("id", n.id)
                                    put("parentId", n.parentId?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put("name", n.name)
                                    put("nodeType", if (n.blobId == null) "directory" else "file")
                                    put("blobId", n.blobId?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put("size", n.blobId?.let { blobs.getValue(it).size.toLong() } ?: 0L)
                                }
                            }
                        }
                    }
                }
                "FileNode/set" -> {
                    args["ifInState"]?.jsonPrimitive?.contentOrNull?.let {
                        if (it != state.toString()) throw JmapError("The server refused the request: stateMismatch")
                    }
                    val notCreated = LinkedHashMap<String, JsonElement>()
                    val createdOut = LinkedHashMap<String, JsonElement>()
                    val notUpdated = LinkedHashMap<String, JsonElement>()
                    val updated = LinkedHashMap<String, JsonElement>()
                    args["create"]?.jsonObject?.forEach { (cid, value) ->
                        val o = value.jsonObject
                        val rawParent = o["parentId"]?.jsonPrimitive?.contentOrNull
                        val parent = rawParent?.let { if (it.startsWith("#")) created[it.drop(1)] else it }
                        val nodeName = o["name"]!!.jsonPrimitive.content
                        if (nodes.values.any { it.parentId == parent && it.name == nodeName }) {
                            notCreated[cid] = buildJsonObject { put("type", "alreadyExists") }
                        } else {
                            val node = Node("n${next++}", parent, nodeName, o["blobId"]?.jsonPrimitive?.contentOrNull)
                            nodes[node.id] = node
                            created[cid] = node.id
                            createdOut[cid] = buildJsonObject { put("id", node.id) }
                            state++
                            writes++
                        }
                    }
                    args["update"]?.jsonObject?.forEach { (nid, value) ->
                        val node = nodes[nid]
                        if (node == null) {
                            notUpdated[nid] = buildJsonObject { put("type", "notFound") }
                        } else {
                            nodes[nid] = node.copy(blobId = value.jsonObject["blobId"]!!.jsonPrimitive.content)
                            updated[nid] = JsonNull
                            state++
                            writes++
                        }
                    }
                    reply(name, id) {
                        put("created", JsonObject(createdOut))
                        put("notCreated", JsonObject(notCreated))
                        put("updated", JsonObject(updated))
                        put("notUpdated", JsonObject(notUpdated))
                    }
                }
                else -> error("unexpected $name")
            }
        }
    }

    private fun reply(name: String, id: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonArray {
            add(name)
            add(buildJsonObject(body))
            add(id)
        }

    override fun blobCall(vararg invocations: JsonArray): List<JsonArray> = error("not used")

    override fun upload(file: Path): Attachment {
        if (fail) throw JmapError("The server would not take ${file.fileName} (HTTP 503).")
        val id = "b${next++}"
        blobs[id] = Files.readAllBytes(file)
        return Attachment(blobId = id, name = file.fileName.toString(), type = "application/json", size = blobs[id]!!.size.toLong())
    }

    override fun download(attachment: Attachment, into: Path): Path {
        val out = into.resolve(attachment.name)
        Files.write(out, blobs.getValue(attachment.blobId))
        return out
    }
}
