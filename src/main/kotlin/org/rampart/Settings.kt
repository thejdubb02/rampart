package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Preferences, kept apart from [Accounts] on purpose: that file is meant to be written by
 * someone else and handed over, and one person's choice of theme has no business travelling
 * with it.
 */
object Settings {
    private fun file() = Accounts.file().resolveSibling("settings.json")

    /** null means follow the operating system, which is the state before anyone chooses. */
    fun dark(): Boolean? = runCatching {
        val path = file()
        if (!path.exists()) return null
        Json.parseToJsonElement(path.readText()).jsonObject["dark"]?.jsonPrimitive?.booleanOrNull
    }.getOrNull()

    fun setDark(value: Boolean) {
        runCatching {
            val path = file()
            path.parent?.createDirectories()
            path.writeText(Json.encodeToString(JsonObject.serializer(), buildJsonObject { put("dark", value) }))
        }
    }
}
