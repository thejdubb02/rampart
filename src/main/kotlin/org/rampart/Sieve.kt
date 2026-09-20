package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Filters, as Sieve, in the format Bulwark already uses.
 *
 * Rules run on the server, so they work with Rampart closed, which is the whole point of
 * having them. Sieve (RFC 5228) is the language, and Stalwart carries it over JMAP as
 * `urn:ietf:params:jmap:sieve`.
 *
 * **The format is not ours.** Reading a real script off the server showed that Bulwark
 * writes its own rule model as JSON in a comment at the top and generates the Sieve
 * underneath it:
 *
 *     /* @metadata:begin
 *     {"version":1,"rules":[ ... ]}
 *     @metadata:end */
 *
 *     require ["fileinto"];
 *
 *     # Rule: DMARC
 *     if header :contains "From" "..." { fileinto "Deleted Items"; }
 *
 * Inventing a second format beside it would mean each client silently destroying the
 * other's rules, on the same mailbox, for the same person. So Rampart reads and writes
 * that one. Rules made here open in Bulwark and the other way round.
 *
 * Two rules the rest of this file exists to keep:
 *
 * - **Never lose a rule we do not fully understand.** A rule whose JSON contains a field,
 *   comparator or action Rampart does not show is kept exactly as it was and marked
 *   read-only, rather than being re-encoded as the nearest thing we do know.
 * - **Never lose what is not ours at all.** Anything in the script beyond the rules the
 *   metadata accounts for, which on this mailbox is a hand written block of delivery
 *   probes, is preserved verbatim and written back underneath.
 */

/** What a rule looks at. The wire names are Bulwark's. */
internal enum class Field(val wire: String, val label: String, val header: String?) {
    FROM("from", "From", "From"),
    TO("to", "To", "To"),
    CC("cc", "Cc", "Cc"),
    SUBJECT("subject", "Subject", "Subject"),
    BODY("body", "Body", null),
}

/** How it compares. */
internal enum class Match(val wire: String, val label: String) {
    CONTAINS("contains", "contains"),
    IS("is", "is exactly"),
    STARTS("starts_with", "starts with"),
    ENDS("ends_with", "ends with"),
}

/** What it does when it matches. */
internal sealed interface Act {
    data class FileInto(val folder: String) : Act
    data class Tag(val keyword: String) : Act
    data object MarkRead : Act
    data object Star : Act
    data object Delete : Act
}

internal data class Test(val field: Field, val match: Match, val value: String)

/**
 * One rule.
 *
 * [raw] is the JSON it was read from, kept so a rule can be written back exactly as it
 * arrived. [understood] false means the JSON held something this build does not show, and
 * the rule is then displayed but not editable: guessing at it is how somebody's filter
 * quietly changes meaning.
 *
 * [global] marks a rule that belongs to the set kept for every account rather than to this
 * one. It is written into the metadata so the mark survives on the server: without it a
 * global rule pushed to three accounts becomes three unrelated account rules the moment
 * anything reads them back, and turning the set off could never remove them again.
 */
internal data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val tests: List<Test>,
    val acts: List<Act>,
    val all: Boolean = true,
    val stop: Boolean = false,
    val enabled: Boolean = true,
    val understood: Boolean = true,
    val global: Boolean = false,
    val raw: JsonObject? = null,
)

private const val BEGIN = "/* @metadata:begin"
private const val END = "@metadata:end */"

private val json = Json { ignoreUnknownKeys = true }

/**
 * A whole filter script: the rules, and everything in it that is not ours.
 *
 * [tail] is kept verbatim and written back under the generated rules. It is the reason this
 * is a type rather than a list.
 */
internal data class Script(
    val rules: List<Rule>,
    val tail: String = "",
    /** False when the script has no metadata block, so it was not written by a builder. */
    val builderMade: Boolean = true,
) {
    val editable: Boolean get() = builderMade
}

// --- reading -------------------------------------------------------------------------

/**
 * Reads a script.
 *
 * No metadata block means nobody's builder wrote this, so nothing is claimed: the rules
 * come back empty, the whole text is the tail, and the caller offers the raw editor only.
 * Parsing hand written Sieve into a builder is exactly where a round trip loses a
 * condition, and the raw editor costs nothing.
 */
