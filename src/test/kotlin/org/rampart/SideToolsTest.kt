package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app bar's rules, which are what a click or a key actually changes. */
class SideToolsTest {

    @Test
    fun `the bar lists Rook, Calendar, Contacts and Files, in that order`() {
        assertEquals(
            listOf(SideTool.ROOK, SideTool.CALENDAR, SideTool.CONTACTS, SideTool.FILES),
            SideTool.entries.toList(),
        )
        assertEquals(listOf(1, 2, 3, 4), SideTool.entries.map { it.digit })
    }

    @Test
    fun `clicking a tool opens it and clicking it again closes it`() {
        val opened = SidePanels().toggle(SideTool.CALENDAR)
        assertEquals(SideTool.CALENDAR, opened.open)
        assertNull(opened.toggle(SideTool.CALENDAR).open)
    }

    @Test
    fun `only one panel is open at a time`() {
        val panels = SidePanels().toggle(SideTool.ROOK).toggle(SideTool.FILES)
        assertEquals(SideTool.FILES, panels.open)
        assertEquals(SideTool.CONTACTS, panels.show(SideTool.CONTACTS).open)
        // Show means show: asking for the one already open leaves it open.
        assertEquals(SideTool.CONTACTS, panels.show(SideTool.CONTACTS).show(SideTool.CONTACTS).open)
    }

    @Test
    fun `closing one tool does not shut a different one`() {
        val panels = SidePanels().show(SideTool.ROOK)
        assertEquals(SideTool.ROOK, panels.close(SideTool.CALENDAR).open)
        assertNull(panels.close(SideTool.ROOK).open)
        assertNull(panels.close().open)
    }

    @Test
    fun `each tool keeps its own width`() {
        val panels = SidePanels().resize(SideTool.CALENDAR, 400f).resize(SideTool.FILES, 280f)
        assertEquals(400f, panels.widthOf(SideTool.CALENDAR))
        assertEquals(280f, panels.widthOf(SideTool.FILES))
        assertEquals(SideTool.ROOK.defaultWidth, panels.widthOf(SideTool.ROOK))
        // Switching panels does not touch a width.
        assertEquals(400f, panels.toggle(SideTool.FILES).toggle(SideTool.CALENDAR).widthOf(SideTool.CALENDAR))
    }

    @Test
    fun `a drag stops at the floor and the ceiling`() {
        assertEquals(SIDE_PANEL_MIN, clampSidePanelWidth(10f))
        assertEquals(SIDE_PANEL_MAX, clampSidePanelWidth(5000f))
        assertEquals(SIDE_PANEL_MIN, clampSidePanelWidth(Float.NaN))
    }

    @Test
    fun `the panel leaves the sidebar, the list and some of the message alone`() {
        assertEquals(1400f - SIDE_PANEL_KEEP, clampSidePanelWidth(700f, available = 1400f))
        assertEquals(400f, clampSidePanelWidth(400f, available = 1400f), "a width that fits is left as it is")
        assertEquals(SIDE_PANEL_MAX, clampSidePanelWidth(900f, available = 3000f))
        assertEquals(SIDE_PANEL_MIN, clampSidePanelWidth(400f, available = 800f), "a small window still gets the floor")
        assertEquals(480f, SidePanels().resize(SideTool.ROOK, 900f, available = 1400f).widthOf(SideTool.ROOK))
    }

    @Test
    fun `widths go to settings under the stable key and come back`() {
        val saved = SidePanels().resize(SideTool.CONTACTS, 420f).resize(SideTool.ROOK, 300f).savedWidths()
        assertEquals(mapOf("contacts" to 420f, "rook" to 300f), saved)
        val back = SidePanels.restored(saved)
        assertEquals(420f, back.widthOf(SideTool.CONTACTS))
        assertEquals(300f, back.widthOf(SideTool.ROOK))
        assertNull(back.open, "a restart opens with no panel, whatever was open before")
    }

    @Test
    fun `a width read back from settings is checked again`() {
        val back = SidePanels.restored(mapOf("files" to 99999f, "someday" to 300f, "calendar" to -4f))
        assertEquals(SIDE_PANEL_MAX, back.widthOf(SideTool.FILES))
        assertEquals(SIDE_PANEL_MIN, back.widthOf(SideTool.CALENDAR))
        assertEquals(setOf(SideTool.FILES, SideTool.CALENDAR), back.widths.keys)
    }

    @Test
    fun `Ctrl and a digit open the tool in that place, and nothing else does`() {
        assertEquals(SideTool.ROOK, SideTool.forKey(1, ctrl = true, shift = false, alt = false, meta = false))
        assertEquals(SideTool.FILES, SideTool.forKey(4, ctrl = true, shift = false, alt = false, meta = false))
        assertNull(SideTool.forKey(2, ctrl = false, shift = false, alt = false, meta = false))
        assertNull(SideTool.forKey(2, ctrl = true, shift = true, alt = false, meta = false))
        assertNull(SideTool.forKey(2, ctrl = true, shift = false, alt = true, meta = false))
        assertNull(SideTool.forKey(5, ctrl = true, shift = false, alt = false, meta = false))
        assertNull(SideTool.forKey(null, ctrl = true, shift = false, alt = false, meta = false))
    }

    @Test
    fun `every tool is in the shortcut list and the palette under the same key`() {
        SideTool.entries.forEach { tool ->
            assertTrue(SHORTCUTS.any { it.keys == tool.keys }, "${tool.keys} is missing from the shortcut list")
            assertEquals(tool.keys, COMMANDS.single { it.id == tool.command }.keys, tool.name)
            assertEquals(tool, SideTool.byCommand(tool.command))
        }
    }

    @Test
    fun `no other shortcut already uses Ctrl and a digit`() {
        val ours = SideTool.entries.map { it.keys }.toSet()
        val taken = SHORTCUTS.filter { it.keys in ours }
        assertEquals(ours.size, taken.size, "each Ctrl and digit is listed once: $taken")
    }
}
