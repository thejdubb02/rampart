package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
 * A message you write often, kept once.
 *
 * Most of what a reply-drafting feature would do, with no model anywhere near it, no key,
 * no spend and nothing leaving the machine. The same sentence sent for the fifth time is
 * the commonest thing anyone writes, and it does not need an LLM to produce it.
 *
 * Placeholders are `{{name}}`, which is the one syntax people have already seen. What a
 * name means is decided when the template is used, not when it is written.
 */
internal data class Template(val name: String, val subject: String, val body: String)

/** What a template needs filling in, in the order it first appears. */
internal fun placeholders(template: Template): List<String> =
    PLACEHOLDER.findAll(template.subject + "\n" + template.body)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

private val PLACEHOLDER = Regex("\\{\\{([^}]*)\\}\\}")

/**
 * The template with its placeholders filled in.
 *
 * A name with no value is left exactly as it was written rather than emptied, because a
 * `{{first name}}` still sitting in a draft is a mistake somebody can see and fix, and a
 * sentence with a hole where a name should be is one they will send.
 */
internal fun fill(template: Template, values: Map<String, String>): Template = template.copy(
    subject = fillOne(template.subject, values),
    body = fillOne(template.body, values),
)

private fun fillOne(text: String, values: Map<String, String>): String =
    PLACEHOLDER.replace(text) { match ->
        val name = match.groupValues[1].trim()
        values[name]?.takeIf { it.isNotBlank() } ?: match.value
    }

/**
 * Some of the filling done for you, from the message being replied to.
 *
 * Deliberately a short list of names people actually use. A template that quietly supports
 * forty magic words is one nobody can predict the behaviour of.
 */
internal fun known(summary: Summary?, me: String = ""): Map<String, String> {
    if (summary == null) return mapOf("me" to me).filterValues { it.isNotBlank() }
    val first = summary.from.trim().split(' ').firstOrNull().orEmpty()
    return mapOf(
        "name" to summary.from.trim(),
        "first name" to first,
        "email" to summary.fromEmail,
        "subject" to summary.subject,
        "me" to me,
    ).filterValues { it.isNotBlank() }
}

/** Where templates live. Beside the accounts, and shared by every account. */
internal object Templates {
    fun file(): Path = Accounts.file().parent.resolve("templates.json")

    /** Never throws. A mangled file means no templates, not no composer. */
    fun read(path: Path = file()): List<Template> = runCatching {
        if (!path.exists()) return emptyList()
        Json.parseToJsonElement(path.readText()).jsonObject["templates"]?.jsonArray.orEmpty().mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            Template(
                name = name,
                subject = o["subject"]?.jsonPrimitive?.content.orEmpty(),
                body = o["body"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    fun write(templates: List<Template>, path: Path = file()) {
        val document = buildJsonObject {
            put("version", 1)
            put(
                "templates",
                buildJsonArray {
                    templates.forEach {
                        add(
                            buildJsonObject {
                                put("name", it.name)
                                put("subject", it.subject)
                                put("body", it.body)
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

/**
 * The same names as [known], worked out from a draft instead of a message being answered.
 *
 * A template is most often reached for on a new message, where there is no [Summary] to
 * read from, so the recipient field is the only thing that knows who this is to. The
 * address book supplies the name when the field holds a bare address, which is what
 * autocomplete leaves behind.
 */
internal fun templateValues(
    to: String,
    from: String,
    subject: String,
    book: List<Person> = emptyList(),
): Map<String, String> {
    val first = to.split(',').firstOrNull()?.trim().orEmpty()
    val email = Regex("<([^>]*)>").find(first)?.groupValues?.get(1)?.trim()
        ?: first.takeIf { looksLikeAddress(it) }.orEmpty()
    // Only when there is a display name to take. A bare address has no name in it, and
    // substringBefore would hand back the address itself and call it one.
    val name = first.substringBefore('<').takeIf { '<' in first }?.trim()?.trim('"').orEmpty()
        .ifBlank { book.firstOrNull { it.email.equals(email, ignoreCase = true) }?.name.orEmpty() }
        .ifBlank { email.substringBefore('@') }
    return mapOf(
        "name" to name,
        "first name" to name.split(' ').first(),
        "email" to email,
        "subject" to subject,
        "me" to from,
    ).filterValues { it.isNotBlank() }
}
