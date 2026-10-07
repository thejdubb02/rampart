package org.rampart

import androidx.compose.ui.graphics.Color
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The catalogue is data, checked before it is read. These tests stay off the network:
 * a passing run must not be one that happened to reach GitHub.
 */
class CatalogueTest {
    private val index = URI("https://raw.githubusercontent.com/thejdubb02/rampart-catalogue/main/index.json")

    @Test
    fun `parseIndex reads a theme and a template`() {
        val items = parseIndex(indexJson(themeEntry(), templateEntry()))
        assertEquals(listOf("harbour-fog", "decline-politely"), items.map { it.id })

        val theme = items[0]
        assertEquals("theme", theme.kind)
        assertEquals("Harbour Fog", theme.name)
        assertEquals("A grey morning over the water.", theme.description)
        assertEquals("Rampart", theme.author)
        assertEquals("themes/harbour-fog.json", theme.path)
        assertEquals(400, theme.size)
        val preview = theme.preview as CataloguePreview.Theme
        assertEquals(true, preview.dark)
        assertEquals("#1B242C", preview.background)
        assertEquals("#243039", preview.surface)
        assertEquals("#E7EEF2", preview.text)
        assertEquals("#7BA3B0", preview.accent)
        assertEquals("#F4F1EA", preview.pageBackground)
        assertEquals("#1C1A17", preview.pageText)
        assertEquals("#3D5A4C", preview.pageAccent)

        val template = items[1].preview as CataloguePreview.Template
        assertEquals("Re: {{subject}}", template.subject)
        assertEquals("Thank you for thinking of me.", template.firstLine)
    }

