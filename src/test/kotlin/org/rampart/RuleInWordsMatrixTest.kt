package org.rampart

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Canned model replies exercise the contract without sending any mailbox data to a model. */
class RuleInWordsMatrixTest {
    private val folders = listOf("Inbox", "Receipts", "Reading", "Lists")

    private data class Case(
        val request: String,
        val reply: String,
        val preview: String? = null,
        val sieve: String? = null,
        val error: String? = null,
    )

    private fun answer(
        name: String,
        field: String,
        comparator: String,
        value: String,
        action: String,
        actionValue: String? = null,
        match: String = "all",
        stop: Boolean = false,
        extraCondition: String = "",
        header: String = "",
    ): String {
        val condition = """{"field":"$field","comparator":"$comparator","value":"$value"$header}"""
        val actionJson = """{"type":"$action"${actionValue?.let { ",\"value\":\"$it\"" }.orEmpty()}}"""
        return """{"name":"$name","matchType":"$match","stopProcessing":$stop,"conditions":[$condition$extraCondition],"actions":[$actionJson]}"""
    }

    @Test
    fun `twenty plain requests parse preview generate and round trip`() {
        val first = answer(
            "Invoices", "from", "contains", "@example.com", "move", "Receipts",
            extraCondition = """,{"field":"subject","comparator":"contains","value":"invoice"}""",
        ).replace(
            """"actions":[{"type":"move","value":"Receipts"}]""",
            """"actions":[{"type":"move","value":"Receipts"},{"type":"mark_read"}]""",
        )
        val cases = listOf(
            Case("File example invoices and read them", first, "Mail from anyone at example.com whose subject contains invoice goes to Receipts and is marked read.", "allof("),
            Case("Exact sender is read", answer("Sender", "from", "is", "a@example.com", "mark_read"), sieve = "addflag \"\\\\Seen\";"),
            Case("Mail to sales is starred", answer("Sales", "to", "contains", "sales@", "star"), sieve = "addflag \"\\\\Flagged\";"),
            Case("Mail copied to audit is tagged", answer("Audit", "cc", "contains", "audit@", "tag", "audited"), sieve = "addflag \"audited\";"),
            Case("Subjects starting with invoice go to Receipts", answer("Invoice", "subject", "starts_with", "Invoice", "move", "Receipts"), sieve = ":matches \"Subject\" \"Invoice*\""),
            Case("Wildcard subjects go to Reading", answer("Reports", "subject", "matches", "Report *", "move", "Reading"), sieve = "\"Report *\""),
            Case("Body containing unsubscribe goes to Reading", answer("News", "body", "contains", "unsubscribe", "move", "Reading"), sieve = "body :text :contains"),
            Case("A custom header marks mail read", answer("Scanner", "header", "is", "yes", "mark_read", header = ",\"header\":\"X-Scanned\""), sieve = "\"X-Scanned\" \"yes\""),
            Case("Large mail is discarded", answer("Large", "size", "is", "> 10M", "discard"), sieve = "size :over 10M"),
            Case("Small mail is starred", answer("Small", "size", "is", "< 500K", "star"), sieve = "size :under 500K"),
            Case("Attachments go to Reading", answer("Attachments", "has_attachment", "is", "true", "move", "Reading"), sieve = "foreverypart"),
            Case("List mail goes to Lists", answer("List", "list_id", "contains", "news.example", "move", "Lists"), sieve = "\"List-Id\""),
            Case("Forward alerts", answer("Forward", "subject", "contains", "alert", "forward", "ops@example.com"), sieve = "redirect \"ops@example.com\";"),
            Case("Discard spam", answer("Spam", "subject", "contains", "spam", "discard"), sieve = "discard;"),
            Case("Stop after filing", answer("Stop", "from", "contains", "boss@", "move", "Inbox", stop = true), sieve = "stop;"),
            Case("Either address matches", answer("Either", "from", "contains", "one@", "star", match = "any", extraCondition = """,{"field":"from","comparator":"contains","value":"two@"}"""), sieve = "anyof("),
            Case("Fenced JSON", "```json\n${answer("Fence", "subject", "is", "hello", "mark_read")}\n```", sieve = "header :is"),
            Case("Prose around JSON", "Here is the rule.\n${answer("Prose", "body", "contains", "receipt", "move", "Receipts")}\nDone.", sieve = "fileinto \"Receipts\";"),
            Case("Correct folder case", answer("Case", "subject", "contains", "bill", "move", "receipts"), sieve = "fileinto \"Receipts\";"),
            Case("Tag and stop", answer("Tag", "from", "contains", "robot@", "tag", "robot", stop = true), sieve = "stop;"),
            Case("Invented folder", answer("Missing", "subject", "contains", "bill", "move", "Bills"), error = "There is no folder called Bills."),
            Case("Unsupported action", answer("Reply", "subject", "contains", "help", "reply"), error = "unsupported action 'reply'"),
            Case("Malformed reply", "Certainly: {not json}", error = "valid JSON"),
        )
        assertTrue(cases.size >= 20)
        cases.forEach { case ->
            val result = ruleOfAnswer(case.reply, folders)
            if (case.error != null) {
                assertContains(result.exceptionOrNull()?.message.orEmpty(), case.error, message = case.request)
            } else {
                val rule = result.getOrThrow()
                case.preview?.let { assertEquals(it, previewRule(rule), case.request) }
                val sieve = sieveOf(Script(listOf(rule)))
                case.sieve?.let { assertContains(sieve, it, message = case.request) }
                val read = scriptOf(sieve)
                assertTrue(read.editable, case.request)
                assertEquals(rule.copy(raw = null), read.rules.single().copy(raw = null), case.request)
            }
        }
        assertIs<MissingFolder>(ruleOfAnswer(cases[20].reply, folders).exceptionOrNull())
    }

    @Test
    fun `the repair packet quotes the exact validation error`() {
        val packet = ruleRepairPacket(AssistantConfig(), "file bills", folders, "{bad}", "Action 1 was wrong.")
        assertContains(packet, "{bad}")
        assertContains(packet, "Action 1 was wrong.")
    }
}
