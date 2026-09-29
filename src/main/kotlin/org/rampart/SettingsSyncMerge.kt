package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * The rules of settings sync, as plain functions of their arguments: which keys may leave
 * the machine, what the file on the server looks like, and how two copies are merged.
 * SettingsSync.kt does the talking and SettingsSyncUi.kt the drawing, and neither of them
 * decides anything this file has an opinion on. docs/settings-sync.md is the design.
 */

/**
 * What a synced value is allowed to look like.
 *
 * Checked on everything read from the server before it is written into a local file,
 * because the accessors in Settings.kt were written for a file only Rampart writes and
 * some of them call `jsonPrimitive` on what they find. A value of the wrong shape from
 * the server would then throw on every read, which turns a bad file on the server into
 * an application that will not start.
 */
internal enum class SyncShape {
    /** A string, number or boolean. */
    TEXT,

    /** A list of objects, each of which its own accessor decodes and may skip. */
    LIST_OF_OBJECTS,

    /** A list of strings, numbers or booleans. */
    LIST_OF_TEXT,

    /** An object whose values are strings, numbers or booleans. */
    MAP_OF_TEXT,

    /** An object whose values are lists of strings. */
    MAP_OF_LISTS;

    fun fits(value: JsonElement): Boolean = when (this) {
        TEXT -> value.isText()
        LIST_OF_OBJECTS -> value is JsonArray && value.all { it is JsonObject }
        LIST_OF_TEXT -> value is JsonArray && value.all { it.isText() }
        MAP_OF_TEXT -> value is JsonObject && value.values.all { it.isText() }
        MAP_OF_LISTS -> value is JsonObject && value.values.all { list -> list is JsonArray && list.all { it.isText() } }
    }
}

private fun JsonElement.isText(): Boolean = this is JsonPrimitive && this !is JsonNull

/**
 * One local file whose allowlisted keys are synced.
 *
 * [prefix] names the file in the synced document, so "theme" in settings.json is
 * "settings.theme" on the server and two files can never collide on a key name.
 */
internal class SyncSource(
    val prefix: String,
    /** The allowlist for this file. A key that is not here never leaves the machine. */
    val keys: Map<String, SyncShape>,
    val store: JsonStore,
)

/**
 * The allowlist. **Anything not named here never leaves the machine**, including every
 * key added to Settings.kt in future until somebody decides, here, that it should.
 *
 * Left out on purpose, with the reason, so the next person does not add them by habit:
 *
 * - Window position and size, compose window size, side panel widths, the collapsed
 *   sidebar and folded sections: they fit one screen and are wrong on the next.
 * - Notifications and close to tray: whether this computer should interrupt somebody is
 *   a question about this computer.
 * - Every address Rampart sends data to (the tracking and diagnostics companions, ntfy,
 *   Gotify, Rook's model endpoint) and the ntfy switches that go with them. A file on the
 *   server must never be able to change where this machine sends anything.
 * - Consent: sending diagnostics and each Rook feature agreed to. Agreeing on one machine
 *   is not agreeing on another.
 * - Rook's mode, model, prices, ceiling and ledger: the key is per machine in the
 *   operating system's store, and so is what has been spent.
 * - Senders whose remote images load: that widens what the reader fetches, and widening
 *   the reader is a decision made in front of the message, not carried in from elsewhere.
 * - Cursors and markers (the tracking cursor, the last changelog seen): machine state.
 * - The sync switch itself, and the stamps sync keeps.
 */
internal val SYNCED_SETTINGS: Map<String, SyncShape> = mapOf(
    "theme" to SyncShape.TEXT,
    "customThemes" to SyncShape.LIST_OF_OBJECTS,
    "iconPack" to SyncShape.TEXT,
    "loader" to SyncShape.TEXT,
    "density" to SyncShape.TEXT,
    "tintRowsByTag" to SyncShape.TEXT,
    "tagColours" to SyncShape.MAP_OF_TEXT,
    "savedSearches" to SyncShape.LIST_OF_OBJECTS,
    "order" to SyncShape.TEXT,
    "markReadDelay" to SyncShape.TEXT,
    "archiveBy" to SyncShape.TEXT,
    "messageMode" to SyncShape.TEXT,
    "messageScale" to SyncShape.TEXT,
    "undoSeconds" to SyncShape.TEXT,
    "undoBarSeconds" to SyncShape.TEXT,
    "signatureAboveQuote" to SyncShape.TEXT,
    "confirmBeforeSend" to SyncShape.TEXT,
    "defaultReplyAll" to SyncShape.TEXT,
    "exactIdentitiesOnly" to SyncShape.TEXT,
    "subAddressDelimiter" to SyncShape.TEXT,
    "attachmentPosition" to SyncShape.TEXT,
    "attachmentClickBehavior" to SyncShape.TEXT,
    "trackedDomains" to SyncShape.LIST_OF_TEXT,
    "changelogSuppressed" to SyncShape.TEXT,
    "quietHours" to SyncShape.TEXT,
)

