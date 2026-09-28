package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * How admin values are shown, typed, checked and compared. The wire formats are Stalwart
 * 0.16's: milliseconds for a duration, bytes for a size, an object of member to true for a
 * set.
 */
class AdminValuesTest {
    private val schema = AdminSchema.parse(
        javaClass.getResourceAsStream("/stalwart-schema-0.16.json")?.readBytes()?.decodeToString()
            ?: error("fixture missing"),
    )

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun durationsReadBothWays() {
        assertEquals("1d 1h 1m 1s", formatDuration(90_061_000))
        assertEquals("250ms", formatDuration(250))
        assertEquals(5_400_000L, parseDuration("1h 30m"))
        assertEquals(5_400_000L, parseDuration("1h30m"))
        assertEquals(90_000L, parseDuration("90s"))
        assertEquals(250L, parseDuration("250ms"))
        assertEquals(172_800_000L, parseDuration(" 2D "))
    }

    @Test
    fun aBareNumberIsNotADuration() {
        // Seconds is what a person means and milliseconds is what is stored. No guessing.
        assertNull(parseDuration("30"))
        assertNull(parseDuration("5 minutes"))
        assertNull(parseDuration("1h x"))
        assertNull(parseDuration(""))
        assertNull(parseDuration("99999999999999999999d"))
    }

    @Test
    fun sizesReadBothWays() {
        assertEquals("50 MB", formatSize(50L shl 20))
        assertEquals("1.5 GB", formatSize(3L shl 29))
        assertEquals("900 bytes", formatSize(900))
        assertEquals(50L shl 20, parseSize("50 MB"))
        assertEquals(3L shl 29, parseSize("1.5GB"))
        assertEquals(512L shl 10, parseSize("512k"))
        assertEquals(1234L, parseSize("1234"))
        assertNull(parseSize("lots"))
    }

    @Test
    fun typedValuesAreCheckedTheWayTheServerWill() {
        val email = AdminType.Text("emailAddress", nullable = true)
        assertEquals(Parsed.Ok(JsonPrimitive("postmaster@example.com")), parseInput(email, " postmaster@example.com "))
        assertIs<Parsed.Bad>(parseInput(email, "postmaster"))
        assertEquals(Parsed.Ok(JsonNull), parseInput(email, ""))

        val required = AdminType.Text("string", nullable = false)
        assertIs<Parsed.Bad>(parseInput(required, "  "))

        val uri = AdminType.Text("uri", nullable = false)
        assertIs<Parsed.Ok>(parseInput(uri, "mailto:postmaster@example.com"))
        assertIs<Parsed.Bad>(parseInput(uri, "postmaster"))

        val short = AdminType.Text("string", nullable = false, maxLength = 3)
        assertIs<Parsed.Bad>(parseInput(short, "four"))
    }

    @Test
    fun numbersRespectTheirLimitsAndFormat() {
        val port = AdminType.Number("unsignedInteger", nullable = false, min = 1.0, max = 65535.0)
        assertEquals(Parsed.Ok(JsonPrimitive(25L)), parseInput(port, "25"))
        assertIs<Parsed.Bad>(parseInput(port, "0"))
        assertIs<Parsed.Bad>(parseInput(port, "70000"))
        assertIs<Parsed.Bad>(parseInput(port, "2.5"))

        val timeout = AdminType.Number("duration", nullable = false)
        assertEquals(Parsed.Ok(JsonPrimitive(300_000L)), parseInput(timeout, "5m"))
        assertIs<Parsed.Bad>(parseInput(timeout, "0s"))
        assertIs<Parsed.Bad>(parseInput(timeout, "5"))

        val quota = AdminType.Number("size", nullable = true)
        assertEquals(Parsed.Ok(JsonPrimitive(1L shl 30)), parseInput(quota, "1 GB"))

        val factor = AdminType.Number("float", nullable = false, min = 1.0)
        assertEquals(Parsed.Ok(JsonPrimitive(1.5)), parseInput(factor, "1.5"))
    }

