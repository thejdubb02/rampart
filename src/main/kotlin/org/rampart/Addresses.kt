package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Somebody you have written to or heard from.
 *
 * [seen] is how often and [last] is when, in epoch seconds. Both, rather than either: the
 * person you mail every week and the one you mailed twice yesterday are both worth
 * offering, and one number cannot say both.
 */
internal data class Person(
    val email: String,
    val name: String = "",
    val seen: Int = 1,
    val last: Long = 0,
)

/**
 * The addresses Rampart offers while you type a recipient.
 *
 * Built out of your own mail rather than a contacts server, because **that is the source
 * every account has**. A server's address book is better where there is one and comes
 * later; a client that can only autocomplete against Stalwart is a client that cannot
 * autocomplete for most of the people who install it.
 *
 * Kept in a small file beside the accounts, so the second run already knows who you write
 * to. It holds nothing that is not already in the mailbox it was read from.
 */
internal object AddressBook {
    fun file(account: String): Path {
        val base = Accounts.file().parent
        // The account key is ours, but it ends up in a filename, so anything that could
        // climb out of the directory is taken out rather than trusted.
        val safe = account.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "default" }
        return base.resolve("addresses-$safe.json")
    }

    /** Never throws. A mangled file means no suggestions, not no composer. */
    fun read(path: Path): List<Person> = runCatching {
        if (!path.exists()) return emptyList()
        Json.parseToJsonElement(path.readText()).jsonObject["people"]?.jsonArray.orEmpty().mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            val email = o["email"]?.jsonPrimitive?.content?.takeIf { e -> e.isNotBlank() } ?: return@mapNotNull null
            Person(
                email = email,
                name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                seen = o["seen"]?.jsonPrimitive?.intOrNull ?: 1,
                last = o["last"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }
    }.getOrDefault(emptyList())

    /** Only the top few hundred are kept: the tail is noise and the file has to stay small. */
    fun write(people: List<Person>, path: Path) {
        val document = buildJsonObject {
            put("version", 1)
            put(
                "people",
                buildJsonArray {
                    people.sortedWith(byUsefulness).take(500).forEach {
                        add(
                            buildJsonObject {
                                put("email", it.email)
                                put("name", it.name)
                                put("seen", it.seen)
                                put("last", it.last)
                            },
                        )
                    }
                },
            )
        }
        path.parent?.createDirectories()
        path.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), document))
        runCatching {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        }
    }
}

/** Most written to, then most recent. Used for both ranking and for what to keep. */
private val byUsefulness = compareByDescending<Person> { it.seen }.thenByDescending { it.last }

/**
 * Folds one more sighting into the book.
 *
 * Not called `remember`: this is a Compose codebase and that name belongs to something
 * else entirely, which is a trap rather than a clash.
 *
 * Matching is on the address, lowercased, because `Dana@Example.org` and
 * `dana@example.org` are the same person and offering both is the kind of duplicate that
 * makes an autocomplete worse than none.
 *
 * A name already on file is kept when the new sighting has none, so one message with a bare
 * address does not wipe the name off somebody you have written to for a year.
 */
internal fun noted(book: List<Person>, email: String, name: String = "", at: Long = 0): List<Person> {
    val address = email.trim().lowercase()
    if (!looksLikeAddress(address)) return book
    val existing = book.firstOrNull { it.email == address }
    val merged = Person(
        email = address,
        name = name.trim().ifBlank { existing?.name.orEmpty() },
        seen = (existing?.seen ?: 0) + 1,
        last = maxOf(at, existing?.last ?: 0),
    )
    return book.filterNot { it.email == address } + merged
}

/**
 * Enough of an address to be worth keeping.
 *
 * Deliberately loose. This is not validation, it is a filter against putting `undisclosed
 * recipients` and a bare display name into the book; the server decides what is deliverable.
 */
internal fun looksLikeAddress(candidate: String): Boolean {
    val at = candidate.indexOf('@')
    return at > 0 && at == candidate.lastIndexOf('@') &&
        candidate.length > at + 3 && candidate.indexOf('.', at) > at && !candidate.any { it.isWhitespace() }
}

/**
 * Who to offer for what has been typed so far.
 *
 * A match on the start of the address or of any word in the name outranks one in the
 * middle, because that is what somebody typing three letters means: `dan` should offer Dana
 * before it offers Jordan.
 */
internal fun suggest(typed: String, book: List<Person>, limit: Int = 6): List<Person> {
    val needle = typed.trim().lowercase()
    if (needle.length < 2) return emptyList()
    val hits = book.filter { it.email.contains(needle) || it.name.lowercase().contains(needle) }
    return hits.sortedWith(
        compareByDescending<Person> { startsSomething(it, needle) }.then(byUsefulness),
    ).take(limit)
}

private fun startsSomething(person: Person, needle: String): Boolean =
    person.email.startsWith(needle) ||
        person.name.lowercase().split(' ', '.', '-').any { it.startsWith(needle) }

/**
 * What is being typed in a recipient field, given the whole field.
 *
 * A recipient field holds a comma separated list, so completion applies to the part after
 * the last comma and nothing before it gets touched.
 */
internal fun typedRecipient(field: String, caret: Int = field.length): String =
    field.take(caret.coerceIn(0, field.length)).substringAfterLast(',').trim()

/** The field with the part being typed replaced by a chosen address, ready for the next one. */
internal fun completeRecipient(field: String, chosen: String, caret: Int = field.length): String {
    val at = caret.coerceIn(0, field.length)
    val before = field.take(at)
    val kept = before.substringBeforeLast(',', missingDelimiterValue = "")
    val head = if (kept.isBlank()) "" else kept.trimEnd() + ", "
    return head + chosen + ", " + field.drop(at)
}
