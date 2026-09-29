package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rook's filter tools, against functions standing in for the Filters page and the server.
 *
 * The point of these is what the tools do not do. propose_filter may build a rule and ask
 * the server about it, and it may put a card on screen. It never saves. list_filters only
 * reads. An account without Sieve gets one sentence and nothing else.
 */
class FilterToolsTest {
    private val dmarc = Rule(
        name = "DMARC reports",
        tests = listOf(
            Test(Field.FROM, Match.CONTAINS, "dmarc"),
            Test(Field.SUBJECT, Match.CONTAINS, "report"),
        ),
        acts = listOf(Act.FileInto("Archive")),
    )

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun asked(json: String) = Asked("propose_filter", args(json))

    /** One answer per turn, in order, from a server that is really listening. Same shape as ChatTest. */
    private fun serving(answers: ArrayDeque<String>, use: (AssistantConfig) -> List<Said>): List<Said> {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.readBytes()
            val text = answers.removeFirstOrNull() ?: "done"
            val bytes = ("""{"choices":[{"message":{"content":"$text"}}],""" +
                """"usage":{"prompt_tokens":5,"completion_tokens":5}}""").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            return use(AssistantConfig(baseUrl = "http://127.0.0.1:${server.address.port}/v1"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `propose_filter through the tool loop makes a card and does not save`() {
        val calls = mutableListOf<String>()
        val filters = FilterTools(
            unavailable = null,
            rules = { calls += "rules"; emptyList() },
            build = { words ->
                calls += "build:$words"
                Result.success(dmarc)
            },
            validate = { rule ->
                calls += "validate"
                assertEquals(dmarc, rule)
                null
            },
        )
        val answers = ArrayDeque(
            listOf(
                """{\"tool\":\"propose_filter\",\"args\":{\"description\":\"archive DMARC reports\"}}""",
                "A card is waiting for you.",
            ),
        )
        val said = serving(answers) { config ->
            converse(
                config = config,
                key = null,
                system = "test",
                history = listOf(Said("user", "make a filter that archives DMARC reports")),
                shown = mutableSetOf(),
                tools = QuietMail,
                record = { _, _ -> },
                filters = filters,
            )
        }

        assertEquals(listOf("build:archive DMARC reports", "validate"), calls)
        val card = filters.proposed.single()
        assertEquals(CardStatus.WAITING, card.status)
        assertEquals(dmarc, card.rule)
        assertEquals("Mail whose from address contains dmarc whose subject contains report goes to Archive.", card.words)
        assertEquals("If from contains \"dmarc\" and subject contains \"report\", file into Archive.", card.does)
        val result = said.first { it.role == "result" }.text
        assertContains(result, "DMARC reports")
        assertContains(result, "Nothing is saved until the person presses Save")
        // Save is a desk transition the window raises. The tool has no way to make it.
        val desk = FilterDesk().add("acct-1", filters.proposed)
        assertEquals("acct-1", desk.card(1)?.account)
        val started = desk.start(1)
        assertEquals(CardStatus.APPLYING, started.card(1)?.status)
        assertEquals(started, started.start(1))
    }

    @Test
    fun `a filter the server rejects makes no card`() {
        val calls = mutableListOf<String>()
        val tools = FilterTools(
            unavailable = null,
            rules = { emptyList() },
            build = { calls += "build"; Result.success(dmarc) },
            validate = { calls += "validate"; "The server rejected this filter: line 3." },
        )
        val answer = tools.run(asked("""{"description":"archive DMARC reports"}"""))!!
        assertContains(answer, "No card was made.")
        assertContains(answer, "The server rejected this filter: line 3.")
        assertTrue(tools.proposed.isEmpty())
        assertEquals(listOf("build", "validate"), calls)
    }

    @Test
    fun `a blank description makes no card and does not build one`() {
        val calls = mutableListOf<String>()
        val tools = FilterTools(
            unavailable = null,
            rules = { emptyList() },
            build = { calls += "build"; Result.success(dmarc) },
            validate = { calls += "validate"; null },
        )
        val answer = tools.run(asked("""{"description":"  "}"""))!!
        assertEquals("That needs a description of the filter.", answer)
        assertTrue(tools.proposed.isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `list_filters says each rule in plain words`() {
        val news = Rule(
            name = "News",
            tests = listOf(Test(Field.FROM, Match.CONTAINS, "@news.example")),
            acts = listOf(Act.FileInto("Archive")),
        )
        val big = Rule(
            name = "Big mail",
            tests = listOf(Test(Field.SIZE, Match.IS, "> 10M")),
            acts = listOf(Act.Delete),
            enabled = false,
            global = true,
        )
        val tools = FilterTools(
            unavailable = null,
            rules = { listOf(news, big) },
            build = { error("list does not build") },
            validate = { error("list does not check") },
        )
        assertEquals(
            "News: Mail from anyone at news.example goes to Archive.\n" +
                "(off) Big mail: Mail larger than 10M is discarded. Kept for every account.",
            tools.run(Asked("list_filters", args("{}"))),
        )
    }

    @Test
    fun `list_filters says when there are none, and names a script it must not rebuild`() {
        val empty = FilterTools(
            unavailable = null,
            rules = { emptyList() },
            build = { error("no") },
            validate = { error("no") },
        )
        assertEquals("No filters yet.", empty.run(Asked("list_filters", args("{}"))))

        val handwritten = FilterTools(
            unavailable = null,
            rules = { throw JmapError(UNEDITABLE_FILTERS) },
            build = { error("no") },
            validate = { error("no") },
        )
        assertEquals(UNEDITABLE_FILTERS, handwritten.run(Asked("list_filters", args("{}"))))
    }

    @Test
    fun `an account without sieve answers both tools with one sentence and makes no card`() {
        val reason = noSieveSentence("Home")
        assertEquals("This server does not offer Sieve, so Home cannot keep filters on it.", reason)
        assertFalse('\n' in reason)
        val calls = mutableListOf<String>()
        val tools = FilterTools(
            unavailable = reason,
            rules = { calls += "rules"; emptyList() },
            build = { calls += "build"; error("built") },
            validate = { calls += "validate"; error("checked") },
        )
        assertEquals(reason, tools.run(Asked("list_filters", args("{}"))))
        assertEquals(reason, tools.run(asked("""{"description":"archive DMARC reports"}""")))
        assertTrue(tools.proposed.isEmpty())
        assertTrue(calls.isEmpty())
        assertEquals("Sign in to an account to keep filters.", noFiltersBecause(null, "Home"))
    }

    @Test
    fun `one turn may put only a few filter cards on screen`() {
        var built = 0
        val tools = FilterTools(
            unavailable = null,
            rules = { emptyList() },
            build = { built += 1; Result.success(dmarc) },
            validate = { null },
            alreadyWaiting = MOST_FILTERS,
        )
        val answer = tools.run(asked("""{"description":"archive DMARC reports"}"""))!!
        assertContains(answer, "already")
        assertTrue(tools.proposed.isEmpty())
        assertEquals(0, built)
    }

    @Test
    fun `clearing the conversation keeps a filter that is still saving`() {
        val waiting = FilterCard(1, "a", dmarc, CardStatus.WAITING)
        val saving = FilterCard(2, "a", dmarc, CardStatus.APPLYING)
        assertEquals(listOf(2), FilterDesk(listOf(waiting, saving)).clear().cards.map { it.number })
    }

    @Test
    fun `other tools are left for the mail and settings tools`() {
        val tools = FilterTools(null, { emptyList() }, { Result.success(dmarc) }, { null })
        assertNull(tools.run(Asked("archive", args("{}"))))
        assertNull(tools.run(Asked("change_setting", args("{}"))))
    }

    @Test
    fun `the prompt names both filter tools and says save is the person's`() {
        val system = Chat.system(listOf("Inbox", "Archive"), "justin@example.com")
        assertContains(system, "{\"tool\":\"list_filters\"")
        assertContains(system, "{\"tool\":\"propose_filter\"")
        assertContains(system, "\"description\"")
        assertContains(system, "only takes effect when the person presses Save")
        assertFalse("—" in system || "–" in system || "…" in system)
    }

    private object QuietMail : MailTools {
        override fun search(text: String, limit: Int): List<Summary> = emptyList()
        override fun read(id: String): String? = null
        override fun file(ids: List<String>, role: String): Int = 0
        override fun markRead(ids: List<String>, read: Boolean): Int = 0
        override fun tag(ids: List<String>, keyword: String, on: Boolean): Int = 0
        override fun draftReply(id: String, text: String): Boolean = false
        override fun trackingToday(): String = "No person opens or clicks were recorded today."
    }
}
