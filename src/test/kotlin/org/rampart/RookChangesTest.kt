package org.rampart

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Confirm before change. The model can put a card on screen and nothing else: these are the
 * rules that make that true, tested without a window or a server.
 */
class RookChangesTest {
    /** Settings in memory, recording every write, so a test can say nothing was written. */
    private class Memory(vararg start: Pair<String, JsonElement>) : SettingPlace {
        val values = mutableMapOf(*start)
        val writes = mutableListOf<Pair<String, JsonElement>>()
        private val list = listOf(
            SettingEntry("t.theme", "Theme", "Colours.", SettingKind.OneOf(listOf(SettingOption("light", "Light"), SettingOption("dark", "Dark"))), SettingHome.COMPUTER, "Settings, Themes", "dark mode"),
            SettingEntry("t.notify", "Notify", "Popups.", SettingKind.OnOff, SettingHome.COMPUTER, "Settings, Notifications"),
            SettingEntry("t.read", "Read only", "Nope.", SettingKind.Words(20), SettingHome.COMPUTER, "Settings"),
        )
        override fun entries() = list
        override fun refusal(entry: SettingEntry) = if (entry.id == "t.read") "It is read only." else null
        override fun current(entry: SettingEntry): JsonElement = values[entry.id] ?: JsonPrimitive("")
        override fun cheap(entry: SettingEntry) = true
        override fun group(entry: SettingEntry) = "memory"
        override fun title(group: String) = "Memory"
        override fun apply(changes: List<Pair<SettingEntry, JsonElement>>): String? {
            changes.forEach { (e, v) -> writes += e.id to v; values[e.id] = v }
            return null
        }
    }

    private fun asked(json: String): Asked = Chat.asked(json) ?: error("not a tool call: $json")

    private fun setup(): Triple<Memory, SettingsMap, SettingsTools> {
        val memory = Memory("t.theme" to JsonPrimitive("light"), "t.notify" to JsonPrimitive(true))
        val map = SettingsMap(listOf(memory))
        return Triple(memory, map, SettingsTools(map, firstNumber = 1))
    }

    @Test
    fun `asking for a change makes a card and writes nothing`() {
        val (memory, _, tools) = setup()
        val answer = tools.run(asked("""{"tool":"change_setting","args":{"id":"t.theme","value":"dark"}}""")).orEmpty()
        assertContains(answer, "Card 1")
        assertContains(answer, "Nothing has changed yet")
        assertTrue(memory.writes.isEmpty())
        val card = tools.drafted.single()
        assertEquals("Light", card.lines.single().beforeWords)
        assertEquals("Dark", card.lines.single().afterWords)
    }

    @Test
    fun `a value the setting does not take makes no card`() {
        val (memory, _, tools) = setup()
        val answer = tools.run(asked("""{"tool":"change_setting","args":{"id":"t.theme","value":"ultraviolet"}}""")).orEmpty()
        assertContains(answer, "No card was made")
        assertTrue(tools.drafted.isEmpty())
        assertTrue(memory.writes.isEmpty())
    }

    @Test
    fun `one bad value in a batch makes no card for the good ones`() {
        val (_, _, tools) = setup()
        val answer = tools.run(
            asked("""{"tool":"change_setting","args":{"changes":[{"id":"t.theme","value":"dark"},{"id":"t.notify","value":"sometimes"}]}}"""),
        ).orEmpty()
        assertContains(answer, "No card was made")
        assertTrue(tools.drafted.isEmpty())
    }

    @Test
    fun `a read only setting and an unknown id make no card`() {
        val (_, _, tools) = setup()
        assertContains(tools.run(asked("""{"tool":"change_setting","args":{"id":"t.read","value":"x"}}""")).orEmpty(), "read only")
        assertContains(tools.run(asked("""{"tool":"change_setting","args":{"id":"t.nothing","value":"x"}}""")).orEmpty(), "no setting called")
        assertTrue(tools.drafted.isEmpty())
    }

    @Test
    fun `a change to what it already is makes no card`() {
        val (_, _, tools) = setup()
        assertContains(tools.run(asked("""{"tool":"change_setting","args":{"id":"t.notify","value":true}}""")).orEmpty(), "already")
        assertTrue(tools.drafted.isEmpty())
    }

