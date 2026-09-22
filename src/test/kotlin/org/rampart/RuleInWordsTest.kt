package org.rampart

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What comes back from a model is not trusted, it is checked. These are the checks.
 *
 * The property being defended: anything that reaches the server went through the same
 * parser a rule written by a person goes through, so there is no path where a made-up
 * comparator or a folder that does not exist becomes Sieve.
 */
class RuleInWordsTest {
    private val folders = listOf("Inbox", "Invoices", "Reading", "Deleted Items")

    private val dmarc = """
        {"name":"DMARC reports","matchType":"all","stopProcessing":false,
         "conditions":[{"field":"subject","comparator":"contains","value":"Report Domain"}],
         "actions":[{"type":"mark_read"},{"type":"delete"}]}
    """.trimIndent()

    @Test
    fun `the example turns into the rule it describes`() {
        val rule = ruleOfAnswer(dmarc, folders).getOrThrow()
        assertEquals("DMARC reports", rule.name)
        assertEquals(listOf(Test(Field.SUBJECT, Match.CONTAINS, "Report Domain")), rule.tests)
        assertEquals(listOf(Act.MarkRead, Act.Delete), rule.acts)
        assertTrue(rule.understood)
    }

    /** It is generated like any other rule, so it is Sieve the server already accepts. */
    @Test
    fun `and it compiles to the same script a hand built rule would`() {
        val rule = ruleOfAnswer(dmarc, folders).getOrThrow()
        val script = sieveOf(Script(listOf(rule)))
        assertContains(script, "header :contains \"Subject\" \"Report Domain\"")
        assertContains(script, "discard;")
        assertEquals(listOf(rule.name), scriptOf(script).rules.map { it.name })
    }

    /** Models fence their JSON however firmly they are told not to. */
    @Test
    fun `a fenced answer is still read`() {
        val fenced = "Here you go:\n```json\n$dmarc\n```"
        assertEquals("DMARC reports", ruleOfAnswer(fenced, folders).getOrThrow().name)
    }

    /*
     * The refusal path. A model that cannot say a thing has to say so, because the failure
     * that matters is not a broken rule, it is a plausible rule that does something else.
     */
    @Test
    fun `a refusal comes back as its own sentence`() {
        val answer = """{"error":"Rampart cannot filter on how old a message is."}"""
        val failure = ruleOfAnswer(answer, folders).exceptionOrNull()
        assertEquals("Rampart cannot filter on how old a message is.", failure?.message)
    }

    @Test
    fun `a comparator this build does not have is refused rather than approximated`() {
        val answer = """
            {"name":"Old","conditions":[{"field":"age","comparator":"older_than","value":"30d"}],
             "actions":[{"type":"delete"}]}
        """.trimIndent()
        assertTrue(ruleOfAnswer(answer, folders).isFailure)
    }

    /** Filing into somewhere that does not exist either fails at delivery or makes a folder. */
    @Test
    fun `an invented folder is refused, and names the one it invented`() {
        val answer = """
            {"name":"Bills","conditions":[{"field":"from","comparator":"contains","value":"billing@"}],
             "actions":[{"type":"move","value":"Bills"}]}
        """.trimIndent()
        assertEquals("There is no folder called Bills.", ruleOfAnswer(answer, folders).exceptionOrNull()?.message)
    }

    @Test
    fun `a folder that is only wrong about case is corrected`() {
        val answer = """
            {"name":"Bills","conditions":[{"field":"from","comparator":"contains","value":"billing@"}],
             "actions":[{"type":"move","value":"invoices"}]}
        """.trimIndent()
        assertEquals(listOf(Act.FileInto("Invoices")), ruleOfAnswer(answer, folders).getOrThrow().acts)
    }

    @Test
    fun `a rule with nothing to do is refused`() {
        val answer = """{"name":"Nothing","conditions":[{"field":"from","comparator":"is","value":"x@y.z"}],"actions":[]}"""
        assertTrue(ruleOfAnswer(answer, folders).isFailure)
    }

    @Test
    fun `an answer that is not JSON at all is refused`() {
        assertTrue(ruleOfAnswer("I am sorry, I cannot help with that.", folders).isFailure)
    }

    /*
     * An id from the model would replace whichever rule already had it on the next save.
     * The one thing in the answer that is never taken as given.
     */
    @Test
    fun `the id is ours, never the model's`() {
        val answer = """
            {"id":"f1","name":"Bills","conditions":[{"field":"from","comparator":"is","value":"a@b.c"}],
             "actions":[{"type":"delete"}]}
        """.trimIndent()
        assertTrue(ruleOfAnswer(answer, folders).getOrThrow().id != "f1")
    }

    /** The prompt has to carry the real folder names, or every move is a guess. */
    @Test
    fun `the folders it may use are named in the prompt`() {
        val system = ruleSystem(folders)
        assertContains(system, "Invoices")
        assertContains(system, "Never invent a folder")
        assertContains(system, "starts_with")
    }

    /** Only what was typed and the folder names. That is the promise the viewer makes. */
    @Test
    fun `the packet carries the sentence and nothing from the mailbox`() {
        val packet = rulePacket(AssistantConfig(), "delete DMARC reports", folders)
        assertContains(packet, "delete DMARC reports")
        assertContains(packet, "Invoices")
    }

    /** The sentence a right-click starts from. No model: the words are built here. */
    @Test
    fun `a message seeds the sentence and leaves the action to the person`() {
        val message = Summary(
            "a",
            "Stalwart",
            "stalwart@example.org",
            "Your certificate renews in 7 days",
            "2026-09-16T09:12:00Z",
            "The certificate will be renewed.",
            false,
        )
        assertEquals(
            "Filter emails like this: from stalwart@example.org, " +
                "subject like \"Your certificate renews in 7 days\". Say what to do with them.",
            filterSeed(message),
        )
        // No address on the row: the name is used, and that name is sometimes only a domain.
        assertEquals(
            "Filter emails like this: from news.example.org, " +
                "subject like \"Your certificate renews in 7 days\". Say what to do with them.",
            filterSeed(message.copy(from = "news.example.org", fromEmail = "")),
        )
    }
}
