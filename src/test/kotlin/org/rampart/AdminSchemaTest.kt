package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The schema reader, against a real one.
 *
 * `stalwart-schema-0.16.json` is cut from `resources/schema/schema.json.gz` in the
 * Stalwart source (0.16.24, commit af37a23): the objects, field sets, forms, lists and
 * enums that domains and accounts reach, and the first three entries of the Management
 * menu. Three long enums (Locale, Permission, TimeZone) are cut to their first twelve
 * values to keep the file small. Nothing in it was written by hand, so a test that passes
 * here is a test against what the server actually publishes.
 */
class AdminSchemaTest {
    private val schema = AdminSchema.parse(
        javaClass.getResourceAsStream("/stalwart-schema-0.16.json")?.readBytes()?.decodeToString()
            ?: error("fixture missing"),
    )

    @Test
    fun viewsResolveToTheObjectBehindThem() {
        val users = assertNotNull(schema.objectFor("x:Account/User"))
        assertEquals("x:Account", users.objectName)
        assertEquals("sysAccount", users.permissionPrefix)
        assertEquals("sysDomain", schema.objectFor("x:Domain")?.permissionPrefix)
        assertNull(schema.objectFor("x:NoSuchThing"))
    }

    @Test
    fun anAccountIsOneOfTwoKindsChosenByItsType() {
        val shape = schema.shapeOf("x:Account") as AdminShape.Multiple
        assertEquals(listOf("User", "Group"), shape.variants.map { it.name })
        val user = JsonObject(mapOf("@type" to JsonPrimitive("User")))
        assertEquals("x:UserAccount", schema.fieldSetFor("x:Account", user))
        assertEquals("x:Domain", schema.fieldSetFor("x:Domain", null))
    }

    @Test
    fun aVariantWithNoFieldsHasNoFieldSet() {
        // Manual DKIM management is a choice with nothing under it.
        val manual = JsonObject(mapOf("@type" to JsonPrimitive("Manual")))
        assertNull(schema.fieldSetFor("x:DkimManagement", manual))
    }

    @Test
    fun theDomainFormKeepsTheServersSectionsAndLabels() {
        val form = schema.form("x:Domain", enterprise = true)
        assertEquals("Domain", form.sections.first().title)
        val name = form.fields.first { it.name == "name" }
        assertEquals("Domain Name", name.label)
        assertEquals(AdminType.Text("string", nullable = false), name.type)
        assertTrue(name.editable)
        val created = form.fields.first { it.name == "createdAt" }
        assertEquals(Update.SERVER_SET, created.update)
        assertFalse(created.settableOnCreate)
        assertEquals(JsonPrimitive(true), form.defaults["isEnabled"])
    }

    @Test
    fun enterpriseFieldsAreLeftOffOtherEditions() {
        val community = schema.form("x:Domain", enterprise = false).fields.map { it.name }
        val enterprise = schema.form("x:Domain", enterprise = true).fields.map { it.name }
        assertTrue("logo" in enterprise)
        assertFalse("logo" in community)
        assertFalse("directoryId" in community)
        assertTrue("name" in community)
    }

    @Test
    fun everyFieldTypeInTheFixtureIsUnderstood() {
        // A type the renderer does not know is allowed, but in this fixture none should be:
        // every one of them is a type 0.16 actually uses.
        val types = listOf("x:Domain", "x:UserAccount", "x:GroupAccount", "x:PasswordCredential", "x:DkimManagementProperties")
            .flatMap { schema.form(it, enterprise = true).fields }
            .map { it.type }
        assertTrue(types.none { it is AdminType.Unknown }, types.filterIsInstance<AdminType.Unknown>().toString())
        assertTrue(types.any { it is AdminType.Number && it.format == "duration" })
        assertTrue(types.any { it is AdminType.Text && it.secret })
        assertTrue(types.any { it is AdminType.Many })
        assertTrue(types.any { it is AdminType.Dictionary })
    }

    @Test
    fun anUnknownTypeIsKeptRatherThanRefused() {
        val type = fieldType(kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"hologram","nullable":true}"""))
        assertEquals(AdminType.Unknown("hologram", true), type)
    }

    @Test
    fun theAccountsListCarriesItsFixedFilter() {
        val list = assertNotNull(schema.listFor("x:Account/User"))
        assertEquals("Accounts", list.title)
        assertEquals(listOf("emailAddress", "description", "createdAt"), list.columns.map { it.name })
        assertEquals(JsonPrimitive("User"), list.staticFilter["@type"])
        assertEquals("name", schema.listFor("x:Domain")?.labelProperty)
    }

    @Test
    fun theMenuIsFlattenedUnderItsTopLevelGroups() {
        val menu = schema.menu()
        val accounts = menu.first { it.viewName == "x:Account/User" }
        assertEquals("Directory", accounts.group)
        assertEquals("Accounts", accounts.label)
        assertEquals("Domains", menu.first { it.viewName == "x:Domain" }.group)
        // The dashboard is a link with no group, and a custom component the renderer does not draw.
        assertEquals("", menu.first { it.viewName == "CustomComponent/Dashboard" }.group)
    }

    @Test
    fun enumChoicesComeWithLabels() {
        val choices = schema.choices("StorageQuota")
        assertTrue(choices.isNotEmpty())
        assertTrue(choices.all { it.label.isNotBlank() })
        assertTrue(schema.choices("NoSuchEnum").isEmpty())
    }

    @Test
    fun somethingThatIsNotASchemaIsRefusedInASentence() {
        val e = assertFailsWith<AdminError> { AdminSchema.parse("""{"hello":"world"}""") }
        assertTrue(e.message!!.endsWith("."))
        assertFailsWith<AdminError> { AdminSchema.parse("<html>") }
    }

    @Test
    fun pagesAreGatedOnPermissionsNotOnTheSchema() {
        val none = AdminAccess(emptySet(), "community")
        assertTrue(adminPages(schema, none).isEmpty())

        val domainsOnly = AdminAccess(setOf("sysDomainQuery", "sysDomainGet"), "community")
        assertEquals(listOf("x:Domain"), adminPages(schema, domainsOnly).map { it.first.viewName })

        // Reading without querying is not enough for a list page.
        val getOnly = AdminAccess(setOf("sysAccountGet"), "community")
        assertTrue(adminPages(schema, getOnly).isEmpty())

        val both = AdminAccess(setOf("sysDomainQuery", "sysDomainGet", "sysAccountQuery", "sysAccountGet"), "enterprise")
        val views = adminPages(schema, both).map { it.first.viewName }
        assertTrue("x:Account/User" in views && "x:Account/Group" in views && "x:Domain" in views)
        // Pages the server lists but Rampart does not manage yet stay out, even when permitted.
        assertFalse(views.any { it.startsWith("x:Tenant") || it.startsWith("x:Role") })
    }

    @Test
    fun theAccountAnswerIsReadForPermissionsAndEdition() {
        val access = AdminAccess.parse("""{"permissions":["sysDomainGet","sysDomainUpdate"],"edition":"enterprise","locale":"en_US"}""")
        assertTrue(access.enterprise)
        val domain = assertNotNull(schema.objectFor("x:Domain"))
        assertTrue(access.can(domain, "Update"))
        assertFalse(access.can(domain, "Destroy"))
        assertEquals("oss", AdminAccess.parse("""{"permissions":[]}""").edition)
    }

    @Test
    fun namesWithoutALabelAreMadeReadable() {
        assertEquals("Catch all address", humanise("catchAllAddress"))
    }
}
