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
