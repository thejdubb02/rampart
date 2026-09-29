package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A saved search folder that runs a query against an account or all accounts.
 *
 * The first shape stored a query string and quick filter toggles, and opening it runs
 * the query through the normal search path. That shape still loads and still runs that
 * way. A search saved from the condition builder carries [condition] instead, and opening
 * it asks the server that tree, or the local copy when the server cannot be asked.
 */
internal data class SavedSearch(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    /** An account key, or [ALL_ACCOUNTS] for a search across every account. */
    val account: String,
    val query: String = "",
    val filters: QuickFilters = QuickFilters(),
    /** The nested conditions, or null for a search saved before the builder existed. */
    val condition: Condition? = null,
    /** Whether it divides into child folders, and by what. */
    val split: Split = Split.NONE,
    /** Set only on a child folder of a split search, which is never stored. */
    val parentId: String? = null,
)

/** JSON encoder and decoder for saved searches. */
internal object SavedSearchJson {
    fun encode(search: SavedSearch): JsonObject = buildJsonObject {
        put("id", search.id)
        put("name", search.name)
        put("account", search.account)
        put("query", search.query)
        put("filters", buildJsonObject {
            put("unread", search.filters.unread)
            put("starred", search.filters.starred)
            put("tagged", search.filters.tagged)
            put("attachment", search.filters.attachment)
            put("knownSender", search.filters.knownSender)
        })
        // Written only when there is something to write, so a search nobody has opened in
        // the builder is stored exactly as it was and an older build still reads it.
        search.condition?.let { put("condition", conditionJson(it)) }
        if (search.split != Split.NONE) put("split", search.split.key)
    }

    fun decode(element: JsonElement): SavedSearch? {
        val obj = element as? JsonObject ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } ?: return null
        val account = obj["account"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            ?: java.util.UUID.randomUUID().toString()
        val query = obj["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val filtersObj = obj["filters"] as? JsonObject
        val filters = if (filtersObj != null) {
            QuickFilters(
                unread = filtersObj["unread"]?.jsonPrimitive?.booleanOrNull ?: false,
                starred = filtersObj["starred"]?.jsonPrimitive?.booleanOrNull ?: false,
                tagged = filtersObj["tagged"]?.jsonPrimitive?.booleanOrNull ?: false,
                attachment = filtersObj["attachment"]?.jsonPrimitive?.booleanOrNull ?: false,
                knownSender = filtersObj["knownSender"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        } else {
            QuickFilters()
        }
        return SavedSearch(
            id = id,
            name = name,
            account = account,
            query = query,
            filters = filters,
            condition = conditionOf(obj["condition"]),
            split = Split.of(obj["split"]?.jsonPrimitive?.contentOrNull),
        )
    }
}

/** Adds or replaces a saved search in the list. */
internal fun saveSearch(existing: List<SavedSearch>, newSearch: SavedSearch): List<SavedSearch> {
    val index = existing.indexOfFirst { it.id == newSearch.id }
    return if (index >= 0) {
        existing.toMutableList().apply { set(index, newSearch) }
    } else {
        existing + newSearch
    }
}

/** Renames an existing saved search by id. */
internal fun renameSavedSearch(existing: List<SavedSearch>, id: String, newName: String): List<SavedSearch> {
    val clean = newName.trim()
    if (clean.isEmpty()) return existing
    return existing.map { if (it.id == id) it.copy(name = clean) else it }
}

/** Updates the query and filters of an existing saved search by id. */
internal fun updateSavedSearchQuery(
    existing: List<SavedSearch>,
    id: String,
    query: String,
    filters: QuickFilters = QuickFilters(),
): List<SavedSearch> = existing.map { if (it.id == id) it.copy(query = query, filters = filters) else it }

/** Removes a saved search by id from the list. */
internal fun deleteSavedSearch(existing: List<SavedSearch>, id: String): List<SavedSearch> =
    existing.filterNot { it.id == id }

/** Filters saved searches that belong to a specific account or all accounts. */
internal fun savedSearchesForAccount(searches: List<SavedSearch>, account: String): List<SavedSearch> =
    searches.filter { it.account == account }

/** The section folding key for saved searches. */
internal fun foldSavedSearches(account: String = ""): String = "saved-searches/$account"

/** Builds the list request for executing a saved search. */
internal fun searchRequestOf(
    search: SavedSearch,
    mailboxId: String = "",
    offset: Int = 0,
): ListRequest = ListRequest(
    account = search.account,
    mailbox = mailboxId,
    query = search.query,
    results = true,
    tag = null,
    filters = search.filters,
    offset = offset,
)

/** Builds the search request from typed search box parameters. */
internal fun searchBoxRequestOf(
    account: String,
    query: String,
    filters: QuickFilters = QuickFilters(),
    mailboxId: String = "",
    offset: Int = 0,
): ListRequest = ListRequest(
    account = account,
    mailbox = mailboxId,
    query = query,
    results = true,
    tag = null,
    filters = filters,
    offset = offset,
)

/**
 * Calculates unread count for a saved search from summaries.
 */
internal fun countUnreadSavedSearch(
    search: SavedSearch,
    messages: List<Summary>,
    knownSenders: Set<String> = emptySet(),
): Int = messages.count { summary ->
    !summary.seen && matchesQuick(summary, search.filters, knownSenders)
}

/** Counts unread saved search matches directly in the local store. */
internal fun countUnreadSavedSearch(
    search: SavedSearch,
    store: Store,
    mailboxId: String,
    knownSenders: Set<String> = emptySet(),
): Int = if (search.query.isNotBlank()) {
    store.countUnreadSearch(search.query, search.filters, knownSenders)
} else {
    store.countUnread(mailboxId, search.filters, knownSenders)
}
