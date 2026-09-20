package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummariseTest {
    private fun turn(from: String, text: String) = Turn(from, "19 Sep", text)

    @Test
    fun `the model is told that what follows is data`() {
        val said = Summarise.system().lowercase()
        assertTrue(said.contains("never") && said.contains("instruction"), Summarise.system())
    }

    @Test
    fun `a thread comes out oldest first, under its subject`() {
        val out = Summarise.user("The quote", listOf(turn("Tom", "can you send it"), turn("Justin", "sent")))
        assertEquals(
            "Subject: The quote\n\nTom (19 Sep):\ncan you send it\n\nJustin (19 Sep):\nsent",
            out,
        )
    }

    @Test
    fun `a message with no subject says so rather than saying nothing`() {
        assertTrue(Summarise.user("   ", emptyList()).contains("(no subject)"))
    }

    @Test
    fun `layout inside a message is not layout the model needs`() {
        val out = Summarise.user("s", listOf(turn("Tom", "  one\n\n\n   two\t\tthree  ")))
        assertTrue(out.endsWith("one two three"), out)
    }

    @Test
    fun `a message with nothing in it is not a turn`() {
        assertEquals("Subject: s", Summarise.user("s", listOf(turn("Tom", "  \n \t "))))
    }

    @Test
    fun `the oldest go first when the thread is too long to send`() {
        val long = "x".repeat(10_000)
        val out = Summarise.user("s", (1..6).map { turn("P$it", long) })
        assertTrue(out.length <= Summarise.BUDGET, "sent ${out.length}")
        assertTrue(out.contains("P6"), "the newest message was dropped")
        assertTrue(!out.contains("P1"), "the oldest message was kept")
    }

    @Test
    fun `one message too big for the budget is cut rather than dropped`() {
        val out = Summarise.user("s", listOf(turn("Tom", "y".repeat(Summarise.BUDGET * 2))))
        assertTrue(out.length <= Summarise.BUDGET, "sent ${out.length}")
        assertTrue(out.contains("Tom"))
        assertTrue(out.endsWith("..."), out.takeLast(20))
    }

    @Test
    fun `nothing this builds is ever over the budget`() {
        val wide = Turn("a".repeat(Summarise.BUDGET), "b".repeat(Summarise.BUDGET), "c".repeat(Summarise.BUDGET))
        assertTrue(Summarise.user("d".repeat(Summarise.BUDGET), listOf(wide)).length <= Summarise.BUDGET)
    }
}
