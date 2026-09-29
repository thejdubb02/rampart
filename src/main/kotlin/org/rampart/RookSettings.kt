package org.rampart

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate

/*
 * The settings map: every setting Rook can find, read and propose a change to, in one list.
 *
 * Three places a setting can live, and they are kept apart because they are changed with
 * different credentials. Rampart's own preferences are a file on this computer. The mailbox's
 * are on the server and changed with the person's own mail session, the same one the
 * Settings pages use. The server's own configuration is changed only with the separate admin
 * sign-in, through the same client and the same gates as the admin console.
 *
 * Nothing in this file writes anything. It describes, reads and checks. Writing happens only
 * from `RookChanges.kt`, and only after a person has pressed Confirm on a card.
 */

/** Where a setting is kept, which is also whose credential changes it. */
internal enum class SettingHome(val words: String) {
    COMPUTER("Rampart on this computer"),
    MAILBOX("your mailbox on the server, changed with your own sign-in"),
    SERVER("the Stalwart server, changed with the separate admin sign-in"),
}

/** One value a choice can take, with the words the Settings page shows for it. */
internal data class SettingOption(val value: String, val label: String)

/** What a setting holds, which is also what a proposed value is checked against. */
internal sealed interface SettingKind {
    data object OnOff : SettingKind

    data class OneOf(val options: List<SettingOption>) : SettingKind

    data class Words(val maxLength: Int, val multiline: Boolean = false) : SettingKind

    /** A calendar day written 2026-10-02, or no day at all. */
    data object Day : SettingKind

    /** A field from the server's own schema, checked the way the admin console checks it. */
    data class Server(val type: AdminType, val choices: List<AdminChoice>) : SettingKind

    /** Something worth reading that is not a setting Rook can change, such as a list of filters. */
    data object Summary : SettingKind
}

internal data class SettingEntry(
    /** Stable and short, because the model has to type it back. */
    val id: String,
    val name: String,
    val description: String,
    val kind: SettingKind,
    val home: SettingHome,
    /** Where a person finds it in the app, in words. */
    val page: String,
    /** Other words somebody might use for it, such as "dark mode" for the theme. */
    val keywords: String = "",
)

/** A value as it will be written, with the words a card shows for it. */
internal sealed interface Checked {
    data class Ok(val value: JsonElement, val words: String) : Checked
    data class Bad(val reason: String) : Checked
}

/** Anything a place could not read or do, as one sentence a person can read. */
internal class SettingTrouble(message: String) : Exception(message)

/**
 * One of the three homes, as something the map can ask.
 *
 * An interface so the tests can stand a fake in for the mailbox and the server, and so the
 * list of things the map can reach is one file long.
 */
internal interface SettingPlace {
    /** Every entry this place describes. Never a network call: list_settings runs this often. */
    fun entries(): List<SettingEntry>

    /** The entry for an id, including ids this place makes up on demand, such as one per domain. */
    fun resolve(id: String): SettingEntry? = entries().firstOrNull { it.id == id }

    /** Null when Rook may propose a change to it, otherwise why not, in one sentence. */
    fun refusal(entry: SettingEntry): String?

    /** The value as it stands. May block on the network. Throws [SettingTrouble] when it cannot. */
    fun current(entry: SettingEntry): JsonElement

    /** Whether reading the value is cheap enough to show in a list of matches. */
    fun cheap(entry: SettingEntry): Boolean = false

    /** Changes with the same group are shown on one card and written together. */
    fun group(entry: SettingEntry): String

    /** A heading for a card of this group. */
    fun title(group: String): String

    /** A check beyond the kind, for one value. Null when it is fine. */
    fun problem(entry: SettingEntry, value: JsonElement): String? = null

    /** A check on a whole group of changes at once, such as an away reply with no message. */
    fun groupProblem(changes: List<Pair<SettingEntry, JsonElement>>): String? = null

    /** More to say about one setting than its value, such as the domains a server field is kept per. */
    fun more(entry: SettingEntry): String? = null

    /** Writes one group. Null when it worked, otherwise why not, in one sentence. */
    fun apply(changes: List<Pair<SettingEntry, JsonElement>>): String?
}

/** Every setting, from every place, and the one way to look one up. */
internal class SettingsMap(val places: List<SettingPlace>) {
    fun all(): List<Pair<SettingEntry, SettingPlace>> = places.flatMap { p -> p.entries().map { it to p } }

    fun find(id: String): Pair<SettingEntry, SettingPlace>? {
        val wanted = id.trim()
        if (wanted.isEmpty()) return null
        for (place in places) place.resolve(wanted)?.let { return it to place }
        return null
    }