    @Test
    fun ipAddressesAreCheckedWithoutALookup() {
        assertTrue(isIpLiteral("192.0.2.10"))
        assertTrue(isIpLiteral("2001:db8::1"))
        assertTrue(isIpLiteral("::ffff:192.0.2.1"))
        assertFalse(isIpLiteral("256.0.0.1"))
        assertFalse(isIpLiteral("example.com"))
        assertFalse(isIpLiteral("1::2::3"))
        assertTrue(isIpNetwork("192.0.2.0/24"))
        assertFalse(isIpNetwork("192.0.2.0/33"))
        assertTrue(isIpNetwork("2001:db8::/32"))
    }

    @Test
    fun aSecretIsNeverShownOrPutBackInABox() {
        val secret = AdminType.Text("secret", nullable = false)
        assertEquals("Set, hidden", displayValue(secret, JsonPrimitive("hunter2")))
        assertEquals("", editText(secret, JsonPrimitive("hunter2")))
    }

    @Test
    fun valuesReadAsWords() {
        assertEquals("Yes", displayValue(AdminType.Flag(false), JsonPrimitive(true)))
        assertEquals("Not set", displayValue(AdminType.Text("string", true), JsonNull))
        assertEquals("30s", displayValue(AdminType.Number("duration", false), JsonPrimitive(30_000)))
        val set = AdminType.Many(AdminType.Text("string", false), 0)
        assertEquals("a.example, b.example", displayValue(set, obj("""{"a.example":true,"b.example":true}""")))
        assertEquals("None", displayValue(set, obj("{}")))
        val list = AdminType.NestedList("x:Credential", 0)
        assertEquals("2 items", displayValue(list, obj("""{"0":{},"1":{}}""")))
        val choice = AdminType.Choice("StorageQuota", false)
        val first = schema.choices("StorageQuota").first()
        assertEquals(first.label, displayValue(choice, JsonPrimitive(first.name), { schema.choices(it) }))
    }

    @Test
    fun setsGoBackOnTheWireAsObjectsOfTrue() {
        assertEquals(obj("""{"a":true,"b":true}"""), membersValue(listOf("a", "b", "a")))
        assertEquals(listOf("a"), setMembers(obj("""{"a":true,"b":false}""")))
    }

    @Test
    fun onlyChangedEditableFieldsAreSent() {
        val form = schema.form("x:Domain", enterprise = false)
        val before = obj("""{"id":"d1","name":"example.com","isEnabled":true,"createdAt":"2026-01-01T00:00:00Z","catchAllAddress":null}""")
        val after = obj(
            """{"id":"d1","name":"example.com","isEnabled":false,"createdAt":"2027-01-01T00:00:00Z",""" +
                """"catchAllAddress":null,"notAField":1}""",
        )
        // isEnabled changed. createdAt is server-set and notAField is not on the form, so
        // neither may reach the server whatever the draft says.
        assertEquals(obj("""{"isEnabled":false}"""), changedFields(form, before, after, creating = false))
    }

    @Test
    fun missingAndNullAreTheSame() {
        val form = schema.form("x:Domain", enterprise = false)
        val before = obj("""{"name":"example.com"}""")
        val after = obj("""{"name":"example.com","catchAllAddress":null}""")
        assertTrue(changedFields(form, before, after, creating = false).isEmpty())
        assertTrue(describeChanges(schema, form, before, after, enterprise = false).isEmpty())
    }

    @Test
    fun theChangeListNamesFieldsAndNestedFieldsInWords() {
        val form = schema.form("x:Domain", enterprise = false)
        val before = obj("""{"name":"example.com","isEnabled":true,"catchAllAddress":null}""")
        val after = obj("""{"name":"example.org","isEnabled":false,"catchAllAddress":"all@example.org"}""")
        val changes = describeChanges(schema, form, before, after, enterprise = false)
        assertEquals(
            listOf(
                AdminChange("Domain Name", "example.com", "example.org"),
                AdminChange("Enabled", "Yes", "No"),
                AdminChange("Catch-All Address", "Not set", "all@example.org"),
            ),
            changes,
        )
    }

