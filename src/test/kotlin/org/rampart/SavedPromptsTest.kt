package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedPromptsTest {
    private val lead = SavedPrompt("Reply to a lead", "Thank them, say we can help, and offer a call this week.")
    private val decline = SavedPrompt("Decline politely", "Say no kindly, with one sentence of why, and leave the door open.")

    @Test
    fun `a list survives being written and read back`() {
        val text = SavedPrompts.encode(listOf(lead, decline))
        assertEquals(listOf(lead, decline), SavedPrompts.decode(text))
        assertTrue(text.contains("\"version\": 1"))
    }

    @Test
    fun `text with quotes, markers and new lines round trips unchanged`() {
        val odd = SavedPrompt("Odd \"one\"", "Line one\nLine \"two\" with <<<MESSAGE and a \\ backslash")
        assertEquals(listOf(odd), SavedPrompts.decode(SavedPrompts.encode(listOf(odd))))
    }

    @Test
    fun `a broken or foreign file reads as an empty list`() {
        assertEquals(emptyList(), SavedPrompts.decode(""))
        assertEquals(emptyList(), SavedPrompts.decode("not json"))
        assertEquals(emptyList(), SavedPrompts.decode("""{"prompts":"nope"}"""))
        assertEquals(emptyList(), SavedPrompts.decode("[1,2,3]"))
    }

    @Test
    fun `bad entries are dropped, long ones cut, repeats kept once`() {
        val long = "x".repeat(SavedPrompts.TEXT_MAX + 50)
        val text = """{"prompts":[
            {"name":"Good","text":"Do it."},
            {"name":"good","text":"A repeat by another case."},
            {"name":"","text":"No name."},
            {"name":"No text","text":"   "},
            {"name":7,"text":"Not a string."},
            {"name":"Long","text":"$long"}
        ]}"""
        val read = SavedPrompts.decode(text)
        assertEquals(listOf("Good", "Long"), read.map { it.name })
        assertEquals(SavedPrompts.TEXT_MAX, read[1].text.length)
    }

    @Test
    fun `saving under a name that exists replaces it, and remove takes it out`() {
        val list = SavedPrompts.upsert(listOf(lead), SavedPrompt("REPLY TO A LEAD", "Shorter."))
        assertEquals(1, list.size)
        assertEquals("Shorter.", list[0].text)
        assertEquals(listOf(lead, decline), SavedPrompts.upsert(listOf(lead), decline))
        assertEquals(listOf(decline), SavedPrompts.remove(listOf(lead, decline), "reply to a lead"))
    }

    @Test
    fun `the save check says what is wrong`() {
        assertNotNull(SavedPrompts.problem(" ", "text", emptyList()))
        assertNotNull(SavedPrompts.problem("name", " ", emptyList()))
        val full = (1..SavedPrompts.MOST).map { SavedPrompt("p$it", "t") }
        assertNotNull(SavedPrompts.problem("new", "t", full))
        assertNull(SavedPrompts.problem("p3", "t", full))
    }

    @Test
    fun `the list lives in Files as Rampart saved-prompts json, made on the first save`() {
        val server = FakePromptFiles()
        val shelf = FilesPromptShelf(server)
        assertEquals(emptyList(), shelf.load())
        assertTrue(server.nodes.isEmpty(), "a read must not make anything")

        shelf.save(listOf(lead))
        val folder = server.nodes.values.single { it.isFolder }
        assertEquals("Rampart", folder.name)
        assertNull(folder.parentId)
        val file = server.nodes.values.single { !it.isFolder }
        assertEquals("saved-prompts.json", file.name)
        assertEquals(folder.id, file.parentId)
        assertEquals(listOf(lead), shelf.load())

        // A second save gives the same node new contents rather than making a copy beside it.
        shelf.save(listOf(lead, decline))
        assertEquals(2, server.nodes.size)
        assertEquals(file.id, server.nodes.values.single { !it.isFolder }.id)
        assertEquals(listOf(lead, decline), FilesPromptShelf(server).load())
    }

    @Test
    fun `the local fallback keeps each account's list apart`() {
        val store = mutableMapOf<String, JsonElement>()
        fun shelf(account: String) = LocalPromptShelf(account, { JsonObject(store.toMap()) }, { change -> store.change() })
        shelf("a").save(listOf(lead))
        shelf("b").save(listOf(decline))
        assertEquals(listOf(lead), shelf("a").load())
        assertEquals(listOf(decline), shelf("b").load())
        assertEquals(PromptPlace.LOCAL, shelf("a").place)
        assertEquals(emptyList(), shelf("c").load())
    }
}

