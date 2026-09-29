package org.rampart

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The sidebar's role-to-colour map. One colour per role, plain for everything else. */
class SidebarRoleColourTest {
    @Test
    fun `each folder role has one colour, and all mail and other folders stay plain`() {
        assertEquals(Color(0xFF3B6FE0), sidebarRoleColour("inbox"))
        assertEquals(sidebarRoleColour("inbox"), sidebarRoleColour("INBOX"))
        assertEquals(sidebarRoleColour("inbox"), sidebarRoleColour("unread"))
        assertEquals(Color(0xFF7B4BBF), sidebarRoleColour("drafts"))
        assertEquals(Color(0xFF1490A8), sidebarRoleColour("scheduled"))
        assertEquals(Color(0xFF2F8F4E), sidebarRoleColour("sent"))
        assertEquals(Color(0xFFB36B00), sidebarRoleColour("archive"))
        assertEquals(Color(0xFFA68B00), sidebarRoleColour("starred"))
        assertEquals(Color(0xFFDB2D54), sidebarRoleColour("junk"))
        assertEquals(sidebarRoleColour("junk"), sidebarRoleColour("Spam"))
        assertEquals(Color(0xFF6E6E7A), sidebarRoleColour("trash"))
        assertNull(sidebarRoleColour("all"))
        assertNull(sidebarRoleColour(null))
        assertNull(sidebarRoleColour("projects"))
    }
}
