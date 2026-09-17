package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IconPackTest {
    @Test
    fun `every pack has a key and a label, and no two share a key`() {
        assertTrue(ICON_PACKS.isNotEmpty())
        ICON_PACKS.forEach {
            assertTrue(it.key.isNotBlank())
            assertTrue(it.label.isNotBlank())
        }
        assertEquals(ICON_PACKS.size, ICON_PACKS.map { it.key }.distinct().size)
    }

    /* A pack named in settings that a later build dropped must not take the app with it. */
    @Test
    fun `an unknown or missing pack falls back rather than failing`() {
        assertSame(LineIcons, iconPack(null))
        assertSame(LineIcons, iconPack(""))
        assertSame(LineIcons, iconPack("a pack that was removed"))
        assertSame(HeavyIcons, iconPack("heavy"))
    }

    /*
     * The seam that makes a third pack possible: a pack that delegates must still return
     * its own glyph for everything it overrode, and the interface's own forRole has to
     * follow the pack rather than the set it was delegating to.
     */
    @Test
    fun `a delegating pack really replaces what it overrides`() {
        assertSame(HeavyIcons.Inbox, HeavyIcons.forRole("inbox"))
        assertSame(HeavyIcons.Trash, HeavyIcons.forRole("trash"))
        assertTrue(HeavyIcons.Inbox !== LineIcons.Inbox, "the heavy set handed back the line glyph")
        assertTrue(HeavyIcons.Star !== LineIcons.Star)
    }

    @Test
    fun `folders with no role get the plain folder glyph`() {
        ICON_PACKS.forEach {
            assertSame(it.Folder, it.forRole(null))
            assertSame(it.Folder, it.forRole("something the server made up"))
        }
    }

    @Test
    fun `the heavy set keeps the shapes and only changes the weight`() {
        // Same glyph name, so it is the same drawing rather than a different icon.
        assertEquals(LineIcons.Inbox.name, HeavyIcons.Inbox.name)
        assertEquals(LineIcons.Inbox.viewportWidth, HeavyIcons.Inbox.viewportWidth)
    }
}
