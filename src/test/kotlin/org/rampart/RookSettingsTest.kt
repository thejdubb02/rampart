package org.rampart

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The settings map: that every setting can be found, that what it takes is checked before
 * anything is shown, and that the server half is no wider than the admin console.
 */
class RookSettingsTest {
    @BeforeTest
    fun useAScratchDirectory() {
        if (System.getProperty("rampart.config.dir").isNullOrBlank()) {
            val dir = java.nio.file.Files.createTempDirectory("rampart-rook-settings-test")
            dir.toFile().deleteOnExit()
            System.setProperty("rampart.config.dir", dir.toString())
        }
    }

    private val choices = ComputerChoices(
        themes = listOf(SettingOption("rampart-light", "Rampart light"), SettingOption("rampart-dark", "Rampart dark"), SettingOption("nord", "Nord")),
        darkThemes = setOf("rampart-dark", "nord"),
        iconPacks = listOf(SettingOption("line", "Line"), SettingOption("heavy", "Heavy")),
        loaders = listOf(SettingOption("RING", "Ring"), SettingOption("WAVE", "Wave")),
    )

    private val schema = AdminSchema.parse(
        javaClass.getResourceAsStream("/stalwart-schema-0.16.json")?.readBytes()?.decodeToString()
            ?: error("fixture missing"),
    )

    private class FakeReach(
        override val schema: AdminSchema,
        override val access: AdminAccess,
        val stored: MutableMap<String, JsonObject> = mutableMapOf(),
    ) : AdminReach {
        val saved = mutableListOf<Pair<String, JsonObject>>()
        override fun rows(obj: AdminObject, properties: List<String>): List<JsonObject> =
            stored.map { (id, o) -> JsonObject(o + ("id" to JsonPrimitive(id))) }
        override fun one(obj: AdminObject, id: String): JsonObject = stored[id] ?: throw AdminError("The server no longer has that domain.")
        override fun save(obj: AdminObject, id: String, changes: JsonObject): AdminSaved {
            saved += id to changes
            stored[id] = JsonObject(stored.getValue(id) + changes)
            return AdminSaved(id, null, emptyMap())
        }
    }

    private val everything = AdminAccess(setOf("sysDomainQuery", "sysDomainGet", "sysDomainUpdate", "sysAccountQuery", "sysAccountGet"), "community")

    @Test
    fun `every Rampart setting has one id and a place in the app`() {
        val entries = ComputerPlace(choices).entries()
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertTrue(entries.all { it.id.startsWith("rampart.") && it.page.startsWith("Settings, ") && it.description.isNotBlank() })
        assertTrue(entries.all { it.home == SettingHome.COMPUTER })
    }

    @Test
    fun `dark mode finds the theme first`() {
        val map = SettingsMap(listOf(ComputerPlace(choices), MailboxPlace(null), ServerPlace(null)))
        assertEquals("rampart.theme", map.search("turn on dark mode").first().first.id)
        assertEquals("rampart.theme", map.search("dark mode").first().first.id)
    }

    @Test
    fun `a question about the away reply finds the away reply`() {
        val map = SettingsMap(listOf(ComputerPlace(choices), MailboxPlace(FakeMailbox()), ServerPlace(null)))
        val ids = map.search("away reply until").map { it.first.id }
        assertEquals("mailbox.away.until", ids.first())
    }

    @Test
    fun `a spam threshold question without an admin sign-in says how to get one`() {
        val map = SettingsMap(listOf(ComputerPlace(choices), MailboxPlace(null), ServerPlace(null)))
        val (entry, place) = map.search("spam threshold").first()
        assertEquals("server", entry.id)
        assertContains(place.refusal(entry).orEmpty(), "Server admin")
    }

    @Test
    fun `a Rampart setting is written through its accessor and read back`() {
        val place = ComputerPlace(choices)
        val theme = place.resolve("rampart.theme")!!
        assertNull(place.apply(listOf(theme to JsonPrimitive("nord"))))
        assertEquals("nord", Settings.theme())
        val delay = place.resolve("rampart.markReadDelay")!!
        assertNull(place.apply(listOf(delay to JsonPrimitive("5000"))))
        assertEquals(5000L, Settings.markReadDelay())
        assertTrue(sameSetting(place.current(delay), JsonPrimitive("5000")))
    }

    @Test
    fun `settings that decide where data goes are read only to Rook`() {
        val place = ComputerPlace(choices)
        listOf("rampart.trackingServer", "rampart.diagnosticsServer", "rampart.assistant", "rampart.adminSignIn").forEach {
            assertNotNull(place.refusal(place.resolve(it)!!), it)
        }
        assertNull(place.refusal(place.resolve("rampart.theme")!!))
    }