    /**
     * The settings that match [query], best first.
     *
     * Every word has to be found somewhere in the setting, so "away until" does not bring
     * back every setting with "until" in its description. A word in the name counts for
     * more than one in the description, which is what puts the theme above the message
     * page when somebody asks for dark mode.
     */
    fun search(query: String): List<Pair<SettingEntry, SettingPlace>> {
        val words = searchWords(query)
        val everything = all()
        if (words.isEmpty()) return everything.filter { it.first.home != SettingHome.SERVER }
        return everything.mapNotNull { pair ->
            val e = pair.first
            val name = e.name.lowercase()
            val keys = (e.keywords + " " + e.id + " " + e.page).lowercase()
            val about = e.description.lowercase()
            var score = 0
            for (w in words) {
                score += when {
                    w in name -> 5
                    w in keys -> 3
                    w in about -> 1
                    else -> return@mapNotNull null
                }
            }
            // Rampart's own and the mailbox's first, because that is what most questions are
            // about, and a server has hundreds of fields that share their words.
            if (e.home != SettingHome.SERVER) score += 2
            pair to score
        }.sortedByDescending { it.second }.map { it.first }
    }
}

/** Lowercased words, with a plural's final s dropped so "signatures" finds "signature". */
internal fun searchWords(query: String): List<String> =
    query.lowercase().split(Regex("[^a-z0-9@.:]+"))
        .filter { it.length > 1 }
        .filterNot { it in setOf("my", "the", "to", "on", "of", "is", "what", "set", "turn", "change", "setting", "settings") }
        .map { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }
        .distinct()

/**
 * A proposed value, checked against what the setting takes.
 *
 * This is the wall between what the model said and what a card may show. The model's answer
 * is untrusted text: a value that is not one the setting takes never reaches a card, and the
 * model is told why in a sentence, the same as a person would be.
 */
internal fun checkValue(kind: SettingKind, raw: JsonElement?): Checked {
    val p = raw as? JsonPrimitive
    return when (kind) {
        SettingKind.OnOff -> onOff(p)?.let { Checked.Ok(JsonPrimitive(it), if (it) "On" else "Off") }
            ?: Checked.Bad("This is on or off, so the value has to be true or false.")
        is SettingKind.OneOf -> {
            val text = p?.contentOrNull?.trim()
            val chosen = if (text == null) null else kind.options.firstOrNull { sameOption(it.value, text) }
                ?: kind.options.firstOrNull { it.label.equals(text, ignoreCase = true) }
            if (chosen != null) Checked.Ok(JsonPrimitive(chosen.value), chosen.label)
            else Checked.Bad("That is not one of the choices. It takes " + kind.options.joinToString(", ") { optionWords(it) } + ".")
        }
        is SettingKind.Words -> {
            val text = when {
                raw == null || raw is JsonNull -> ""
                p == null -> return Checked.Bad("This takes text.")
                else -> p.content
            }
            when {
                text.length > kind.maxLength -> Checked.Bad("This can be at most ${kind.maxLength} characters.")
                !kind.multiline && ('\n' in text || '\r' in text) -> Checked.Bad("This takes a single line.")
                text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } ->
                    Checked.Bad("That has characters in it that are not text.")
                else -> Checked.Ok(JsonPrimitive(text), wordsFor(text))
            }
        }
        SettingKind.Day -> {
            val text = p?.contentOrNull?.trim().orEmpty()
            when {
                raw == null || raw is JsonNull || text.isEmpty() || text.equals("none", ignoreCase = true) ->
                    Checked.Ok(JsonNull, "No date")
                !Regex("""\d{4}-\d{2}-\d{2}""").matches(text) || runCatching { LocalDate.parse(text) }.isFailure ->
                    Checked.Bad("A day is written year first, like 2026-10-02.")
                else -> Checked.Ok(JsonPrimitive(text), text)
            }
        }
        is SettingKind.Server -> checkServer(kind, raw)
        SettingKind.Summary -> Checked.Bad("This one is for reading, not for changing.")
    }
}

private fun checkServer(kind: SettingKind.Server, raw: JsonElement?): Checked {
    val type = kind.type
    val p = raw as? JsonPrimitive
    val words = { v: JsonElement -> displayValue(type, v, { kind.choices }) }
    return when (type) {
        is AdminType.Flag -> onOff(p)?.let { Checked.Ok(JsonPrimitive(it), if (it) "Yes" else "No") }
            ?: if (type.nullable && (raw == null || raw is JsonNull)) Checked.Ok(JsonNull, "Not set")
            else Checked.Bad("This is yes or no, so the value has to be true or false.")
        is AdminType.Choice -> {
            val text = p?.contentOrNull?.trim()
            if (text == null && type.nullable) return Checked.Ok(JsonNull, "Not set")
            val chosen = kind.choices.firstOrNull { it.name == text }
                ?: kind.choices.firstOrNull { it.label.equals(text, ignoreCase = true) }
            if (chosen != null) Checked.Ok(JsonPrimitive(chosen.name), chosen.label)
            else Checked.Bad("That is not one of the choices. It takes " + kind.choices.joinToString(", ") { "${it.name} (${it.label})" } + ".")
        }
        is AdminType.Text -> if (type.secret) {
            Checked.Bad("Rook never handles passwords, keys or other secrets. Change this in the admin console.")
        } else {
            parsedToChecked(parseInput(type, p?.contentOrNull.orEmpty()), words)
        }
        is AdminType.Number, is AdminType.Reference, is AdminType.Timestamp ->
            parsedToChecked(parseInput(type, p?.contentOrNull.orEmpty()), words)
        else -> Checked.Bad("Rook cannot change this kind of value. The admin console can.")
    }
}

