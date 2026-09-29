package org.rampart

import kotlinx.serialization.json.Json
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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files

/*
 * Saved prompts, Gemini's Gems: a named instruction the person reuses, such as "reply to a
 * lead" or "decline politely", picked from the composer's Help me write and from the Rook
 * panel rather than typed again.
 *
 * Kept on the person's own server, so the same list is there on every machine they sign in
 * from: one JSON file, Rampart/saved-prompts.json, in the account's Stalwart Files, written
 * as the signed in user through the same FileNode calls the Files page makes (Files.kt).
 * Where the account has no Files (an IMAP account, or a server without file storage) the
 * list is kept on this computer instead, and every place that shows it says so, because a
 * list that silently does not follow somebody to their other machine is a list they stop
 * trusting.
 *
 * A saved prompt is the person's own words and goes to the model as an instruction, the
 * same as anything they type into Help me write. The file is only ever written by Rampart,
 * but it is still read as input: a name or a text that is not a string is dropped, and so
 * is anything past the limits below.
 */

internal data class SavedPrompt(val name: String, val text: String)

/** Where the list is kept, which the picker says in words. */
internal enum class PromptPlace(val words: String) {
    SERVER("Saved in your Files, in the Rampart folder, so every computer you sign in from has them."),
    LOCAL("Kept on this computer only, because this account has no Files storage on its server."),
}

internal object SavedPrompts {
    /** The folder at the top of the account's Files, shared with anything else Rampart keeps there later. */
    const val FOLDER = "Rampart"

    const val FILE = "saved-prompts.json"

    /** More than anybody keeps. A list longer than this is not a picker any more. */
    const val MOST = 50

    const val NAME_MAX = 60

    /** A saved instruction is a paragraph, not a document. */
    const val TEXT_MAX = 2000

    /** The file's own version, so a later shape can tell an old file from a new one. */
    private const val VERSION = 1

    private val pretty = Json { prettyPrint = true }

    /** The list as the file holds it. Pretty printed, because somebody may open it in Files to look. */
    fun encode(prompts: List<SavedPrompt>): String = pretty.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("version", VERSION)
            putJsonArray("prompts") {
                prompts.forEach { p -> addJsonObject { put("name", p.name); put("text", p.text) } }
            }
        },
    )

    /**
     * The list in a file, forgiving about everything but shape: an entry without a name and
     * a text is dropped, over-long ones are cut, and a name that appears twice keeps its
     * first. A file that is not this JSON at all reads as an empty list, never an error, so
     * a broken file costs the list and not the composer.
     */
    fun decode(text: String): List<SavedPrompt> {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val list = root["prompts"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val name = o.string("name")?.let(::cleanName)?.ifBlank { null } ?: return@mapNotNull null
            val body = o.string("text")?.trim()?.take(TEXT_MAX)?.ifBlank { null } ?: return@mapNotNull null
            SavedPrompt(name, body)
        }.distinctBy { it.name.lowercase() }.take(MOST)
    }

    /** [prompt] added, or put in place of the one with the same name, ignoring case. */
    fun upsert(prompts: List<SavedPrompt>, prompt: SavedPrompt): List<SavedPrompt> {
        val clean = SavedPrompt(cleanName(prompt.name), prompt.text.trim().take(TEXT_MAX))
        val at = prompts.indexOfFirst { it.name.equals(clean.name, ignoreCase = true) }
        return if (at >= 0) prompts.toMutableList().also { it[at] = clean } else (prompts + clean).take(MOST)
    }

    fun remove(prompts: List<SavedPrompt>, name: String): List<SavedPrompt> =
        prompts.filterNot { it.name.equals(name, ignoreCase = true) }

    /** Why [name] and [text] cannot be saved, in one sentence, or null when they can. */
    fun problem(name: String, text: String, existing: List<SavedPrompt>): String? = when {
        cleanName(name).isBlank() -> "Give the prompt a name."
        text.isBlank() -> "There is no instruction to save."
        text.trim().length > TEXT_MAX -> "A saved prompt can be at most $TEXT_MAX characters."
        existing.size >= MOST && existing.none { it.name.equals(cleanName(name), ignoreCase = true) } ->
            "There are already $MOST saved prompts. Delete one first."
        else -> null
    }

    /** One line, no control characters, at most [NAME_MAX]: it is a label in a menu. */
    fun cleanName(name: String): String =
        name.filterNot { it.isISOControl() }.trim().take(NAME_MAX).trim()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** Where one account's saved prompts are read and written. Blocking; call off the window's thread. */
