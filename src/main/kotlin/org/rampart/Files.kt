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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The files Stalwart keeps for an account, over JMAP for file storage
 * (`urn:ietf:params:jmap:filenode`). docs/files.md is what the server actually does and
 * where in its source that was read.
 *
 * Everything here that builds a request or reads an answer is a plain function of its
 * arguments, so it is checked in FilesTest without a server. [FileStore] is the only part
 * that talks, and it talks through [FilesTransport], which [Jmap] implements.
 */

/** The capability every FileNode call names in `using`, and no other call does. */
internal const val FILENODE = "urn:ietf:params:jmap:filenode"

/** RFC 9404's blob methods, which is how an attachment becomes a file without a round trip. */
internal const val BLOB = "urn:ietf:params:jmap:blob"

/**
 * One file or folder as the server holds it.
 *
 * A folder is a node with no blob: Stalwart reports `nodeType` "directory" and a null
 * blobId, size and type for it. [parentId] null is the top level. There is no root node
 * to hang things from in 0.16, whatever the draft's `role` says: every role comes back null.
 */
internal data class FileNode(
    val id: String,
    val parentId: String?,
    val name: String,
    val isFolder: Boolean,
    val blobId: String? = null,
    val size: Long = 0L,
    val type: String = "",
    /** RFC 3339, as the server wrote it. Empty when it did not say. */
    val modified: String = "",
)

/** Every node, with the lookups the page keeps asking for worked out once. */
internal class FileTree(val nodes: List<FileNode>) {
    val byId: Map<String, FileNode> = nodes.associateBy { it.id }

    /**
     * A node whose parent is not in the list is drawn at the top level rather than lost.
     * That happens when a folder was shared into this account without the folders above it.
     */
    private val children: Map<String?, List<FileNode>> = nodes
        .groupBy { node -> node.parentId?.takeIf { it in byId } }
        .mapValues { (_, list) -> list.sortedWith(FOLDERS_FIRST) }

    /** Folders first, then files, each by name the way a person reads it. */
    fun childrenOf(folderId: String?): List<FileNode> = children[folderId].orEmpty()

    /** The folders from the top level down to [id], for the path above the list. */
    fun pathTo(id: String?): List<FileNode> {
        val path = ArrayList<FileNode>()
        var at = id?.let { byId[it] }
        // Bounded, because a server that ever answered with a loop must not hang the window.
        while (at != null && path.size < 256) {
            path.add(0, at)
            at = at.parentId?.let { byId[it] }
        }
        return path
    }

    /** Everything under [id], not counting itself. */
    fun descendants(id: String): List<FileNode> {
        val out = ArrayList<FileNode>()
        val queue = ArrayDeque(childrenOf(id))
        while (queue.isNotEmpty() && out.size < nodes.size) {
            val next = queue.removeFirst()
            out.add(next)
            if (next.isFolder) queue.addAll(childrenOf(next.id))
        }
        return out
    }

    /** Every folder with its depth, in the order the sidebar tree draws them. */
    fun folderOutline(): List<Pair<FileNode, Int>> {
        val out = ArrayList<Pair<FileNode, Int>>()
        fun walk(parent: String?, depth: Int) {
            if (depth > 64) return
            childrenOf(parent).filter { it.isFolder }.forEach {
                out.add(it to depth)
                walk(it.id, depth + 1)
            }
        }
        walk(null, 0)
        return out
    }

    /**
     * The folders [node] could be moved into: not itself and nothing inside it, which the
     * server would refuse as a loop, and not where it already is, which would do nothing.
     */
    fun moveTargets(node: FileNode): List<Pair<FileNode, Int>> {
        val inside = descendants(node.id).map { it.id }.toSet() + node.id
        return folderOutline().filter { (folder, _) -> folder.id !in inside && folder.id != node.parentId }
    }

    companion object {
        val FOLDERS_FIRST: Comparator<FileNode> =
            compareBy<FileNode> { !it.isFolder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }
    }
}

// ---- requests ----------------------------------------------------------------------

/** What the page draws, and nothing it does not. */
internal val FILE_NODE_PROPERTIES =
    listOf("id", "parentId", "name", "nodeType", "blobId", "size", "type", "modified")

/**
 * One page of the whole tree: a query for ids and a get that reads them by back-reference,
 * in a single round trip.
 *
 * The whole tree rather than a folder at a time, because the folder tree beside the list
 * needs every folder anyway and a folder tree read lazily is a tree that is never quite
 * right. Sorted by name on the server only to keep the pages stable; the page sorts again.
 */
