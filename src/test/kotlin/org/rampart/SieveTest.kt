package org.rampart

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SieveTest {
    /** A real script taken off the server, with the one real address changed. */
    private val bulwark: String = SieveTest::class.java.classLoader
        .getResource("bulwark-script.sieve")!!.readText()

    private val filing = Rule(
        id = "r1",
        name = "Invoices",
        tests = listOf(Test(Field.FROM, Match.CONTAINS, "hetzner.test")),
        acts = listOf(Act.FileInto("Invoices")),
    )

    // --- reading what Bulwark wrote ---------------------------------------------------

    @Test
    fun `a real Bulwark script reads as its rules`() {
        val script = scriptOf(bulwark)
        assertTrue(script.builderMade)
        assertEquals(1, script.rules.size)
        val rule = script.rules.single()
        assertEquals("DMARC", rule.name)
        assertEquals(listOf(Test(Field.FROM, Match.CONTAINS, "dmarc@example.org")), rule.tests)
        assertEquals(listOf(Act.FileInto("Deleted Items"), Act.MarkRead), rule.acts)
        assertTrue(rule.understood)
    }

    /*
     * The hand written probe rules under Bulwark's own output. Losing these would stop the
     * delivery monitoring on a live mail server, silently, the first time somebody saved a
     * filter from Rampart.
     */
    @Test
    fun `what is not ours is kept`() {
        val script = scriptOf(bulwark)
        assertContains(script.tail, "zz-canary@*")
        assertContains(script.tail, "discard;")
    }

    @Test
    fun `and it is still there after we write the script back`() {
        val written = sieveOf(scriptOf(bulwark))
        assertContains(written, "zz-canary@*")
        assertContains(written, "External rules")
    }

    @Test
    fun `a script we wrote reads back as the same rules`() {
        val script = Script(
            listOf(
                filing,
                Rule(
                    id = "r2",
                    name = "Newsletters",
                    tests = listOf(
                        Test(Field.SUBJECT, Match.CONTAINS, "newsletter"),
                        Test(Field.BODY, Match.CONTAINS, "unsubscribe"),
                    ),
                    acts = listOf(Act.FileInto("Reading"), Act.MarkRead),
                    all = false,
                    stop = true,
                ),
                Rule(id = "r3", name = "Starred", tests = listOf(Test(Field.TO, Match.IS, "me@example.org")), acts = listOf(Act.Star)),
            ),
        )
        val read = scriptOf(sieveOf(script))
        assertEquals(script.rules.map { it.copy(raw = null) }, read.rules.map { it.copy(raw = null) })
    }

    @Test
    fun `patterns survive the round trip`() {
        val rules = listOf(
            filing.copy(tests = listOf(Test(Field.SUBJECT, Match.STARTS, "Invoice"))),
            filing.copy(id = "r2", name = "Late", tests = listOf(Test(Field.SUBJECT, Match.ENDS, "overdue"))),
        )
        assertEquals(
            rules.map { it.tests },
            scriptOf(sieveOf(Script(rules))).rules.map { it.tests },
        )
    }

    // --- not losing things ------------------------------------------------------------

    /* No metadata means nobody's builder wrote it, so we claim none of it. */
    @Test
    fun `a hand written script is left entirely alone`() {
        val hand = """
            require ["fileinto"];
            if header :contains "list-id" "announce.example.org" { fileinto "Lists"; }
        """.trimIndent()
        val script = scriptOf(hand)
        assertFalse(script.builderMade)
        assertFalse(script.editable)
        assertTrue(script.rules.isEmpty())
        assertEquals(hand, script.tail)
    }

    @Test
    fun `mangled metadata is treated as no metadata rather than as no rules`() {
        val broken = "/* @metadata:begin\n{not json\n@metadata:end */\n\nrequire [\"fileinto\"];\n"
        val script = scriptOf(broken)
        assertFalse(script.builderMade)
        assertEquals(broken, script.tail)
    }

    /*
     * A rule using something this build does not show is kept exactly as it arrived and
     * marked not understood, rather than re-encoded as the nearest thing we do know.
     */
    @Test
    fun `a rule with an action we do not offer is kept and flagged`() {
        val meta = """{"version":1,"rules":[{"id":"x","name":"Forwarder","enabled":true,"matchType":"all",""" +
            """"conditions":[{"field":"from","comparator":"contains","value":"a.test"}],""" +
            """"actions":[{"type":"redirect","value":"someone@else.test"}],"stopProcessing":false}]}"""
        val script = scriptOf("/* @metadata:begin\n$meta\n@metadata:end */\n")
        val rule = script.rules.single()
        assertFalse(rule.understood)
        assertContains(sieveOf(script), "redirect")
    }

    @Test
    fun `a condition on a header we do not offer is kept and flagged`() {
        val meta = """{"version":1,"rules":[{"id":"x","name":"List","enabled":true,"matchType":"all",""" +
            """"conditions":[{"field":"list_id","comparator":"contains","value":"announce"}],""" +
            """"actions":[{"type":"move","value":"Lists"}],"stopProcessing":false}]}"""
        val rule = scriptOf("/* @metadata:begin\n$meta\n@metadata:end */\n").rules.single()
        assertFalse(rule.understood)
        assertContains(rule.raw.toString(), "list_id")
    }

    // --- generating -------------------------------------------------------------------

    @Test
    fun `require names only what is used`() {
        assertContains(sieveOf(Script(listOf(filing))), """require ["fileinto"];""")
        assertContains(sieveOf(Script(listOf(filing.copy(acts = listOf(Act.Star))))), """require ["imap4flags"];""")
    }

    @Test
    fun `several tests join with allof or anyof`() {
        val two = filing.copy(tests = filing.tests + Test(Field.SUBJECT, Match.CONTAINS, "invoice"))
        assertContains(sieveOf(Script(listOf(two))), "allof(")
        assertContains(sieveOf(Script(listOf(two.copy(all = false)))), "anyof(")
    }

    /* Turning a rule off must not be the same as deleting it. */
    @Test
    fun `a disabled rule keeps its metadata and generates nothing`() {
        val written = sieveOf(Script(listOf(filing.copy(enabled = false))))
        assertContains(written, "\"Invoices\"")
        assertFalse(written.contains("# Rule: Invoices"), written)
        assertEquals(1, scriptOf(written).rules.size)
        assertFalse(scriptOf(written).rules.single().enabled)
    }

    /* Without escaping, a filter looking for a literal asterisk would match every message. */
    @Test
    fun `a typed wildcard is escaped, not honoured`() {
        val star = filing.copy(tests = listOf(Test(Field.SUBJECT, Match.STARTS, "50*")))
        assertContains(sieveOf(Script(listOf(star))), """header :matches "Subject" "50\\**"""")
    }

    @Test
    fun `a quote in a value cannot end the string`() {
        assertEquals("""  "say \"hi\""  """.trim(), sieveQuote("""say "hi""""))
        assertEquals("""  "back\\slash"  """.trim(), sieveQuote("""back\slash"""))
    }

    @Test
    fun `an empty script is still readable by a builder`() {
        val written = sieveOf(Script(emptyList()))
        assertTrue(scriptOf(written).builderMade)
        assertTrue(scriptOf(written).rules.isEmpty())
    }
}
