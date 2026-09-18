package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvatarTest {
    @Test
    fun `two words give one letter each`() {
        assertEquals("WS", initialsOf("Willhite Strategy", "justin@willhitestrategy.com"))
        assertEquals("DW", initialsOf("Dana Whitfield", "dana@example.org"))
    }

    @Test
    fun `one word gives its own first two letters, never the address's`() {
        // "Blueprint" plus admin@ used to come out as "BA", which belongs to nobody.
        assertEquals("BL", initialsOf("Blueprint", "admin@blueprint.example"))
        assertEquals("ST", initialsOf("Stalwart", "noreply@example.org"))
    }

    @Test
    fun `no usable name falls back to the address`() {
        assertEquals("AD", initialsOf("", "admin@blueprint.example"))
        assertEquals("NO", initialsOf("1234", "noreply@example.org"))
        assertEquals("?", initialsOf("", "1234@example.org"))
    }

    @Test
    fun `the colour follows the address, not the display name`() {
        // So someone keeps their colour when they change how they sign their mail.
        assertEquals(avatarColor("dana@example.org"), avatarColor("DANA@example.org"))
        assertTrue(avatarColor("a@example.org") != avatarColor("zzzz@example.org"))
    }
}

class AccountNameTest {
    @Test
    fun `an account remembered under its address shows a name, not a truncated email`() {
        assertEquals("justin", shortAccountName("justin@willhitestrategy.com", "justin@willhitestrategy.com"))
        // admin@ says nothing about which server it is, so the host is the useful half.
        assertEquals("blueprint", shortAccountName("admin@blueprint.example", "admin@blueprint.example"))
    }

    @Test
    fun `a name someone actually set is left alone`() {
        assertEquals("Willhite Strategy", shortAccountName("Willhite Strategy", "justin@willhitestrategy.com"))
    }
}
