package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which folders Move is allowed to offer, and how deep each one sits.
 *
 * Both are arithmetic on a list, and both are the part that goes wrong quietly: a Move
 * menu that offers Archive a second time, or that shows a tree flat with no indenting, is
 * a menu people stop reading rather than one that errors.
 */
class MoveTest {

    private fun box(id: String, name: String, role: String? = null, parent: String? = null) =
        Mailbox(id, name, role, 0, parent)

    private val all = listOf(
        box("in", "Inbox", "inbox"),
        box("arc", "Archive", "archive"),
        box("jnk", "Junk", "junk"),
        box("trs", "Trash", "trash"),
        box("snt", "Sent", "sent"),
        box("drf", "Drafts", "drafts"),
        box("cli", "Clients"),
        box("acme", "Acme", parent = "cli"),
        box("deep", "Invoices", parent = "acme"),
    )

    private fun offered(here: String?) =
        all.filter { it.id != here && it.role !in MOVE_COVERED }

    @Test
    fun `the folders with a button of their own are not offered again`() {
        val names = offered(null).map { it.name }
        listOf("Archive", "Junk", "Trash", "Sent", "Drafts").forEach {
            assertTrue(it !in names, "$it has a button and must not also be in Move")
        }
        // Inbox has no button, so it stays: moving something back out of a folder is
        // exactly what Move is for.
        assertTrue("Inbox" in names)
        assertEquals(listOf("Inbox", "Clients", "Acme", "Invoices"), names)
    }

    @Test
    fun `the folder it is already in is not offered`() {
        assertTrue("Clients" !in offered("cli").map { it.name })
    }

    @Test
    fun `depth is counted within the list Move was given, not the whole tree`() {
        val shown = offered(null)
        assertEquals(0, depthOf(all.first { it.id == "cli" }, shown))
        assertEquals(1, depthOf(all.first { it.id == "acme" }, shown))
        assertEquals(2, depthOf(all.first { it.id == "deep" }, shown))
        // A folder whose parent was filtered out reads as top level rather than as an
        // orphan indented under nothing.
        assertEquals(0, depthOf(box("x", "Sub", parent = "jnk"), shown))
    }

    @Test
    fun `a loop in the parents does not hang the menu`() {
        // Not something a server should produce, and not something a menu should spin on.
        val looped = listOf(box("a", "A", parent = "b"), box("b", "B", parent = "a"))
        assertTrue(depthOf(looped[0], looped) <= 6)
    }
}
