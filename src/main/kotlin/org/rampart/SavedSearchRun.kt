package org.rampart

import kotlinx.serialization.json.JsonObject
import java.time.ZoneId

/** How many messages opening a saved search with conditions shows. */
internal const val SAVED_SEARCH_PAGE = 200

/**
 * What running a saved search found, and the sentence to show when it is not the whole
 * answer. [note] is null when the list is exactly what was asked for. [detail] is what the
 * runtime said, for the quieter second line under the sentence.
 */
internal data class SavedRun(val messages: List<Summary>, val note: String? = null, val detail: String? = null)

/**
 * Runs a saved search's conditions against one account.
 *
 * The server first when it can take the tree, because it can see mail that was never
 * fetched. [server] is null where it cannot: IMAP has no way to be handed a JMAP filter,
 * so an IMAP account is answered from its local copy, which is the same fallback a JMAP
 * account gets when the server does not answer.
 *
 * Every way this comes back short says why in one sentence: no copy, a condition the copy
 * cannot answer, or a server that did not reply. An empty list with no note means nothing
 * matched.
 */
internal fun runConditionSearch(
    condition: Condition?,
    except: List<String>,
    server: ((JsonObject) -> List<Summary>)?,
    store: Store?,
    limit: Int = SAVED_SEARCH_PAGE,
    zone: ZoneId = Regional.zone(),
): SavedRun {
    var serverError: String? = null
    if (server != null) {
        val asked = runCatching { server(jmapFilter(condition, except, zone)) }
        asked.getOrNull()?.let { return SavedRun(it) }
        serverError = asked.exceptionOrNull()?.let { whyFailed(it) }
    }
    val serverFailed = server != null
    val local = localWhere(condition, except, zone)
    return when {
        local is LocalQuery.Unanswerable -> SavedRun(
            emptyList(),
            if (serverFailed) "The server did not answer. " + local.reason else local.reason,
            serverError,
        )
        store == null -> SavedRun(
            emptyList(),
            if (serverFailed) "The server did not answer and this account has no saved copy to search."
            else "This account has no saved copy to search.",
            serverError,
        )
        else -> {
            val found = runCatching { store.matching(local as LocalQuery.Sql, limit) }
            found.fold(
                onSuccess = { rows ->
                    SavedRun(
                        rows,
                        if (serverFailed) "The server did not answer, so this is from the saved copy." else null,
                        serverError,
                    )
                },
                onFailure = { SavedRun(emptyList(), "The saved copy could not be searched.", whyFailed(it)) },
            )
        }
    }
}

/**
 * Several accounts' runs as one list, newest first, each row stamped with its account.
 *
 * One account falling short does not empty the list: its note is kept and the rest show.
 */
internal fun combinedRuns(runs: Map<String, SavedRun>, limit: Int = SAVED_SEARCH_PAGE): SavedRun = SavedRun(
    merged(runs.mapValues { it.value.messages }, limit),
    runs.values.firstNotNullOfOrNull { it.note },
    runs.values.firstNotNullOfOrNull { it.detail },
)

/** The unread count and the child folders a saved search shows in the sidebar. */
internal data class SavedCount(val unread: Int, val groups: List<SplitGroup>)

/**
 * Counts a saved search with conditions against one account's local copy.
 *
 * The local copy only, the same as every other count in the sidebar: a count is redone
 * whenever mail arrives, and asking every server each time would be a search per saved
 * search per arrival. [leftover] is the toggles the conditions could not hold, applied
 * afterwards, so the count and the list agree.
 */
internal fun countConditionSearch(
    search: SavedSearch,
    except: List<String>,
    store: Store?,
    knownSenders: Set<String> = emptySet(),
    zone: ZoneId = Regional.zone(),
): SavedCount {
    val local = localWhere(search.condition, except, zone) as? LocalQuery.Sql ?: return SavedCount(0, emptyList())
    val rows = store?.let { runCatching { it.matchingForCount(local) }.getOrNull() }.orEmpty()
        .let { all -> if (search.filters.active) all.filter { matchesQuick(it, search.filters, knownSenders) } else all }
    return SavedCount(rows.count { !it.seen }, splitGroups(rows, search.split))
}

/** Counts from several accounts added together, with the children of the same key merged. */
internal fun combinedCounts(counts: List<SavedCount>, split: Split): SavedCount {
    val merged = counts.flatMap { it.groups }.groupBy { it.key }.map { (key, same) ->
        SplitGroup(key, same.first().label, same.sumOf { it.total }, same.sumOf { it.unread })
    }.sortedWith(compareByDescending<SplitGroup> { it.total }.thenBy { it.label.lowercase() }.thenBy { it.key })
        .take(SPLIT_LIMIT)
    return SavedCount(counts.sumOf { it.unread }, if (split == Split.NONE) emptyList() else merged)
}

/**
 * Stores what the builder made for [id].
 *
 * The old query text goes, because the conditions now say everything it said, and the
 * two toggles conditions cannot hold stay. Deleting a saved search, or changing it, never
 * touches mail: the search is a line in the settings file and nothing else.
 */
internal fun updateSavedSearchConditions(
    existing: List<SavedSearch>,
    id: String,
    name: String,
    condition: Condition,
    split: Split,
): List<SavedSearch> = existing.map {
    if (it.id != id) it
    else it.copy(
        name = name.trim().ifEmpty { it.name },
        query = "",
        filters = legacyLeftover(it.filters),
        condition = condition,
        split = split,
    )
}