internal fun fileNodePage(accountId: String, position: Int, limit: Int): Array<JsonArray> = arrayOf(
    fileMethod("FileNode/query", "q") {
        put("accountId", accountId)
        putJsonArray("sort") {
            addJsonObject { put("property", "name"); put("isAscending", true) }
        }
        put("position", position)
        put("limit", limit)
        put("calculateTotal", true)
    },
    fileMethod("FileNode/get", "g") {
        put("accountId", accountId)
        putJsonObject("#ids") {
            put("resultOf", "q")
            put("name", "FileNode/query")
            put("path", "/ids")
        }
        putJsonArray("properties") { FILE_NODE_PROPERTIES.forEach { add(it) } }
    },
)

/** A new folder. Collisions are refused rather than renamed: a folder somebody named is the name they wanted. */
internal fun createFolderCall(accountId: String, name: String, parentId: String?): JsonArray =
    fileMethod("FileNode/set", "s") {
        put("accountId", accountId)
        putJsonObject("create") {
            putJsonObject("n") {
                put("name", name)
                put("parentId", parentOrTop(parentId))
                put("nodeType", "directory")
            }
        }
    }

/**
 * A new file over a blob that is already on the server.
 *
 * `onExists: rename` because an upload landing on a name that is taken should neither
 * replace what was there nor fail: the server picks "name (2).ext" and says so.
 */
internal fun createFileCall(
    accountId: String,
    name: String,
    parentId: String?,
    blobId: String,
    type: String,
): JsonArray = fileMethod("FileNode/set", "s") {
    put("accountId", accountId)
    put("onExists", "rename")
    putJsonObject("create") {
        putJsonObject("n") {
            put("name", name)
            put("parentId", parentOrTop(parentId))
            put("blobId", blobId)
            // Stalwart takes a type only when it has a slash and fits in 256 octets, and
            // refuses the whole create otherwise, so a doubtful one is left for it to guess.
            fileTypeFor(type)?.let { put("type", it) }
        }
    }
}

internal fun renameCall(accountId: String, id: String, name: String): JsonArray =
    fileMethod("FileNode/set", "s") {
        put("accountId", accountId)
        putJsonObject("update") { putJsonObject(id) { put("name", name) } }
    }

/** [parentId] null moves it to the top level. */
internal fun moveCall(accountId: String, id: String, parentId: String?): JsonArray =
    fileMethod("FileNode/set", "s") {
        put("accountId", accountId)
        putJsonObject("update") {
            putJsonObject(id) { put("parentId", parentOrTop(parentId)) }
        }
    }

/**
 * Deleting one node. Without [withContents] the server refuses a folder that is not
 * empty with `nodeHasChildren`, which is the answer wanted when the question did not
 * mention the contents.
 */
internal fun destroyCall(accountId: String, id: String, withContents: Boolean): JsonArray =
    fileMethod("FileNode/set", "s") {
        put("accountId", accountId)
        if (withContents) put("onDestroyRemoveChildren", true)
        putJsonArray("destroy") { add(id) }
    }

/**
 * A copy of a mail attachment as a blob of its own, made on the server (RFC 9404).
 *
 * The attachment's own blobId cannot be handed to FileNode/set: Stalwart's names a slice
 * of the message and still carries its transfer encoding, and FileNode/set stores the whole
 * blob the hash points at, which is the entire message. Blob/upload reads the slice,
 * decodes it and stores just the file.
 */
internal fun sliceBlobCall(accountId: String, blobId: String, type: String): JsonArray =
    fileMethod("Blob/upload", "b") {
        put("accountId", accountId)
        putJsonObject("create") {
            putJsonObject("b") {
                putJsonArray("data") { addJsonObject { put("blobId", blobId) } }
                put("type", type.ifBlank { "application/octet-stream" })
            }
        }
    }

