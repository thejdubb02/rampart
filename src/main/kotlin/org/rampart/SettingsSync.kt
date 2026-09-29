package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/*
 * Settings that follow somebody between computers, through a file in their own account's
 * Files: a folder named Rampart at the top level with settings.json in it, one per
 * account. Class 3 in CLAUDE.md, done as the signed-in user over the mail session; no
 * admin token is involved and none could help.
 *
 * SettingsSyncMerge.kt holds the rules. This file finds the file, reads it, writes it,
 * and decides when. docs/settings-sync.md is the design.
 */

/** The folder at the top of Files that holds the file. */
internal const val SYNC_FOLDER = "Rampart"

/** The file inside it. */
internal const val SYNC_FILE = "settings.json"

/** The wait after a change before sending it, so dragging a slider is one write and not forty. */
internal const val SYNC_DEBOUNCE_MS = 4_000L

/** How often a running Rampart looks for changes made on another computer. */
internal const val SYNC_EVERY_MS = 30L * 60 * 1000

/** How many times one sync re-reads and merges after losing a race, before giving up for now. */
private const val SYNC_ATTEMPTS = 4

/** Where the file is, or is not, and the FileNode state it was seen in. */
internal data class SyncLocation(
    val folderId: String?,
    val file: FileNode?,
    /** The FileNode state from the same read, which the write names in `ifInState`. */
    val state: String?,
    /** Set when something that is not a folder is sitting where the folder has to go. */
    val blocked: String? = null,
)

/** Somebody else wrote first. The answer is always to read again and merge. */
internal class SyncConflict : Exception("The settings file changed on the server while this computer was writing it.")

/**
 * The settings file in one account's Files, over the same [FilesTransport] the Files page
 * uses. Every request is built here, from the same pieces Files.kt builds its own from.
 */
internal class SettingsFile(private val link: FilesTransport) {

    /**
     * The folder and the file, in one round trip.
     *
     * Both queries filter by name on the server and the answers are checked again here by
     * exact name and place, so a server whose name filter matched more loosely than
     * expected can only cost a longer answer, never the wrong file.
     */
    fun locate(): SyncLocation {
        val answers = link.fileCall(
            syncQuery("q1") {
                put("isTopLevel", true)
                put("name", SYNC_FOLDER)
            },
            syncGet("g1", "q1"),
            syncQuery("q2") { put("name", SYNC_FILE) },
            syncGet("g2", "q2"),
        )
        val tops = fileNodesIn(answers[1]).filter { it.parentId == null && it.name == SYNC_FOLDER }
        val folder = tops.firstOrNull { it.isFolder }
        val state = ((answers[3].getOrNull(1) as? JsonObject)?.get("state") as? JsonPrimitive)?.contentOrNull
            ?: ((answers[1].getOrNull(1) as? JsonObject)?.get("state") as? JsonPrimitive)?.contentOrNull
        if (folder == null && tops.isNotEmpty()) {
            return SyncLocation(
                null, null, state,
                blocked = "A file named $SYNC_FOLDER at the top of Files is where the settings folder has to go.",
            )
        }
        val file = folder?.let { f ->
            fileNodesIn(answers[3]).firstOrNull { !it.isFolder && it.parentId == f.id && it.name == SYNC_FILE }
        }
        return SyncLocation(folder?.id, file, state)
    }

