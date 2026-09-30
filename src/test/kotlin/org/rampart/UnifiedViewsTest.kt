package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What goes into All unread, All starred and All mail, and which accounts feed them. */
class UnifiedViewsTest {
    private fun box(id: String, name: String, role: String? = null) = Mailbox(id, name, role, 0)

    private fun mail(id: String, at: String, seen: Boolean = false, flagged: Boolean = false) = Summary(
        id = id, from = "Someone", fromEmail = "someone@example.com", subject = id,
        receivedAt = at, preview = "", seen = seen, flagged = flagged,
    )

    private val mine = listOf(box("i", "Inbox", "inbox"), box("r", "Archive", "archive"), box("p", "Projects"), box("j", "Junk", "junk"))
    private val group = listOf(box("i", "Inbox", "inbox"), box("x", "Projects"))

    @Test
    fun `a view is read from the chosen folders on every account, newest first, each message stamped`() {
        val asked = mutableListOf<Pair<String, String>>()
        val result = composeUnified(
            UnifiedView.ALL_MAIL,
            listOf(UnifiedSource("me", mine), UnifiedSource("group", group)),
            listOf("role:inbox", "name:projects"),
        ) { key, folder, _ ->
            asked += key to folder
            listOf(mail("$key-$folder", if (key == "me") "2026-09-20T09:00:00Z" else "2026-09-21T09:00:00Z"))
        }
        assertEquals(listOf("me" to "i", "me" to "p", "group" to "i", "group" to "x"), asked)
        assertEquals(listOf("group", "group", "me", "me"), result.messages.map { it.account })
        assertEquals(0, result.unreadable)
        assertNull(result.note)
    }

    @Test
    fun `a message in two chosen folders shows once, and the same id on two accounts shows twice`() {
        val result = composeUnified(
            UnifiedView.ALL_MAIL,
            listOf(UnifiedSource("me", mine), UnifiedSource("group", group)),
            listOf("role:inbox", "name:projects"),
        ) { _, _, _ -> listOf(mail("same", "2026-09-20T09:00:00Z")) }
        assertEquals(2, result.messages.size)
        assertEquals(setOf("me", "group"), result.messages.map { it.account }.toSet())
    }

    @Test
    fun `the view's own filter is asked for and applied again`() {
        var filters: QuickFilters? = null
        val result = composeUnified(
            UnifiedView.UNREAD,
            listOf(UnifiedSource("me", mine)),
            UnifiedView.UNREAD.defaultFeed,
        ) { _, _, asked ->
            filters = asked
            // A backend that ignored the filter.
            listOf(mail("new", "2026-09-20T09:00:00Z"), mail("old", "2026-09-19T09:00:00Z", seen = true))
        }
        assertTrue(filters!!.unread)
        assertEquals(listOf("new"), result.messages.map { it.id })

        val starred = composeUnified(UnifiedView.STARRED, listOf(UnifiedSource("me", mine)), listOf("role:inbox")) { _, _, _ ->
            listOf(mail("plain", "2026-09-20T09:00:00Z"), mail("star", "2026-09-19T09:00:00Z", flagged = true))
        }
        assertEquals(listOf("star"), starred.messages.map { it.id })
    }

    @Test
    fun `one folder that cannot be read loses its share and is counted, not the whole list`() {
        val result = composeUnified(
            UnifiedView.ALL_MAIL,
            listOf(UnifiedSource("me", mine), UnifiedSource("group", group)),
            listOf("role:inbox"),
        ) { key, _, _ -> if (key == "group") null else listOf(mail("ok", "2026-09-20T09:00:00Z")) }
        assertEquals(listOf("ok"), result.messages.map { it.id })
        assertEquals(1, result.unreadable)
        assertTrue(result.note!!.startsWith("One folder"))
    }

    @Test
    fun `a folder chosen by name is skipped on an account without one`() {
        assertEquals(listOf("i"), feedFolderIds(listOf("role:inbox", "name:clients"), group))
        assertEquals(listOf("p"), feedFolderIds(listOf("name:projects"), mine))
        assertEquals(emptyList(), feedFolderIds(listOf("nonsense"), mine))
    }

    @Test
    fun `the chooser offers each folder once across accounts and never junk or trash`() {
        val choices = feedChoices(listOf(mine, group))
        assertEquals(listOf("role:archive", "role:inbox", "name:projects"), choices.map { it.first })
    }

    @Test
    fun `defaults feed each view until something is chosen, and an empty choice goes back to them`() {
        assertEquals(listOf("role:inbox"), feedOf(UnifiedView.UNREAD, emptyMap()))
        val (key, value) = feedChange(UnifiedView.STARRED, listOf("name:projects", "role:inbox", "name:projects"))
        val prefs = mapOf(key to value!!)
        assertEquals(listOf("name:projects", "role:inbox"), feedOf(UnifiedView.STARRED, prefs))
        assertEquals(UnifiedView.UNREAD.defaultFeed, feedOf(UnifiedView.UNREAD, prefs))
        assertNull(feedChange(UnifiedView.STARRED, emptyList()).second)
    }

    @Test
    fun `the switch on one shared account is saved under that account and touches no other`() {
        val sales = sharedKey("me@example.com", "mail.example.com", "g")
        val support = sharedKey("me@example.com", "mail.example.com", "s")
        val login = "me@example.com@mail.example.com"
        val (key, value) = includeChange(sales, included = false)
        assertTrue(key.endsWith("#shared/g@mail.example.com"))
        val prefs = mapOf(key to value!!)
        assertFalse(includedInUnified(sales, prefs))
        assertTrue(includedInUnified(support, prefs))
        assertTrue(includedInUnified(login, prefs))
        assertEquals(listOf(login, support), unifiedKeys(listOf(login, sales, support), prefs))
        // Switching it back on forgets the entry rather than storing "yes".
        assertNull(includeChange(sales, included = true).second)
    }

    @Test
    fun `your own accounts cannot be switched out`() {
        val login = "me@example.com@mail.example.com"
        assertTrue(includedInUnified(login, mapOf(includeKey(login) to "no")))
    }

    @Test
    fun `the unread total leaves out a shared inbox that is switched out`() {
        val mine = "me@example.com@mail.example.com"
        val sales = sharedKey("me@example.com", "mail.example.com", "g")
        fun inbox(unread: Int) = listOf(Mailbox("in", "Inbox", "inbox", unread, unreadThreads = unread))
        val boxes = mapOf(mine to inbox(2), sales to inbox(4))
        assertEquals(6, unifiedInboxUnread(boxes, emptyMap()))
        val (key, value) = includeChange(sales, included = false)
        assertEquals(2, unifiedInboxUnread(boxes, mapOf(key to value!!)))
        // Your own inbox still counts when something tries to switch it out.
        assertEquals(6, unifiedInboxUnread(boxes, mapOf(includeKey(mine) to "no")))
        assertEquals(2, unifiedInboxUnread(boxes, mapOf(key to value, includeKey(mine) to "no")))
    }

    @Test
    fun `each view has its own pseudo folder that no server folder can be`() {
        UnifiedView.entries.forEach { view ->
            assertEquals(view, UnifiedView.of(view.mailbox().id))
            assertTrue(view.id.startsWith("*"))
        }
        assertNull(UnifiedView.of(ALL_ACCOUNTS))
        assertNull(UnifiedView.of("i"))
    }
}