private fun fileMethod(name: String, callId: String, args: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonArray =
    buildJsonArray {
        add(name)
        add(buildJsonObject(args))
        add(callId)
    }

// ---- answers -----------------------------------------------------------------------

/** The nodes in a FileNode/get response. A node without an id cannot be acted on, so it is left out. */
internal fun fileNodesIn(getResponse: JsonArray): List<FileNode> {
    val list = (getResponse.getOrNull(1) as? JsonObject)?.get("list") as? JsonArray ?: return emptyList()
    return list.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val id = o.text("id") ?: return@mapNotNull null
        val blobId = o.text("blobId")
        // nodeType is the answer when there is one. A server that leaves it out still gives
        // a folder no blob, so that is the fallback rather than calling everything a file.
        val isFolder = when (o.text("nodeType")) {
            "directory" -> true
            "file" -> false
            else -> blobId == null
        }
        FileNode(
            id = id,
            parentId = o.text("parentId"),
            name = o.text("name").orEmpty().ifBlank { if (isFolder) "(unnamed folder)" else "(unnamed file)" },
            isFolder = isFolder,
            blobId = if (isFolder) null else blobId,
            size = (o["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
            type = o.text("type").orEmpty(),
            modified = o.text("modified").orEmpty(),
        )
    }
}

/** The query's total and how many ids this page held, for knowing whether to ask again. */
internal fun queryProgress(queryResponse: JsonArray): Pair<Int, Int> {
    val o = queryResponse.getOrNull(1) as? JsonObject ?: return 0 to 0
    val ids = (o["ids"] as? JsonArray)?.size ?: 0
    val total = (o["total"] as? JsonPrimitive)?.longOrNull?.toInt() ?: ids
    return total to ids
}

/** The id the server gave the one thing a [createFolderCall] or [createFileCall] made. */
internal fun createdId(setResponse: JsonArray): String? =
    ((setResponse.getOrNull(1) as? JsonObject)?.get("created") as? JsonObject)
        ?.get("n")?.let { it as? JsonObject }?.text("id")

/** The new blob a [sliceBlobCall] made. */
internal fun slicedBlobId(uploadResponse: JsonArray): String? =
    ((uploadResponse.getOrNull(1) as? JsonObject)?.get("created") as? JsonObject)
        ?.get("b")?.let { it as? JsonObject }?.text("id")

/**
 * Why a FileNode/set did not do what it was asked, as one sentence, or null when it did.
 *
 * [field] is notCreated, notUpdated or notDestroyed, and [key] is the creation id or the
 * node id. [subject] is what the sentence is about, already in words: "the folder
 * Reports".
 */
internal fun setRefusal(setResponse: JsonArray, field: String, key: String, subject: String): String? {
    val body = setResponse.getOrNull(1) as? JsonObject ?: return "The server's reply about $subject was empty."
    val error = (body[field] as? JsonObject)?.get(key) as? JsonObject
    if (error != null) return setErrorSentence(error, subject)
    val done = when (field) {
        "notCreated" -> (body["created"] as? JsonObject)?.containsKey(key) == true
        "notUpdated" -> (body["updated"] as? JsonObject)?.containsKey(key) == true
        "notDestroyed" -> (body["destroyed"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == key } == true
        else -> false
    }
    return if (done) null else "The server did not say whether it changed $subject."
}

/**
 * One SetError, in words.
 *
 * The server's own description wins where it wrote one, because Stalwart's are specific
 * ("Name contains a forbidden character.") and ours could only guess. The type decides
 * the sentence where it did not.
 */
internal fun setErrorSentence(error: JsonObject, subject: String): String {
    val description = error.text("description")?.trim()?.trimEnd('.')?.ifBlank { null }
    val sentence = when (error.text("type")) {
        "alreadyExists" -> "Something with that name is already in that folder"
        "nodeHasChildren" -> "The server kept $subject because it is not empty"
        "notFound" -> "$subject is no longer on the server".replaceFirstChar { it.uppercase() }
        "forbidden" -> "This account is not allowed to change $subject"
        "overQuota" -> "There is no room left in this account's storage for $subject"
        "tooLarge" -> "$subject is larger than this server accepts".replaceFirstChar { it.uppercase() }
        "willDestroy" -> "$subject is already being deleted".replaceFirstChar { it.uppercase() }
        "invalidProperties" -> "The server would not accept $subject"
        "blobNotFound" -> "The server could not find the contents of $subject"
        else -> "The server refused to change $subject"
    }
    return if (description != null) "$sentence: $description." else "$sentence."
}

// ---- names, sizes, dates -----------------------------------------------------------

private val SERVER_FORBIDDEN = "/<>:\"\\|?*"
private val SERVER_RESERVED = setOf(
    ".", "..", "CON", "PRN", "AUX", "NUL",
) + (0..9).flatMap { listOf("COM$it", "LPT$it") }

/**
 * A name the server will take for a node.
 *
 * Stalwart refuses the whole create over one character in "/<>:\"\\|?*", over a name of
 * more than 255 octets, and over a handful of Windows device names; a file from a Linux
 * disk can have any of those. So they are replaced here rather than the upload failing,
 * and a name that ends up empty becomes "file".
 */
internal fun serverNodeName(proposed: String): String {
    var name = buildString {
        for (ch in proposed.trim()) {
            append(if (ch in SERVER_FORBIDDEN || ch.isISOControl()) '_' else ch)
        }
    }.trim()
    if (name.isEmpty()) name = "file"
    if (name.uppercase() in SERVER_RESERVED) name = "_$name"
    // Octets, not characters: the limit is on the UTF-8 form.
    while (name.encodeToByteArray().size > 255) name = name.dropLast(1)
    return name
}

/** Whether a name typed into New folder or Rename is one the server will take, and why not. */
internal fun nameProblem(name: String): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "A name is needed."
        trimmed.any { it in SERVER_FORBIDDEN } -> "A name cannot contain any of / < > : \" \\ | ? *"
        trimmed.uppercase() in SERVER_RESERVED -> "That name is reserved and the server will not take it."
        trimmed.encodeToByteArray().size > 255 -> "That name is too long for the server."
        else -> null
    }
}

/** The media type to send with a file, or null to let the server work it out. */
internal fun fileTypeFor(type: String): String? =
    type.trim().takeIf { it.contains('/') && it.encodeToByteArray().size in 1..256 }

/**
 * Throws unless [written] is inside [folder].
 *
 * A file's name came from whoever put it there, which on a shared folder is somebody else.
 * [uniqueIn] already keeps only the last component of it and strips what Windows cannot
 * hold; this is the second check, on the path that was actually written.
 */
internal fun requireInside(folder: Path, written: Path) {
    val root = folder.toAbsolutePath().normalize()
    val path = written.toAbsolutePath().normalize()
    if (!path.startsWith(root) || path == root) {
        throw JmapError("The file's name would have put it outside the folder it was meant for, so it was not saved.")
    }
}

/** A folder has no size worth showing: the server does not add one up. */
internal fun sizeLabel(node: FileNode): String = if (node.isFolder) "" else humanSize(node.size)

/**
 * When it last changed, in the reader's own time zone and language, with the year because
 * a file is kept for longer than a message is read. The server's text when it is not a date.
 */
internal fun modifiedLabel(
    modified: String,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).withZone(zone).format(Instant.parse(modified))
}.getOrDefault(modified)

