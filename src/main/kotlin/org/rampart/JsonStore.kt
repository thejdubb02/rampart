package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * One small JSON file beside the accounts file, read whole and written whole.
 *
 * **Every write reads the file first and changes one key**, so saving the window size
 * cannot lose the theme, and recording what a model call cost cannot lose the key it was
 * made with. That matters more than it sounds: these are written from several places, on
 * a keystroke in the case of a signature.
 *
 * **Written beside the file and moved over it.** An interrupted write cannot then leave
 * half a file behind, which is the failure that loses somebody's settings rather than one
 * setting. Saving on every keystroke makes being caught mid-write a great deal likelier
 * than it would otherwise be.
 *
 * **A read never throws.** A missing file, a truncated one, or something that is not JSON
 * at all all read as empty, and every caller then falls back to its own defaults. A
 * settings file somebody edited by hand should cost them their settings, not the
 * application.
 *
 * Written once because there are two of these and there will be more: [Settings] for
 * preferences and [Assistant] for what a model is allowed to do and what it has cost.
 * They are separate files on purpose, and they should not be two separate opinions about
 * how to write a file safely.
 */
internal class JsonStore(private val name: String) {
    private fun file() = Accounts.file().resolveSibling(name)

    fun read(): JsonObject = runCatching {
        val path = file()
        if (!path.exists()) JsonObject(emptyMap())
        else Json.parseToJsonElement(path.readText()).jsonObject
    }.getOrDefault(JsonObject(emptyMap()))

    fun write(change: MutableMap<String, JsonElement>.() -> Unit) {
        runCatching {
            val updated = read().toMutableMap().apply(change)
            val path = file()
            path.parent?.createDirectories()
            val temp = path.resolveSibling("$name.new")
            temp.writeText(Json.encodeToString(JsonObject.serializer(), JsonObject(updated)))
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
