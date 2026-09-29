package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.ZoneId

/**
 * How the conditions inside one group combine.
 *
 * Three rather than two because "none of" is the one people reach for next ("from the
 * list, but not the automated ones") and saying it as a negated "any of" is the same
 * thing JMAP's NOT operator already means (RFC 8620 5.5: none of the conditions match).
 */
internal enum class Joiner(val key: String, val label: String) {
    ALL("all", "all of"),
    ANY("any", "any of"),
    NONE("none", "none of"),
    ;

    companion object {
        fun of(key: String?): Joiner? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What one condition looks at.
 *
 * [valued] is false for the ones that are a yes or no about the message and take no
 * text. The labels are what the builder shows, so they read as the end of a sentence
 * that starts "Mail where".
 */
internal enum class SearchField(val key: String, val label: String, val valued: Boolean = true) {
    TEXT("text", "any text contains"),
    FROM("from", "sender contains"),
    TO("to", "recipient contains"),
    SUBJECT("subject", "subject contains"),
    LIST("list", "mailing list is"),
    TAG("tag", "has the tag"),
    UNREAD("unread", "is unread", valued = false),
    STARRED("starred", "is starred", valued = false),
    ATTACHMENT("attachment", "has an attachment", valued = false),
    AFTER("after", "arrived on or after"),
    BEFORE("before", "arrived before"),
    LARGER("larger", "is larger than"),
    SMALLER("smaller", "is smaller than"),
    ;

    companion object {
        fun of(key: String?): SearchField? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A saved search's conditions, as a tree.
 *
 * A [Group] holds conditions and other groups, so "from the bank and (unread or starred)"
 * is one group inside another rather than a second saved search. It is data and nothing
 * else: what the server is asked and what the local copy is asked are both worked out from
 * the same tree, below, so the two cannot mean different things.
 */
internal sealed interface Condition {
    data class Group(val joiner: Joiner = Joiner.ALL, val items: List<Condition> = emptyList()) : Condition
    data class Match(val field: SearchField, val value: String = "") : Condition
}

/** How deep the builder lets groups nest. Past three, nobody can read back what they wrote. */
internal const val MAX_GROUP_DEPTH = 3

/**
 * Why one condition cannot be used as written, or null when it can.
 *
 * Shown beside the condition in the builder. A condition with a problem is left out of
 * the search rather than sent, because a date the server cannot read is an error there and
 * a guess here.
 */
internal fun problemOf(match: Condition.Match): String? {
    if (!match.field.valued) return null
    val value = match.value.trim()
    if (value.isEmpty()) return "This needs a value."
    return when (match.field) {
        SearchField.AFTER, SearchField.BEFORE ->
            if (dateOf(value) == null) "Write the date as year-month-day, like 2026-09-01." else null
        SearchField.LARGER, SearchField.SMALLER ->
            if (bytesOf(value) == null) "Write a size like 500 KB or 2 MB." else null
        else -> null
    }
}

/**
 * The tree with nothing unusable in it, or null when nothing is left.
 *
 * Blank and unreadable conditions go, empty groups go, and a group of one is kept as a
 * group only when it is "none of", because that one changes the meaning of its only
 * child. Null means "every message": the caller decides what that is worth.
 */
internal fun normalized(condition: Condition?): Condition? = when (condition) {
    null -> null
    is Condition.Match -> condition.takeIf { problemOf(it) == null }
        ?.let { if (it.field.valued) it.copy(value = it.value.trim()) else it.copy(value = "") }
    is Condition.Group -> {
        val kept = condition.items.mapNotNull { normalized(it) }
        when {
            kept.isEmpty() -> null
            kept.size == 1 && condition.joiner != Joiner.NONE -> kept.single()
            else -> condition.copy(items = kept)
        }
    }
}

/** A plain date as the instant that day starts in [zone]. */
internal fun dateOf(value: String): LocalDate? = runCatching { LocalDate.parse(value.trim()) }.getOrNull()

private fun startOf(value: String, zone: ZoneId): String? = dateOf(value)?.atStartOfDay(zone)?.toInstant()?.toString()

private val SIZE = Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*(b|bytes?|k|kb|m|mb|g|gb)?\\s*$", RegexOption.IGNORE_CASE)

/** "500 KB", "2 MB" or a bare number of bytes, in bytes. Binary units, as every mail client uses. */
internal fun bytesOf(value: String): Long? {
    val found = SIZE.matchEntire(value) ?: return null
    val number = found.groupValues[1].toDoubleOrNull() ?: return null
    val unit = when (found.groupValues[2].lowercase()) {
        "k", "kb" -> 1024L
        "m", "mb" -> 1024L * 1024
        "g", "gb" -> 1024L * 1024 * 1024
        else -> 1L
    }
    return (number * unit).toLong()
}

/**
 * The Email/query filter for [condition], across the account less [except].
 *
 * A group becomes a FilterOperator, whose `operator` is AND, OR or NOT exactly as RFC 8620
 * 5.5 defines them, and a condition becomes one FilterCondition with one property (RFC 8621
 * 4.4.1). One property per object rather than several merged: merged properties are an
 * implicit AND, which is right inside "all of" and wrong everywhere else, and keeping one
 * shape means the tree on screen and the tree on the wire are the same tree.
 *
 * Null [condition] is every message outside [except]. With nothing to leave out either,
 * the filter is an empty object, which the RFC defines as matching everything.
 */
internal fun jmapFilter(
    condition: Condition?,
    except: Collection<String> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault(),
): JsonObject {
    val tree = normalized(condition)?.let { jmapNode(it, zone) }
    val skip = except.filter { it.isNotBlank() }.distinct()
    val outside = if (skip.isEmpty()) null else buildJsonObject {
        putJsonArray("inMailboxOtherThan") { skip.forEach { add(it) } }
    }
    return when {
        tree == null -> outside ?: JsonObject(emptyMap())
        outside == null -> tree
        else -> buildJsonObject {
            put("operator", "AND")
            putJsonArray("conditions") {
                add(outside)
                add(tree)
            }
        }
    }
}

private fun jmapNode(condition: Condition, zone: ZoneId): JsonObject = when (condition) {
    is Condition.Group -> buildJsonObject {
        put(
            "operator",
            when (condition.joiner) {
                Joiner.ALL -> "AND"
                Joiner.ANY -> "OR"
                Joiner.NONE -> "NOT"
            },
        )
        putJsonArray("conditions") { condition.items.forEach { add(jmapNode(it, zone)) } }
    }
    is Condition.Match -> buildJsonObject {
        val value = condition.value
        when (condition.field) {
            SearchField.TEXT -> put("text", value)
            SearchField.FROM -> put("from", value)
            SearchField.TO -> put("to", value)
            SearchField.SUBJECT -> put("subject", value)
            SearchField.LIST -> putJsonArray("header") {
                add("List-Id")
                add(value)
            }
            SearchField.TAG -> put("hasKeyword", value)
            SearchField.UNREAD -> put("notKeyword", "\$seen")
            SearchField.STARRED -> put("hasKeyword", "\$flagged")
            SearchField.ATTACHMENT -> put("hasAttachment", true)
            SearchField.AFTER -> put("after", startOf(value, zone).orEmpty())
            SearchField.BEFORE -> put("before", startOf(value, zone).orEmpty())
            SearchField.LARGER -> put("minSize", bytesOf(value) ?: 0L)
            SearchField.SMALLER -> put("maxSize", bytesOf(value) ?: 0L)
        }
    }
}

/**
 * What the local copy can be asked for a condition tree.
 *
 * [Sql] is a WHERE clause built only from the fixed fragments in [localWhere], with every
 * value the person typed in [args] for a prepared statement to bind. Nothing typed ever
 * becomes part of the SQL text, which is the whole reason the clause is built here and
 * not by whoever wants to run it.
 */
internal sealed interface LocalQuery {
    class Sql internal constructor(val where: String, val args: List<Any>) : LocalQuery
    data class Unanswerable(val reason: String) : LocalQuery
}

/**
 * The WHERE clause for [condition] against the `message` table, leaving out [except].
 *
 * Two conditions have no column in the local copy, recipients and attachments, and a
 * tree that uses either anywhere is [LocalQuery.Unanswerable] as a whole. Dropping only
 * that branch would quietly widen an "all of" or narrow an "any of", and a list that looks
 * right and is not is worse than one that says why it is empty.
 */
internal fun localWhere(
    condition: Condition?,
    except: Collection<String> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault(),
): LocalQuery {
    val args = ArrayList<Any>()
    val parts = ArrayList<String>()
    val skip = except.filter { it.isNotBlank() }.distinct()
    if (skip.isNotEmpty()) {
        parts += "id NOT IN (SELECT message_id FROM mailbox_message WHERE mailbox_id IN (" +
            skip.joinToString(",") { "?" } + "))"
        args.addAll(skip)
    }
    val tree = normalized(condition)
    if (tree != null) {
        unanswerable(tree)?.let { return LocalQuery.Unanswerable(it) }
        parts += localNode(tree, args, zone)
    }
    return LocalQuery.Sql(if (parts.isEmpty()) "1 = 1" else parts.joinToString(" AND "), args)
}

private fun unanswerable(condition: Condition): String? = when (condition) {
    is Condition.Group -> condition.items.firstNotNullOfOrNull { unanswerable(it) }
    is Condition.Match -> when (condition.field) {
        SearchField.TO -> "The saved copy does not record who a message was sent to, so this search needs the server."
        SearchField.ATTACHMENT -> "The saved copy does not record attachments, so this search needs the server."
        else -> null
    }
}

private fun localNode(condition: Condition, args: MutableList<Any>, zone: ZoneId): String = when (condition) {
    is Condition.Group -> {
        val inner = condition.items.map { localNode(it, args, zone) }
        when (condition.joiner) {
            Joiner.ALL -> inner.joinToString(" AND ", "(", ")")
            Joiner.ANY -> inner.joinToString(" OR ", "(", ")")
            Joiner.NONE -> inner.joinToString(" OR ", "NOT (", ")")
        }
    }
    is Condition.Match -> {
        val value = condition.value
        when (condition.field) {
            SearchField.TEXT -> {
                args += ftsPhrase(value)
                "id IN (SELECT id FROM search WHERE search MATCH ?)"
            }
            SearchField.FROM -> {
                val like = containsLike(value)
                args += like
                args += like
                "(lower(sender) LIKE ? ESCAPE '\\' OR lower(senderEmail) LIKE ? ESCAPE '\\')"
            }
            SearchField.SUBJECT -> {
                args += containsLike(value)
                "lower(subject) LIKE ? ESCAPE '\\'"
            }
            SearchField.LIST -> {
                args += containsLike(value)
                "lower(listId) LIKE ? ESCAPE '\\'"
            }
            // Padded on both sides, the way withKeyword does it, so `work` is not `workshop`.
            SearchField.TAG -> {
                args += "% " + likeEscaped(value.lowercase()) + " %"
                "(' ' || lower(keywords) || ' ') LIKE ? ESCAPE '\\'"
            }
            SearchField.UNREAD -> "seen = 0"
            SearchField.STARRED -> "flagged = 1"
            SearchField.AFTER -> {
                args += startOf(value, zone).orEmpty()
                "receivedAt >= ?"
            }
            SearchField.BEFORE -> {
                args += startOf(value, zone).orEmpty()
                "receivedAt < ?"
            }
            SearchField.LARGER -> {
                args += bytesOf(value) ?: 0L
                "size >= ?"
            }
            // A size of zero is a row written before sizes were kept, not an empty message,
            // so it is not "smaller than" anything.
            SearchField.SMALLER -> {
                args += bytesOf(value) ?: 0L
                "(size > 0 AND size < ?)"
            }
            SearchField.TO, SearchField.ATTACHMENT -> error("Checked by unanswerable before this is reached.")
        }
    }
}

private fun likeEscaped(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private fun containsLike(value: String): String = "%" + likeEscaped(value.lowercase()) + "%"

/** Every word as a quoted FTS5 phrase, the same way Store.search makes one, so typing syntax is only text. */
private fun ftsPhrase(value: String): String =
    value.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { "\"" + it.replace("\"", "") + "\"" }

/**
 * The List-Id a message belongs to, reduced to the part that identifies the list.
 *
 * RFC 2919 puts the identifier in angle brackets after an optional description, as in
 * `"Rampart users" <users.rampart.example.org>`. The description changes whenever a list
 * owner edits it, the identifier does not, so only the identifier is kept.
 */
internal fun listIdOf(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return ""
    val inside = text.substringAfterLast('<', "").substringBefore('>', "")
    return (inside.ifBlank { text }).trim().lowercase()
}

/** Encodes a condition tree for the settings file. */
internal fun conditionJson(condition: Condition): JsonObject = when (condition) {
    is Condition.Group -> buildJsonObject {
        putJsonArray(condition.joiner.key) { condition.items.forEach { add(conditionJson(it)) } }
    }
    is Condition.Match -> buildJsonObject {
        put("field", condition.field.key)
        if (condition.field.valued) put("value", condition.value)
    }
}

/**
 * Reads a condition tree back, or null when it is not one.
 *
 * A field this build does not know is dropped rather than failing the whole search: a
 * newer version may have added one, and losing a single condition is recoverable in the
 * builder where losing the saved search is not.
 */
internal fun conditionOf(element: JsonElement?): Condition? {
    val obj = element as? JsonObject ?: return null
    obj["field"]?.let { field ->
        val known = SearchField.of((field as? JsonPrimitive)?.contentOrNull) ?: return null
        val value = (obj["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return Condition.Match(known, value)
    }
    val joiner = Joiner.entries.firstOrNull { obj[it.key] is JsonArray } ?: return null
    val items = (obj[joiner.key] as JsonArray).mapNotNull { conditionOf(it) }
    return Condition.Group(joiner, items)
}

/**
 * The tree a saved search from before conditions existed stands for.
 *
 * Its text and the three toggles that are properties of a message become conditions in an
 * "all of", which is what the toggle row always meant. Tagged and known sender are left as
 * toggles: each is an OR over a list the account builds as it goes (the tags seen so far,
 * the address book), and freezing today's list into the search would stop it following.
 */
internal fun legacyCondition(query: String, filters: QuickFilters): Condition.Group {
    val items = buildList {
        if (query.isNotBlank()) add(Condition.Match(SearchField.TEXT, query.trim()))
        if (filters.unread) add(Condition.Match(SearchField.UNREAD))
        if (filters.starred) add(Condition.Match(SearchField.STARRED))
        if (filters.attachment) add(Condition.Match(SearchField.ATTACHMENT))
    }
    return Condition.Group(Joiner.ALL, items)
}

/** The toggles a converted search keeps, which are the two [legacyCondition] cannot say. */
internal fun legacyLeftover(filters: QuickFilters): QuickFilters =
    QuickFilters(tagged = filters.tagged, knownSender = filters.knownSender)

/**
 * The tree with the node at [path] replaced by what [change] makes of it.
 *
 * A path is the index of each child on the way down from the root; the empty path is the
 * root itself. The builder edits through these rather than holding mutable nodes, so the
 * tree on screen is always a value that can be compared, saved or thrown away on Cancel.
 */
internal fun Condition.Group.changedAt(path: List<Int>, change: (Condition) -> Condition): Condition.Group {
    if (path.isEmpty()) return change(this) as? Condition.Group ?: this
    val index = path.first()
    if (index !in items.indices) return this
    val child = items[index]
    val next = if (path.size == 1) change(child) else (child as? Condition.Group)?.changedAt(path.drop(1), change) ?: child
    return copy(items = items.toMutableList().also { it[index] = next })
}

/** The tree without the node at [path]. The root itself cannot be removed, only emptied. */
internal fun Condition.Group.removedAt(path: List<Int>): Condition.Group {
    if (path.isEmpty()) return this
    val last = path.last()
    return changedAt(path.dropLast(1)) { parent ->
        (parent as? Condition.Group)?.let { group -> group.copy(items = group.items.filterIndexed { i, _ -> i != last }) }
            ?: parent
    }
}

/** The tree with [item] added at the end of the group at [path]. */
internal fun Condition.Group.addedTo(path: List<Int>, item: Condition): Condition.Group =
    changedAt(path) { node -> (node as? Condition.Group)?.let { it.copy(items = it.items + item) } ?: node }

/** Every problem in the tree, which is what decides whether it can be saved. */
internal fun problemsIn(condition: Condition): List<String> = when (condition) {
    is Condition.Group -> condition.items.flatMap { problemsIn(it) }
    is Condition.Match -> listOfNotNull(problemOf(condition))
}