    @Test
    fun `confirming is the only thing that writes, and only once`() {
        val (memory, map, tools) = setup()
        tools.run(asked("""{"tool":"change_setting","args":{"id":"t.theme","value":"dark"}}"""))
        var desk = ChangeDesk().add(tools.drafted)
        assertEquals(CardStatus.WAITING, desk.card(1)?.status)
        assertTrue(memory.writes.isEmpty())

        desk = desk.start(1)
        assertEquals(CardStatus.APPLYING, desk.card(1)?.status)
        // A second press while the first is being written does nothing.
        assertEquals(desk, desk.start(1))
        val outcome = applyCard(desk.card(1)!!, map)
        assertNull(outcome)
        desk = desk.finish(1, outcome)
        assertEquals(CardStatus.DONE, desk.card(1)?.status)
        assertEquals(listOf<Pair<String, JsonElement>>("t.theme" to JsonPrimitive("dark")), memory.writes)
        // And a card already done cannot be started again.
        assertEquals(desk, desk.start(1))
    }

    @Test
    fun `a dismissed card cannot be confirmed afterwards`() {
        val (_, _, tools) = setup()
        tools.run(asked("""{"tool":"change_setting","args":{"id":"t.theme","value":"dark"}}"""))
        val desk = ChangeDesk().add(tools.drafted).dismiss(1)
        assertEquals(CardStatus.DISMISSED, desk.card(1)?.status)
        assertEquals(desk, desk.start(1))
    }

    @Test
    fun `a setting that changed since the card was made is left alone`() {
        val (memory, map, tools) = setup()
        tools.run(asked("""{"tool":"change_setting","args":{"id":"t.theme","value":"dark"}}"""))
        val card = tools.drafted.single()
        memory.values["t.theme"] = JsonPrimitive("dark")
        val outcome = applyCard(card, map)
        assertNotNull(outcome)
        assertContains(outcome, "has changed since")
        assertTrue(memory.writes.isEmpty())
    }

    @Test
    fun `a card whose value was tampered with is checked again before writing`() {
        val (memory, map, tools) = setup()
        tools.run(asked("""{"tool":"change_setting","args":{"id":"t.theme","value":"dark"}}"""))
        val card = tools.drafted.single()
        val forged = card.copy(lines = card.lines.map { it.copy(after = JsonPrimitive("ultraviolet")) })
        assertNotNull(applyCard(forged, map))
        assertTrue(memory.writes.isEmpty())
    }

    @Test
    fun `only so many cards wait at once`() {
        val memory = Memory("t.theme" to JsonPrimitive("light"), "t.notify" to JsonPrimitive(true))
        val tools = SettingsTools(SettingsMap(listOf(memory)), firstNumber = 5, alreadyWaiting = MOST_WAITING)
        assertContains(tools.run(asked("""{"tool":"change_setting","args":{"id":"t.theme","value":"dark"}}""")).orEmpty(), "already")
        assertTrue(tools.drafted.isEmpty())
    }

    @Test
    fun `card numbers carry on from the desk`() {
        val (_, map, _) = setup()
        val desk = ChangeDesk(listOf(ChangeCard(3, "memory", SettingHome.COMPUTER, "Memory", emptyList(), CardStatus.DONE)))
        val tools = SettingsTools(map, desk.nextNumber)
        assertContains(tools.run(asked("""{"tool":"change_setting","args":{"id":"t.theme","value":"dark"}}""")).orEmpty(), "Card 4")
        assertEquals(listOf(3, 4), desk.add(tools.drafted).cards.map { it.number })
    }

    @Test
    fun `clearing keeps only a card still being written`() {
        val a = ChangeCard(1, "g", SettingHome.COMPUTER, "t", emptyList(), CardStatus.WAITING)
        val b = ChangeCard(2, "g", SettingHome.COMPUTER, "t", emptyList(), CardStatus.APPLYING)
        assertEquals(listOf(2), ChangeDesk(listOf(a, b)).clear().cards.map { it.number })
    }