/**
 * Files as Stalwart keeps them, in memory: FileNode/query and get by back reference,
 * FileNode/set create and update, upload and download. Enough of the server for the shelf
 * to be run end to end.
 */
private class FakePromptFiles : FilesTransport {
    val nodes = LinkedHashMap<String, FileNode>()
    private val blobs = HashMap<String, ByteArray>()
    private var next = 0

    override val accountId = "acc"
    override val canSliceBlobs = false

    override fun fileCall(vararg invocations: JsonArray): List<JsonArray> = invocations.map { call ->
        val name = call[0].jsonPrimitive.content
        val args = call[1].jsonObject
        val id = call[2].jsonPrimitive.content
        when (name) {
            "FileNode/query" -> reply(name, id) {
                putJsonArray("ids") { nodes.keys.forEach { add(it) } }
                put("total", nodes.size)
            }
            "FileNode/get" -> reply(name, id) {
                putJsonArray("list") {
                    nodes.values.forEach { n ->
                        add(
                            buildJsonObject {
                                put("id", n.id)
                                put("parentId", n.parentId?.let(::JsonPrimitive) ?: JsonNull)
                                put("name", n.name)
                                put("nodeType", if (n.isFolder) "directory" else "file")
                                put("blobId", n.blobId?.let(::JsonPrimitive) ?: JsonNull)
                                put("size", n.size)
                            },
                        )
                    }
                }
            }
            "FileNode/set" -> reply(name, id) {
                args["create"]?.jsonObject?.let { create ->
                    putJsonObject("created") {
                        create.forEach { (key, value) ->
                            val o = value.jsonObject
                            val made = "n${next++}"
                            val blob = (o["blobId"] as? JsonPrimitive)?.contentOrNull
                            nodes[made] = FileNode(
                                id = made,
                                parentId = (o["parentId"] as? JsonPrimitive)?.contentOrNull,
                                name = o["name"]!!.jsonPrimitive.content,
                                isFolder = blob == null,
                                blobId = blob,
                                size = blob?.let { blobs[it]?.size?.toLong() } ?: 0L,
                            )
                            putJsonObject(key) { put("id", made) }
                        }
                    }
                }
                args["update"]?.jsonObject?.let { update ->
                    putJsonObject("updated") {
                        update.forEach { (node, patch) ->
                            val blob = patch.jsonObject["blobId"]!!.jsonPrimitive.content
                            nodes[node] = nodes.getValue(node).copy(blobId = blob, size = blobs.getValue(blob).size.toLong())
                            put(node, JsonNull)
                        }
                    }
                }
            }
            else -> error("unexpected $name")
        }
    }

    override fun blobCall(vararg invocations: JsonArray): List<JsonArray> = error("no blob call expected")

    override fun upload(file: Path): Attachment {
        val blob = "b${next++}"
        blobs[blob] = Files.readAllBytes(file)
        return Attachment(blob, file.fileName.toString(), "application/json", blobs.getValue(blob).size.toLong())
    }

    override fun download(attachment: Attachment, into: Path): Path =
        into.resolve(attachment.name).also { Files.write(it, blobs.getValue(attachment.blobId)) }

    private fun reply(name: String, id: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonArray =
        buildJsonArray {
            add(name)
            add(buildJsonObject(body))
            add(id)
        }
}