    @Test
    fun `a choice is taken by value or by label and nothing else`() {
        val kind = SettingKind.OneOf(choices.themes)
        assertEquals(JsonPrimitive("rampart-dark"), (checkValue(kind, JsonPrimitive("Rampart dark")) as Checked.Ok).value)
        assertEquals(JsonPrimitive("nord"), (checkValue(kind, JsonPrimitive("nord")) as Checked.Ok).value)
        assertIs<Checked.Bad>(checkValue(kind, JsonPrimitive("solarized-evil")))
        assertIs<Checked.Bad>(checkValue(kind, null))
        val delay = SettingKind.OneOf(listOf(SettingOption("0", "At once"), SettingOption("5000", "After 5 seconds")))
        assertEquals(JsonPrimitive("5000"), (checkValue(delay, JsonPrimitive(5000)) as Checked.Ok).value)
    }

    @Test
    fun `on and off accepts the obvious words and refuses the rest`() {
        assertEquals(JsonPrimitive(true), (checkValue(SettingKind.OnOff, JsonPrimitive("on")) as Checked.Ok).value)
        assertEquals(JsonPrimitive(false), (checkValue(SettingKind.OnOff, JsonPrimitive(false)) as Checked.Ok).value)
        assertIs<Checked.Bad>(checkValue(SettingKind.OnOff, JsonPrimitive("maybe")))
    }

    @Test
    fun `text is bounded and a single line stays single`() {
        assertIs<Checked.Bad>(checkValue(SettingKind.Words(5), JsonPrimitive("far too long")))
        assertIs<Checked.Bad>(checkValue(SettingKind.Words(50), JsonPrimitive("two\nlines")))
        assertIs<Checked.Ok>(checkValue(SettingKind.Words(50, multiline = true), JsonPrimitive("two\nlines")))
        assertIs<Checked.Bad>(checkValue(SettingKind.Words(50), JsonPrimitive("bell\u0007")))
    }

    @Test
    fun `a day is a real day or none`() {
        assertEquals(JsonPrimitive("2026-10-03"), (checkValue(SettingKind.Day, JsonPrimitive("2026-10-03")) as Checked.Ok).value)
        assertEquals(JsonNull, (checkValue(SettingKind.Day, JsonPrimitive("none")) as Checked.Ok).value)
        assertIs<Checked.Bad>(checkValue(SettingKind.Day, JsonPrimitive("2026-02-30")))
        assertIs<Checked.Bad>(checkValue(SettingKind.Day, JsonPrimitive("next Friday")))
    }

    @Test
    fun `every field the server describes is in the map`() {
        val place = ServerPlace(FakeReach(schema, everything))
        val ids = place.entries().map { it.id }.toSet()
        assertTrue("server.Domain.catchAllAddress" in ids)
        assertTrue("server.Domain.isEnabled" in ids)
        // Fields from both kinds of account, each once.
        assertTrue("server.Account.memberGroupIds" in ids)
        assertTrue("server.Account.emailAddress" in ids)
        assertEquals(ids.size, place.entries().size)
    }

    @Test
    fun `a server field is changed per domain, and only one the console could change`() {
        val reach = FakeReach(schema, everything, mutableMapOf("b" to JsonObject(mapOf("name" to JsonPrimitive("example.org")))))
        val place = ServerPlace(reach)
        val generic = place.resolve("server.Domain.catchAllAddress")!!
        assertContains(place.refusal(generic).orEmpty(), "per domain")
        assertContains(place.more(generic).orEmpty(), "server.Domain.catchAllAddress@b")
        val one = place.resolve("server.Domain.catchAllAddress@b")!!
        assertNull(place.refusal(one))
        assertNull(place.resolve("server.Domain.catchAllAddress@../../x"))
        // The server sets its own creation date.
        assertNotNull(place.refusal(place.resolve("server.Domain.createdAt@b")!!))
        // Accounts can be read with this sign-in but not updated.
        assertContains(place.refusal(place.resolve("server.Account.emailAddress@a")!!).orEmpty(), "not allowed")
    }

    @Test
    fun `a secret on the server is never taken from Rook`() {
        // The fixture keeps its secrets inside nested credentials, which Rook cannot edit at
        // all, so the field is made here the way the schema would describe a top-level one.
        val secret = SettingKind.Server(AdminType.Text("secret", nullable = false), emptyList())
        assertIs<Checked.Bad>(checkValue(secret, JsonPrimitive("hunter2")))
        val place = ServerPlace(FakeReach(schema, everything))
        val credentials = place.resolve("server.Account.credentials")!!
        assertIs<Checked.Bad>(checkValue(credentials.kind, JsonPrimitive("hunter2")))
    }