    /** The file's contents, or why they could not be used. Never throws for a bad file, only for a failed download. */
    fun read(node: FileNode, names: Map<String, SyncShape>, now: Long): SyncRead {
        if (node.size > SYNC_MAX_BYTES) {
            return SyncRead.Unreadable("The settings file on the server is too large to be one Rampart wrote.")
        }
        val dir = Files.createTempDirectory("rampart-sync")
        try {
            val written = link.download(asAttachment(node), dir)
            requireInside(dir, written)
            if (Files.size(written) > SYNC_MAX_BYTES) {
                return SyncRead.Unreadable("The settings file on the server is too large to be one Rampart wrote.")
            }
            val text = runCatching { Files.readString(written) }.getOrNull()
                ?: return SyncRead.Unreadable("The settings file on the server is not text.")
            return parseSyncDocument(text, names, now)
        } finally {
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    /**
     * Puts [text] on the server where [at] says the file is, or creates it there.
     *
     * Every write names the state the file was read in (`ifInState`), and creating never
     * replaces (`onExists` left at its default, which refuses), so a write that would
     * land on top of another computer's throws [SyncConflict] instead. The caller reads
     * again, merges, and tries once more.
     */
    fun write(at: SyncLocation, text: String) {
        val dir = Files.createTempDirectory("rampart-sync")
        val blobId = try {
            val file = dir.resolve(SYNC_FILE)
            Files.writeString(file, text)
            link.upload(file).blobId
        } finally {
            runCatching { dir.toFile().deleteRecursively() }
        }
        val answers = try {
            when {
                at.file != null -> link.fileCall(
                    syncSet(at.state) {
                        putJsonObject("update") { putJsonObject(at.file.id) { put("blobId", blobId) } }
                    },
                )
                at.folderId != null -> link.fileCall(
                    syncSet(at.state) { putJsonObject("create") { fileCreate(at.folderId, blobId) } },
                )
                // Two calls in one request, the second naming the folder the first made by
                // its creation id, so there is never a moment with an empty folder and no file.
                else -> link.fileCall(
                    syncSet(at.state) {
                        putJsonObject("create") {
                            putJsonObject("f") {
                                put("name", SYNC_FOLDER)
                                put("parentId", JsonNull)
                                put("nodeType", "directory")
                            }
                        }
                    },
                    syncSet(null) { putJsonObject("create") { fileCreate("#f", blobId) } },
                )
            }
        } catch (e: JmapError) {
            if (isStateMismatch(e)) throw SyncConflict()
            throw e
        }
        for (answer in answers) {
            val body = answer.getOrNull(1) as? JsonObject
                ?: throw JmapError("The server's reply about the settings file was empty.")
            for (field in listOf("notCreated", "notUpdated")) {
                val errors = body[field] as? JsonObject ?: continue
                val error = errors.values.firstOrNull() as? JsonObject ?: continue
                when ((error["type"] as? JsonPrimitive)?.contentOrNull) {
                    // Another computer made it, or removed it, since the read.
                    "alreadyExists", "notFound", "stateMismatch" -> throw SyncConflict()
                    else -> throw JmapError(setErrorSentence(error, "the settings file"))
                }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.fileCreate(parent: String, blobId: String) {
        putJsonObject("n") {
            put("name", SYNC_FILE)
            put("parentId", parent)
            put("blobId", blobId)
            put("type", "application/json")
        }
    }

    private fun syncQuery(callId: String, filter: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonArray =
        buildJsonArray {
            add("FileNode/query")
            addJsonObject {
                put("accountId", link.accountId)
                putJsonObject("filter", filter)
                put("limit", 50)
            }
            add(callId)
        }

    private fun syncGet(callId: String, queryId: String): JsonArray = buildJsonArray {
        add("FileNode/get")
        addJsonObject {
            put("accountId", link.accountId)
            putJsonObject("#ids") {
                put("resultOf", queryId)
                put("name", "FileNode/query")
                put("path", "/ids")
            }
            putJsonArray("properties") { FILE_NODE_PROPERTIES.forEach { add(it) } }
        }
        add(callId)
    }

    private fun syncSet(state: String?, args: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonArray =
        buildJsonArray {
            add("FileNode/set")
            add(
                buildJsonObject {
                    put("accountId", link.accountId)
                    state?.let { put("ifInState", it) }
                    args()
                },
            )
            add("s")
        }
}

/**
 * Whether a failed request was the server refusing `ifInState`. [Jmap] turns a method
 * level error into a [JmapError] naming its type, so the type is in the message.
 */
internal fun isStateMismatch(e: Throwable): Boolean = e.message?.contains("stateMismatch") == true

/** What one sync of one account did. */
internal data class SyncResult(
    /** The local keys whose values changed because of what the server held, by source prefix. */
    val applied: Set<String>,
    /** Something worth one sentence even though it worked, or null. */
    val note: String? = null,
)

/**
 * One sync of one account: read, merge into the local files, and write back if the
 * server's copy is missing anything.
 *
 * **Local first, and only from what was checked.** The merge is written into each local
 * file inside that file's own lock, from its current contents, so a change made on this
 * computer while the file was downloading is merged rather than lost. Nothing is sent if
 * that local write failed, and nothing from the server reaches a local file without
 * passing [SyncShape.fits].
 */
internal class SettingsSyncer(
    private val sources: List<SyncSource>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val names = syncedNames(sources)

    fun sync(file: SettingsFile): SyncResult {
        val applied = LinkedHashSet<String>()
        repeat(SYNC_ATTEMPTS) {
            val at = file.locate()
            at.blocked?.let { throw JmapError(it) }
            val read = at.file?.let { file.read(it, names, clock()) }
            if (read is SyncRead.Newer) {
                throw JmapError(
                    "The settings file on the server was written by a newer Rampart, so this one left it alone. " +
                        "Updating Rampart here will let it sync again.",
                )
            }
            val remote = (read as? SyncRead.Read)?.document ?: SyncDocument(emptyMap())
            applied += pull(remote.entries)
            val local = localAll()
            if (read is SyncRead.Read && remote.entries == local) return SyncResult(applied)
            try {
                file.write(at, encodeSyncDocument(local, remote.passthrough))
                val note = (read as? SyncRead.Unreadable)?.let { "${it.why} It was replaced with this computer's settings." }
                return SyncResult(applied, note)
            } catch (_: SyncConflict) {
                // Read again and merge again: the loop is the whole answer to a race.
            }
        }
        throw JmapError("Another computer kept changing the settings file at the same moment, so this one will try again later.")
    }

    /** Every allowlisted entry on this computer, across every source. */
    fun localAll(): Map<String, SyncEntry> =
        sources.flatMap { localEntries(it.prefix, it.keys, it.store.read()).entries }.associate { it.key to it.value }

    /** Merges [remote] into each local file and returns the names that changed. */
    private fun pull(remote: Map<String, SyncEntry>): Set<String> {
        val changed = LinkedHashSet<String>()
        for (source in sources) {
            val mine = remote.filterKeys { it.startsWith(source.prefix + ".") }
            if (mine.isEmpty()) continue
            var here: Set<String> = emptySet()
            val saved = source.store.write {
                val merged = mergeEntries(localEntries(source.prefix, source.keys, this), mine)
                here = applyEntries(source.prefix, source.keys, merged, this)
            }
            if (!saved) {
                throw JmapError(
                    "Rampart could not save the settings it read from the server on this computer, " +
                        "so it sent nothing back either.",
                )
            }
            here.mapTo(changed) { "${source.prefix}.$it" }
        }
        return changed
    }
}

/** Where one signed-in account stands. */
internal sealed interface SyncStatus {
    data object Off : SyncStatus

    /** This account cannot hold the file, and [why] says so. */
    data class LocalOnly(val why: String) : SyncStatus

    data object Working : SyncStatus

    data class Synced(val at: Long, val note: String? = null) : SyncStatus

    data class Failed(val why: String, val lastSynced: Long? = null) : SyncStatus
}

/** The line under the switch, for one account. */
internal fun syncStatusLine(
    status: SyncStatus,
    zone: ZoneId = Regional.zone(),
    locale: Locale = Regional.locale(),
): String {
    val region = shownIn(Regional.current(), locale, zone)
    fun time(at: Long) = formatTime(region, Instant.ofEpochMilli(at))
    return when (status) {
        SyncStatus.Off -> "Sync is off on this computer, so settings stay in the file here."
        is SyncStatus.LocalOnly -> "${status.why} Settings stay in the file on this computer."
        SyncStatus.Working -> "Syncing with the server now."
        is SyncStatus.Synced -> "Synced with the server at ${time(status.at)}." +
            (status.note?.let { " $it" } ?: "")
        is SyncStatus.Failed -> "Not synced: ${status.why}" +
            (status.lastSynced?.let { " Last synced at ${time(it)}." } ?: "")
    }
}

/**
 * One signed-in account as sync sees it: somewhere to keep the file, or why there is not.
 * [label] is what the settings page calls it, the address.
 */
internal data class SyncTarget(val key: String, val label: String, val link: FilesTransport?, val whyNot: String?)

/**
 * The running sync: one background thread, one account at a time, never two syncs at
 * once. Everything that asks for a sync goes through [soon], which is what makes a burst
 * of changes one write.
 */
internal object SettingsSync {
    val sources: List<SyncSource> by lazy {
        listOf(
            SyncSource("settings", SYNCED_SETTINGS, Settings.store),
            SyncSource("assistant", SYNCED_ASSISTANT, Assistant.store),
        )
    }

    private val worker = Executors.newSingleThreadScheduledExecutor { run ->
        Thread(run, "settings-sync").apply { isDaemon = true }
    }
    private val lock = Any()
    private var pending: ScheduledFuture<*>? = null
    private var periodic: ScheduledFuture<*>? = null

    @Volatile private var targets: List<SyncTarget> = emptyList()

    /** By account key, so two accounts with the same address on different servers stay apart. */
    @Volatile private var status: Map<String, SyncStatus> = emptyMap()
    private val lastGood = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val statusListeners = CopyOnWriteArrayList<(List<Pair<String, SyncStatus>>) -> Unit>()
    private val appliedListeners = CopyOnWriteArrayList<(Set<String>) -> Unit>()

    /** On unless this computer has been switched off. Kept in settings.json and never synced. */
    fun enabled(): Boolean = (Settings.store.read()[SYNC_SWITCH] as? JsonPrimitive)?.booleanOrNull ?: true

    fun setEnabled(on: Boolean) {
        Settings.store.write { put(SYNC_SWITCH, JsonPrimitive(on)) }
        publish()
        if (on) soon(0)
    }

    /** Each signed-in account's label and where it stands, in the order they signed in. */
    fun statuses(): List<Pair<String, SyncStatus>> =
        targets.map { it.label to (status[it.key] ?: SyncStatus.Working) }

    fun addStatusListener(listener: (List<Pair<String, SyncStatus>>) -> Unit) { statusListeners.add(listener) }
    fun removeStatusListener(listener: (List<Pair<String, SyncStatus>>) -> Unit) { statusListeners.remove(listener) }

    /** Told the names ("settings.theme") whose local values the server changed, so the window can show them. */
    fun addAppliedListener(listener: (Set<String>) -> Unit) { appliedListeners.add(listener) }
    fun removeAppliedListener(listener: (Set<String>) -> Unit) { appliedListeners.remove(listener) }

    /**
     * The accounts signed in now. A sync runs at once, which is the read on sign-in, and
     * then every [SYNC_EVERY_MS] for changes made on another computer.
     */
    fun follow(next: List<SyncTarget>) {
        targets = next
        publish()
        soon(0)
        synchronized(lock) {
            if (periodic == null) {
                periodic = worker.scheduleWithFixedDelay({ soon(0) }, SYNC_EVERY_MS, SYNC_EVERY_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    /**
     * Called from the write paths in Settings and Assistant, inside their file lock, after
     * [stampChanges] has stamped a change. Only schedules; the sending happens later.
     */
    fun localChanged() = soon(SYNC_DEBOUNCE_MS)

    private fun soon(delayMs: Long) = synchronized(lock) {
        pending?.cancel(false)
        pending = worker.schedule(::syncAll, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun syncAll() {
        if (!enabled()) {
            publish()
            return
        }
        val now = targets.filter { it.link != null }
        val syncer = SettingsSyncer(sources)
        val applied = LinkedHashSet<String>()
        // A second pass only when there is more than one account and the first pass
        // brought something in: a change read from the second account's file has then
        // not yet reached the first account's.
        for (pass in 0 until 2) {
            var brought = false
            for (target in now) {
                setStatus(target.key, SyncStatus.Working)
                runCatching { syncer.sync(SettingsFile(target.link!!)) }
                    .onSuccess {
                        if (it.applied.isNotEmpty()) brought = true
                        applied += it.applied
                        val at = System.currentTimeMillis()
                        lastGood[target.key] = at
                        setStatus(target.key, SyncStatus.Synced(at, it.note))
                    }
                    .onFailure {
                        val why = it.message?.trim()?.ifBlank { null } ?: "The server could not be reached."
                        setStatus(target.key, SyncStatus.Failed(why, lastGood[target.key]))
                    }
            }
            if (!brought || now.size < 2) break
        }
        if (applied.isNotEmpty()) appliedListeners.forEach { runCatching { it(applied) } }
    }

    private fun setStatus(key: String, next: SyncStatus) {
        status = status + (key to next)
        tell()
    }

    /** Recomputes every account's line from the switch and the accounts, keeping what is known. */
    private fun publish() {
        val on = enabled()
        status = targets.associate { target ->
            target.key to when {
                target.link == null -> SyncStatus.LocalOnly(target.whyNot ?: "This account has nowhere to keep settings.")
                !on -> SyncStatus.Off
                else -> status[target.key]?.takeIf { it !is SyncStatus.Off && it !is SyncStatus.LocalOnly }
                    ?: SyncStatus.Working
            }
        }
        tell()
    }

    private fun tell() {
        val lines = statuses()
        statusListeners.forEach { runCatching { it(lines) } }
    }
}
