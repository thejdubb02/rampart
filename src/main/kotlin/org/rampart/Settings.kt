package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
            path.writeText(Json.encodeToString(JsonObject.serializer(), JsonObject(updated)))
        }
    }

    /** null means follow the operating system, which is the state before anyone chooses. */
    fun dark(): Boolean? = read()["dark"]?.jsonPrimitive?.booleanOrNull

    fun setDark(value: Boolean) = write { put("dark", kotlinx.serialization.json.JsonPrimitive(value)) }

    fun sidebarCollapsed(): Boolean = read()["sidebarCollapsed"]?.jsonPrimitive?.booleanOrNull ?: false

    fun setSidebarCollapsed(value: Boolean) =
        write { put("sidebarCollapsed", kotlinx.serialization.json.JsonPrimitive(value)) }

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
