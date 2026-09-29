package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The answers here have the shape Stalwart 0.16.24's FileNode/get writes
 * (crates/jmap/src/file/get.rs): a folder is `nodeType` "directory" with a null blobId,
 * size and type, and a top-level node has a null parentId.
 */
private fun arr(text: String): JsonArray = Json.parseToJsonElement(text).jsonArray

private val GET = arr(
    """
    ["FileNode/get", {"accountId": "a", "state": "1", "notFound": [], "list": [
      {"id": "f1", "parentId": null, "name": "Reports", "nodeType": "directory",
       "blobId": null, "size": null, "type": null, "modified": "2026-09-01T10:00:00Z"},
      {"id": "f2", "parentId": "f1", "name": "2026", "nodeType": "directory",
       "blobId": null, "size": null, "type": null, "modified": "2026-09-02T10:00:00Z"},
      {"id": "n1", "parentId": "f2", "name": "q3.pdf", "nodeType": "file",
       "blobId": "Bq3", "size": 2048, "type": "application/pdf", "modified": "2026-09-03T10:00:00Z"},
      {"id": "n2", "parentId": "f1", "name": "notes.txt", "nodeType": "file",
       "blobId": "Bnotes", "size": 12, "type": "text/plain", "modified": "2026-09-04T10:00:00Z"},
      {"id": "n3", "parentId": null, "name": "avatar.png", "nodeType": "file",
       "blobId": "Bav", "size": 300, "type": "image/png", "modified": "2026-09-05T10:00:00Z"},
      {"id": "n4", "parentId": "gone", "name": "Shared thing", "nodeType": "file",
       "blobId": "Bsh", "size": 1, "type": "text/plain", "modified": ""},
      {"parentId": null, "name": "no id"}
    ]}, "g"]
    """,
)

private fun tree() = FileTree(fileNodesIn(GET))

private fun args(call: JsonArray): JsonObject = call[1].jsonObject

class FilesTest {

    // ---- reading ------------------------------------------------------------------

    @Test
    fun `a get answer becomes folders and files, and a node with no id is dropped`() {
        val nodes = fileNodesIn(GET)
        assertEquals(6, nodes.size)
        val reports = nodes.first { it.id == "f1" }
        assertTrue(reports.isFolder)
        assertNull(reports.blobId)
        assertNull(reports.parentId)
        val pdf = nodes.first { it.id == "n1" }
        assertFalse(pdf.isFolder)
        assertEquals("Bq3", pdf.blobId)
        assertEquals(2048L, pdf.size)
        assertEquals("application/pdf", pdf.type)
        assertEquals("f2", pdf.parentId)
    }

    @Test
    fun `a node without nodeType is a folder only when it has no blob`() {
        val nodes = fileNodesIn(
            arr("""["FileNode/get", {"list": [{"id": "x", "name": "a"}, {"id": "y", "name": "b", "blobId": "B"}]}, "g"]"""),
        )
        assertTrue(nodes.first { it.id == "x" }.isFolder)
        assertFalse(nodes.first { it.id == "y" }.isFolder)
    }

    @Test
    fun `the tree puts folders first, nests them, and keeps an orphan at the top`() {
        val t = tree()
        assertEquals(listOf("f1", "n3", "n4"), t.childrenOf(null).map { it.id })
        assertEquals(listOf("f2", "n2"), t.childrenOf("f1").map { it.id })
        assertEquals(listOf("n1"), t.childrenOf("f2").map { it.id })
        assertEquals(listOf("f1", "f2"), t.pathTo("f2").map { it.id })
        assertEquals(listOf("Reports" to 0, "2026" to 1), t.folderOutline().map { it.first.name to it.second })
    }

    @Test
    fun `a folder cannot be moved into itself or anything inside it`() {
        val t = tree()
        val reports = t.byId.getValue("f1")
        assertTrue(t.moveTargets(reports).isEmpty())
        // A file already in Reports is offered 2026 but not Reports.
        val notes = t.byId.getValue("n2")
        assertEquals(listOf("f2"), t.moveTargets(notes).map { it.first.id })
    }

    @Test
    fun `a parent loop from the server does not hang the path`() {
        val t = FileTree(
            listOf(
                FileNode("a", "b", "a", true),
                FileNode("b", "a", "b", true),
            ),
        )
        assertTrue(t.pathTo("a").size <= 256)
        assertTrue(t.descendants("a").size <= 2)
    }

