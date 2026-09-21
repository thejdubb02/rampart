package org.rampart

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The assistant's settings, its consent and its running total.
 *
 * Every test here is about the same promise: nothing happens that nobody asked for, and
 * what it costs is knowable before the bill is. The ceiling in particular is a ceiling,
 * not a warning, so it is checked from both sides.
 */
class AssistantTest {
    private val previous = System.getProperty("rampart.config.dir")
    private lateinit var dir: java.nio.file.Path

    @BeforeTest
    fun isolate() {
        dir = Files.createTempDirectory("rampart-assistant-")
        System.setProperty("rampart.config.dir", dir.toString())
    }

    @AfterTest
    fun restore() {
        if (previous == null) System.clearProperty("rampart.config.dir")
        else System.setProperty("rampart.config.dir", previous)
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a fresh install has the assistant switched off`() {
        assertEquals(AssistantMode.OFF, Assistant.config().mode)
        assertFalse(Assistant.agreed(Assistant.SUMMARISE))
        assertEquals(0.0, Assistant.spent())
        assertEquals("The assistant is switched off.", Assistant.whyNot(Assistant.SUMMARISE))
    }

    @Test
    fun `settings survive being written and read back`() {
        val want = AssistantConfig(
            mode = AssistantMode.LOCAL,
            baseUrl = "http://127.0.0.1:11434/v1",
            model = "llama3.2",
            dollarsIn = 0.0,
            dollarsOut = 0.0,
            ceiling = 0.0,
        )
        Assistant.setConfig(want)
        assertEquals(want, Assistant.config())
    }

    @Test
    fun `an unreadable file reads as off rather than throwing`() {
        Assistant.setConfig(AssistantConfig(mode = AssistantMode.LOCAL))
        Files.write(dir.resolve("assistant.json"), "{not json at all".toByteArray())
        assertEquals(AssistantMode.OFF, Assistant.config().mode)
    }

    @Test
    fun `a mode nobody has heard of is off, which is the safe direction`() {
        Files.write(dir.resolve("assistant.json"), """{"mode":"AUTOPILOT"}""".toByteArray())
        assertEquals(AssistantMode.OFF, Assistant.config().mode)
    }

    @Test
    fun `agreeing to one feature is not agreeing to another`() {
        Assistant.agree(Assistant.SUMMARISE)
        assertTrue(Assistant.agreed(Assistant.SUMMARISE))
        assertFalse(Assistant.agreed("triage"))
        Assistant.forgetAgreements()
        assertFalse(Assistant.agreed(Assistant.SUMMARISE))
    }

    @Test
    fun `what a call cost is kept per feature and adds up`() {
        val config = AssistantConfig(mode = AssistantMode.LOCAL, dollarsIn = 1.0, dollarsOut = 5.0, ceiling = 0.0)
        Assistant.setConfig(config)
        Assistant.record(Assistant.SUMMARISE, tokensIn = 1_000_000, tokensOut = 0, config = config)
        Assistant.record(Assistant.SUMMARISE, tokensIn = 0, tokensOut = 1_000_000, config = config)
        Assistant.record("triage", tokensIn = 500_000, tokensOut = 0, config = config)

        val summarise = Assistant.breakdown()[Assistant.SUMMARISE]
        assertNotNull(summarise)
        assertEquals(2, summarise.calls)
        assertEquals(1_000_000, summarise.tokensIn)
        assertEquals(1_000_000, summarise.tokensOut)
        assertEquals(6.0, summarise.dollars, 0.0001)
        assertEquals(6.5, Assistant.spent(), 0.0001)
    }

    @Test
    fun `the ceiling stops rather than warns, and stops before the call`() {
        val config = AssistantConfig(mode = AssistantMode.LOCAL, dollarsIn = 1.0, dollarsOut = 1.0, ceiling = 2.0)
        Assistant.setConfig(config)
        assertFalse(Assistant.blocked(config))

        Assistant.record(Assistant.SUMMARISE, tokensIn = 1_900_000, tokensOut = 0, config = config)
        assertFalse(Assistant.blocked(config), "under the ceiling is not blocked")

        Assistant.record(Assistant.SUMMARISE, tokensIn = 200_000, tokensOut = 0, config = config)
        assertTrue(Assistant.blocked(config), "at the ceiling it stops")
        assertNotNull(Assistant.whyNot(Assistant.SUMMARISE, config))
    }

    @Test
    fun `a ceiling of nothing means no ceiling, not no spending`() {
        val config = AssistantConfig(mode = AssistantMode.LOCAL, dollarsIn = 1.0, dollarsOut = 1.0, ceiling = 0.0)
        Assistant.setConfig(config)
        Assistant.record(Assistant.SUMMARISE, tokensIn = 50_000_000, tokensOut = 0, config = config)
        assertFalse(Assistant.blocked(config))
        assertNull(Assistant.whyNot(Assistant.SUMMARISE, config))
    }

    @Test
    fun `the ledger does not grow without end`() {
        val config = AssistantConfig(mode = AssistantMode.LOCAL, ceiling = 0.0)
        Assistant.setConfig(config)
        val old = java.time.YearMonth.now().minusMonths(18).toString()
        val file = dir.resolve("assistant.json")
        val before = Files.readString(file)
        Files.writeString(
            file,
            before.dropLast(1) + ""","ledger":{"$old":{"summarise":{"calls":1,"tokensIn":1,"tokensOut":1,"dollars":0.1}}}}""",
        )
        Assistant.record(Assistant.SUMMARISE, tokensIn = 1, tokensOut = 1, config = config)
        assertFalse(Files.readString(file).contains(old), "a month from a year and a half ago was kept")
    }

    @Test
    fun `this month is the month, spelled the way the ledger spells it`() {
        assertEquals(java.time.YearMonth.now().toString(), Assistant.thisMonth())
        assertEquals(7, Assistant.thisMonth().length)
    }

    @Test
    fun `a folder on the never list is refused before anything about the assistant itself`() {
        val config = AssistantConfig(mode = AssistantMode.LOCAL, ceiling = 0.0)
        Assistant.setConfig(config)
        assertTrue(Assistant.deniedFolders("me@example.com").isEmpty(), "empty by default")

        Assistant.setDeniedFolders("me@example.com", setOf("HR"))
        assertEquals(setOf("HR"), Assistant.deniedFolders("me@example.com"))

        // Refused even though the assistant is on and nothing else is wrong: the mode, the
        // key and the ceiling all come after this check, not before it.
        val why = Assistant.whyNot(Assistant.SUMMARISE, config, account = "me@example.com", folder = "HR")
        assertNotNull(why)
        assertTrue(why.contains("HR"), "the reason names the folder: $why")

        // A different folder on the same account, or the same folder on a different
        // account, is not touched by it.
        assertNull(Assistant.whyNot(Assistant.SUMMARISE, config, account = "me@example.com", folder = "Inbox"))
        assertNull(Assistant.whyNot(Assistant.SUMMARISE, config, account = "someone@else.com", folder = "HR"))

        // Turning the folder back off is turning it back off.
        Assistant.setDeniedFolders("me@example.com", emptySet())
        assertNull(Assistant.whyNot(Assistant.SUMMARISE, config, account = "me@example.com", folder = "HR"))
    }

    @Test
    fun `bringing your own key and not bringing one is said plainly`() {
        val config = AssistantConfig(mode = AssistantMode.BYOK, ceiling = 0.0)
        Assistant.setConfig(config)
        // No key has been stored in this test's world, so it must say so rather than
        // letting a call be attempted with nothing to authenticate with.
        val why = Assistant.whyNot(Assistant.SUMMARISE, config)
        assertNotNull(why)
        assertTrue(why.endsWith("."), "a reason shown to somebody is a sentence: $why")
    }
}
