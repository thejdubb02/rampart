package org.rampart

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FiltersTest {
    private val everywhere = Rule(
        id = "g1",
        name = "Receipts",
        tests = listOf(Test(Field.SUBJECT, Match.CONTAINS, "receipt")),
        acts = listOf(Act.FileInto("Receipts")),
        global = true,
    )

    private val mine = Rule(
        id = "a1",
        name = "Newsletter",
        tests = listOf(Test(Field.FROM, Match.CONTAINS, "news.test")),
        acts = listOf(Act.FileInto("Reading")),
    )

    @Test
    fun `the global rules run before the account's own`() {
        val merged = scriptFor(Script(listOf(mine)), listOf(everywhere))
        assertEquals(listOf("Receipts", "Newsletter"), merged.rules.map { it.name })
    }

    /*
     * The one that makes removing a global rule work at all. A merge would leave last
     * week's rule on the server for ever, because nothing else ever deletes it.
     */
    @Test
    fun `saving again replaces the global set rather than adding to it`() {
        val once = scriptFor(Script(listOf(mine)), listOf(everywhere))
        val twice = scriptFor(once, listOf(everywhere.copy(id = "g2", name = "Invoices")))
        assertEquals(listOf("Invoices", "Newsletter"), twice.rules.map { it.name })
    }

    @Test
    fun `an account the set is off for keeps only its own rules`() {
        val settings = GlobalFilters(listOf(everywhere), exceptions = setOf("b@x.test"))
        assertFalse(settings.appliesTo("b@x.test"))
        val merged = scriptFor(Script(listOf(mine)), settings.forAccount("b@x.test"))
        assertEquals(listOf("Newsletter"), merged.rules.map { it.name })
    }

    /** An account signed in after the set was made gets it, without anybody opting it in. */
    @Test
    fun `an account nobody has said anything about gets the set`() {
        assertTrue(GlobalFilters(listOf(everywhere)).appliesTo("new@x.test"))
    }

    /*
     * Without the mark in the metadata the set is unremovable: read back, every pushed
     * rule looks like a rule that account made for itself, so the next save keeps it and
     * adds the global copy beside it.
     */
    @Test
    fun `a global rule is still global after a trip through the server`() {
        val written = sieveOf(scriptFor(Script(listOf(mine)), listOf(everywhere)))
        val read = scriptOf(written)
        assertEquals(listOf("Receipts"), read.rules.filter { it.global }.map { it.name })
        assertEquals(listOf("Newsletter"), ownRules(read).map { it.name })
    }

    /*
     * The metadata is what every client reads; the Sieve underneath it is generated. A
     * rule written back as it arrived means an edit shows once and is gone on the next
     * open, which reads as the save having failed silently.
     */
    @Test
    fun `editing a rule that came off the server reaches the metadata`() {
        val first = scriptOf(sieveOf(Script(listOf(mine))))
        val edited = first.rules.single().copy(name = "Reading list")
        val again = scriptOf(sieveOf(first.copy(rules = listOf(edited))))
        assertEquals("Reading list", again.rules.single().name)
    }

    /*
     * A mailbox can hold several scripts and only one of them filters anything. Editing an
     * inactive one is a save that appears to work and changes nothing about the mail.
     */
    @Test
    fun `the script we read and write is the one the server is running`() {
        val scripts = listOf(
            Jmap.SieveInfo("1", "rampart", active = false, blobId = "b1"),
            Jmap.SieveInfo("2", "roundcube", active = true, blobId = "b2"),
        )
        assertEquals("roundcube", theOneRunning(scripts)?.name)
        assertEquals("rampart", theOneRunning(scripts.map { it.copy(active = false) })?.name)
        assertEquals(null, theOneRunning(emptyList()))
    }

    /** A rule this build does not understand is still written back exactly as it came. */
    @Test
    fun `a rule we do not understand is not rebuilt`() {
        val odd = """
            /* @metadata:begin
            {"version":1,"rules":[{"id":"x","name":"Lists","conditions":[{"field":"list_id",
            "comparator":"contains","value":"news"}],"actions":[{"type":"move","value":"Lists"}]}]}
            @metadata:end */
        """.trimIndent()
        val script = scriptOf(odd)
        assertFalse(script.rules.single().understood)
        assertContains(sieveOf(script), "list_id")
    }
}