internal interface PromptShelf {
    val place: PromptPlace

    fun load(): List<SavedPrompt>

    fun save(prompts: List<SavedPrompt>)
}

/**
 * The list in the account's own Files, through [link], the same transport the Files page
 * uses. The folder and the file are made the first time something is saved, never on a read.
 */
internal class FilesPromptShelf(private val link: FilesTransport) : PromptShelf {
    override val place = PromptPlace.SERVER

    override fun load(): List<SavedPrompt> {
        val node = find().second ?: return emptyList()
        return SavedPrompts.decode(read(node))
    }

    override fun save(prompts: List<SavedPrompt>) {
        val (folder, existing) = find()
        val folderId = folder?.id ?: FileStore(link).createFolder(SavedPrompts.FOLDER, null)
        val dir = Files.createTempDirectory("rampart-prompts")
        try {
            val local = dir.resolve(SavedPrompts.FILE)
            Files.writeString(local, SavedPrompts.encode(prompts))
            val blobId = link.upload(local).blobId
            if (existing != null) {
                // New contents on the same node, so the file keeps its place, its id and
                // anything it was shared with, rather than a second copy appearing beside it.
                val answer = link.fileCall(replaceContentsCall(link.accountId, existing.id, blobId))[0]
                setRefusal(answer, "notUpdated", existing.id, "your saved prompts")?.let { throw JmapError(it) }
            } else {
                val answer = link.fileCall(createFileCall(link.accountId, SavedPrompts.FILE, folderId, blobId, "application/json"))[0]
                setRefusal(answer, "notCreated", "n", "your saved prompts")?.let { throw JmapError(it) }
            }
        } finally {
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    /** The Rampart folder at the top level and the file in it, either of which may not exist yet. */
    private fun find(): Pair<FileNode?, FileNode?> {
        val tree = FileStore(link).tree()
        val folder = tree.childrenOf(null).firstOrNull { it.isFolder && it.name == SavedPrompts.FOLDER }
        val file = folder?.let { f -> tree.childrenOf(f.id).firstOrNull { !it.isFolder && it.name == SavedPrompts.FILE } }
        return folder to file
    }

    private fun read(node: FileNode): String {
        // A list of a few prompts is a few kilobytes. Anything far bigger is not ours.
        if (node.size > 512L * 1024) throw JmapError("The saved prompts file in Files is too large to be Rampart's, so it was not read.")
        val dir = Files.createTempDirectory("rampart-prompts")
        try {
            val written = link.download(asAttachment(node), dir)
            requireInside(dir, written)
            return Files.readString(written)
        } finally {
            runCatching { dir.toFile().deleteRecursively() }
        }
    }
}

/** A FileNode/set that gives node [id] new contents. Stalwart 0.16 accepts blobId in an update (docs/files.md). */
internal fun replaceContentsCall(accountId: String, id: String, blobId: String): JsonArray = buildJsonArray {
    add("FileNode/set")
    add(
        buildJsonObject {
            put("accountId", accountId)
            putJsonObject("update") { putJsonObject(id) { put("blobId", blobId) } }
        },
    )
    add("s")
}

/**
 * The list on this computer, for an account without Files. One file for every account,
 * keyed by the account, so signing out of one does not take another's prompts with it.
 */
internal class LocalPromptShelf(
    private val account: String,
    private val read: () -> JsonObject = { localPrompts.read() },
    private val write: (MutableMap<String, JsonElement>.() -> Unit) -> Unit = { change -> localPrompts.write(change) },
) : PromptShelf {
    override val place = PromptPlace.LOCAL

    override fun load(): List<SavedPrompt> {
        val text = (read()[account] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: return emptyList()
        return SavedPrompts.decode(text)
    }

    override fun save(prompts: List<SavedPrompt>) = write { put(account, JsonPrimitive(SavedPrompts.encode(prompts))) }
}

private val localPrompts by lazy { JsonStore("saved-prompts.json") }

/** The shelf for an account: its Files when it has them, this computer otherwise. */
internal fun promptShelfFor(files: FilesTransport?, account: String): PromptShelf =
    if (files != null) FilesPromptShelf(files) else LocalPromptShelf(account)