    @Test
    fun aChangedKindIsOneLine() {
        val form = schema.form("x:Domain", enterprise = false)
        val before = obj("""{"dkimManagement":{"@type":"Automatic"}}""")
        val after = obj("""{"dkimManagement":{"@type":"Manual"}}""")
        val changes = describeChanges(schema, form, before, after, enterprise = false)
        assertEquals(1, changes.size)
        assertEquals("DKIM Management", changes[0].label)
        assertEquals("Manual DKIM management", changes[0].after)
    }

    @Test
    fun aNestedChangeIsNamedByItsPath() {
        val form = schema.form("x:Domain", enterprise = false)
        val inner = schema.form("x:DkimManagementProperties", enterprise = false).fields.first { it.type is AdminType.Number }
        val before = obj("""{"dkimManagement":{"@type":"Automatic","${inner.name}":1}}""")
        val after = obj("""{"dkimManagement":{"@type":"Automatic","${inner.name}":2}}""")
        val changes = describeChanges(schema, form, before, after, enterprise = false)
        assertEquals(listOf("DKIM Management, ${inner.label}"), changes.map { it.label })
    }

    @Test
    fun aChangedSecretSaysSoWithoutTheValue() {
        val form = schema.form("x:PasswordCredential", enterprise = false)
        val changes = describeChanges(schema, form, obj("""{"secret":"hunter2"}"""), obj("""{"secret":"correcthorse"}"""), enterprise = false)
        assertEquals(listOf(AdminChange("Secret", "Set, hidden", "A new value, hidden")), changes.map { it.copy(label = "Secret") })
        assertFalse(changes.any { "hunter2" in it.before || "correcthorse" in it.after })
    }

    @Test
    fun aDraftWithARequiredValueEmptyIsCaught() {
        val form = schema.form("x:Domain", enterprise = false)
        assertEquals(setOf("name"), draftProblems(form, obj("""{"name":""}"""), creating = true).keys)
        assertTrue(draftProblems(form, obj("""{"name":"example.com"}"""), creating = true).isEmpty())
    }

    @Test
    fun aNewObjectStartsFromTheServersDefaults() {
        val domain = startingValue(schema, "x:Domain", null, enterprise = false)
        assertEquals(JsonPrimitive(true), domain["isEnabled"])
        assertEquals(JsonPrimitive("mailto:postmaster"), domain["reportAddressUri"])

        val user = startingValue(schema, "x:Account", "User", enterprise = false)
        assertEquals(JsonPrimitive("User"), user["@type"])
        // The nested objects an account cannot be made without start as their first kind,
        // which is what the server's own console sends.
        assertTrue(user["encryptionAtRest"] is JsonObject)
        assertTrue(user["roles"] is JsonObject)
    }

    @Test
    fun creatingSendsEverythingSetExceptNulls() {
        val form = schema.form("x:Domain", enterprise = false)
        val draft = obj("""{"name":"example.com","isEnabled":true,"catchAllAddress":null,"createdAt":"x"}""")
        assertEquals(obj("""{"name":"example.com","isEnabled":true}"""), changedFields(form, JsonObject(emptyMap()), draft, creating = true))
    }

    @Test
    fun aNewAccountCarriesItsKindAndAnEditNeverDoes() {
        val form = schema.form("x:UserAccount", enterprise = false)
        val draft = obj("""{"@type":"User","name":"jane"}""")
        val created = changedFields(form, JsonObject(emptyMap()), draft, creating = true)
        assertEquals(JsonPrimitive("User"), created["@type"])
        val edited = changedFields(form, obj("""{"@type":"User","name":"jane"}"""), obj("""{"@type":"Group","name":"janet"}"""), creating = false)
        assertEquals(obj("""{"name":"janet"}"""), edited)
    }
}
