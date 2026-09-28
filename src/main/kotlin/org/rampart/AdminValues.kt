package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal

/*
 * Values on admin screens: how each field type is shown, how what somebody typed becomes
 * the value the server wants, and what changed between the object as read and as edited.
 *
 * All of it pure, so it is tested without a server or a window. The wire formats are the
 * ones Stalwart 0.16 writes in `crates/registry`: a duration is a count of milliseconds, a
 * size is a count of bytes, a set is an object whose keys are its members, and a list of
 * nested objects is an object keyed "0", "1" and so on.
 */

/** What an input box turned into: a value for the server, or the reason it cannot be one. */
internal sealed interface Parsed {
    data class Ok(val value: JsonElement) : Parsed
    data class Bad(val reason: String) : Parsed
}

/** One line of the confirm-before-save list. [before] and [after] are already words. */
internal data class AdminChange(val label: String, val before: String, val after: String)

private val durationUnits = listOf(
    "d" to 86_400_000L,
    "h" to 3_600_000L,
    "m" to 60_000L,
    "s" to 1_000L,
    "ms" to 1L,
)

/** 90061000 becomes "1d 1h 1m 1s". Zero is "0s", which the server refuses but can still hold. */
internal fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0s"
    var left = ms
    val parts = ArrayList<String>()
    for ((unit, size) in durationUnits) {
        val n = left / size
        if (n > 0) {
            parts += "$n$unit"
            left -= n * size
        }
    }
    return parts.joinToString(" ")
}

/**
 * "1h 30m", "90s", "2d", "250ms" and combinations of them, in milliseconds. Null when it is
 * not one.
 *
 * A bare number is refused on purpose. Seconds is what a person means and milliseconds is
 * what the server stores, and guessing between them turns a five minute timeout into five
 * milliseconds without a word.
 */
internal fun parseDuration(text: String): Long? = runCatching { durationOf(text) }.getOrNull()

private fun durationOf(text: String): Long? {
    val cleaned = text.trim().lowercase()
    if (cleaned.isEmpty()) return null
    val piece = Regex("""(\d+)\s*(ms|d|h|m|s)""")
    var total = 0L
    var at = 0
    val squeezed = cleaned.replace(Regex("""\s+"""), " ")
    for (match in piece.findAll(squeezed)) {
        if (squeezed.substring(at, match.range.first).isNotBlank()) return null
        val n = match.groupValues[1].toLongOrNull() ?: return null
        val size = durationUnits.first { it.first == match.groupValues[2] }.second
        total = Math.addExact(total, Math.multiplyExact(n, size))
        at = match.range.last + 1
    }
    if (at == 0 || squeezed.substring(at).isNotBlank()) return null
    return total
}

private val sizeUnits = listOf("TB" to (1L shl 40), "GB" to (1L shl 30), "MB" to (1L shl 20), "KB" to (1L shl 10))