/**
 * The question Delete asks, naming what goes.
 *
 * A folder says how much is inside it, because "delete Reports" and "delete Reports and
 * the 40 files in it" are different decisions and only the second is the true one.
 */
internal fun deleteQuestion(node: FileNode, tree: FileTree): String {
    if (!node.isFolder) return "Delete the file ${node.name}? It is removed from the server, not moved to a trash."
    val inside = tree.descendants(node.id)
    if (inside.isEmpty()) return "Delete the empty folder ${node.name}?"
    val files = inside.count { !it.isFolder }
    val folders = inside.count { it.isFolder }
    val what = listOfNotNull(
        files.takeIf { it > 0 }?.let { if (it == 1) "1 file" else "$it files" },
        folders.takeIf { it > 0 }?.let { if (it == 1) "1 folder" else "$it folders" },
    ).joinToString(" and ")
    return "Delete the folder ${node.name} and everything in it: $what? " +
        "They are removed from the server, not moved to a trash."
}

/** "the file x" or "the folder y", for the sentences above. */
internal fun describe(node: FileNode): String = (if (node.isFolder) "the folder " else "the file ") + node.name

/** A parent id as JSON, where null is the top level and has to be sent as null, not left out. */
private fun parentOrTop(parentId: String?): JsonElement = if (parentId == null) JsonNull else JsonPrimitive(parentId)

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.ifBlank { null }

// ---- talking to the server ---------------------------------------------------------

/**
 * What [FileStore] needs from a JMAP session, and nothing more. [Jmap] is the real one.
 * An interface so the store's own logic, the paging and the fallbacks, can be run in a
 * test against canned answers.
 */
internal interface FilesTransport {
    val accountId: String

    /** Whether the server offers RFC 9404 Blob/upload, the server-side way to copy an attachment. */
    val canSliceBlobs: Boolean

    /** A request whose `using` names file storage. */
    fun fileCall(vararg invocations: JsonArray): List<JsonArray>

    /** A request whose `using` names the blob methods. */
    fun blobCall(vararg invocations: JsonArray): List<JsonArray>

    fun upload(file: Path): Attachment

    fun download(attachment: Attachment, into: Path): Path
}

/** How many nodes one page of [FileStore.tree] asks for; under Stalwart's default of 500 per get. */
internal const val FILE_PAGE = 250

/** A ceiling on pages, so a server that keeps reporting more cannot keep the page loading forever. */
private const val MAX_PAGES = 40

internal class FileStore(private val link: FilesTransport) {

