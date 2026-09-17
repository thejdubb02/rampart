package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Which servers to offer on the sign-in screen. Everything needed to find a mailbox and
 * nothing needed to open one.
 *
 * The file this comes from is meant to be shareable and to be written by someone else,
 * including by an assistant setting a mailbox up for a person who does not want to think
 * about JMAP. That only stays safe if it can never carry a secret, so reading drops every
 * field but these three and writing emits only these three. A `password` key in the file
 * is read as nothing and is gone the next time the file is written.
 */
data class SavedAccount(val name: String, val server: String, val email: String)

object Accounts {
    fun file(): Path {
        // Tests set this so a test run cannot rewrite the settings of whoever is running
        // it. Nothing in the app sets it, so the real path is the only one that ships.
        System.getProperty("rampart.config.dir")?.takeIf { it.isNotBlank() }
            ?.let { return Path.of(it, "accounts.json") }
        val home = System.getProperty("user.home")
        val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "Rampart") }
            ?: System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "rampart") }
            ?: Path.of(home, ".config", "rampart")
        return base.resolve("accounts.json")
    }

    /** Never throws. A broken or hand-mangled file means no saved accounts, not no app. */
    fun read(path: Path = file()): List<SavedAccount> = runCatching {
        if (!path.exists()) return emptyList()
        Json.parseToJsonElement(path.readText()).jsonObject["accounts"]?.jsonArray.orEmpty().mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            val server = o.text("server") ?: return@mapNotNull null
            val email = o.text("email") ?: return@mapNotNull null
            SavedAccount(o.text("name") ?: email, server, email)
        }
    }.getOrDefault(emptyList())

    fun write(accounts: List<SavedAccount>, path: Path = file()) {
        val document = buildJsonObject {
            put("version", 1)
            put(
                "accounts",
                buildJsonArray {
                    accounts.forEach {
                        add(
                            buildJsonObject {
                                put("name", it.name)
                                put("server", it.server)
                                put("email", it.email)
                            },
                        )
                    }
                },
            )
        }
        path.parent?.createDirectories()
        path.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), document))
        runCatching { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
    }

    /** Adds an account after a sign-in that worked, so the second run is one click. */
    fun remember(account: SavedAccount, path: Path = file()) {
        val existing = read(path)
        if (existing.any { it.server == account.server && it.email == account.email }) return
        write(existing + account, path)
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
}
