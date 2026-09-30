package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The layout choice and the focused layout's back step, both plain values.
 * A window is not needed: a bad stored value and a back press are decided here.
 */
class MailLayoutTest {
    @Test
    fun `unknown layout is split and back uncovers the list`() {
        assertEquals("split", mailLayoutKey(null))
        assertEquals("split", mailLayoutKey(""))
        assertEquals("split", mailLayoutKey("nope"))
        assertEquals("split", mailLayoutKey("side by side"))
        assertEquals(MailLayout.SPLIT, MailLayout.of(null))
        assertEquals(MailLayout.SPLIT, MailLayout.of("nope"))
        assertEquals(MailLayout.FOCUSED, MailLayout.of("focused"))
        assertEquals(MailLayout.FOCUSED, MailLayout.of("FOCUSED"))
        assertEquals(MailLayout.BOTTOM, MailLayout.of("bottom"))

        val open = FocusedNav().opened("a")
        assertEquals("a", open.openKey)
        assertTrue(showsFocusedMessage(MailLayout.FOCUSED, open, "a"))
        val back = open.backed()
        assertNull(back.openKey)
        // The selected row is still "a". Back only lifts the cover.
        assertFalse(showsFocusedMessage(MailLayout.FOCUSED, back, "a"))
        assertFalse(showsFocusedMessage(MailLayout.SPLIT, open, "a"))
        assertFalse(showsFocusedMessage(MailLayout.BOTTOM, open, "a"))
        // Next message keeps the cover. The same move while the list is up does not open one.
        assertEquals("b", focusAfterSelectionChange(open, "a", "b").openKey)
        assertNull(focusAfterSelectionChange(back, "a", "b").openKey)
        assertNull(focusAfterSelectionChange(open, "a", null).openKey)
    }
}