    @Test
    fun `version 2 is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            parseIndex("""{"version": 2, "items": []}""")
        }
        assertEquals("This catalogue needs a newer Rampart.", error.message)
    }

    @Test
    fun `a catalogue that is not JSON is rejected`() {
        assertFailsWith<IllegalArgumentException> { parseIndex("{") }
    }

    @Test
    fun `an unknown kind and a bad path are skipped`() {
        val items = parseIndex(
            indexJson(
                themeEntry(),
                themeEntry(id = "escaped", path = "../x.json"),
                """{"kind":"sound","id":"chime","name":"Chime","path":"themes/chime.json","size":12,"sha256":"$SHA","preview":{"dark":true}}""",
                themeEntry(id = "too-big", size = 9000),
                "null",
                templateEntry(),
            ),
        )
        assertEquals(listOf("harbour-fog", "decline-politely"), items.map { it.id })
    }

    @Test
    fun `a theme path stays under the catalogue`() {
        assertEquals(
            URI("https://raw.githubusercontent.com/thejdubb02/rampart-catalogue/main/themes/x.json"),
            safeItemUrl(index, "themes/x.json"),
        )
        assertEquals(
            URI("https://raw.githubusercontent.com/thejdubb02/rampart-catalogue/main/templates/ok.json"),
            safeItemUrl(index, "templates/ok.json"),
        )
    }

    @Test
    fun `a path that leaves the catalogue is refused`() {
        listOf(
            "../x.json",
            "/themes/x.json",
            "https://evil/x.json",
            "themes/../../x.json",
            "themes/x.json?a=1",
            "themes\\x.json",
            "templates/x.txt",
            "themes/%2e%2e/x.json",
        ).forEach { assertNull(safeItemUrl(index, it), it) }
        assertNull(safeItemUrl(URI("http://example.test/index.json"), "themes/x.json"))
    }

    @Test
    fun `an http catalogue address is refused`() {
        val error = assertFailsWith<CatalogueProblem> { catalogueUri("http://example.test/index.json") }
        assertEquals("The catalogue address has to be https.", error.message)
        assertFailsWith<CatalogueProblem> { catalogueUri("HTTP://example.test/index.json") }
    }

    @Test
    fun `a redirect stays on https`() {
        val here = URI("https://example.test/main/index.json")
        assertEquals(URI("https://example.test/main/themes/x.json"), redirectTarget(here, "themes/x.json"))
        assertNull(redirectTarget(here, "http://example.test/stolen.json"))
        assertNull(redirectTarget(here, "https://user:secret@example.test/x.json"))
    }

    @Test
    fun `verified accepts the named bytes and nothing else`() {
        val bytes = "catalogue".encodeToByteArray()
        val item = catalogueItem(bytes)
        assertTrue(verified(bytes, item))
        val changed = bytes.copyOf()
        changed[0] = (changed[0].toInt() xor 1).toByte()
        assertTrue(!verified(changed, item))
        assertTrue(!verified(bytes, item.copy(size = bytes.size + 1)))
    }

    @Test
    fun `a hybrid theme round trips its page`() {
        val theme = ThemeJson.decode(HYBRID)
        assertEquals("custom:harbour-fog", theme.key)
        val page = theme.page
        assertNotNull(page)
        assertEquals(false, page.dark)
        assertEquals("custom:harbour-fog-page", page.key)
        assertEquals(theme.label, page.label)
        assertNull(page.art)
        assertNull(page.page)

        val again = ThemeJson.decode(ThemeJson.encode(theme))
        assertEquals(theme, again)
        assertEquals(theme.background, again.background)
        assertEquals(page.background, again.page?.background)
        assertEquals(page.text, again.page?.text)
        assertEquals(page.accent, again.page?.accent)
        assertNotNull(again.page)
        assertTrue("description" !in ThemeJson.encode(again))
    }

    @Test
    fun `a light theme with a page is rejected`() {
        val light = HYBRID.replace("\"dark\": true", "\"dark\": false")
        val error = assertFailsWith<IllegalArgumentException> { ThemeJson.decode(light) }
        assertEquals("A theme with a reading page has to be dark.", error.message)
    }

    @Test
    fun `a theme file without a page still loads`() {
        val loaded = ThemeJson.decode(PLAIN)
        assertNull(loaded.page)
        assertEquals(Color(0xFF102030), loaded.background)
        assertTrue("\"page\"" !in ThemeJson.encode(loaded))
    }

    /**
     * installTheme writes this computer's settings, so the test calls the merge it uses.
     * A second add of the same key replaces the first and does not leave both.
     */
    @Test
    fun `installing a theme twice leaves one entry`() {
        val first = ThemeJson.decode(HYBRID)
        val second = first.copy(accent = Color(0xFFAABBCC))
        val saved = themesWith(themesWith(emptyList(), first), second)
        assertEquals(1, saved.size)
        assertEquals("custom:harbour-fog", saved.single().key)
        assertEquals(Color(0xFFAABBCC), saved.single().accent)
        assertEquals(first.page, saved.single().page)
    }

    @Test
    fun `installing a template with an existing name replaces it`() {
        val path = Files.createTempDirectory("rampart-catalogue").resolve("templates.json")
        try {
            val kept = Template("Quote follow-up", "Following up", "Hi")
            val existing = Template("Decline politely", "Old subject", "Old body")
            Templates.write(listOf(existing, kept), path)
            val added = catalogueTemplate(
                """
                {
                  "name": "Decline politely",
                  "subject": "Not this time",
                  "body": "Hello {{first name}},",
                  "description": "A short no."
                }
                """.trimIndent(),
            )
            installTemplate(added, path)
            val saved = Templates.read(path)
            assertEquals(listOf(added, kept), saved)
            assertTrue("{{first name}}" in saved[0].body)
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun `parseIndex accepts a filter and its path stays under the catalogue`() {
        val items = parseIndex(
            indexJson(
                filterEntry(),
                filterEntry(id = "escaped", path = "filters/../x.json"),
                """{"kind":"filter","id":"bare","name":"Bare","path":"filters/bare.json","size":12,"sha256":"$SHA","preview":{}}""",
            ),
        )
        assertEquals(listOf("newsletters"), items.map { it.id })
        assertEquals("filter", items.single().kind)
        val preview = items.single().preview as CataloguePreview.Filter
        assertEquals("File newsletters and mark them read.", preview.summary)
        assertEquals(
            URI("https://raw.githubusercontent.com/thejdubb02/rampart-catalogue/main/filters/x.json"),
            safeItemUrl(index, "filters/x.json"),
        )
        assertNull(safeItemUrl(index, "filters/../x.json"))
    }

    @Test
    fun `a filter recipe files into a folder and marks read`() {
        val first = assertNotNull(scriptWithCatalogueFilter(Script(emptyList()), "acct", FILE_AND_READ))
        val again = assertNotNull(scriptWithCatalogueFilter(first, "acct", FILE_AND_READ))
        val added = ownRules(again)
        assertEquals(2, added.size)
        assertNotEquals(added[0].id, added[1].id)
        added.forEach { rule ->
            assertEquals("Newsletters", rule.name)
            assertFalse(rule.global)
            assertNotEquals("recipe-1", rule.id)
            assertEquals(listOf(Act.FileInto("Reading"), Act.MarkRead), rule.acts)
        }
    }

    @Test
    fun `a filter recipe that forwards is refused`() {
        listOf("forward", "redirect").forEach { type ->
            val prepared = prepareCatalogueFilter(forwardRecipe(type))
            assertNull(prepared.rule)
            assertEquals("This recipe forwards mail, so it was not added.", prepared.reason)
            assertNull(scriptWithCatalogueFilter(Script(emptyList()), "acct", forwardRecipe(type)))
        }
    }

    @Test
    fun `a filter recipe that does not parse is refused`() {
        listOf("nope", """{"hello":"there"}""", NOT_UNDERSTOOD).forEach { text ->
            val prepared = prepareCatalogueFilter(text)
            assertNull(prepared.rule, text)
            assertEquals(CATALOGUE_NOT_A_FILTER, prepared.reason)
            assertNull(scriptWithCatalogueFilter(Script(emptyList()), "acct", text))
        }
    }

    @Test
    fun `a filter that deletes is kept and the words say delete`() {
        val rule = assertNotNull(prepareCatalogueFilter(DELETE_RECIPE).rule)
        assertEquals(listOf(Act.Delete), rule.acts)
        assertTrue("delete" in summarise(rule))
    }

    @Test
    fun `a filter for a missing folder names that folder`() {
        val rule = assertNotNull(prepareCatalogueFilter(FILE_AND_READ).rule)
        assertEquals("This account has no folder named Reading", catalogueFolderRefusal(rule, listOf("Inbox")))
        assertNull(catalogueFolderRefusal(rule, listOf("reading")))
        val fitted = catalogueRuleInFolders(rule, listOf("reading"))
        assertEquals("reading", (fitted.acts.first() as Act.FileInto).folder)
    }

    @Test
    fun `a filter already on the account is said in the confirm step`() {
        assertEquals("Already on Work", alreadyOnAccount("Newsletters", listOf("newsletters"), "Work"))
        assertNull(alreadyOnAccount("Newsletters", listOf("Receipts"), "Work"))
    }

    @Test
    fun `parseIndex accepts a prompt and skips a blank summary`() {
        val items = parseIndex(
            indexJson(
                promptEntry(),
                promptEntry(id = "blank", summary = ""),
            ),
        )
        assertEquals(listOf("reply-to-a-lead"), items.map { it.id })
        assertEquals("prompt", items.single().kind)
        val preview = items.single().preview as CataloguePreview.Prompt
        assertEquals("Reply to a lead in a few lines.", preview.summary)
    }

    @Test
    fun `cataloguePrompt keeps the text and refuses one that is too long or hidden`() {
        val good = cataloguePrompt("""{"name":"Reply to a lead","text":"Be brief."}""")
        assertEquals(SavedPrompt("Reply to a lead", "Be brief."), good)

        val tooLong = "x".repeat(SavedPrompts.TEXT_MAX + 1)
        val longError = assertFailsWith<IllegalArgumentException> {
            cataloguePrompt("""{"name":"Long","text":"$tooLong"}""")
        }
        assertEquals("This prompt is longer than a saved prompt can be, so it was not added.", longError.message)

        val hiddenText = assertFailsWith<IllegalArgumentException> {
            cataloguePrompt("""{"name":"Hidden","text":"see\u200Bthis"}""")
        }
        assertEquals("This prompt contains hidden characters, so it was not added.", hiddenText.message)

        val hiddenName = assertFailsWith<IllegalArgumentException> {
            cataloguePrompt("""{"name":"Go\u202Eback","text":"Be brief."}""")
        }
        assertEquals("This prompt contains hidden characters, so it was not added.", hiddenName.message)

        val blank = assertFailsWith<IllegalArgumentException> {
            cataloguePrompt("""{"name":"Blank","text":"  "}""")
        }
        assertEquals("This is not a saved prompt Rampart understands, so it was not added.", blank.message)

        val kept = cataloguePrompt("""{"name":"Note","text":"line one\n\tline two"}""")
        assertEquals(SavedPrompt("Note", "line one\n\tline two"), kept)
    }

    @Test
    fun `preparePrompt is ready, already saved, or full`() {
        val file = """{"name":"Reply to a lead","text":"Be brief."}"""
        val ready = preparePrompt(file, emptyList()) as PromptStage.Ready
        assertEquals(SavedPrompt("Reply to a lead", "Be brief."), ready.prompt)
        assertFalse(ready.already)

        val again = preparePrompt(file, listOf(SavedPrompt("REPLY TO A LEAD", "Old words."))) as PromptStage.Ready
        assertTrue(again.already)

        val full = (1..SavedPrompts.MOST).map { SavedPrompt("Name $it", "Body $it") }
        val failed = preparePrompt(file, full) as PromptStage.Failed
        assertEquals("There are already 50 saved prompts. Delete one first.", failed.reason)
    }

    @Test
    fun `commitCataloguePrompt adds, replaces, and stops at fifty`() = runBlocking {
        var saved = emptyList<SavedPrompt>()
        val shelf = object : PromptShelf {
            override val place = PromptPlace.LOCAL
            override fun load(): List<SavedPrompt> = saved
            override fun save(prompts: List<SavedPrompt>) {
                saved = prompts
            }
        }
        assertNull(commitCataloguePrompt(shelf, SavedPrompt("Reply to a lead", "Be brief.")))
        assertEquals(listOf(SavedPrompt("Reply to a lead", "Be brief.")), saved)

        assertNull(commitCataloguePrompt(shelf, SavedPrompt("reply to a lead", "Be warmer.")))
        assertEquals(listOf(SavedPrompt("reply to a lead", "Be warmer.")), saved)

        val full = (1..SavedPrompts.MOST).map { SavedPrompt("Name $it", "Body $it") }
        saved = full
        val refused = commitCataloguePrompt(shelf, SavedPrompt("One more", "No room."))
        assertEquals("There are already 50 saved prompts. Delete one first.", refused)
        assertEquals(full, saved)
    }

    @Test
    fun `added means the saved theme key or the saved template name`() {
        val theme = ThemeJson.decode(HYBRID)
        val themeItem = parseIndex(indexJson(themeEntry())).single()
        assertTrue(catalogueAdded(themeItem, listOf(theme), emptyList()))
        assertTrue(!catalogueAdded(themeItem, emptyList(), emptyList()))
        val templateItem = parseIndex(indexJson(templateEntry())).single()
        assertTrue(catalogueAdded(templateItem, emptyList(), listOf(Template("Decline politely", "s", "b"))))
        assertTrue(!catalogueAdded(templateItem, emptyList(), listOf(Template("Something else", "s", "b"))))
    }

    private fun catalogueItem(bytes: ByteArray) = CatalogueItem(
        kind = "theme",
        id = "harbour-fog",
        name = "Harbour Fog",
        description = "",
        author = "",
        path = "themes/harbour-fog.json",
        size = bytes.size,
        sha256 = sha256(bytes),
        preview = CataloguePreview.Theme(true, "#102030", "#203040", "#F0E0D0", "#708090"),
    )

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

private const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

private fun indexJson(vararg items: String) = """{"version": 1, "items": [${items.joinToString(",")}]}"""

private fun themeEntry(
    id: String = "harbour-fog",
    path: String = "themes/harbour-fog.json",
    size: Int = 400,
) = """
    {
      "kind": "theme",
      "id": "$id",
      "name": "Harbour Fog",
      "description": "A grey morning over the water.",
      "author": "Rampart",
      "path": "$path",
      "size": $size,
      "sha256": "$SHA",
      "preview": {
        "dark": true,
        "background": "#1B242C",
        "surface": "#243039",
        "text": "#E7EEF2",
        "accent": "#7BA3B0",
        "page": {"background": "#F4F1EA", "text": "#1C1A17", "accent": "#3D5A4C"}
      }
    }
""".trimIndent()

private fun filterEntry(
    id: String = "newsletters",
    path: String = "filters/newsletters.json",
) = """
    {
      "kind": "filter",
      "id": "$id",
      "name": "Newsletters",
      "description": "File digests away.",
      "author": "Rampart",
      "path": "$path",
      "size": 180,
      "sha256": "$SHA",
      "preview": {"summary": "File newsletters and mark them read."}
    }
""".trimIndent()

private val FILE_AND_READ = """
    {
      "id": "recipe-1",
      "name": "Newsletters",
      "global": true,
      "matchType": "all",
      "conditions": [
        {"field": "from", "comparator": "contains", "value": "news.example"}
      ],
      "actions": [
        {"type": "move", "value": "Reading"},
        {"type": "mark_read"}
      ]
    }
""".trimIndent()

private fun forwardRecipe(type: String) = """
    {
      "name": "Send away",
      "conditions": [{"field": "from", "comparator": "contains", "value": "boss"}],
      "actions": [{"type": "$type", "value": "other@example.com"}]
    }
""".trimIndent()

private val NOT_UNDERSTOOD = """
    {
      "name": "Odd",
      "conditions": [{"field": "from", "comparator": "contains", "value": "a"}],
      "actions": [{"type": "explode"}]
    }
""".trimIndent()

private val DELETE_RECIPE = """
    {
      "name": "Drop digests",
      "conditions": [{"field": "from", "comparator": "contains", "value": "news.example"}],
      "actions": [{"type": "delete"}]
    }
""".trimIndent()

private fun promptEntry(
    id: String = "reply-to-a-lead",
    summary: String = "Reply to a lead in a few lines.",
) = """
    {
      "kind": "prompt",
      "id": "$id",
      "name": "Reply to a lead",
      "description": "A short reply.",
      "author": "Rampart",
      "path": "prompts/reply-to-a-lead.json",
      "size": 80,
      "sha256": "$SHA",
      "preview": {"summary": "$summary"}
    }
""".trimIndent()

private fun templateEntry() = """
    {
      "kind": "template",
      "id": "decline-politely",
      "name": "Decline politely",
      "description": "A short no.",
      "author": "Rampart",
      "path": "templates/decline-politely.json",
      "size": 200,
      "sha256": "$SHA",
      "preview": {"subject": "Re: {{subject}}", "firstLine": "Thank you for thinking of me."}
    }
""".trimIndent()

private const val COLOURS = """
  "background": "#102030",
  "surface": "#203040",
  "surfaceVariant": "#304050",
  "selection": "#405060",
  "text": "#F0E0D0",
  "muted": "#C0B0A0",
  "line": "#506070",
  "accent": "#708090",
  "onAccent": "#010203"
"""

private val HYBRID = """
    {
      "name": "Harbour Fog",
      "dark": true,
      "description": "A grey morning, ignored.",
      $COLOURS,
      "page": { $COLOURS }
    }
""".trimIndent()

private val PLAIN = """
    {
      "name": "Harbour",
      "dark": true,
      $COLOURS
    }
""".trimIndent()