/**
 * Rook's settings that are neither secret nor about where data goes: only the folders
 * that must never be read. A boundary somebody drew on one computer is one they expect
 * on the next, and it is the one Rook setting whose absence on a second machine could
 * send mail somewhere its owner said it must not go.
 */
internal val SYNCED_ASSISTANT: Map<String, SyncShape> = mapOf(
    "deniedFolders" to SyncShape.MAP_OF_LISTS,
)

/** The key each local file keeps its own stamps under. Never synced itself. */
internal const val SYNC_STAMPS = "syncStamps"

/** The per-computer switch, in settings.json. Never synced: it is what decides syncing. */
internal const val SYNC_SWITCH = "settingsSync"

/** The version of the file on the server. A newer one is left alone rather than rewritten. */
internal const val SYNC_FORMAT = 1

/** The largest settings file read from the server. Anything bigger is not one of ours. */
internal const val SYNC_MAX_BYTES = 512 * 1024

/** How far ahead of this clock a stamp from elsewhere may be before it is not believed. */
internal const val SYNC_MAX_AHEAD_MS = 366L * 24 * 60 * 60 * 1000

/**
 * One setting as synced: its value, or [value] null for "put back to the default", and
 * when that was decided.
 *
 * A reset is kept as an entry rather than dropped. Otherwise a setting put back to its
 * default on one computer would simply be missing from the file, and missing reads as
 * "nobody has said anything", so the old value on the next computer would win and come
 * back.
 */
internal data class SyncEntry(val value: JsonElement?, val updatedAt: Long)

/** What was read from the server: the entries this version understands, and the rest. */
internal data class SyncDocument(
    val entries: Map<String, SyncEntry>,
    /**
     * Entries under names this version does not sync, kept exactly as they were so a newer
     * Rampart's settings survive an older one writing the file. Never applied locally.
     */
    val passthrough: Map<String, JsonElement> = emptyMap(),
)

/** Why a file from the server was not used. */
internal sealed interface SyncRead {
    data class Read(val document: SyncDocument) : SyncRead

    /** Not JSON, not ours, or not a shape this version can read. Ignored and replaced. */
    data class Unreadable(val why: String) : SyncRead

    /** Written by a later Rampart. Not applied and not overwritten. */
    data class Newer(val format: Int) : SyncRead
}

/** Every allowlisted name across [sources], as it appears on the server. */
internal fun syncedNames(sources: List<SyncSource>): Map<String, SyncShape> =
    sources.flatMap { source -> source.keys.map { (key, shape) -> "${source.prefix}.$key" to shape } }.toMap()

/**
 * The entries a local file holds for its allowlisted keys.
 *
 * A key with a value and no stamp was set before sync existed, and counts as stamped at
 * zero, so any change made anywhere since beats it. A stamp with no value is a reset.
 * A key with neither has never been touched and says nothing.
 */
internal fun localEntries(prefix: String, keys: Map<String, SyncShape>, file: Map<String, JsonElement>): Map<String, SyncEntry> {
    val stamps = stampsIn(file)
    val out = LinkedHashMap<String, SyncEntry>()
    for ((key, shape) in keys) {
        val value = file[key]?.takeIf { shape.fits(it) }
        val stamp = stamps[key]
        if (value == null && stamp == null) continue
        out["$prefix.$key"] = SyncEntry(value, stamp ?: 0L)
    }
    return out
}

private fun stampsIn(file: Map<String, JsonElement>): Map<String, Long> =
    (file[SYNC_STAMPS] as? JsonObject)?.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.longOrNull?.let { key to it }
    }?.toMap().orEmpty()

/**
 * The stamp for a change made now.
 *
 * Never lower than one past the newest stamp this file has seen, from anywhere. So a
 * change made after this computer took in a change from another one always beats it,
 * even when this computer's clock is behind the other's. That is the part of clock skew
 * that matters in practice: "I changed it back and it did not stick". What it cannot fix
 * is two computers changing the same setting before either has heard of the other; then
 * the later clock wins, which is the documented choice (docs/settings-sync.md).
 */
internal fun nextStamp(file: Map<String, JsonElement>, now: Long): Long =
    maxOf(now, (stampsIn(file).values.maxOrNull() ?: 0L) + 1)

/**
 * Stamps every allowlisted key that [change] altered, inside the same write, so a value
 * and its stamp are always on disk together. Returns whether anything was stamped, which
 * is whether there is anything new to send.
 */
