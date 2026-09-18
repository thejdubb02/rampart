package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Where the window was and how big, so reopening puts it back. */
data class SavedWindow(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean)

/**
 * Preferences, kept apart from [Accounts] on purpose: that file is meant to be written by
 * someone else and handed over, and one person's theme and window size have no business
 * travelling with it.
 *
 * Every write reads the file first and changes one key, so saving the window size cannot
 * lose the theme.
 */
object Settings {
    private fun file() = Accounts.file().resolveSibling("settings.json")

    private fun read(): JsonObject = runCatching {
        val path = file()
        if (!path.exists()) JsonObject(emptyMap())
        else Json.parseToJsonElement(path.readText()).jsonObject
    }.getOrDefault(JsonObject(emptyMap()))

    private fun write(change: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit) {
        runCatching {
            val updated = read().toMutableMap().apply(change)
            val path = file()
            path.parent?.createDirectories()
            // Written beside the file and moved over it, so an interrupted write cannot
            // leave half a file behind. Signatures save on every keystroke, which makes
            // being caught mid-write a great deal likelier than it was.
            val temp = path.resolveSibling("settings.json.new")
            temp.writeText(Json.encodeToString(JsonObject.serializer(), JsonObject(updated)))
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** The chosen theme's key, or null before anyone has chosen one. */
    fun theme(): String? = read()["theme"]?.jsonPrimitive?.contentOrNull

    fun setTheme(key: String) = write { put("theme", JsonPrimitive(key)) }

    /**
     * What the light/dark switch was set to before themes existed, and nothing writes it any
     * more. It is still read so that an existing install that had been switched to dark opens
     * dark rather than snapping back to whatever the operating system says.
     */
    fun dark(): Boolean? = read()["dark"]?.jsonPrimitive?.booleanOrNull

    /** On by default: a mail client that does not tell you about mail is a folder browser. */
    fun notifyOnArrival(): Boolean = read()["notify"]?.jsonPrimitive?.booleanOrNull ?: true

    fun setNotifyOnArrival(value: Boolean) = write { put("notify", JsonPrimitive(value)) }

    /** An order that is no longer in the enum reads as the default rather than crashing. */
    internal fun order(): Order = read()["order"]?.jsonPrimitive?.contentOrNull
        ?.let { name -> Order.entries.firstOrNull { it.name == name } } ?: Order.NEWEST

    internal fun setOrder(value: Order) = write { put("order", JsonPrimitive(value.name)) }

    /**
     * How long an open message waits before it counts as read, in milliseconds.
     *
     * Zero means at once, which is the default and what Rampart has always done. The reason
     * to want anything else is arrow-keying down a list: without a pause, every message you
     * pass through is marked read, which is how a morning's unread mail disappears.
     */
    fun markReadDelay(): Long = read()["markReadDelay"]?.jsonPrimitive?.longOrNull ?: 0L

    fun setMarkReadDelay(value: Long) = write { put("markReadDelay", JsonPrimitive(value)) }

    /**
     * Where Archive puts things: "" the Archive folder itself, "year", or "month".
     *
     * A folder with fifteen years of mail in it is a folder nobody opens. The subfolder is
     * made on the first message of a new year or month and never otherwise.
     */
    fun archiveBy(): String = read()["archiveBy"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun setArchiveBy(value: String) = write { put("archiveBy", JsonPrimitive(value)) }

    /**
     * Whether the sign-off goes above the quoted original rather than under all of it.
     *
     * Off by default, which is where every sign-off has been until now and where webmail
     * has it set. See [signed] for what each of the two actually looks like.
     */
    fun signatureAboveQuote(): Boolean = read()["signatureAboveQuote"]?.jsonPrimitive?.booleanOrNull ?: false

    fun setSignatureAboveQuote(value: Boolean) =
        write { put("signatureAboveQuote", JsonPrimitive(value)) }

    /**
     * The colour chosen for each tag, by lowercased keyword.
     *
     * Kept here rather than on the server because there is nowhere on the server to keep
     * it: webmail's colours are in per-user encrypted files only it can open, and the
     * mailbox itself has no place for a client's furniture. A tag with nothing stored uses
     * the colour derived from its name, which is what everyone starts with.
     */
    fun tagColours(): Map<String, Long> =
        (read()["tagColours"] as? JsonObject)?.mapNotNull { (key, value) ->
            value.jsonPrimitive.longOrNull?.let { key to it }
        }?.toMap() ?: emptyMap()

    /** [colour] null puts a tag back to the colour derived from its name. */
    fun setTagColour(keyword: String, colour: Long?) = write {
        val current = (this["tagColours"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val key = keyword.lowercase()
        if (colour == null) current.remove(key) else current[key] = JsonPrimitive(colour)
        put("tagColours", JsonObject(current))
    }

    /**
     * The sidebar sections that are folded away.
     *
     * Kept as the set that is closed rather than the set that is open, so a tag or an
     * account that appears later is open by default. The other way round, every new tag
     * would arrive folded and look like it had not arrived at all.
     */
    fun collapsedSections(): Set<String> =
        (read()["collapsedSections"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()

    fun setCollapsedSections(sections: Set<String>) = write {
        put("collapsedSections", JsonArray(sections.sorted().map(::JsonPrimitive)))
    }

    /**
     * Whether a tagged row in the message list carries its tag's colour.
     *
     * Off by default, which is the one place this deliberately differs from Bulwark. Its
     * list is roomier; Rampart's already tints an unread row, and two tints on one row read
     * as a rendering fault rather than as two facts.
     */
    fun tintRowsByTag(): Boolean = read()["tintRowsByTag"]?.jsonPrimitive?.booleanOrNull == true

    fun setTintRowsByTag(value: Boolean) = write { put("tintRowsByTag", JsonPrimitive(value)) }

    /**
     * Where the companion server is, or empty when there is not one.
     *
     * Empty is the normal case and the whole feature is then visibly unavailable rather
     * than silently missing: a tracking toggle that does nothing is worse than no toggle.
     * The token that goes with it is in the credential store, never here.
     */
    fun trackingServer(): String = read()["trackingServer"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun setTrackingServer(value: String) = write { put("trackingServer", JsonPrimitive(value.trim())) }

    /**
     * The recipient domains tracking was last switched on for.
     *
     * By domain rather than by address: deciding to track outreach is a decision about a
     * company, and being asked again for every new contact at the same place is how a
     * per-message switch turns into somebody reaching for a global one.
     */
    fun trackedDomains(): Set<String> =
        (read()["trackedDomains"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()

    fun rememberTracking(domain: String, on: Boolean) = write {
        val now = trackedDomains().toMutableSet()
        if (on) now.add(domain.lowercase()) else now.remove(domain.lowercase())
        put("trackedDomains", JsonArray(now.sorted().map(::JsonPrimitive)))
    }

    /** The moment the companion was last asked what had been fetched. */
    fun trackingCursor(): Long = read()["trackingCursor"]?.jsonPrimitive?.longOrNull ?: 0L

    fun setTrackingCursor(value: Long) = write { put("trackingCursor", JsonPrimitive(value)) }

    /** Which loader is drawn while Rampart waits. Kept apart from the theme, like the icons. */
    fun loader(): String = read()["loader"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun setLoader(value: String) = write { put("loader", JsonPrimitive(value)) }

    /** Which icon pack, kept apart from the theme so the two can be chosen separately. */
    fun iconPack(): String = read()["iconPack"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun setIconPack(value: String) = write { put("iconPack", JsonPrimitive(value)) }

    /**
     * How long a sent message waits before it actually goes, in seconds.
     *
     * Five by default, which is long enough to notice the wrong recipient and short enough
     * that nobody waits for it. Zero turns it off for anyone who finds the pause worse than
     * the mistake.
     */
    fun undoSeconds(): Int = read()["undoSeconds"]?.jsonPrimitive?.intOrNull ?: 5

    fun setUndoSeconds(value: Int) = write { put("undoSeconds", JsonPrimitive(value)) }

    /**
     * How long the "archived" strip stays up offering to take it back, in seconds.
     *
     * A different number from [undoSeconds] and deliberately so. That one is how long a
     * message is physically held before it goes, and it costs something: every send waits.
     * This one is only how long an offer stays on screen, and the move it offers to reverse
     * can still be reversed by hand afterwards, so it can be generous.
     *
     * Zero means it stays until dismissed, which is what it did before there was a choice.
     */
    fun undoBarSeconds(): Int = read()["undoBarSeconds"]?.jsonPrimitive?.intOrNull ?: 8

    fun setUndoBarSeconds(value: Int) = write { put("undoBarSeconds", JsonPrimitive(value)) }

    /**
     * Senders whose pictures may be fetched from the web. Domains, not addresses: see
     * [imageSenderKey].
     */
    fun imageSenders(): Set<String> =
        (read()["imageSenders"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
            ?: emptySet()

    fun allowImagesFrom(key: String) = write {
        val current = (this["imageSenders"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        if (current.none { it.jsonPrimitive.contentOrNull == key }) current.add(JsonPrimitive(key))
        put("imageSenders", JsonArray(current))
    }

    fun sidebarCollapsed(): Boolean = read()["sidebarCollapsed"]?.jsonPrimitive?.booleanOrNull ?: false

    fun setSidebarCollapsed(value: Boolean) =
        write { put("sidebarCollapsed", JsonPrimitive(value)) }

    /**
     * null when nothing is stored, or when what is stored would put the window somewhere
     * nobody can reach it. A monitor that has been unplugged since the last run would
     * otherwise reopen Rampart off the edge of the screen, where it looks like it failed
     * to start.
     */
    fun window(): SavedWindow? {
        val saved = read()["window"]?.jsonObject ?: return null
        fun int(key: String) = saved[key]?.jsonPrimitive?.intOrNull
        val width = int("width") ?: return null
        val height = int("height") ?: return null
        val x = int("x") ?: return null
        val y = int("y") ?: return null
        if (width < 640 || height < 480 || width > 20_000 || height > 20_000) return null
        if (x < -width / 2 || y < -64 || x > 20_000 || y > 20_000) return null
        return SavedWindow(x, y, width, height, saved["maximized"]?.jsonPrimitive?.booleanOrNull ?: false)
    }

    fun setWindow(value: SavedWindow) = write {
        put(
            "window",
            buildJsonObject {
                put("x", value.x)
                put("y", value.y)
                put("width", value.width)
                put("height", value.height)
                put("maximized", value.maximized)
            },
        )
    }

}