private fun parsedToChecked(parsed: Parsed, words: (JsonElement) -> String): Checked = when (parsed) {
    is Parsed.Ok -> Checked.Ok(parsed.value, words(parsed.value))
    is Parsed.Bad -> Checked.Bad(parsed.reason)
}

private fun onOff(p: JsonPrimitive?): Boolean? {
    p ?: return null
    p.booleanOrNull?.let { return it }
    return when (p.contentOrNull?.trim()?.lowercase()) {
        "on", "yes", "true", "enabled", "enable" -> true
        "off", "no", "false", "disabled", "disable" -> false
        else -> null
    }
}

/** "5000" and "5000.0" name the same choice, and so do "1.0" and "1". */
private fun sameOption(value: String, text: String): Boolean {
    if (value == text) return true
    val a = value.toDoubleOrNull() ?: return false
    val b = text.toDoubleOrNull() ?: return false
    return a == b
}

private fun optionWords(o: SettingOption): String =
    if (o.value.isEmpty()) "\"\" (${o.label})" else if (o.value == o.label) o.value else "${o.value} (${o.label})"

/** Long text cut down for a card or a list line, and empty text named as such. */
internal fun wordsFor(text: String): String = when {
    text.isEmpty() -> "Empty"
    text.length > 160 -> "\"" + text.take(157) + "...\""
    else -> "\"$text\""
}

/** A value as words, for a list line, a card or an answer to get_setting. */
internal fun describeValue(kind: SettingKind, value: JsonElement?): String {
    val p = value as? JsonPrimitive
    return when (kind) {
        SettingKind.OnOff -> when (p?.booleanOrNull) { true -> "On"; false -> "Off"; null -> "Not set" }
        is SettingKind.OneOf -> {
            val text = p?.contentOrNull
            if (text == null) "Not chosen yet"
            else kind.options.firstOrNull { sameOption(it.value, text) }?.label ?: text
        }
        is SettingKind.Words -> wordsFor(p?.contentOrNull.orEmpty())
        SettingKind.Day -> p?.contentOrNull?.ifBlank { null } ?: "No date"
        is SettingKind.Server -> displayValue(kind.type, value, { kind.choices })
        SettingKind.Summary -> p?.contentOrNull ?: "Nothing to show"
    }
}

/** What a setting takes, in words, for get_setting. */
internal fun describeKind(kind: SettingKind): String = when (kind) {
    SettingKind.OnOff -> "on or off (true or false)"
    is SettingKind.OneOf -> "one of " + kind.options.joinToString(", ") { optionWords(it) }
    is SettingKind.Words -> "text, at most ${kind.maxLength} characters" + if (kind.multiline) ", on several lines if wanted" else ", on one line"
    SettingKind.Day -> "a day written year first like 2026-10-02, or none"
    is SettingKind.Server -> when (val t = kind.type) {
        is AdminType.Flag -> "yes or no (true or false)"
        is AdminType.Choice -> "one of " + kind.choices.joinToString(", ") { "${it.name} (${it.label})" }
        is AdminType.Number -> when (t.format) {
            "duration" -> "a length of time with a unit, like 30s, 5m, 2h or 1d"
            "size" -> "a size, like 512 KB, 50 MB or 2 GB"
            "float" -> "a number"
            else -> "a whole number"
        } + (t.min?.let { ", at least ${it.toLong()}" } ?: "") + (t.max?.let { ", at most ${it.toLong()}" } ?: "")
        is AdminType.Text -> if (t.secret) "a secret, which Rook never handles" else when (t.format) {
            "emailAddress" -> "an email address"
            "uri" -> "a full address with a scheme, like https://"
            "ipAddress" -> "an IP address"
            "ipNetwork" -> "an IP address or range"
            else -> "text"
        } + (t.maxLength?.let { ", at most $it characters" } ?: "")
        is AdminType.Reference -> "the id of a ${t.objectName.removePrefix("x:")}"
        is AdminType.Timestamp -> "a date and time like 2026-09-28T12:00:00Z"
        else -> "a kind of value only the admin console edits"
    } + if (kind.type.nullable) ", or nothing" else ""
    SettingKind.Summary -> "nothing: it is for reading"
}

/** A missing value, JSON null and an empty string all mean "not set" when two readings are compared. */
internal fun sameSetting(a: JsonElement?, b: JsonElement?): Boolean {
    fun norm(v: JsonElement?): JsonElement? = when {
        v == null || v is JsonNull -> null
        v is JsonPrimitive && v.isString && v.content.isEmpty() -> null
        else -> v
    }
    val x = norm(a)
    val y = norm(b)
    if (x is JsonPrimitive && y is JsonPrimitive) {
        if (x.content == y.content) return true
        // A choice is kept as text on a card and as a number in the file, so 5000 and
        // "5000.0" are the same reading.
        val dx = x.content.toDoubleOrNull()
        val dy = y.content.toDoubleOrNull()
        return dx != null && dy != null && dx == dy
    }
    return x == y
}
