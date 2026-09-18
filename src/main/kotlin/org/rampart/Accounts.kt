package org.rampart

import jakarta.mail.AuthenticationFailedException
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
 * about JMAP. That only stays safe if it can never carry a secret, so reading keeps the
 * fields that find a mailbox and drops everything else. A `password` key in the file
 * is read as nothing and is gone the next time the file is written.
 */
data class SavedAccount(
    val name: String,
    val server: String,
    val email: String,
    /** "jmap" or "imap". */
    val protocol: String = "jmap",
    /** Where mail is sent from. Empty on JMAP, which sends through the same server. */
    val sendServer: String = "",
)

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
            // A record with no protocol is a JMAP account: that is all Rampart could save
            // until now, and treating it as IMAP would send a JMAP host to the wrong protocol.
            val protocol = o.text("protocol") ?: "jmap"
            SavedAccount(o.text("name") ?: email, server, email, protocol, o.text("sendServer") ?: "")
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
                                put("protocol", it.protocol)
                                put("sendServer", it.sendServer)
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

/**
 * A server that answered and refused the password is the right server. Trying the rest of
 * the list after that turns one clear refusal into a minute of waiting and then an error
 * about the last host, which is the wrong one.
 */
internal fun passwordRejected(error: Throwable): Boolean {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    return chain.any {
        it is AuthenticationFailedException ||
            it.javaClass.name.endsWith("AuthenticationFailedException") ||
            (it is JmapError &&
                it.message.orEmpty().contains("did not accept that email address and password"))
    }
}

/**
 * Discovery is a guess. A hostname someone typed is not: they already know where the
 * mailbox lives, and walking the published list after that would sign them into the
 * wrong server the moment any earlier candidate happened to accept the same password.
 *
 * [protocol] is only set when a saved account already told us which one worked. A typed
 * hostname with no such memory is tried as JMAP then IMAP on that host, never as a
 * different host.
 */
internal fun routesToTry(
    email: String,
    server: String,
    protocol: String = "",
    lookup: (String) -> List<Srv> = ::srv,
): List<Route> {
    val typed = server.trim()
    if (typed.isBlank()) return routesFor(email, lookup)
    val host = hostOfServer(typed)
    val port = portOfServer(typed)
    return when (protocol.trim().lowercase()) {
        "imap" -> listOf(Route.Imap(host, port ?: 993))
        "jmap" -> listOf(Route.Jmap(typed))
        else -> listOf(Route.Jmap(typed), Route.Imap(host, port ?: 993))
    }
}

internal fun accountFor(route: Route, email: String): SavedAccount = when (route) {
    is Route.Jmap -> SavedAccount(email, route.server, email, "jmap")
    is Route.Imap -> SavedAccount(
        name = email,
        server = withPort(route.host, route.port, 993),
        email = email,
        protocol = "imap",
        sendServer = withPort(route.sendHost, route.sendPort, 587),
    )
}

internal fun lookingFor(route: Route): String {
    val host = when (route) {
        is Route.Jmap -> hostOfServer(route.server).ifBlank { route.server }
        is Route.Imap -> route.host
    }
    return "Looking for a server at $host"
}

internal fun noServerFor(email: String): String =
    "No mail server could be found for $email. Type the server name under Server settings."

internal fun openRoute(route: Route, email: String, password: String): MailBackend = when (route) {
    is Route.Jmap -> Jmap.connect(route.server, email, password)
    // The submission host travels with the route. Defaulting it to the reading host would
    // work by luck on the servers that run both on one machine and fail on the ones that do
    // not, and our own is the second kind: 465 is open for submission and 587 is not.
    is Route.Imap -> Imap.connect(route.host, email, password, route.port, route.sendHost, route.sendPort)
}

/** The same thing for an account already signed in once, whose protocol is known. */
internal fun openSaved(account: SavedAccount, password: String): MailBackend =
    if (account.protocol == "imap") {
        Imap.connect(
            host = hostOfServer(account.server),
            user = account.email,
            password = password,
            port = portOfServer(account.server) ?: 993,
            sendHost = hostOfServer(account.sendServer).ifBlank { hostOfServer(account.server) },
            sendPort = portOfServer(account.sendServer) ?: 587,
        )
    } else {
        Jmap.connect(account.server, account.email, password)
    }

/** A port that is not the default lives in the host string, the same way [Route] writes it. */
internal fun withPort(host: String, port: Int, default: Int): String =
    if (port == default) host else "$host:$port"

internal fun hostOfServer(server: String): String {
    val host = stripServer(server)
    val colon = host.lastIndexOf(':')
    if (colon > 0 && host.substring(colon + 1).all { it.isDigit() }) return host.substring(0, colon)
    return host
}

internal fun portOfServer(server: String): Int? {
    val host = stripServer(server)
    val colon = host.lastIndexOf(':')
    if (colon <= 0) return null
    return host.substring(colon + 1).toIntOrNull()
}

private fun stripServer(server: String): String {
    var s = server.trim()
    s = s.removePrefix("https://").removePrefix("http://")
    return s.substringBefore("/")
}