internal fun scriptOf(text: String): Script {
    val begin = text.indexOf(BEGIN)
    val end = text.indexOf(END)
    if (begin < 0 || end < begin) return Script(emptyList(), text, builderMade = false)

    val meta = text.substring(begin + BEGIN.length, end).trim()
    val rules = runCatching {
        json.parseToJsonElement(meta).jsonObject["rules"]?.jsonArray.orEmpty().mapNotNull(::ruleOf)
    }.getOrNull() ?: return Script(emptyList(), text, builderMade = false)

    val body = text.substring(end + END.length)
    return Script(rules, tailOf(body, rules))
}

/**
 * Everything after the part the metadata accounts for.
 *
 * Walks the generated section and stops at the first thing that is not a `require` or a
 * rule block the metadata named. On this mailbox that is a hand written block of delivery
 * probes, which Bulwark also leaves alone, and which must survive us saving.
 */
private fun tailOf(body: String, rules: List<Rule>): String {
    val names = rules.map { it.name }.toSet()
    val lines = body.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        val named = line.removePrefix("#").trim().removePrefix("Rule:").trim()
        when {
            line.isEmpty() -> i++
            line.startsWith("require") -> {
                while (i < lines.size && !lines[i].contains(';')) i++
                i++
            }
            line.startsWith("#") && named in names -> {
                // Skip the comment and the block under it, however long it runs.
                i++
                while (i < lines.size && lines[i].trim().isEmpty()) i++
                val close = lines.drop(i).indexOfFirst { it.trim() == "}" }
                if (close < 0) return lines.drop(i).joinToString("\n").trim()
                i += close + 1
            }
            else -> return lines.drop(i).joinToString("\n").trim()
        }
    }
    return ""
}