    /** Every node in the account. Blocking; call it off the window's thread. */
    fun tree(): FileTree {
        val nodes = LinkedHashMap<String, FileNode>()
        var position = 0
        for (page in 0 until MAX_PAGES) {
            val answers = link.fileCall(*fileNodePage(link.accountId, position, FILE_PAGE))
            val (total, count) = queryProgress(answers[0])
            fileNodesIn(answers[1]).forEach { nodes[it.id] = it }
            position += count
            if (count == 0 || position >= total) break
        }
        return FileTree(nodes.values.toList())
    }

    fun createFolder(name: String, parentId: String?): String {
        val clean = name.trim()
        nameProblem(clean)?.let { throw JmapError(it) }
        val answer = link.fileCall(createFolderCall(link.accountId, clean, parentId))[0]
        setRefusal(answer, "notCreated", "n", "the folder $clean")?.let { throw JmapError(it) }
        return createdId(answer) ?: throw JmapError("The server made the folder $clean and did not say where.")
    }

    fun rename(node: FileNode, name: String) {
        val clean = name.trim()
        nameProblem(clean)?.let { throw JmapError(it) }
        val answer = link.fileCall(renameCall(link.accountId, node.id, clean))[0]
        setRefusal(answer, "notUpdated", node.id, describe(node))?.let { throw JmapError(it) }
    }

    fun move(node: FileNode, parentId: String?) {
        val answer = link.fileCall(moveCall(link.accountId, node.id, parentId))[0]
        setRefusal(answer, "notUpdated", node.id, describe(node))?.let { throw JmapError(it) }
    }

    fun delete(node: FileNode) {
        val answer = link.fileCall(destroyCall(link.accountId, node.id, withContents = node.isFolder))[0]
        setRefusal(answer, "notDestroyed", node.id, describe(node))?.let { throw JmapError(it) }
    }

    /** A file from this machine into [parentId]. The upload endpoint first, then the node over it. */
    fun upload(file: Path, parentId: String?): String {
        if (!Files.isRegularFile(file)) throw JmapError("${file.fileName} is not a file, so it was not uploaded.")
        val blob = link.upload(file)
        return fileOver(blob.blobId, serverNodeName(file.fileName.toString()), blob.type, parentId)
    }

    /**
     * A mail attachment into [parentId], without it coming down to this machine where the
     * server can do the copy itself. See [sliceBlobCall] for why the attachment's own blobId
     * is not simply reused.
     */
    fun saveAttachment(attachment: Attachment, parentId: String?): String {
        val name = serverNodeName(attachment.name)
        val blobId = if (link.canSliceBlobs) {
            val answer = link.blobCall(sliceBlobCall(link.accountId, attachment.blobId, attachment.type))[0]
            slicedBlobId(answer) ?: throw JmapError(
                setRefusal(answer, "notCreated", "b", "the attachment $name")
                    ?: "The server did not copy the attachment $name.",
            )
        } else {
            // The long way round, for a server without Blob/upload: down to a folder of
            // this save's own, and straight back up.
            val dir = Files.createTempDirectory("rampart-files")
            try {
                val local = link.download(attachment, dir)
                requireInside(dir, local)
                link.upload(local).blobId
            } finally {
                runCatching { dir.toFile().deleteRecursively() }
            }
        }
        return fileOver(blobId, name, attachment.type, parentId)
    }

    /** A file's contents into [folder], under a name that cannot leave it. */
    fun download(node: FileNode, folder: Path): Path {
        val attachment = asAttachment(node)
        val written = link.download(attachment, folder)
        try {
            requireInside(folder, written)
        } catch (e: JmapError) {
            runCatching { Files.deleteIfExists(written) }
            throw e
        }
        return written
    }

    private fun fileOver(blobId: String, name: String, type: String, parentId: String?): String {
        val answer = link.fileCall(createFileCall(link.accountId, name, parentId, blobId, type))[0]
        setRefusal(answer, "notCreated", "n", "the file $name")?.let { throw JmapError(it) }
        return createdId(answer) ?: throw JmapError("The server stored $name and did not say where.")
    }
}

/**
 * A file as something a message can carry.
 *
 * The node's own blobId, as it is: Stalwart's Email/set reads a blob through the same
 * access check a download does, and a file in the account's own storage passes it, so
 * attaching a file from Files is a reference and not a second upload.
 */
internal fun asAttachment(node: FileNode): Attachment {
    val blobId = node.blobId ?: throw JmapError("${node.name} is a folder, and a folder has no contents to fetch.")
    return Attachment(
        blobId = blobId,
        name = node.name,
        type = node.type.ifBlank { "application/octet-stream" },
        size = node.size,
    )
}
