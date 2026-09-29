package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/** A missing destination is different from invalid model output because the person can create it. */
internal class MissingFolder(val folder: String) : Exception("There is no folder called $folder.")

/** The complete contract between the model and the rule builder. */
internal fun ruleSystem(folders: List<String>): String = buildString {
    appendLine("Turn the person's request into exactly one mail filter. Return strict JSON only.")
    appendLine("Do not use Markdown, a code fence, comments, prose, or keys not listed here.")
    appendLine("The only rule object is:")
    appendLine("""{"name":"short name","matchType":"all|any","stopProcessing":false,"conditions":[condition],"actions":[action]}""")
    appendLine("Conditions may be joined with all or any. Each condition has field, comparator, and value.")
    appendLine("Fields are exactly: from, to, cc, subject, body, header, size, has_attachment, list_id.")
    appendLine("Comparators are exactly: contains, is, starts_with, matches.")
    appendLine("A header condition also has a header key containing the RFC header name.")
    appendLine("A size condition uses comparator is and a value such as > 10M or < 500K.")
    appendLine("A has_attachment condition uses comparator is and value true.")
    appendLine("Actions are exactly: move with value, tag with value, mark_read, star, forward with value, discard.")
    appendLine("stopProcessing true means no later rule is evaluated. It is not an action.")
    if (folders.isEmpty()) appendLine("No folders exist, so do not use move.") else {
        appendLine("Existing folder names are exactly: " + folders.joinToString(", ") + ".")
        appendLine("Use an existing spelling when possible. Never invent a folder.")
    }
    appendLine("Examples:")
    appendLine("Request: Mail from anyone at example.com whose subject contains invoice goes to Receipts and is marked read.")
    appendLine("""{"name":"Example.com invoices","matchType":"all","stopProcessing":false,"conditions":[{"field":"from","comparator":"contains","value":"@example.com"},{"field":"subject","comparator":"contains","value":"invoice"}],"actions":[{"type":"move","value":"Receipts"},{"type":"mark_read"}]}""")
    appendLine("Request: Star mail sent to sales or carrying List-Id customers.example.")
    appendLine("""{"name":"Sales or customer list","matchType":"any","stopProcessing":false,"conditions":[{"field":"to","comparator":"contains","value":"sales"},{"field":"list_id","comparator":"contains","value":"customers.example"}],"actions":[{"type":"star"}]}""")
    appendLine("Request: Discard messages over 10 MB and stop.")
    appendLine("""{"name":"Large mail","matchType":"all","stopProcessing":true,"conditions":[{"field":"size","comparator":"is","value":"> 10M"}],"actions":[{"type":"discard"}]}""")
    appendLine("Request: Forward messages with an attachment to archive@example.com.")
    appendLine("""{"name":"Forward attachments","matchType":"all","stopProcessing":false,"conditions":[{"field":"has_attachment","comparator":"is","value":"true"}],"actions":[{"type":"forward","value":"archive@example.com"}]}""")
    appendLine("If the request cannot be represented exactly, return {\"error\":\"A plain sentence explaining what is unsupported.\"}.")
}

internal fun rulePacket(config: AssistantConfig, words: String, folders: List<String>): String =
    Llm.packet(config.model, ruleSystem(folders), words.trim(), maxTokens = 700)

/** The second turn includes the rejected answer and the exact reason it was rejected. */
internal fun ruleRepairPacket(config: AssistantConfig, words: String, folders: List<String>, answer: String, error: String): String =
    Llm.packetOf(
        config.model,
        ruleSystem(folders),
        listOf(
            Said("user", words.trim()),
            Said("assistant", answer),
            Said("user", "Your answer was invalid: $error Return a corrected rule as strict JSON only."),
        ),
        maxTokens = 700,
    )

private val strictJson = Json { isLenient = false; ignoreUnknownKeys = false }
private val headerName = Regex("^[A-Za-z0-9][A-Za-z0-9-]*$")
private val sieveSize = Regex("^[<>]\\s*[1-9][0-9]*[KMG]?$", RegexOption.IGNORE_CASE)
private val email = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

private fun failure(message: String): Result<Rule> = Result.failure(LlmError(message))