/** One rule out of the metadata, or null if it is not even shaped like one. */
internal fun ruleOf(element: kotlinx.serialization.json.JsonElement): Rule? {
    val o = element as? JsonObject ?: return null
    val name = o["name"]?.jsonPrimitive?.content ?: return null
    var understood = true

    val tests = (o["conditions"] as? JsonArray).orEmpty().map { c ->
        val co = c.jsonObject
        val field = Field.entries.firstOrNull { it.wire == co["field"]?.jsonPrimitive?.content }
        val match = Match.entries.firstOrNull { it.wire == co["comparator"]?.jsonPrimitive?.content }
        if (field == null || match == null) understood = false
        Test(
            field ?: Field.FROM,
            match ?: Match.CONTAINS,
            co["value"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    val acts = (o["actions"] as? JsonArray).orEmpty().mapNotNull { a ->
        val ao = a.jsonObject
        val value = ao["value"]?.jsonPrimitive?.content.orEmpty()
        when (ao["type"]?.jsonPrimitive?.content) {
            "move" -> Act.FileInto(value)
            "mark_read" -> Act.MarkRead
            "mark_flagged", "flag", "star" -> Act.Star
            "delete", "discard" -> Act.Delete
            "tag", "addflag", "label" -> Act.Tag(value)
            else -> {
                understood = false
                null
            }
        }
    }

    return Rule(
        id = o["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
        name = name,
        tests = tests,
        acts = acts,
        all = o["matchType"]?.jsonPrimitive?.content != "any",
        stop = o["stopProcessing"]?.jsonPrimitive?.content == "true",
        enabled = o["enabled"]?.jsonPrimitive?.content != "false",
        global = o["global"]?.jsonPrimitive?.content == "true",
        understood = understood && tests.isNotEmpty() && acts.isNotEmpty(),
        raw = o,
    )
}

// --- writing -------------------------------------------------------------------------

/** Sieve needs a string escaped before it goes inside quotes, and it has only two rules. */
internal fun sieveQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * The value as its tag expects it.
 *
 * Sieve has no "starts with", so those become `:matches` with a wildcard, and any `*` or
 * `?` the person typed is escaped first. Without that, a filter looking for a literal
 * asterisk would quietly become one that matches every message.
 */
private fun pattern(match: Match, value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")
    return when (match) {
        Match.STARTS -> "$escaped*"
        Match.ENDS -> "*$escaped"
        else -> value
    }
}

private fun tagOf(match: Match) = when (match) {
    Match.CONTAINS -> ":contains"
    Match.IS -> ":is"
    Match.STARTS, Match.ENDS -> ":matches"
}

private fun testLine(test: Test): String = when (test.field) {
    Field.BODY -> "body :text ${tagOf(test.match)} ${sieveQuote(pattern(test.match, test.value))}"
    else -> "header ${tagOf(test.match)} ${sieveQuote(test.field.header!!)} " +
        sieveQuote(pattern(test.match, test.value))
}

private fun actLine(act: Act): String = when (act) {
    is Act.FileInto -> "fileinto ${sieveQuote(act.folder)};"
    is Act.Tag -> "addflag ${sieveQuote(act.keyword)};"
    Act.MarkRead -> "addflag \"\\\\Seen\";"
    Act.Star -> "addflag \"\\\\Flagged\";"
    Act.Delete -> "discard;"
}

/** Which extensions the script actually uses, so the `require` line is not a guess. */
private fun required(rules: List<Rule>): List<String> = buildList {
    if (rules.any { r -> r.acts.any { it is Act.FileInto } }) add("fileinto")
    if (rules.any { r -> r.acts.any { it is Act.Tag || it is Act.MarkRead || it is Act.Star } }) add("imap4flags")
    if (rules.any { r -> r.tests.any { it.field == Field.BODY } }) add("body")
}

/**
 * The rule as metadata.
 *
 * A rule this build does not fully understand is written back byte for byte, because
 * rebuilding it from a model that dropped a condition is how a filter silently changes
 * meaning. One it does understand is rebuilt, and the JSON it arrived as is laid
 * underneath rather than instead: an edit has to reach the file, and a key another client
 * wrote and this one does not show has to survive us saving.
 */
internal fun metaOf(rule: Rule): JsonObject {
    if (!rule.understood) return rule.raw ?: JsonObject(emptyMap())
    return JsonObject(rule.raw.orEmpty() + built(rule))
}

private fun built(rule: Rule): JsonObject = buildJsonObject {
    put("id", rule.id)
    put("name", rule.name)
    put("enabled", rule.enabled)
    put("global", rule.global)
    put("matchType", if (rule.all) "all" else "any")
    put(
        "conditions",
        buildJsonArray {
            rule.tests.forEach {
                add(
                    buildJsonObject {
                        put("field", it.field.wire)
                        put("comparator", it.match.wire)
                        put("value", it.value)
                    },
                )
            }
        },
    )
    put(
        "actions",
        buildJsonArray {
            rule.acts.forEach {
                add(
                    buildJsonObject {
                        when (it) {
                            is Act.FileInto -> { put("type", "move"); put("value", it.folder) }
                            is Act.Tag -> { put("type", "tag"); put("value", it.keyword) }
                            Act.MarkRead -> put("type", "mark_read")
                            Act.Star -> put("type", "mark_flagged")
                            Act.Delete -> put("type", "delete")
                        }
                    },
                )
            }
        },
    )
    put("stopProcessing", rule.stop)
}

/**
 * The whole script: metadata, the rules it describes, then whatever was not ours.
 *
 * A disabled rule keeps its metadata and generates no Sieve, which is how a rule is turned
 * off without being lost.
 */
internal fun sieveOf(script: Script): String {
    val out = StringBuilder()
    val meta = buildJsonObject {
        put("version", 1)
        put("rules", buildJsonArray { script.rules.forEach { add(metaOf(it)) } })
    }
    out.append(BEGIN).append('\n')
        .append(Json.encodeToString(JsonObject.serializer(), meta)).append('\n')
        .append(END).append("\n\n")

    val live = script.rules.filter { it.enabled && it.tests.isNotEmpty() && it.acts.isNotEmpty() }
    val needs = required(live)
    if (needs.isNotEmpty()) {
        out.append("require [").append(needs.joinToString(", ") { sieveQuote(it) }).append("];\n\n")
    }
    live.forEach { rule ->
        out.append("# Rule: ").append(rule.name).append('\n')
        val join = if (rule.all) "allof" else "anyof"
        val condition = if (rule.tests.size == 1) {
            testLine(rule.tests.single())
        } else {
            "$join(" + rule.tests.joinToString(", ", transform = ::testLine) + ")"
        }
        out.append("if ").append(condition).append(" {\n")
        rule.acts.forEach { out.append("    ").append(actLine(it)).append('\n') }
        if (rule.stop) out.append("    stop;\n")
        out.append("}\n\n")
    }
    if (script.tail.isNotBlank()) out.append(script.tail.trim()).append('\n')
    return out.toString().trimEnd() + "\n"
}