    @Test
    fun `list and get answer in words and say what is read only`() {
        val (_, _, tools) = setup()
        val list = tools.run(asked("""{"tool":"list_settings","args":{"search":"dark mode"}}""")).orEmpty()
        assertContains(list, "t.theme  Theme: Light")
        val get = tools.run(asked("""{"tool":"get_setting","args":{"id":"t.read"}}""")).orEmpty()
        assertContains(get, "Rook cannot change this: It is read only.")
        assertNull(tools.run(asked("""{"tool":"archive","args":{"ids":["1"]}}""")))
    }

    @Test
    fun `an away reply with no message is refused before a card is made`() {
        val mailbox = FakeMailbox(away = Vacation(text = ""))
        val tools = SettingsTools(SettingsMap(listOf(MailboxPlace(mailbox))), 1)
        val answer = tools.run(
            asked("""{"tool":"change_setting","args":{"changes":[{"id":"mailbox.away.enabled","value":true},{"id":"mailbox.away.until","value":"2026-10-03"}]}}"""),
        ).orEmpty()
        assertContains(answer, "empty email")
        assertTrue(tools.drafted.isEmpty())
        assertTrue(mailbox.writes.isEmpty())
    }

    @Test
    fun `an away reply is one card, written in one go after Confirm`() {
        val mailbox = FakeMailbox(away = Vacation(text = "Back Monday."))
        val map = SettingsMap(listOf(MailboxPlace(mailbox)))
        val tools = SettingsTools(map, 1)
        tools.run(
            asked("""{"tool":"change_setting","args":{"changes":[{"id":"mailbox.away.enabled","value":true},{"id":"mailbox.away.until","value":"2026-10-03"}]}}"""),
        )
        val card = tools.drafted.single()
        assertEquals(2, card.lines.size)
        assertTrue(mailbox.writes.isEmpty())
        assertNull(applyCard(card, map))
        assertEquals(listOf("vacation"), mailbox.writes)
        assertEquals("2026-10-03T00:00:00Z", mailbox.away?.to)
        assertTrue(mailbox.away?.enabled == true)
    }

    @Test
    fun `the prompt names the tools, the day and the rule about confirming`() {
        val prompt = settingsPrompt(java.time.LocalDate.of(2026, 9, 28))
        assertContains(prompt, "Monday 2026-09-28")
        assertContains(prompt, "list_settings")
        assertContains(prompt, "change_setting")
        assertContains(prompt, "cannot press it")
        listOf('\u2014', '\u2013', '\u2026').forEach { assertTrue(it !in prompt) }
    }

    /** Finished cards past the newest few leave. Waiting and applying stay, and numbers do not rewind. */
    @Test
    fun `old settled cards leave and the next number keeps climbing`() {
        fun card(n: Int, status: CardStatus) =
            ChangeCard(n, "g", SettingHome.COMPUTER, "t", emptyList(), status)
        val desk = ChangeDesk(listOf(
            card(9, CardStatus.DONE),
            card(1, CardStatus.FAILED),
            card(2, CardStatus.DISMISSED),
            card(3, CardStatus.DONE),
            card(4, CardStatus.WAITING),
            card(5, CardStatus.APPLYING),
        ))
        val trimmed = desk.settle()
        assertEquals(listOf(1, 2, 3, 4, 5), trimmed.cards.map { it.number })
        assertEquals(CardStatus.WAITING, trimmed.card(4)?.status)
        assertEquals(CardStatus.APPLYING, trimmed.card(5)?.status)
        assertEquals(KEPT_SETTLED, trimmed.cards.count { it.status.settled })
        assertEquals(10, trimmed.nextNumber)

        val added = desk.add(listOf(card(desk.nextNumber, CardStatus.DONE)))
        assertEquals(listOf(1, 2, 3, 4, 5, 10), added.cards.map { it.number })
        assertEquals(CardStatus.WAITING, added.card(4)?.status)
        assertEquals(CardStatus.APPLYING, added.card(5)?.status)
        assertEquals(CardStatus.WAITING, added.card(10)?.status)
        assertEquals(KEPT_SETTLED, added.cards.count { it.status.settled })
        assertEquals(11, added.nextNumber)
    }
}
