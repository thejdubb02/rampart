package org.rampart

import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SavedSearchSplitTest {
    private fun mail(
        id: String,
        name: String,
        email: String,
        at: String,
        seen: Boolean = true,
        keywords: Set<String> = emptySet(),
        list: String = "",
    ) = Summary(id, name, email, "Subject $id", at, "", seen, keywords = keywords, listId = list)

    private val rows = listOf(
        mail("1", "Dana", "dana@example.org", "2026-09-01T09:00:00Z", seen = false, keywords = setOf("work")),
        mail("2", "Dana Price", "DANA@example.org", "2026-09-03T09:00:00Z", keywords = setOf("work", "\$seen", "billing")),
        mail("3", "Alex", "alex@example.org", "2026-09-02T09:00:00Z", seen = false, list = "news.example.org"),
        mail("4", "Alex", "alex@example.org", "2026-09-04T09:00:00Z", list = "news.example.org"),
        mail("5", "Cass", "cass@example.org", "2026-09-05T09:00:00Z", seen = false, list = "\"Old name\" <Club.example.org>"),
        mail("6", "", "", "2026-09-06T09:00:00Z"),
    )

    @Test
    fun `by sender groups addresses whatever their case, named by the newest message`() {
        assertEquals(
            listOf(
                SplitGroup("alex@example.org", "Alex", 2, 1),
                SplitGroup("dana@example.org", "Dana Price", 2, 1),
                SplitGroup("cass@example.org", "Cass", 1, 1),
            ),
            splitGroups(rows, Split.SENDER),
        )
    }

    @Test
    fun `by list uses the identifier and leaves out mail from no list`() {
        assertEquals(
            listOf(SplitGroup("news.example.org", "news.example.org", 2, 1), SplitGroup("club.example.org", "club.example.org", 1, 1)),
            splitGroups(rows, Split.LIST),
        )
    }

    @Test
    fun `by tag puts a message in every tag it carries and skips protocol keywords`() {
        assertEquals(
            listOf(SplitGroup("work", "Work", 2, 1), SplitGroup("billing", "Billing", 1, 0)),
            splitGroups(rows, Split.TAG),
        )
    }

    @Test
    fun `not splitting has no children, and the largest groups are kept`() {
        assertEquals(emptyList(), splitGroups(rows, Split.NONE))
        val many = (1..40).flatMap { n -> List(n) { mail("$n-$it", "S$n", "s$n@example.org", "2026-09-01T00:00:00Z") } }
        val kept = splitGroups(many, Split.SENDER)
        assertEquals(SPLIT_LIMIT, kept.size)
        assertEquals("s40@example.org", kept.first().key)
        assertEquals(40 - SPLIT_LIMIT + 1, kept.last().total)
    }

    @Test
    fun `children appear and disappear as the matching mail does`() {
        val before = splitGroups(rows, Split.SENDER).map { it.key }
        val arrived = rows + mail("7", "Eve", "eve@example.org", "2026-09-07T09:00:00Z", seen = false)
        assertEquals(before + "eve@example.org", splitGroups(arrived, Split.SENDER).map { it.key })
        val deleted = rows.filterNot { it.fromEmail.equals("cass@example.org", ignoreCase = true) }
        assertEquals(before - "cass@example.org", splitGroups(deleted, Split.SENDER).map { it.key })
    }

    private val parent = SavedSearch(
        id = "p",
        name = "Clients",
        account = "a",
        condition = Condition.Group(Joiner.ANY, listOf(Condition.Match(SearchField.UNREAD), Condition.Match(SearchField.STARRED))),
        split = Split.SENDER,
    )

    @Test
    fun `a child is the parent's conditions and one more`() {
        val child = childSearch(parent, SplitGroup("dana@example.org", "Dana", 2, 1))
        assertEquals("p/sender/dana@example.org", child.id)
        assertEquals("p", child.parentId)
        assertEquals("Dana", child.name)
        assertEquals(Split.NONE, child.split)
        assertEquals(
            Condition.Group(Joiner.ALL, listOf(parent.condition!!, Condition.Match(SearchField.FROM, "dana@example.org"))),
            child.condition,
        )
    }

    @Test
    fun `the sidebar list puts each parent's children straight after it`() {
        val plain = SavedSearch(id = "q", name = "Plain", account = "a", query = "x")
        val listed = withSplitChildren(
            listOf(parent, plain),
            mapOf("p" to listOf(SplitGroup("b@x", "B", 3, 0), SplitGroup("a@x", "A", 1, 1)), "q" to listOf(SplitGroup("z", "Z", 1, 1))),
        )
        assertEquals(listOf("p", "p/sender/b@x", "p/sender/a@x", "q"), listed.map { it.id })
    }

    @Test
    fun `counting a split search reads the local copy and merges accounts`() {
        val path = Files.createTempDirectory("rampart-split").resolve("mail.db")
        try {
            Store.open(path, null).use { store ->
                store.put("inbox", rows)
                store.put("junk", listOf(mail("9", "Spam", "spam@example.org", "2026-09-09T09:00:00Z", seen = false)))
                val count = countConditionSearch(parent, listOf("junk"), store, zone = ZoneOffset.UTC)
                assertEquals(3, count.unread)
                assertEquals(listOf("alex@example.org", "cass@example.org", "dana@example.org"), count.groups.map { it.key })
                val both = combinedCounts(listOf(count, count), Split.SENDER)
                assertEquals(6, both.unread)
                assertTrue(both.groups.all { it.total == 2 })
            }
        } finally {
            path.deleteIfExists()
        }
    }
}
