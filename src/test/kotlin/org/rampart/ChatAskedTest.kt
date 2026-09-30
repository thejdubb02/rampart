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

    @Test
    fun `a fenced call keeps the prose before and after it`() {
        val asked = Chat.asked(
            """
            Checking the inbox.

            ```json
            {"tool":"search","args":{"text":"dmarc"}}
            ```

            I will read whatever turns up.
            """.trimIndent(),
        )!!
        assertEquals("search", asked.tool)
        assertEquals("dmarc", asked.args["text"].toString().trim('"'))
        assertEquals("Checking the inbox.\n\nI will read whatever turns up.", asked.lead)
        assertEquals(false, asked.several)
    }

    @Test
    fun `a fenced call in the middle of a reply is the call`() {
        val asked = Chat.asked(
            "Sure.\n```\n{\"tool\":\"read\",\"args\":{\"id\":\"abc\"}}\n```\nNext.",
        )!!
        assertEquals("read", asked.tool)
        assertEquals("abc", asked.args["id"].toString().trim('"'))
        assertEquals("Sure.\n\nNext.", asked.lead)
    }

    @Test
    fun `a multi line call in the middle keeps the prose around it`() {
        val asked = Chat.asked(
            """
            I will look that up.
            {
              "tool": "search",
              "args": {"text": "dmarc"}
            }
            One moment.
            """.trimIndent(),
        )!!
        assertEquals("search", asked.tool)
        assertEquals("dmarc", asked.args["text"].toString().trim('"'))
        assertEquals("I will look that up.\n\nOne moment.", asked.lead)
    }

    @Test
    fun `two calls are refused and the prose is kept`() {
        val asked = Chat.asked(
            """
            First this.
            {"tool":"search","args":{"text":"a"}}
            Then that.
            {"tool":"read","args":{"id":"b"}}
            Thanks.
            """.trimIndent(),
        )!!
        assertEquals(true, asked.several)
        assertEquals("", asked.tool)
        assertEquals("First this.\n\nThen that.\n\nThanks.", asked.lead)
    }

    @Test
    fun `an OpenAI tool call maps onto tool and args`() {
        val arguments = """{"text":"hotel"}""".replace("\"", "\\\"")
        val asked = Chat.asked(
            """{"tool_calls":[{"function":{"name":"search","arguments":"$arguments"}}]}""",
        )!!
        assertEquals("search", asked.tool)
        assertEquals("hotel", asked.args["text"].toString().trim('"'))
        assertEquals(false, asked.several)
    }

    @Test
    fun `a name and arguments object maps onto tool and args`() {
        val asked = Chat.asked("""{"name":"archive","arguments":{"ids":["a"]}}""")!!
        assertEquals("archive", asked.tool)
        assertEquals("""["a"]""", asked.args["ids"].toString())
    }
}
