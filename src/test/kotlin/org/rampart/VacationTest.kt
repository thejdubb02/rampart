package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Tests vacation configuration validation, parsing, and serialization logic. We must
 * verify robust decoding of null and missing values to prevent mail client crashes.
 */
class VacationTest {

    @Test
    fun fullObjectRoundTripsSuccessfully() {
        val jsonStr = """
            {
              "id": "singleton",
              "isEnabled": true,
              "fromDate": "2026-09-20T00:00:00Z",
              "toDate": "2026-09-25T12:00:00Z",
              "subject": "Out of office",
              "textBody": "I am currently away.",
              "htmlBody": "<p>I am currently away.</p>"
            }
        """.trimIndent()
        val jsonObject = Json.parseToJsonElement(jsonStr).jsonObject
        val vacation = vacationOf(jsonObject)

        assertTrue(vacation.enabled)
        assertEquals("2026-09-20T00:00:00Z", vacation.from)
        assertEquals("2026-09-25T12:00:00Z", vacation.to)
        assertEquals("Out of office", vacation.subject)
        assertEquals("I am currently away.", vacation.text)
        assertEquals("<p>I am currently away.</p>", vacation.html)

        val patch = vacationPatch(vacation)
        assertEquals(true, (patch["isEnabled"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals("2026-09-20T00:00:00Z", (patch["fromDate"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("2026-09-25T12:00:00Z", (patch["toDate"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("Out of office", (patch["subject"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("I am currently away.", (patch["textBody"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("<p>I am currently away.</p>", (patch["htmlBody"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun optionalFieldsAsJsonNullBehaveGracefully() {
        val jsonStr = """
            {
              "isEnabled": false,
              "fromDate": null,
              "toDate": null,
              "subject": null,
              "textBody": null,
              "htmlBody": null
            }
        """.trimIndent()
        val jsonObject = Json.parseToJsonElement(jsonStr).jsonObject
        val vacation = vacationOf(jsonObject)

        assertTrue(!vacation.enabled)
        assertNull(vacation.from)
        assertNull(vacation.to)
        assertNull(vacation.subject)
        assertEquals("", vacation.text)
        assertNull(vacation.html)
    }

    @Test
    fun missingFieldsMapToNullOrDefaultValues() {
        val jsonStr = "{}"
        val jsonObject = Json.parseToJsonElement(jsonStr).jsonObject
        val vacation = vacationOf(jsonObject)

        assertTrue(!vacation.enabled)
        assertNull(vacation.from)
        assertNull(vacation.to)
        assertNull(vacation.subject)
        assertEquals("", vacation.text)
        assertNull(vacation.html)
    }

    @Test
    fun patchEmitsJsonNullForNullFieldsAndPreservesIsEnabled() {
        val vacation = Vacation(
            enabled = true,
            text = "Hello",
        )
        val patch = vacationPatch(vacation)

        assertEquals(true, (patch["isEnabled"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals(JsonNull, patch["fromDate"])
        assertEquals(JsonNull, patch["toDate"])
        assertEquals(JsonNull, patch["subject"])
        assertEquals("Hello", (patch["textBody"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(JsonNull, patch["htmlBody"])
    }

    @Test
    fun vacationProblemCorrectlyIdentifiesValidationIssues() {
        val inactiveEmpty = Vacation(enabled = false, text = "")
        assertNull(vacationProblem(inactiveEmpty))

        val activeEmpty = Vacation(enabled = true, text = "")
        assertEquals(
            "An auto-reply with no message in it will send an empty email.",
            vacationProblem(activeEmpty),
        )

        val activeBlank = Vacation(enabled = true, text = "   ")
        assertEquals(
            "An auto-reply with no message in it will send an empty email.",
            vacationProblem(activeBlank),
        )

        val validDates = Vacation(
            enabled = true,
            text = "Hello",
            from = "2026-09-20T00:00:00Z",
            to = "2026-09-21T00:00:00Z",
        )
        assertNull(vacationProblem(validDates))

        val equalDates = Vacation(
            enabled = true,
            text = "Hello",
            from = "2026-09-20T00:00:00Z",
            to = "2026-09-20T00:00:00Z",
        )
        assertEquals(
            "The end date has to be after the start date.",
            vacationProblem(equalDates),
        )

        val invertedDates = Vacation(
            enabled = true,
            text = "Hello",
            from = "2026-09-20T00:00:00Z",
            to = "2026-09-19T00:00:00Z",
        )
        assertEquals(
            "The end date has to be after the start date.",
            vacationProblem(invertedDates),
        )

        val unparseableFrom = Vacation(
            enabled = true,
            text = "Hello",
            from = "invalid-date",
            to = "2026-09-21T00:00:00Z",
        )
        assertEquals(
            "That date could not be read.",
            vacationProblem(unparseableFrom),
        )

        val unparseableTo = Vacation(
            enabled = true,
            text = "Hello",
            from = "2026-09-20T00:00:00Z",
            to = "invalid-date",
        )
        assertEquals(
            "That date could not be read.",
            vacationProblem(unparseableTo),
        )

        val onlyFrom = Vacation(
            enabled = true,
            text = "Hello",
            from = "2026-09-20T00:00:00Z",
            to = null,
        )
        assertNull(vacationProblem(onlyFrom))

        val onlyTo = Vacation(
            enabled = true,
            text = "Hello",
            from = null,
            to = "2026-09-21T00:00:00Z",
        )
        assertNull(vacationProblem(onlyTo))

        val validNoDates = Vacation(
            enabled = true,
            text = "Hello",
            from = null,
            to = null,
        )
        assertNull(vacationProblem(validNoDates))
    }
}
