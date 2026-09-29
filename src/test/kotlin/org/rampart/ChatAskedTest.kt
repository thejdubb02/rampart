package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatAskedTest {
    @Test
    fun `a bare call is a call with no lead`() {
        val asked = Chat.asked("""{"tool":"search","args":{"text":"dmarc"}}""")!!
        assertEquals("search", asked.tool)
        assertEquals("", asked.lead)
    }

    @Test
    fun `a sentence then the call on its own last line runs the call and keeps the sentence`() {
        val asked = Chat.asked(
            "Let me first check what filtering options are available.\n" +
                """{"tool":"list_settings","args":{"search":"filter"}}""",
        )!!
        assertEquals("list_settings", asked.tool)
        assertEquals("filter", asked.args["search"].toString().trim('"'))
        assertEquals("Let me first check what filtering options are available.", asked.lead)
    }

    @Test
    fun `a call buried inside a sentence is still a sentence`() {
        assertNull(Chat.asked("""I could run {"tool":"archive","args":{"ids":["a"]}} if you like."""))
    }

    @Test
    fun `plain words are not a call`() {
        assertNull(Chat.asked("Done. Nothing else to archive."))
    }
}