internal fun stampChanges(
    keys: Map<String, SyncShape>,
    before: Map<String, JsonElement>,
    after: MutableMap<String, JsonElement>,
    now: Long,
): Boolean {
    val changed = keys.keys.filter { before[it] != after[it] }
    if (changed.isEmpty()) return false
    val stamp = nextStamp(after, now)
    val stamps = (after[SYNC_STAMPS] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    changed.forEach { stamps[it] = JsonPrimitive(stamp) }
    after[SYNC_STAMPS] = JsonObject(stamps)
    return true
}

/**
 * Which of two entries for the same setting wins: the later stamp, and on a tie the one
 * that sorts higher, so two computers deciding the same tie independently decide it the
 * same way and the file settles rather than flipping between them.
 */
internal fun newer(a: SyncEntry?, b: SyncEntry?): SyncEntry? {
    if (a == null) return b
    if (b == null) return a
    if (a.updatedAt != b.updatedAt) return if (a.updatedAt > b.updatedAt) a else b
    val ka = a.value?.toString().orEmpty()
    val kb = b.value?.toString().orEmpty()
    return if (ka >= kb) a else b
}

/** Newest wins per setting, never per file, so unrelated changes on two computers both survive. */
internal fun mergeEntries(local: Map<String, SyncEntry>, remote: Map<String, SyncEntry>): Map<String, SyncEntry> =
    (local.keys + remote.keys).associateWith { newer(local[it], remote[it])!! }

/**
 * Writes [merged] into one local file, in place, and returns the keys whose value
 * changed. Only this source's allowlisted keys are touched; everything else in the file,
 * the window, the secrets' names, the switch, is left exactly as it was.
 */
internal fun applyEntries(
    prefix: String,
    keys: Map<String, SyncShape>,
    merged: Map<String, SyncEntry>,
    file: MutableMap<String, JsonElement>,
): Set<String> {
    val stamps = (file[SYNC_STAMPS] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    val changed = LinkedHashSet<String>()
    for ((key, shape) in keys) {
        val entry = merged["$prefix.$key"] ?: continue
        val value = entry.value?.takeIf { shape.fits(it) }
        if (entry.value != null && value == null) continue
        if (file[key] != value) {
            if (value == null) file.remove(key) else file[key] = value
            changed += key
        }
        stamps[key] = JsonPrimitive(entry.updatedAt)
    }
    file[SYNC_STAMPS] = JsonObject(stamps)
    return changed
}

/** The file as it goes to the server. Only [entries] and [passthrough] are in it. */
internal fun encodeSyncDocument(entries: Map<String, SyncEntry>, passthrough: Map<String, JsonElement> = emptyMap()): String {
    val body = buildJsonObject {
        put("format", SYNC_FORMAT)
        put("about", "Rampart's settings, synced between computers. docs/settings-sync.md in the Rampart repository.")
        putJsonObject("settings") {
            passthrough.toSortedMap().forEach { (name, raw) -> put(name, raw) }
            entries.toSortedMap().forEach { (name, entry) ->
                put(
                    name,
                    buildJsonObject {
                        put("updatedAt", entry.updatedAt)
                        if (entry.value == null) put("reset", true) else put("value", entry.value)
                    },
                )
            }
        }
    }
    return PRETTY.encodeToString(JsonObject.serializer(), body)
}

private val PRETTY = Json { prettyPrint = true }

/**
 * Reads the file from the server. Never throws: anything wrong with it is an answer.
 *
 * Entries are checked one at a time and a bad one is dropped on its own, so one mangled
 * setting does not cost every other. Names not in [names] are kept aside untouched.
 * A stamp from further ahead than [SYNC_MAX_AHEAD_MS] is not believed, because a single
 * computer with its clock set years ahead would otherwise win every argument until then.
 */
internal fun parseSyncDocument(text: String, names: Map<String, SyncShape>, now: Long): SyncRead {
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        ?: return SyncRead.Unreadable("The settings file on the server is not JSON.")
    val format = (root["format"] as? JsonPrimitive)?.intOrNull
        ?: return SyncRead.Unreadable("The settings file on the server does not say which format it is in.")
    if (format > SYNC_FORMAT) return SyncRead.Newer(format)
    val settings = root["settings"] as? JsonObject
        ?: return SyncRead.Unreadable("The settings file on the server holds no settings.")
    val entries = LinkedHashMap<String, SyncEntry>()
    val passthrough = LinkedHashMap<String, JsonElement>()
    for ((name, raw) in settings) {
        val shape = names[name]
        if (shape == null) {
            passthrough[name] = raw
            continue
        }
        val o = raw as? JsonObject ?: continue
        val at = (o["updatedAt"] as? JsonPrimitive)?.takeIf { it.isString.not() }?.longOrNull ?: continue
        if (at < 0 || at > now + SYNC_MAX_AHEAD_MS) continue
        val reset = (o["reset"] as? JsonPrimitive)?.contentOrNull == "true"
        val value = o["value"]
        when {
            reset -> entries[name] = SyncEntry(null, at)
            value != null && shape.fits(value) -> entries[name] = SyncEntry(value, at)
        }
    }
    return SyncRead.Read(SyncDocument(entries, passthrough))
}