    @Test
    fun `query progress reads the total and the page size`() {
        val q = arr("""["FileNode/query", {"ids": ["a", "b"], "total": 5, "position": 0}, "q"]""")
        assertEquals(5 to 2, queryProgress(q))
    }

    // ---- requests -----------------------------------------------------------------

    @Test
    fun `a page is a query and a get that reads its ids by reference`() {
        val (query, get) = fileNodePage("acc", 250, 250)
        assertEquals("FileNode/query", query[0].jsonPrimitive.content)
        assertEquals(250, args(query)["position"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", args(query)["calculateTotal"]!!.jsonPrimitive.content)
        assertEquals("FileNode/get", get[0].jsonPrimitive.content)
        val ref = args(get)["#ids"]!!.jsonObject
        assertEquals("q", ref["resultOf"]!!.jsonPrimitive.content)
        assertEquals("FileNode/query", ref["name"]!!.jsonPrimitive.content)
        assertEquals("/ids", ref["path"]!!.jsonPrimitive.content)
        assertEquals("acc", args(get)["accountId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a new folder is a create with no blob, and top level is an explicit null parent`() {
        val top = args(createFolderCall("acc", "Reports", null))
        val made = top["create"]!!.jsonObject["n"]!!.jsonObject
        assertEquals("Reports", made["name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, made["parentId"])
        assertFalse("blobId" in made)
        assertFalse("onExists" in top)
        val nested = args(createFolderCall("acc", "2026", "f1"))["create"]!!.jsonObject["n"]!!.jsonObject
        assertEquals("f1", nested["parentId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a new file points at its blob and asks the server to rename on a collision`() {
        val call = createFileCall("acc", "q3.pdf", "f2", "Bnew", "application/pdf")
        assertEquals("FileNode/set", call[0].jsonPrimitive.content)
        assertEquals("rename", args(call)["onExists"]!!.jsonPrimitive.content)
        val made = args(call)["create"]!!.jsonObject["n"]!!.jsonObject
        assertEquals("Bnew", made["blobId"]!!.jsonPrimitive.content)
        assertEquals("application/pdf", made["type"]!!.jsonPrimitive.content)
        assertEquals("f2", made["parentId"]!!.jsonPrimitive.content)
        // A type the server would refuse is left out rather than sinking the upload.
        val odd = args(createFileCall("acc", "x", null, "B", "nonsense"))["create"]!!.jsonObject["n"]!!.jsonObject
        assertFalse("type" in odd)
    }

    @Test
    fun `rename and move are patches of one property`() {
        val rename = args(renameCall("acc", "n2", "todo.txt"))["update"]!!.jsonObject["n2"]!!.jsonObject
        assertEquals(setOf("name"), rename.keys)
        assertEquals("todo.txt", rename["name"]!!.jsonPrimitive.content)
        val move = args(moveCall("acc", "n2", "f2"))["update"]!!.jsonObject["n2"]!!.jsonObject
        assertEquals(setOf("parentId"), move.keys)
        assertEquals("f2", move["parentId"]!!.jsonPrimitive.content)
        val toTop = args(moveCall("acc", "n2", null))["update"]!!.jsonObject["n2"]!!.jsonObject
        assertEquals(JsonNull, toTop["parentId"])
    }

    @Test
    fun `deleting a folder with its contents says so, and a file does not`() {
        val folder = args(destroyCall("acc", "f1", withContents = true))
        assertEquals("true", folder["onDestroyRemoveChildren"]!!.jsonPrimitive.content)
        assertEquals(listOf("f1"), folder["destroy"]!!.jsonArray.map { it.jsonPrimitive.content })
        val file = args(destroyCall("acc", "n1", withContents = false))
        assertFalse("onDestroyRemoveChildren" in file)
    }

    @Test
    fun `an attachment is copied by slicing its blob on the server`() {
        val call = sliceBlobCall("acc", "Bpart", "application/pdf")
        assertEquals("Blob/upload", call[0].jsonPrimitive.content)
        val made = args(call)["create"]!!.jsonObject["b"]!!.jsonObject
        assertEquals("Bpart", made["data"]!!.jsonArray[0].jsonObject["blobId"]!!.jsonPrimitive.content)
        assertEquals("application/pdf", made["type"]!!.jsonPrimitive.content)
    }

    // ---- refusals -----------------------------------------------------------------

    @Test
    fun `a refused create reads as one sentence with the server's own reason`() {
        val answer = arr(
            """["FileNode/set", {"notCreated": {"n": {"type": "invalidProperties",
               "description": "Name contains a forbidden character.", "properties": ["name"]}}}, "s"]""",
        )
        assertEquals(
            "The server would not accept the folder a:b: Name contains a forbidden character.",
            setRefusal(answer, "notCreated", "n", "the folder a:b"),
        )
    }

    @Test
    fun `each set error type has its own sentence`() {
        fun say(type: String) = setErrorSentence(JsonObject(mapOf("type" to JsonPrimitive(type))), "the folder Reports")
        assertEquals("Something with that name is already in that folder.", say("alreadyExists"))
        assertEquals("The server kept the folder Reports because it is not empty.", say("nodeHasChildren"))
        assertEquals("The folder Reports is no longer on the server.", say("notFound"))
        assertEquals("This account is not allowed to change the folder Reports.", say("forbidden"))
        assertEquals("The server refused to change the folder Reports.", say("somethingNew"))
    }

    @Test
    fun `success is null, and silence is not success`() {
        val created = arr("""["FileNode/set", {"created": {"n": {"id": "x"}}}, "s"]""")
        assertNull(setRefusal(created, "notCreated", "n", "it"))
        assertEquals("x", createdId(created))
        val destroyed = arr("""["FileNode/set", {"destroyed": ["f1", "f2", "n1"]}, "s"]""")
        assertNull(setRefusal(destroyed, "notDestroyed", "f1", "it"))
        val silent = arr("""["FileNode/set", {"updated": {}}, "s"]""")
        assertEquals("The server did not say whether it changed it.", setRefusal(silent, "notUpdated", "n2", "it"))
    }

    // ---- names ---------------------------------------------------------------------

    @Test
    fun `a local name the server would refuse is made acceptable`() {
        assertEquals("a_b_c.txt", serverNodeName("a:b?c.txt"))
        assertEquals("_con", serverNodeName("con"))
        assertEquals("file", serverNodeName("  "))
        assertEquals("_..", serverNodeName(".."))
        val long = serverNodeName("\u00e9".repeat(300))
        assertTrue(long.encodeToByteArray().size <= 255)
    }

    @Test
    fun `a typed name is checked before it is sent`() {
        assertNull(nameProblem("Reports 2026"))
        assertEquals("A name is needed.", nameProblem("   "))
        assertTrue(nameProblem("a/b") != null)
        assertTrue(nameProblem("NUL") != null)
    }

    @Test
    fun `a hostile name from the server lands inside the chosen folder`() {
        val dir = Files.createTempDirectory("files-test")
        try {
            for (hostile in listOf("../../escape.txt", "/etc/passwd", "..\\..\\win.ini", "..", "C:\\x\\y.txt", "a/../../b")) {
                val landed = uniqueIn(dir, hostile)
                requireInside(dir, landed)
                assertEquals(dir.toAbsolutePath().normalize(), landed.toAbsolutePath().normalize().parent, hostile)
            }
            assertFailsWith<JmapError> { requireInside(dir, dir.resolve("../outside.txt")) }
            assertFailsWith<JmapError> { requireInside(dir, dir) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a download that comes back outside the folder is deleted and refused`() {
        val dir = Files.createTempDirectory("files-test")
        val outside = Files.createTempFile("files-test-outside", ".txt")
        try {
            val store = FileStore(FakeLink(downloadTo = { outside }))
            assertFailsWith<JmapError> { store.download(FileNode("n", null, "x", false, "B"), dir) }
            assertFalse(Files.exists(outside))
        } finally {
            dir.toFile().deleteRecursively()
            Files.deleteIfExists(outside)
        }
    }

    // ---- the store ------------------------------------------------------------------

    @Test
    fun `the tree is read in pages until the total is reached`() {
        val calls = ArrayList<Int>()
        val link = FakeLink(
            files = { invocations ->
                val position = args(invocations[0])["position"]!!.jsonPrimitive.content.toInt()
                calls.add(position)
                val ids = if (position == 0) listOf("a", "b") else listOf("c")
                listOf(
                    arr("""["FileNode/query", {"ids": ${ids.joinToString(",", "[", "]") { "\"$it\"" }}, "total": 3}, "q"]"""),
                    arr(
                        """["FileNode/get", {"list": [${ids.joinToString(",") { """{"id": "$it", "name": "$it", "nodeType": "directory"}""" }}]}, "g"]""",
                    ),
                )
            },
        )
        val t = FileStore(link).tree()
        assertEquals(listOf(0, 2), calls)
        assertEquals(setOf("a", "b", "c"), t.byId.keys)
    }

    @Test
    fun `saving an attachment uses the server's own copy when it can`() {
        var blobAsked: JsonArray? = null
        var fileAsked: JsonArray? = null
        val link = FakeLink(
            slice = true,
            blobs = { invocations ->
                blobAsked = invocations[0]
                listOf(arr("""["Blob/upload", {"created": {"b": {"id": "Bfresh", "size": 3}}}, "b"]"""))
            },
            files = { invocations ->
                fileAsked = invocations[0]
                listOf(arr("""["FileNode/set", {"created": {"n": {"id": "new"}}}, "s"]"""))
            },
        )
        val id = FileStore(link).saveAttachment(Attachment("Bpart", "report?.pdf", "application/pdf", 3), "f1")
        assertEquals("new", id)
        assertEquals("Bpart", args(blobAsked!!)["create"]!!.jsonObject["b"]!!.jsonObject["data"]!!.jsonArray[0].jsonObject["blobId"]!!.jsonPrimitive.content)
        val made = args(fileAsked!!)["create"]!!.jsonObject["n"]!!.jsonObject
        assertEquals("Bfresh", made["blobId"]!!.jsonPrimitive.content)
        assertEquals("report_.pdf", made["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without blob methods an attachment goes down and back up`() {
        var uploaded: Path? = null
        val link = FakeLink(
            slice = false,
            downloadTo = { dir -> Files.writeString(dir.resolve("a.txt"), "hi") },
            uploadAs = { path -> uploaded = path; Attachment("Bup", path.fileName.toString(), "text/plain", 2) },
            files = { listOf(arr("""["FileNode/set", {"created": {"n": {"id": "new"}}}, "s"]""")) },
        )
        FileStore(link).saveAttachment(Attachment("Bpart", "a.txt", "text/plain", 2), null)
        assertEquals("a.txt", uploaded!!.fileName.toString())
        // The temp folder is gone afterwards.
        assertFalse(Files.exists(uploaded))
    }

    @Test
    fun `a file is attached by reference to its own blob`() {
        val attachment = asAttachment(FileNode("n1", null, "q3.pdf", false, "Bq3", 2048, "application/pdf"))
        assertEquals("Bq3", attachment.blobId)
        assertEquals("q3.pdf", attachment.name)
        assertFailsWith<JmapError> { asAttachment(FileNode("f", null, "Reports", true)) }
    }

    // ---- words ---------------------------------------------------------------------

    @Test
    fun `delete names what goes with a folder`() {
        val t = tree()
        assertEquals(
            "Delete the folder Reports and everything in it: 2 files and 1 folder? " +
                "They are removed from the server, not moved to a trash.",
            deleteQuestion(t.byId.getValue("f1"), t),
        )
        assertTrue(deleteQuestion(t.byId.getValue("n3"), t).startsWith("Delete the file avatar.png?"))
        val empty = FileTree(listOf(FileNode("e", null, "Empty", true)))
        assertEquals("Delete the empty folder Empty?", deleteQuestion(empty.byId.getValue("e"), empty))
    }

    @Test
    fun `size and date are drawn the way the rest of the app draws them`() {
        assertEquals("2.0 KB", sizeLabel(FileNode("n", null, "x", false, "B", 2048)))
        assertEquals("", sizeLabel(FileNode("f", null, "x", true)))
        assertEquals("September 3, 2026, 10:00 AM", modifiedLabel("2026-09-03T10:00:00Z", ZoneId.of("UTC"), Locale.US))
        assertEquals("not a date", modifiedLabel("not a date"))
    }
}

private class FakeLink(
    private val slice: Boolean = true,
    private val files: (Array<out JsonArray>) -> List<JsonArray> = { error("no file call expected") },
    private val blobs: (Array<out JsonArray>) -> List<JsonArray> = { error("no blob call expected") },
    private val downloadTo: (Path) -> Path = { error("no download expected") },
    private val uploadAs: (Path) -> Attachment = { error("no upload expected") },
) : FilesTransport {
    override val accountId = "acc"
    override val canSliceBlobs get() = slice
    override fun fileCall(vararg invocations: JsonArray) = files(invocations)
    override fun blobCall(vararg invocations: JsonArray) = blobs(invocations)
    override fun upload(file: Path) = uploadAs(file)
    override fun download(attachment: Attachment, into: Path) = downloadTo(into)
}
