package org.rampart

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplatesTest {
    private val quote = Template(
        name = "Quote follow-up",
        subject = "Following up on {{subject}}",
        body = "Hi {{first name}},\n\nJust checking in on the quote.\n\n{{me}}",
    )

    @Test
    fun `placeholders come back in the order they appear, once each`() {
        assertEquals(listOf("subject", "first name", "me"), placeholders(quote))
    }

    @Test
    fun `a template with none says so`() {
        assertEquals(emptyList(), placeholders(Template("Done", "Done", "Done")))
    }

    @Test
    fun `filling replaces every copy of a name`() {
        val twice = Template("t", "{{name}}", "{{name}} and {{name}}")
        assertEquals("Dana and Dana", fill(twice, mapOf("name" to "Dana")).body)
    }

    /*
     * A name with no value stays as it was written. A sentence with a hole where a name
     * should be is one somebody sends; a visible {{first name}} is one they fix.
     */
    @Test
    fun `an unfilled placeholder is left alone rather than emptied`() {
        val out = fill(quote, mapOf("subject" to "the Tuesday job"))
        assertEquals("Following up on the Tuesday job", out.subject)
        assertTrue(out.body.contains("{{first name}}"), out.body)
    }

    @Test
    fun `a blank value counts as no value`() {
        assertTrue(fill(quote, mapOf("first name" to "   ")).body.contains("{{first name}}"))
    }

    @Test
    fun `what is known comes off the message being replied to`() {
        val summary = Summary(
            "a", "Dana Whitfield", "dana@example.org", "the quote",
            "2026-09-15T09:00:00Z", "", true,
        )
        val values = known(summary, me = "Justin")
        assertEquals("Dana Whitfield", values["name"])
        assertEquals("Dana", values["first name"])
        assertEquals("dana@example.org", values["email"])
        assertEquals("the quote", values["subject"])
        assertEquals("Justin", values["me"])
    }

    @Test
    fun `a new message knows only who is writing it`() {
        assertEquals(mapOf("me" to "Justin"), known(null, me = "Justin"))
        assertEquals(emptyMap(), known(null))
    }

    @Test
    fun `a one word sender has a first name`() {
        val summary = Summary("a", "Stalwart", "no-reply@example.org", "s", "2026-09-15T09:00:00Z", "", true)
        assertEquals("Stalwart", known(summary)["first name"])
    }

    @Test
    fun `templates survive a round trip through the file`() {
        val path = Files.createTempDirectory("rampart-templates").resolve("templates.json")
        try {
            Templates.write(listOf(quote), path)
            assertEquals(listOf(quote), Templates.read(path))
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun `a missing or mangled file is no templates, not a crash`() {
        val dir = Files.createTempDirectory("rampart-templates")
        assertEquals(emptyList(), Templates.read(dir.resolve("nothing.json")))
        val bad = dir.resolve("bad.json")
        bad.toFile().writeText("{ not json")
        assertEquals(emptyList(), Templates.read(bad))
    }
}

class TemplateValuesTest {
    @Test
    fun `a name and address are pulled out of a written recipient`() {
        val values = templateValues("Dana Reyes <dana@example.test>", "me@example.test", "Quote")
        assertEquals("Dana Reyes", values["name"])
        assertEquals("Dana", values["first name"])
        assertEquals("dana@example.test", values["email"])
        assertEquals("me@example.test", values["me"])
        assertEquals("Quote", values["subject"])
    }

    @Test
    fun `a bare address borrows its name from the address book`() {
        val book = listOf(Person(email = "dana@example.test", name = "Dana Reyes"))
        val values = templateValues("dana@example.test", "me@example.test", "", book)
        assertEquals("Dana Reyes", values["name"])
        assertEquals("dana@example.test", values["email"])
    }

    @Test
    fun `an unknown bare address falls back to the local part rather than nothing`() {
        val values = templateValues("dana@example.test", "me@example.test", "")
        assertEquals("dana", values["name"])
    }

    @Test
    fun `only the first recipient fills the template`() {
        val values = templateValues("dana@example.test, sam@example.test", "me@example.test", "")
        assertEquals("dana@example.test", values["email"])
    }
}
