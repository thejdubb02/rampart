package org.rampart

import androidx.compose.runtime.compositionLocalOf

/**
 * Currently highlighted setting item.
 */
internal val LocalHighlightedSetting = compositionLocalOf<SettingSearchItem?> { null }

/**
 * A searchable setting item derived from Rook computer settings.
 */
internal data class SettingSearchItem(
    val id: String,
    val name: String,
    val pageTitle: String,
    val pageKey: String,
    val keywords: String = "",
)

/** Message shown when a search query produces no matching settings. */
internal const val NO_MATCHES_MESSAGE = "No setting matches"

/**
 * Builds the search index from the computer settings map entries and page navigation items.
 */
internal fun buildSettingSearchItems(
    entries: List<SettingEntry>,
    pages: List<Triple<String, String, String>> = SettingsPages,
): List<SettingSearchItem> {
    val pageMap = pages.associate { it.second.lowercase() to it.first }
    return entries.map { entry ->
        val pageTitle = entry.page.removePrefix("Settings, ").trim()
        val pageKey = pageMap[pageTitle.lowercase()] ?: pageTitle.lowercase().replace(" ", "")
        SettingSearchItem(
            id = entry.id,
            name = entry.name,
            pageTitle = pageTitle,
            pageKey = pageKey,
            keywords = entry.keywords,
        )
    }
}

/**
 * Default search items constructed from Rook computer settings.
 */
internal fun defaultSettingSearchItems(
    pages: List<Triple<String, String, String>> = SettingsPages,
): List<SettingSearchItem> {
    val place = ComputerPlace(ComputerChoices())
    return buildSettingSearchItems(place.entries(), pages)
}

/**
 * Matches settings against a query string.
 * Matching is case-insensitive across title, page name and keywords.
 * Title prefix matches are ranked first.
 */
internal fun matchSettings(
    query: String,
    items: List<SettingSearchItem>,
): List<SettingSearchItem> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()

    return items
        .mapNotNull { item ->
            val name = item.name.lowercase()
            val page = item.pageTitle.lowercase()
            val keys = item.keywords.lowercase()

            val rank = when {
                name.startsWith(q) -> 1
                name.contains(q) -> 2
                keys.contains(q) -> 3
                page.contains(q) -> 4
                q.contains(" ") && q.split(" ").filter { it.isNotBlank() }.all { word ->
                    name.contains(word) || keys.contains(word) || page.contains(word)
                } -> 5
                else -> return@mapNotNull null
            }
            item to rank
        }
        .sortedWith(compareBy({ it.second }, { it.first.name }))
        .map { it.first }
}

/**
 * Filters navigation pages to those whose title matches or whose settings match.
 */
internal fun filterNavPages(
    query: String,
    pages: List<Triple<String, String, String>>,
    matchedSettings: List<SettingSearchItem>,
): List<Triple<String, String, String>> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return pages

    val matchedTitles = matchedSettings.map { it.pageTitle.lowercase() }.toSet()
    val matchedKeys = matchedSettings.map { it.pageKey.lowercase() }.toSet()

    return pages.filter { (key, label, _) ->
        label.lowercase().contains(q) ||
            key.lowercase().contains(q) ||
            matchedTitles.contains(label.lowercase()) ||
            matchedKeys.contains(key.lowercase())
    }
}

/**
 * Evaluates search results for a given query string.
 */
internal data class SettingsSearchResult(
    val query: String,
    val settings: List<SettingSearchItem>,
    val filteredPages: List<Triple<String, String, String>>,
    val emptyMessage: String?,
)

/**
 * Executes a search over settings and navigation pages.
 */
internal fun searchSettings(
    query: String,
    items: List<SettingSearchItem>,
    pages: List<Triple<String, String, String>> = SettingsPages,
): SettingsSearchResult {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        return SettingsSearchResult(
            query = query,
            settings = emptyList(),
            filteredPages = pages,
            emptyMessage = null,
        )
    }

    val matchedSettings = matchSettings(trimmed, items)
    val filteredPages = filterNavPages(trimmed, pages, matchedSettings)
    val emptyMessage = if (matchedSettings.isEmpty()) NO_MATCHES_MESSAGE else null

    return SettingsSearchResult(
        query = query,
        settings = matchedSettings,
        filteredPages = filteredPages,
        emptyMessage = emptyMessage,
    )
}