    @Test
    fun `a server value is checked the way the console checks it`() {
        val place = ServerPlace(FakeReach(schema, everything))
        val catchAll = place.resolve("server.Domain.catchAllAddress@b")!!
        assertIs<Checked.Bad>(checkValue(catchAll.kind, JsonPrimitive("not an address")))
        assertIs<Checked.Ok>(checkValue(catchAll.kind, JsonPrimitive("postmaster@example.org")))
    }

    @Test
    fun `an object outside the console's list is described and never read`() {
        // A made-up object, standing in for any of the many the console does not manage.
        val extra = AdminSchema.parse(
            """
            {"objects":{"x:SpamFilter":{"type":"object","description":"Spam filtering.","permissionPrefix":"sysSpam"}},
             "schemas":{"x:SpamFilter":{"type":"single","schemaName":"x:SpamFilter"}},
             "fields":{"x:SpamFilter":{"properties":{"threshold":{"description":"Score above which mail is spam","type":{"type":"number","format":"float"},"update":"mutable"}}}},
             "forms":{},"lists":{},"enums":{},"layouts":[]}
            """.trimIndent(),
        )
        val reach = FakeReach(extra, AdminAccess(setOf("sysSpamGet", "sysSpamQuery", "sysSpamUpdate"), "community"))
        val map = SettingsMap(listOf(ServerPlace(reach)))
        val (entry, place) = map.search("spam threshold").first()
        assertEquals("server.SpamFilter.threshold", entry.id)
        assertContains(entry.description, "Score above which")
        assertContains(place.refusal(entry).orEmpty(), "web console")
        val trouble = runCatching { place.current(place.resolve("server.SpamFilter.threshold@x")!!) }.exceptionOrNull()
        assertIs<SettingTrouble>(trouble)
        assertNull(place.apply(emptyList()))
        assertNotNull(place.apply(listOf(place.resolve("server.SpamFilter.threshold@x")!! to JsonPrimitive(5.0))))
        assertTrue(reach.saved.isEmpty())
    }

    @Test
    fun `a server change sends only the field that changed`() {
        val reach = FakeReach(
            schema, everything,
            mutableMapOf("b" to JsonObject(mapOf("name" to JsonPrimitive("example.org"), "isEnabled" to JsonPrimitive(true)))),
        )
        val place = ServerPlace(reach)
        val entry = place.resolve("server.Domain.catchAllAddress@b")!!
        assertNull(place.apply(listOf(entry to JsonPrimitive("postmaster@example.org"))))
        assertEquals(listOf("catchAllAddress"), reach.saved.single().second.keys.toList())
    }

    @Test
    fun `a signature is written as plain text in both halves`() {
        assertEquals("Jo &amp; Co<br>&lt;b&gt;", signatureHtml("Jo & Co\n<b>"))
        assertEquals("", signatureHtml("  "))
    }

    @Test
    fun `an away reply's days become midnight`() {
        val place = MailboxPlace(FakeMailbox())
        val until = place.resolve("mailbox.away.until")!!
        val on = place.resolve("mailbox.away.enabled")!!
        val v = withAway(Vacation(text = "Back soon"), listOf(on to JsonPrimitive(true), until to JsonPrimitive("2026-10-03")))
        assertEquals("2026-10-03T00:00:00Z", v.to)
        assertTrue(v.enabled)
        assertNull(withAway(v, listOf(until to JsonNull)).to)
    }
}

/** A mailbox with an away reply and one identity, in memory. */
internal class FakeMailbox(
    var away: Vacation? = Vacation(),
    var ids: List<Identity> = listOf(Identity("i1", "Jo", "jo@example.org", "Jo")),
) : MailboxAccess {
    val writes = mutableListOf<String>()
    override fun vacation(): Vacation? = away
    override fun setVacation(value: Vacation) { writes += "vacation"; away = value }
    override fun identities(): List<Identity> = ids
    override fun setSignature(identityId: String, text: String, html: String) {
        writes += "signature"
        ids = ids.map { if (it.id == identityId) it.copy(textSignature = text, htmlSignature = html) else it }
    }
    override fun filters(): String = "No filters."
    override fun security(): String = "Two-step sign-in is off."
    override fun phone(): String = "Available."
}