/** Parses model text, validates every key and value, and creates a fresh local rule id. */
internal fun ruleOfAnswer(answer: String, folders: List<String>): Result<Rule> {
    if ('{' !in answer) return failure("The answer did not contain a JSON object.")
    val root = jsonObjectsIn(answer).firstOrNull() ?: return failure("The answer did not contain valid JSON.")
    root["error"]?.let {
        if (root.keys != setOf("error")) return failure("A refusal may contain only the error field.")
        val sentence = (it as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return if (sentence.isBlank()) failure("The error field was empty.") else failure(sentence)
    }
    val allowed = setOf("name", "matchType", "stopProcessing", "conditions", "actions")
    (root.keys - allowed).firstOrNull()?.let { return failure("The rule contained unsupported field '$it'.") }
    val missing = allowed - root.keys
    if (missing.isNotEmpty()) return failure("The rule was missing field '${missing.first()}'.")
    val name = root.string("name") ?: return failure("The name must be text.")
    if (name.isBlank()) return failure("The name must not be empty.")
    val matchType = root.string("matchType") ?: return failure("matchType must be 'all' or 'any'.")
    if (matchType !in setOf("all", "any")) return failure("matchType must be 'all' or 'any'.")
    val stop = (root["stopProcessing"] as? JsonPrimitive)?.booleanOrNull
        ?: return failure("stopProcessing must be true or false.")
    val conditions = root["conditions"] as? JsonArray ?: return failure("conditions must be an array.")
    if (conditions.isEmpty()) return failure("The rule needs at least one condition.")
    val tests = mutableListOf<Test>()
    conditions.forEachIndexed { index, element ->
        val item = element as? JsonObject ?: return failure("Condition ${index + 1} must be an object.")
        val fieldName = item.string("field") ?: return failure("Condition ${index + 1} needs a text field.")
        val field = Field.entries.firstOrNull { it.wire == fieldName }
            ?: return failure("Condition ${index + 1} uses unsupported field '$fieldName'.")
        val expected = if (field == Field.HEADER) setOf("field", "comparator", "value", "header") else setOf("field", "comparator", "value")
        (item.keys - expected).firstOrNull()?.let { return failure("Condition ${index + 1} contained unsupported field '$it'.") }
        if (!item.keys.containsAll(expected)) return failure("Condition ${index + 1} was missing a required field.")
        val comparatorName = item.string("comparator") ?: return failure("Condition ${index + 1} needs a text comparator.")
        val match = listOf(Match.CONTAINS, Match.IS, Match.STARTS, Match.MATCHES).firstOrNull { it.wire == comparatorName }
            ?: return failure("Condition ${index + 1} uses unsupported comparator '$comparatorName'.")
        val value = item.string("value") ?: return failure("Condition ${index + 1} needs a text value.")
        val header = item.string("header")
        if (field == Field.HEADER && (header == null || !headerName.matches(header))) return failure("Condition ${index + 1} needs a valid header name.")
        if (field == Field.SIZE && (match != Match.IS || !sieveSize.matches(value))) return failure("A size condition must use comparator 'is' and a value such as '> 10M'.")
        if (field == Field.ATTACHMENT && (match != Match.IS || value != "true")) return failure("A has_attachment condition must use comparator 'is' and value 'true'.")
        if (field !in setOf(Field.SIZE, Field.ATTACHMENT) && value.isBlank()) return failure("Condition ${index + 1} must not have an empty value.")
        tests += Test(field, match, value, header)
    }
    val actions = root["actions"] as? JsonArray ?: return failure("actions must be an array.")
    if (actions.isEmpty()) return failure("The rule needs at least one action.")
    val acts = mutableListOf<Act>()
    actions.forEachIndexed { index, element ->
        val item = element as? JsonObject ?: return failure("Action ${index + 1} must be an object.")
        val type = item.string("type") ?: return failure("Action ${index + 1} needs a text type.")
        val needsValue = type in setOf("move", "tag", "forward")
        val expected = if (needsValue) setOf("type", "value") else setOf("type")
        (item.keys - expected).firstOrNull()?.let { return failure("Action ${index + 1} contained unsupported field '$it'.") }
        if (item.keys != expected) return failure("Action ${index + 1} was missing a required field.")
        val value = item.string("value").orEmpty()
        acts += when (type) {
            "move" -> {
                if (value.isBlank()) return failure("A move action needs a folder name.")
                val real = folders.firstOrNull { it.equals(value, ignoreCase = true) } ?: return Result.failure(MissingFolder(value))
                Act.FileInto(real)
            }
            "tag" -> if (value.isBlank()) return failure("A tag action needs a tag.") else Act.Tag(value)
            "mark_read" -> Act.MarkRead
            "star" -> Act.Star
            "forward" -> if (!email.matches(value)) return failure("A forward action needs a valid email address.") else Act.Forward(value)
            "discard" -> Act.Delete
            else -> return failure("Action ${index + 1} uses unsupported action '$type'.")
        }
    }
    return Result.success(Rule(UUID.randomUUID().toString(), name, tests, acts, matchType == "all", stop, raw = null))
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Finds complete JSON objects while ignoring braces inside quoted JSON strings. */
private fun jsonObjectsIn(text: String): Sequence<JsonObject> = sequence {
    text.indices.filter { text[it] == '{' }.forEach { start ->
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val parsed = runCatching {
                                strictJson.parseToJsonElement(text.substring(start, index + 1)) as? JsonObject
                            }.getOrNull()
                            if (parsed != null) yield(parsed)
                            break
                        }
                    }
                }
            }
        }
    }
}