/** In the binary units the server's own console uses, trimmed of a pointless ".0". */
internal fun formatSize(bytes: Long): String {
    for ((unit, size) in sizeUnits) {
        if (bytes >= size) {
            val n = BigDecimal(bytes).divide(BigDecimal(size), 1, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
            return "${n.toPlainString()} $unit"
        }
    }
    return "$bytes bytes"
}

/** "50 MB", "1.5GB", "512k", or a bare number of bytes. Null when it is none of those. */
internal fun parseSize(text: String): Long? {
    val m = Regex("""^\s*(\d+(?:\.\d+)?)\s*([kmgt]?)(?:i?b|bytes?)?\s*$""", RegexOption.IGNORE_CASE)
        .matchEntire(text) ?: return null
    val n = m.groupValues[1].toBigDecimalOrNull() ?: return null
    val size = when (m.groupValues[2].lowercase()) {
        "k" -> 1L shl 10
        "m" -> 1L shl 20
        "g" -> 1L shl 30
        "t" -> 1L shl 40
        else -> 1L
    }
    val bytes = n.multiply(BigDecimal(size))
    return runCatching { bytes.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull()
}

/** A set's members. Stalwart writes an object of member to true; an array is taken too, in case. */
internal fun setMembers(value: JsonElement?): List<String> = when (value) {
    is JsonObject -> value.filterValues { (it as? JsonPrimitive)?.booleanOrNull != false }.keys.toList()
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    else -> emptyList()
}

internal fun membersValue(members: List<String>): JsonObject =
    JsonObject(members.distinct().associateWith { JsonPrimitive(true) })

/** The text an input box starts with, for the types edited as text. */
internal fun editText(type: AdminType, value: JsonElement?): String {
    if (value == null || value is JsonNull) return ""
    val p = value as? JsonPrimitive ?: return ""
    return when (type) {
        is AdminType.Number -> when (type.format) {
            "duration" -> p.longOrNull?.let { formatDuration(it) } ?: p.content
            "size" -> p.longOrNull?.let { formatSize(it) } ?: p.content
            else -> p.content
        }
        // A secret is never put back into a box. The box starts empty and means "unchanged".
        is AdminType.Text -> if (type.secret) "" else p.content
        else -> p.content
    }
}

private val emailPattern = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

/**
 * What somebody typed into a text-like field, checked the way the server will check it.
 *
 * Checked here as well as there so a mistake is pointed at beside the field it is in,
 * rather than coming back from the server as one line about the whole object. The server
 * stays the judge: this only catches what can be known without asking it.
 */
internal fun parseInput(type: AdminType, text: String): Parsed {
    val t = text.trim()
    if (t.isEmpty()) {
        return if (type.nullable) Parsed.Ok(JsonNull) else Parsed.Bad("This needs a value.")
    }
    return when (type) {
        is AdminType.Text -> {
            val raw = if (type.multiline) text else t
            when {
                type.maxLength != null && raw.length > type.maxLength ->
                    Parsed.Bad("This can be at most ${type.maxLength} characters.")
                type.format == "emailAddress" && !emailPattern.matches(t) ->
                    Parsed.Bad("This needs to be an email address, like postmaster@example.com.")
                type.format == "uri" && !looksLikeUri(t) ->
                    Parsed.Bad("This needs to be a full address with a scheme, like https:// or mailto:.")
                type.format == "ipAddress" && !isIpLiteral(t) ->
                    Parsed.Bad("This needs to be an IP address, like 192.0.2.10 or 2001:db8::1.")
                type.format == "ipNetwork" && !isIpNetwork(t) ->
                    Parsed.Bad("This needs to be an address or a range, like 192.0.2.0/24.")
                type.format == "color" && !Regex("^#[0-9a-fA-F]{6}$").matches(t) ->
                    Parsed.Bad("This needs to be a colour written as #RRGGBB.")
                else -> Parsed.Ok(JsonPrimitive(raw))
            }
        }
        is AdminType.Number -> parseNumber(type, t)
        is AdminType.Reference -> Parsed.Ok(JsonPrimitive(t))
        is AdminType.Timestamp -> {
            if (runCatching { java.time.OffsetDateTime.parse(t) }.isSuccess) Parsed.Ok(JsonPrimitive(t))
            else Parsed.Bad("This needs a date and time like 2026-09-28T12:00:00Z.")
        }
        else -> Parsed.Bad("This kind of value is not typed in.")
    }
}

private fun parseNumber(type: AdminType.Number, t: String): Parsed {
    val value: Number = when (type.format) {
        "duration" -> parseDuration(t)
            ?: return Parsed.Bad("Give it a unit, like 30s, 5m, 2h or 1d.")
        "size" -> parseSize(t)
            ?: return Parsed.Bad("This needs a size, like 512 KB, 50 MB or 2 GB.")
        "float" -> t.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: return Parsed.Bad("This needs a number.")
        else -> t.toLongOrNull()
            ?: return Parsed.Bad("This needs a whole number.")
    }
    val d = value.toDouble()
    if (type.format == "unsignedInteger" && d < 0) return Parsed.Bad("This cannot be negative.")
    // Stalwart refuses a zero duration outright ("Invalid duration value"), whatever the minimum says.
    if (type.format == "duration" && d <= 0) return Parsed.Bad("This has to be longer than zero.")
    type.min?.let { if (d < it) return Parsed.Bad("This has to be at least ${describeLimit(type, it)}.") }
    type.max?.let { if (d > it) return Parsed.Bad("This can be at most ${describeLimit(type, it)}.") }
    return Parsed.Ok(if (value is Double) JsonPrimitive(value) else JsonPrimitive(value.toLong()))
}

private fun describeLimit(type: AdminType.Number, limit: Double): String = when (type.format) {
    "duration" -> formatDuration(limit.toLong())
    "size" -> formatSize(limit.toLong())
    "float" -> BigDecimal(limit).stripTrailingZeros().toPlainString()
    else -> limit.toLong().toString()
}

private fun looksLikeUri(t: String): Boolean =
    runCatching { java.net.URI(t) }.getOrNull()?.let { !it.scheme.isNullOrBlank() && !t.contains(' ') } == true

/**
 * Checked by pattern, never by `InetAddress.getByName`, because that resolves a host name
 * through DNS and a validation check has no business making a network call.
 */
internal fun isIpLiteral(t: String): Boolean {
    val v4 = t.split('.')
    if (v4.size == 4 && v4.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }) return true
    if (':' !in t) return false
    if (t.count { it == ':' } > 7 || t.contains(":::")) return false
    if (Regex("::").findAll(t).count() > 1) return false
    val groups = t.split(':')
    val last = groups.last()
    // An IPv6 address may end in an IPv4 one, as ::ffff:192.0.2.1 does.
    val tail = if ('.' in last) { if (!isIpLiteral(last)) return false; groups.dropLast(1) } else groups
    return tail.all { g -> g.length <= 4 && g.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }
}

internal fun isIpNetwork(t: String): Boolean {
    val address = t.substringBefore('/')
    if (!isIpLiteral(address)) return false
    if ('/' !in t) return true
    val bits = t.substringAfter('/').toIntOrNull() ?: return false
    return bits in 0..(if (':' in address) 128 else 32)
}

/**
 * A value as words, for a list column, a read-only field or a line of the change list.
 *
 * A secret is never shown, not even to somebody allowed to change it: the most this says
 * is whether one is set. [choices] turns an enum's name into its label.
 */
internal fun displayValue(
    type: AdminType,
    value: JsonElement?,
    choices: (String) -> List<AdminChoice> = { emptyList() },
    variants: (String) -> List<AdminVariant> = { emptyList() },
): String {
    if (value == null || value is JsonNull) return "Not set"
    return when (type) {
        is AdminType.Text -> if (type.secret) "Set, hidden" else (value as? JsonPrimitive)?.contentOrNull.orEmpty()
        is AdminType.Number -> {
            val n = (value as? JsonPrimitive)
            when (type.format) {
                "duration" -> n?.longOrNull?.let { formatDuration(it) }
                "size" -> n?.longOrNull?.let { formatSize(it) }
                "float" -> n?.doubleOrNull?.let { BigDecimal(it).stripTrailingZeros().toPlainString() }
                else -> n?.longOrNull?.toString()
            } ?: n?.content.orEmpty()
        }
        is AdminType.Flag -> when ((value as? JsonPrimitive)?.booleanOrNull) {
            true -> "Yes"
            false -> "No"
            null -> "Not set"
        }
        is AdminType.Choice -> {
            val name = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
            choices(type.enumName).firstOrNull { it.name == name }?.label ?: name
        }
        is AdminType.Many -> setMembers(value).joinToString(", ") { member ->
            val item = type.item
            if (item is AdminType.Choice) choices(item.enumName).firstOrNull { it.name == member }?.label ?: member
            else member
        }.ifEmpty { "None" }
        is AdminType.Nested -> {
            val o = value as? JsonObject
            val chosen = (o?.get("@type") as? JsonPrimitive)?.contentOrNull
            if (chosen != null) variants(type.objectName).firstOrNull { it.name == chosen }?.label ?: chosen
            else "Set"
        }
        is AdminType.NestedList -> countOf((value as? JsonObject)?.size ?: (value as? JsonArray)?.size ?: 0, "item")
        is AdminType.Dictionary -> countOf((value as? JsonObject)?.size ?: 0, "entry", "entries")
        is AdminType.Reference, is AdminType.Timestamp, is AdminType.Blob ->
            (value as? JsonPrimitive)?.contentOrNull.orEmpty()
        is AdminType.Unknown -> "Edit this in the server's own web console"
    }
}

private fun countOf(n: Int, one: String, many: String = one + "s"): String = when (n) {
    0 -> "None"
    1 -> "1 $one"
    else -> "$n $many"
}

/**
 * Every field that differs between [before] and [after], as lines a person can check.
 *
 * Nested objects are walked with their own form, so a change deep inside DKIM management
 * reads as "DKIM Management, Selector" rather than as the whole block being different. When
 * the variant itself changed there is nothing to compare field by field, so that is one
 * line saying which kind it was and which it now is.
 */
internal fun describeChanges(
    schema: AdminSchema,
    form: AdminForm,
    before: JsonObject,
    after: JsonObject,
    enterprise: Boolean,
    prefix: String = "",
): List<AdminChange> {
    val out = ArrayList<AdminChange>()
    val choices = { name: String -> schema.choices(name) }
    val variants = { name: String -> (schema.shapeOf(name) as? AdminShape.Multiple)?.variants.orEmpty() }
    for (field in form.fields) {
        val was = before[field.name]
        val now = after[field.name]
        if (sameValue(was, now)) continue
        val label = if (prefix.isEmpty()) field.label else "$prefix, ${field.label}"
        val type = field.type
        if (type is AdminType.Nested && was is JsonObject && now is JsonObject &&
            was["@type"] == now["@type"]
        ) {
            val inner = schema.fieldSetFor(type.objectName, now)
            if (inner != null) {
                out += describeChanges(schema, schema.form(inner, enterprise), was, now, enterprise, label)
                continue
            }
        }
        if (type is AdminType.Text && type.secret) {
            out += AdminChange(label, if (was == null || was is JsonNull) "Not set" else "Set, hidden", "A new value, hidden")
            continue
        }
        out += AdminChange(
            label,
            displayValue(type, was, choices, variants),
            displayValue(type, now, choices, variants),
        )
    }
    return out
}

/**
 * The `update` (or `create`) body for a save: every top-level field that changed, whole.
 *
 * Whole values rather than JSON pointers into them, because a changed nested object is
 * small and replacing it leaves nothing for a partial patch to get subtly wrong. Only fields
 * the form offers and the server lets this operation set are ever sent, so a bug in the
 * renderer cannot write to a server-set field or to one the form did not show.
 */
internal fun changedFields(form: AdminForm, before: JsonObject, after: JsonObject, creating: Boolean): JsonObject {
    val out = LinkedHashMap<String, JsonElement>()
    // The kind of a new object (User or Group for an account) is not a form field, but the
    // server cannot make one without it. On an update it is never sent: changing the kind
    // of something that exists is not an edit this renderer offers.
    if (creating) after["@type"]?.let { out["@type"] = it }
    for (field in form.fields) {
        val allowed = if (creating) field.settableOnCreate else field.editable
        if (!allowed) continue
        val now = after[field.name] ?: continue
        if (!creating && sameValue(before[field.name], now)) continue
        if (creating && now is JsonNull) continue
        out[field.name] = now
    }
    return JsonObject(out)
}

/** A missing value and a null one mean the same thing to the server, so they compare equal. */
internal fun sameValue(a: JsonElement?, b: JsonElement?): Boolean {
    val x = if (a is JsonNull) null else a
    val y = if (b is JsonNull) null else b
    return x == y
}

/**
 * Problems with a whole draft that no single input box can see: a required value left
 * empty, a set shorter than its minimum. Keyed by field name.
 */
internal fun draftProblems(form: AdminForm, draft: JsonObject, creating: Boolean): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (field in form.fields) {
        val applies = if (creating) field.settableOnCreate else field.editable
        if (!applies) continue
        val v = draft[field.name]
        when (val type = field.type) {
            is AdminType.Many -> if (setMembers(v).size < type.minItems) {
                out[field.name] = "This needs at least ${type.minItems}."
            }
            is AdminType.Text -> if (!type.nullable && !type.secret &&
                ((v as? JsonPrimitive)?.contentOrNull.isNullOrBlank())
            ) {
                out[field.name] = "This needs a value."
            }
            is AdminType.Choice, is AdminType.Reference -> if (!type.nullable && (v == null || v is JsonNull)) {
                out[field.name] = "This needs a value."
            }
            else -> {}
        }
    }
    return out
}

