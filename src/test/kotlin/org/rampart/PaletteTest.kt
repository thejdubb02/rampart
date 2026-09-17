package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The overlay is Compose; these checks are that typing ranks the list the way a
 * palette has to, not that the box draws.
 */
class PaletteTest {

    private fun ids(query: String) = matches(query).map { it.id }

    @Test
    fun blankQueryReturnsTheWholeList() {
        assertEquals(COMMANDS, matches(""))
        assertEquals(COMMANDS, matches("   "))
        assertEquals(COMMANDS, matches("\t"))
    }

    @Test
    fun caseIsIgnored() {
        assertEquals(ids("re"), ids("RE"))
        assertEquals(ids("re"), ids("Re"))
        assertEquals("reply", ids("RePlY").first())
        assertEquals("go-sent", ids("GO TO SENT").first())
    }

    @Test
    fun subsequenceMatchingFindsReply() {
        assertEquals(listOf("reply", "reply-all"), ids("rpl"))
        assertEquals("go-archive", ids("gtarch").first())
    }

    @Test
    fun aPrefixMatchSortsAboveAMidStringMatch() {
        val got = ids("re")
        assertEquals("reply", got.first())
        assertTrue(got.indexOf("reply") < got.indexOf("previous"), "Reply starts with re")
        assertTrue(got.indexOf("reply") < got.indexOf("read"), "Mark as read only contains re")
    }

    @Test
    fun originalOrderIsKeptInsideATier() {
        assertEquals(
            listOf("go-inbox", "go-unified", "go-archive", "go-sent", "go-drafts"),
            ids("gt"),
        )
        assertEquals(listOf("reply", "reply-all"), ids("re").take(2))
        val byIn = ids("in")
        assertTrue(
            byIn.indexOf("go-inbox") < byIn.indexOf("go-unified"),
            "inbox before all inboxes is list order, not alphabetical",
        )
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertEquals(emptyList(), matches("qzzzz"))
        assertEquals(emptyList(), matches("xyzzy"))
    }

    @Test
    fun everyIdInCommandsIsUnique() {
        val ids = COMMANDS.map { it.id }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun everyLabelIsNonBlank() {
        for (row in COMMANDS) {
            assertEquals(false, row.label.isBlank(), row.toString())
        }
    }

    @Test
    fun `the palette and the shortcut list agree about every key`() {
        // They are two lists on purpose: the shortcut list has entries the palette cannot
        // have, like Shift+click. What must never happen is the same action being given
        // two different keys in the two places, which a reader would find by trying one.
        // Split on "or", because the shortcut list writes "J or Down" where the palette
        // has room for one key and writes "J". Both are true; they must not disagree.
        val shortcutKeys = SHORTCUTS.flatMap { it.keys.split(" or ") }.map { it.trim() }.toSet()
        COMMANDS.mapNotNull { it.keys }.forEach { keys ->
            assertTrue(keys in shortcutKeys, "the palette offers $keys and the shortcut list does not")
        }
    }
}
