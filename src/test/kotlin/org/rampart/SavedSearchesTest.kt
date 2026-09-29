package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for saved search queries and settings persistence.
 */
class SavedSearchesTest {

    @Test
    fun storageRoundTripPreservesAllFields() {
        val original = listOf(
            SavedSearch(
                id = "search-1",
                name = "Urgent Invoices",
                account = "acc-work",
                query = "invoice OR payment",
                filters = QuickFilters(unread = true, starred = true),
            ),
            SavedSearch(
                id = "search-2",
                name = "All Unread News",
                account = ALL_ACCOUNTS,
                query = "newsletter",
                filters = QuickFilters(unread = true, tagged = true, attachment = true, knownSender = true),
            ),
            SavedSearch(
                id = "search-3",
                name = "Plain Folder",
                account = "acc-personal",
                query = "",
                filters = QuickFilters(),
            ),
        )

        val encoded = original.map { SavedSearchJson.encode(it) }
        val decoded = encoded.mapNotNull { SavedSearchJson.decode(it) }

        assertEquals(original.size, decoded.size)
        assertEquals(original, decoded)
    }

    @Test
    fun settingsRoundTripPersistsToFile() {
        val path = Files.createTempFile("rampart-settings-test", ".json")
        try {
            val original = listOf(
                SavedSearch(
                    id = "saved-1",
                    name = "Team Updates",
                    account = "work",
                    query = "status update",
                    filters = QuickFilters(starred = true),
                ),
            )
            if (System.getProperty("rampart.config.dir").isNullOrBlank()) {
                System.setProperty("rampart.config.dir", Files.createTempDirectory("rampart-settings-test").toString())
            }
            Settings.setSavedSearches(original)

            val loaded = Settings.savedSearches()
            assertEquals(1, loaded.size)
            assertEquals("saved-1", loaded[0].id)
            assertEquals("Team Updates", loaded[0].name)
            assertEquals("work", loaded[0].account)
            assertEquals("status update", loaded[0].query)
            assertTrue(loaded[0].filters.starred)
            assertFalse(loaded[0].filters.unread)
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun malformedSavedEntryIsSkippedRatherThanCrashing() {
        val invalidEntries = JsonArray(
            listOf(
                // Missing name.
                buildJsonObject {
                    put("id", "bad-1")
                    put("account", "work")
                    put("query", "test")
                },
                // Missing account.
                buildJsonObject {
                    put("id", "bad-2")
                    put("name", "No Account")
                    put("query", "test")
                },
                // Blank name.
                buildJsonObject {
                    put("id", "bad-3")
                    put("name", "   ")
                    put("account", "work")
                },
                // Blank account.
                buildJsonObject {
                    put("id", "bad-4")
                    put("name", "Valid Name")
                    put("account", "  ")
                },
                // Primitive instead of object.
                JsonPrimitive("not an object"),
                // Valid entry amidst corrupt entries.
                buildJsonObject {
                    put("id", "good-1")
                    put("name", "Valid Search")
                    put("account", "work")
                    put("query", "hello")
                    put("filters", buildJsonObject {
                        put("unread", true)
                    })
                },
            ),
        )

        val decoded = invalidEntries.mapNotNull { SavedSearchJson.decode(it) }
        assertEquals(1, decoded.size)
        assertEquals("good-1", decoded[0].id)
        assertEquals("Valid Search", decoded[0].name)
        assertEquals("work", decoded[0].account)
        assertEquals("hello", decoded[0].query)
        assertTrue(decoded[0].filters.unread)
    }

    @Test
    fun queryBuiltFromSavedSearchEqualsTheOneTheSearchBoxBuilds() {
        val queryText = "project launch"
        val filters = QuickFilters(unread = true, starred = true, knownSender = true)
        val account = "work-account"

        val savedSearch = SavedSearch(
            id = "test-id",
            name = "Project Launch",
            account = account,
            query = queryText,
            filters = filters,
        )

        val fromSaved = searchRequestOf(savedSearch)
        val fromSearchBox = searchBoxRequestOf(
            account = account,
            query = queryText,
            filters = filters,
        )

        assertEquals(fromSearchBox, fromSaved)
        assertEquals(account, fromSaved.account)
        assertEquals(queryText, fromSaved.query)
        assertEquals(filters, fromSaved.filters)
        assertTrue(fromSaved.results)
        assertEquals("", fromSaved.mailbox)
        assertNull(fromSaved.tag)
        assertEquals(0, fromSaved.offset)
    }

    @Test
    fun pureListModificationFunctions() {
        val search1 = SavedSearch(id = "1", name = "First", account = "acc1", query = "one")
        val search2 = SavedSearch(id = "2", name = "Second", account = "acc2", query = "two")
        val initial = listOf(search1, search2)

        // Save new search.
        val search3 = SavedSearch(id = "3", name = "Third", account = "acc1", query = "three")
        val withThird = saveSearch(initial, search3)
        assertEquals(3, withThird.size)
        assertEquals("3", withThird.last().id)

        // Overwrite existing search.
        val search1Updated = search1.copy(name = "First Renamed")
        val updated = saveSearch(withThird, search1Updated)
        assertEquals(3, updated.size)
        assertEquals("First Renamed", updated[0].name)

        // Rename search.
        val renamed = renameSavedSearch(updated, "2", "Second Renamed")
        assertEquals("Second Renamed", renamed[1].name)

        // Empty rename is ignored.
        val blankRenamed = renameSavedSearch(renamed, "2", "   ")
        assertEquals("Second Renamed", blankRenamed[1].name)

        // Update query and filters.
        val newFilters = QuickFilters(starred = true)
        val queryUpdated = updateSavedSearchQuery(renamed, "3", "new three", newFilters)
        assertEquals("new three", queryUpdated[2].query)
        assertTrue(queryUpdated[2].filters.starred)

        // Delete search.
        val deleted = deleteSavedSearch(queryUpdated, "2")
        assertEquals(2, deleted.size)
        assertFalse(deleted.any { it.id == "2" })

        // Account filter.
        val acc1Searches = savedSearchesForAccount(deleted, "acc1")
        assertEquals(2, acc1Searches.size)
        val acc2Searches = savedSearchesForAccount(deleted, "acc2")
        assertEquals(0, acc2Searches.size)
    }

    @Test
    fun unreadCountForSavedSearch() {
        val summaries = listOf(
            Summary(
                id = "m1",
                from = "Alice",
                fromEmail = "alice@example.com",
                subject = "Bug report",
                receivedAt = "2026-09-29T10:00:00Z",
                preview = "",
                seen = false,
                flagged = true,
                keywords = setOf(),
            ),
            Summary(
                id = "m2",
                from = "Bob",
                fromEmail = "bob@example.com",
                subject = "Billing invoice",
                receivedAt = "2026-09-29T10:00:00Z",
                preview = "",
                seen = true,
                flagged = true,
                keywords = setOf(),
            ),
            Summary(
                id = "m3",
                from = "Charlie",
                fromEmail = "charlie@example.com",
                subject = "Design review",
                receivedAt = "2026-09-29T10:00:00Z",
                preview = "",
                seen = false,
                flagged = false,
                keywords = setOf("work"),
            ),
        )

        val starredSearch = SavedSearch(
            id = "s1",
            name = "Starred",
            account = "acc",
            filters = QuickFilters(starred = true),
        )
        // m1 is unread and starred. m2 is read and starred. m3 is unread but not starred.
        assertEquals(1, countUnreadSavedSearch(starredSearch, summaries))

        val allSearch = SavedSearch(
            id = "s2",
            name = "All Unread",
            account = "acc",
            filters = QuickFilters(),
        )
        // m1 and m3 are unread.
        assertEquals(2, countUnreadSavedSearch(allSearch, summaries))
    }
}