/**
 * A starting value for something being made: the variant's `@type` when there is a choice,
 * then the server's own defaults, then every nested object that cannot be left out filled
 * the same way with its first variant, which is what the server's own console does.
 */
internal fun startingValue(schema: AdminSchema, objectName: String, variant: String?, enterprise: Boolean, depth: Int = 0): JsonObject {
    val out = LinkedHashMap<String, JsonElement>()
    val shape = schema.shapeOf(objectName)
    val chosen = when (shape) {
        is AdminShape.Multiple -> (shape.variants.firstOrNull { it.name == variant } ?: shape.variants.firstOrNull())
        else -> null
    }
    chosen?.let { out["@type"] = JsonPrimitive(it.name) }
    val fieldSet = when (shape) {
        is AdminShape.Single -> shape.schemaName
        is AdminShape.Multiple -> chosen?.schemaName
        null -> null
    } ?: return JsonObject(out)
    val form = schema.form(fieldSet, enterprise)
    out.putAll(form.defaults)
    // Deep enough for any real schema; the limit only exists so a schema that refers to
    // itself cannot recurse forever.
    if (depth < 6) {
        for (field in form.fields) {
            val type = field.type
            if (field.name !in out && type is AdminType.Nested && !type.nullable && field.settableOnCreate) {
                out[field.name] = startingValue(schema, type.objectName, null, enterprise, depth + 1)
            }
        }
    }
    return JsonObject(out)
}
