package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchConditionsTest {
    private val utc = ZoneOffset.UTC

    private fun match(field: SearchField, value: String = "") = Condition.Match(field, value)
    private fun all(vararg items: Condition) = Condition.Group(Joiner.ALL, items.toList())
    private fun any(vararg items: Condition) = Condition.Group(Joiner.ANY, items.toList())
    private fun none(vararg items: Condition) = Condition.Group(Joiner.NONE, items.toList())

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    /** From the bank, and either unread or starred, and not a newsletter. */
    private val nested = all(
        match(SearchField.FROM, "bank.example"),
        any(match(SearchField.UNREAD), match(SearchField.STARRED)),
        none(match(SearchField.SUBJECT, "newsletter")),
    )

    @Test
    fun `nested groups become FilterOperators with AND, OR and NOT`() {
        assertEquals(
            json(
                """
                {"operator":"AND","conditions":[
                  {"from":"bank.example"},
                  {"operator":"OR","conditions":[{"notKeyword":"${'$'}seen"},{"hasKeyword":"${'$'}flagged"}]},
                  {"operator":"NOT","conditions":[{"subject":"newsletter"}]}
                ]}
                """,
            ),
            jmapFilter(nested, zone = utc),
        )
    }

    @Test
    fun `three levels deep keep their shape`() {
        val deep = any(all(match(SearchField.TAG, "work"), none(match(SearchField.LIST, "news.example.org"))), match(SearchField.TEXT, "invoice"))
        assertEquals(
            json(
                """
                {"operator":"OR","conditions":[
                  {"operator":"AND","conditions":[
                    {"hasKeyword":"work"},
                    {"operator":"NOT","conditions":[{"header":["List-Id","news.example.org"]}]}
                  ]},
                  {"text":"invoice"}
                ]}
                """,
            ),
            jmapFilter(deep, zone = utc),
        )
    }

    @Test
    fun `a group of one is its child, except none of which negates it`() {
        assertEquals(json("""{"from":"a"}"""), jmapFilter(all(any(match(SearchField.FROM, "a"))), zone = utc))
        assertEquals(
            json("""{"operator":"NOT","conditions":[{"from":"a"}]}"""),
            jmapFilter(none(match(SearchField.FROM, "a")), zone = utc),
        )
    }

    @Test
    fun `blank conditions and empty groups are dropped before anything is sent`() {
        val messy = all(match(SearchField.FROM, "  "), any(), match(SearchField.SUBJECT, " quote "), none())
        assertEquals(json("""{"subject":"quote"}"""), jmapFilter(messy, zone = utc))
        assertEquals(json("{}"), jmapFilter(all(any()), zone = utc))
        assertNull(normalized(all(match(SearchField.AFTER, "last tuesday"))))
    }

    @Test
    fun `junk and trash are left out around the tree, not inside it`() {
        assertEquals(
            json(
                """
                {"operator":"AND","conditions":[
                  {"inMailboxOtherThan":["junk","trash"]},
                  {"subject":"quote"}
                ]}
                """,
            ),
            jmapFilter(match(SearchField.SUBJECT, "quote"), listOf("junk", "trash", ""), zone = utc),
        )
        assertEquals(json("""{"inMailboxOtherThan":["junk"]}"""), jmapFilter(null, listOf("junk"), zone = utc))
    }

    @Test
    fun `dates, sizes and flags use the RFC 8621 property for each`() {
        assertEquals(
            json(
                """
                {"operator":"AND","conditions":[
                  {"after":"2026-09-01T00:00:00Z"},{"before":"2026-10-01T00:00:00Z"},
                  {"minSize":2097152},{"maxSize":512000},{"hasAttachment":true},{"to":"me@example.org"}
                ]}
                """,
            ),
            jmapFilter(
                all(
                    match(SearchField.AFTER, "2026-09-01"),
                    match(SearchField.BEFORE, "2026-10-01"),
                    match(SearchField.LARGER, "2 MB"),
                    match(SearchField.SMALLER, "500kb"),
                    match(SearchField.ATTACHMENT, "ignored"),
                    match(SearchField.TO, "me@example.org"),
                ),
                zone = utc,
            ),
        )
    }

    @Test
    fun `a date starts at midnight where the person is`() {
        assertEquals(
            json("""{"after":"2026-09-01T04:00:00Z"}"""),
            jmapFilter(match(SearchField.AFTER, "2026-09-01"), zone = ZoneOffset.ofHours(-4)),
        )
    }

    @Test
    fun `the local query is fixed SQL with every value bound`() {
        val query = assertIs<LocalQuery.Sql>(localWhere(nested, listOf("junk"), utc))
        assertEquals(
            "id NOT IN (SELECT message_id FROM mailbox_message WHERE mailbox_id IN (?)) AND " +
                "((lower(sender) LIKE ? ESCAPE '\\' OR lower(senderEmail) LIKE ? ESCAPE '\\') AND " +
                "(seen = 0 OR flagged = 1) AND NOT (lower(subject) LIKE ? ESCAPE '\\'))",
            query.where,
        )
        assertEquals(listOf<Any>("junk", "%bank.example%", "%bank.example%", "%newsletter%"), query.args)
    }

    @Test
    fun `typed text never reaches the SQL text`() {
        val hostile = "x' OR 1=1; DROP TABLE message; --"
        val tree = any(
            match(SearchField.FROM, hostile),
            match(SearchField.SUBJECT, "100%_done\\"),
            match(SearchField.TAG, hostile),
            match(SearchField.TEXT, "\"quoted\" NEAR(x)"),
            match(SearchField.LIST, hostile),
        )
        val query = assertIs<LocalQuery.Sql>(localWhere(tree, zone = utc))
        assertFalse("DROP" in query.where)
        assertFalse("1=1" in query.where)
        assertEquals(query.where.count { it == '?' }, query.args.size)
        // LIKE's own wildcards are escaped, so a subject with a percent sign is that text.
        assertTrue("%100\\%\\_done\\\\%" in query.args)
        assertTrue("\"quoted\" \"NEAR(x)\"" in query.args)
    }

    @Test
    fun `recipient and attachment make the local query unanswerable wherever they are`() {
        val buried = all(match(SearchField.FROM, "a"), any(match(SearchField.STARRED), none(match(SearchField.TO, "b"))))
        assertIs<LocalQuery.Unanswerable>(localWhere(buried, zone = utc))
        assertIs<LocalQuery.Unanswerable>(localWhere(match(SearchField.ATTACHMENT), zone = utc))
    }

    private fun mail(
        id: String,
        from: String,
        subject: String,
        at: String,
        seen: Boolean = true,
        flagged: Boolean = false,
        keywords: Set<String> = emptySet(),
        list: String = "",
        size: Long = 0,
    ) = Summary(
        id, from, "${from.lowercase()}@${if (from == "Bank") "bank.example" else "example.org"}", subject, at,
        "about $subject", seen, flagged, keywords, size = size, listId = list,
    )

    private fun <T> withStore(block: (Store) -> T): T {
        val path = Files.createTempDirectory("rampart-conditions").resolve("mail.db")
        return try {
            Store.open(path, null).use(block)
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun `the local copy answers a nested tree the way the server would`() = withStore { store ->
        store.put(
            "inbox",
            listOf(
                mail("1", "Bank", "Statement ready", "2026-09-20T09:00:00Z", seen = false),
                mail("2", "Bank", "Your newsletter", "2026-09-21T09:00:00Z", seen = false),
                mail("3", "Bank", "Card payment", "2026-09-22T09:00:00Z", flagged = true),
                mail("4", "Bank", "Old notice", "2026-09-23T09:00:00Z"),
                mail("5", "Dana", "Statement from Dana", "2026-09-24T09:00:00Z", seen = false),
            ),
        )
        store.put("junk", listOf(mail("6", "Bank", "Statement (phish)", "2026-09-25T09:00:00Z", seen = false)))
        val query = assertIs<LocalQuery.Sql>(localWhere(nested, listOf("junk"), utc))
        assertEquals(listOf("3", "1"), store.matching(query).map { it.id })
    }

    @Test
    fun `lists, tags, dates and sizes are answered from their columns`() = withStore { store ->
        store.put(
            "inbox",
            listOf(
                mail("a", "List", "Weekly", "2026-09-01T09:00:00Z", list = "weekly.news.example.org", size = 40_000),
                mail("b", "Tom", "Plans", "2026-09-10T09:00:00Z", keywords = setOf("work", "\$seen"), size = 3_000_000),
                mail("c", "Tom", "Workshop", "2026-09-12T09:00:00Z", keywords = setOf("workshop")),
            ),
        )
        fun ids(condition: Condition) =
            store.matching(localWhere(condition, zone = utc) as LocalQuery.Sql).map { it.id }
        assertEquals(listOf("a"), ids(match(SearchField.LIST, "news.example")))
        assertEquals(listOf("b"), ids(match(SearchField.TAG, "Work")))
        assertEquals(listOf("c", "b"), ids(match(SearchField.AFTER, "2026-09-02")))
        assertEquals(listOf("b"), ids(match(SearchField.LARGER, "1 MB")))
        assertEquals(listOf("a"), ids(match(SearchField.SMALLER, "1 MB")))
        assertEquals(listOf("c", "a"), ids(none(match(SearchField.TAG, "work"))))
        // The size and list survive the trip through the table.
        assertEquals(3_000_000, store.matching(localWhere(match(SearchField.TAG, "work"), zone = utc) as LocalQuery.Sql).single().size)
    }

    @Test
    fun `hostile text is only ever searched for`() = withStore { store ->
        store.put("inbox", listOf(mail("1", "Dana", "x' OR 1=1; DROP TABLE message; --", "2026-09-20T09:00:00Z")))
        val tree = any(match(SearchField.SUBJECT, "DROP TABLE"), match(SearchField.TEXT, "\"; DROP"))
        assertEquals(listOf("1"), store.matching(localWhere(tree, zone = utc) as LocalQuery.Sql).map { it.id })
        assertEquals(emptyList(), store.matching(localWhere(match(SearchField.FROM, "' OR ''='"), zone = utc) as LocalQuery.Sql))
        assertEquals(1, store.messages("inbox").size)
    }

    @Test
    fun `conditions survive the settings file and unknown fields are dropped`() {
        assertEquals(nested, conditionOf(conditionJson(nested)))
        val newer = json("""{"any":[{"field":"from","value":"a"},{"field":"mood","value":"happy"},{"all":[]}]}""")
        assertEquals(any(match(SearchField.FROM, "a"), all()), conditionOf(newer))
    }

    @Test
    fun `a search saved before conditions reads back as it was`() {
        val old = json(
            """
            {"id":"s1","name":"Invoices","account":"a","query":"invoice",
             "filters":{"unread":true,"starred":false,"tagged":true,"attachment":false,"knownSender":false}}
            """,
        )
        val search = SavedSearchJson.decode(old)!!
        assertNull(search.condition)
        assertEquals(Split.NONE, search.split)
        assertEquals("invoice", search.query)
        assertEquals(QuickFilters(unread = true, tagged = true), search.filters)
        // Written back unchanged, with no keys an older build would not expect.
        assertFalse("condition" in SavedSearchJson.encode(search))
        assertFalse("split" in SavedSearchJson.encode(search))
    }

    @Test
    fun `a new search round trips with its tree and its split`() {
        val search = SavedSearch(id = "s2", name = "Lists", account = "a", condition = nested, split = Split.LIST)
        assertEquals(search, SavedSearchJson.decode(SavedSearchJson.encode(search)))
    }

    @Test
    fun `an old search opened in the builder starts from what it meant`() {
        val filters = QuickFilters(unread = true, starred = true, tagged = true, attachment = true, knownSender = true)
        assertEquals(
            all(match(SearchField.TEXT, "invoice"), match(SearchField.UNREAD), match(SearchField.STARRED), match(SearchField.ATTACHMENT)),
            legacyCondition(" invoice ", filters),
        )
        assertEquals(QuickFilters(tagged = true, knownSender = true), legacyLeftover(filters))
        val saved = updateSavedSearchConditions(
            listOf(SavedSearch(id = "s", name = "Old", account = "a", query = "invoice", filters = filters)),
            "s", "", nested, Split.SENDER,
        ).single()
        assertEquals("Old", saved.name)
        assertEquals("", saved.query)
        assertEquals(nested, saved.condition)
        assertEquals(QuickFilters(tagged = true, knownSender = true), saved.filters)
    }

    @Test
    fun `a problem is named before the search runs`() {
        assertEquals("This needs a value.", problemOf(match(SearchField.FROM, " ")))
        assertNull(problemOf(match(SearchField.UNREAD)))
        assertTrue(problemOf(match(SearchField.AFTER, "yesterday"))!!.contains("year-month-day"))
        assertTrue(problemOf(match(SearchField.LARGER, "big"))!!.contains("2 MB"))
        assertEquals(1536L, bytesOf("1.5 KB"))
        assertEquals(1000L, bytesOf("1000"))
    }

    @Test
    fun `the builder edits the tree by path without touching the rest`() {
        val start = all(match(SearchField.FROM, "a"), any(match(SearchField.UNREAD)))
        val added = start.addedTo(listOf(1), match(SearchField.STARRED))
        assertEquals(all(match(SearchField.FROM, "a"), any(match(SearchField.UNREAD), match(SearchField.STARRED))), added)
        val changed = added.changedAt(listOf(1, 0)) { match(SearchField.TAG, "work") }
        assertEquals(all(match(SearchField.FROM, "a"), any(match(SearchField.TAG, "work"), match(SearchField.STARRED))), changed)
        val regrouped = changed.changedAt(listOf(1)) { (it as Condition.Group).copy(joiner = Joiner.NONE) }
        assertEquals(Joiner.NONE, (regrouped.items[1] as Condition.Group).joiner)
        assertEquals(all(match(SearchField.FROM, "a"), any(match(SearchField.STARRED))), changed.removedAt(listOf(1, 0)))
        assertEquals(all(any(match(SearchField.TAG, "work"), match(SearchField.STARRED))), changed.removedAt(listOf(0)))
        assertEquals(changed, changed.removedAt(emptyList()))
        assertEquals(changed, changed.changedAt(listOf(9)) { match(SearchField.TEXT, "x") })
        assertEquals(listOf("This needs a value."), problemsIn(changed.addedTo(emptyList(), match(SearchField.SUBJECT))))
    }

    @Test
    fun `a list id is its identifier, not its description`() {
        assertEquals("users.rampart.example.org", listIdOf("\"Rampart Users\" <Users.Rampart.example.org>"))
        assertEquals("plain.example.org", listIdOf(" plain.example.org "))
        assertEquals("", listIdOf(null))
    }

    @Test
    fun `the server is asked first and the copy answers when it cannot`() = withStore { store ->
        store.put("inbox", listOf(mail("1", "Bank", "Statement", "2026-09-20T09:00:00Z", seen = false)))
        var sent: JsonObject? = null
        val live = runConditionSearch(nested, listOf("junk"), { sent = it; listOf(mail("9", "Bank", "From the server", "x")) }, store, zone = utc)
        assertEquals(listOf("9"), live.messages.map { it.id })
        assertNull(live.note)
        assertEquals(jmapFilter(nested, listOf("junk"), utc), sent)

        val offline = runConditionSearch(nested, emptyList(), { error("no route to host") }, store, zone = utc)
        assertEquals(listOf("1"), offline.messages.map { it.id })
        assertEquals("The server did not answer, so this is from the saved copy.", offline.note)
        assertEquals("no route to host", offline.detail)

        val imapNoCopy = runConditionSearch(nested, emptyList(), null, null, zone = utc)
        assertEquals("This account has no saved copy to search.", imapNoCopy.note)
        val imapTo = runConditionSearch(match(SearchField.TO, "me"), emptyList(), null, store, zone = utc)
        assertTrue(imapTo.note!!.startsWith("The saved copy does not record who"))
    }
}
