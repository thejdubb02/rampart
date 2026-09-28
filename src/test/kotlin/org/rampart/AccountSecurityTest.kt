package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire format for Stalwart's own-credential objects, both ways.
 *
 * Nothing here can be checked against a live server from where the tests run, so the
 * replies below are written from Stalwart 0.16.24's source (`crates/jmap/src/registry/
 * mapping/account.rs`), and `docs/account-security.md` says which line each came from.
 */
class AccountSecurityTest {
    private fun parse(text: String): JsonArray = Json.parseToJsonElement(text).jsonArray
    private fun args(call: JsonArray): JsonObject = call[1].jsonObject

    // ---- what goes out --------------------------------------------------------------

    @Test
    fun `reading the password state asks for the singleton by its wire name`() {
        val call = passwordStateCall("b")
        assertEquals("x:AccountPassword/get", call[0].jsonPrimitive.content)
        assertEquals("b", args(call)["accountId"]!!.jsonPrimitive.content)
        assertEquals("singleton", args(call)["ids"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `with no management account the call leaves accountId out for the server to fill in`() {
        assertFalse(args(appPasswordsCall(null)).containsKey("accountId"))
        assertEquals(JsonNull, args(appPasswordsCall(null))["ids"])
    }

    @Test
    fun `a password change sends the new and current secret, and a code only when there is one`() {
        val plain = args(changePasswordCall("b", "old", "new", null))["update"]!!.jsonObject["singleton"]!!.jsonObject
        assertEquals("new", plain["secret"]!!.jsonPrimitive.content)
        assertEquals("old", plain["currentSecret"]!!.jsonPrimitive.content)
        assertFalse(plain.containsKey("otpAuth/otpCode"))

        val blank = args(changePasswordCall("b", "old", "new", "  "))["update"]!!.jsonObject["singleton"]!!.jsonObject
        assertFalse(blank.containsKey("otpAuth/otpCode"))

        val coded = args(changePasswordCall("b", "old", "new", "123 456"))["update"]!!.jsonObject["singleton"]!!.jsonObject
        assertEquals("123456", coded["otpAuth/otpCode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `turning two-step on sends the url by patch path and never a code for it`() {
        val update = args(enableTwoStepCall("b", "pw", "otpauth://totp/x?secret=AB"))["update"]!!
            .jsonObject["singleton"]!!.jsonObject
        assertEquals("otpauth://totp/x?secret=AB", update["otpAuth/otpUrl"]!!.jsonPrimitive.content)
        assertEquals("pw", update["currentSecret"]!!.jsonPrimitive.content)
        assertFalse(update.containsKey("secret"), "Turning two-step on must not touch the password.")
        assertFalse(update.containsKey("otpAuth"), "A whole otpAuth object would overwrite the stored URL.")
    }

    @Test
    fun `turning two-step off nulls the url and carries the current code`() {
        val update = args(disableTwoStepCall("b", "pw", "654 321"))["update"]!!.jsonObject["singleton"]!!.jsonObject
        assertEquals(JsonNull, update["otpAuth/otpUrl"])
        assertEquals("654321", update["otpAuth/otpCode"]!!.jsonPrimitive.content)
        assertFalse(update.containsKey("secret"))
    }

    @Test
    fun `a new app password asks for inherited permissions and sends no secret of its own`() {
        val made = args(createAppPasswordCall("b", "  Laptop  ", "2026-10-28T12:00:00Z"))["create"]!!
            .jsonObject["new"]!!.jsonObject
        assertEquals("x:AppPassword/set", createAppPasswordCall("b", "x", null)[0].jsonPrimitive.content)
        assertEquals("Laptop", made["description"]!!.jsonPrimitive.content)
        assertEquals("Inherit", made["permissions"]!!.jsonObject["@type"]!!.jsonPrimitive.content)
        assertEquals("2026-10-28T12:00:00Z", made["expiresAt"]!!.jsonPrimitive.content)
        assertFalse(made.containsKey("secret"), "The server makes the secret; a client-chosen one is refused.")

        val forever = args(createAppPasswordCall("b", "x", null))["create"]!!.jsonObject["new"]!!.jsonObject
        assertFalse(forever.containsKey("expiresAt"))
    }

    @Test
    fun `revoking destroys by id`() {
        val call = revokeAppPasswordCall("b", "c")
        assertEquals("x:AppPassword/set", call[0].jsonPrimitive.content)
        assertEquals("c", args(call)["destroy"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `expiry is whole days from now in UTC, and never for Never`() {
        val now = Instant.parse("2026-09-28T10:15:30.123Z")
        assertNull(expiryFor(AppPasswordLife.NEVER, now))
        assertEquals("2026-10-28T10:15:30Z", expiryFor(AppPasswordLife.MONTH, now))
        assertEquals("2027-09-28T10:15:30Z", expiryFor(AppPasswordLife.YEAR, now))
    }

    // ---- what comes back ------------------------------------------------------------

    @Test
    fun `password state reads masks, not values`() {
        val on = parse(
            """["x:AccountPassword/get",{"accountId":"b","list":[{"id":"singleton","secret":"****",
            "otpAuth":{"otpUrl":"****"}}],"notFound":[]},"0"]""",
        )
        assertEquals(PasswordState(hasPassword = true, twoStepOn = true), readPasswordState(on))

        val off = parse(
            """["x:AccountPassword/get",{"list":[{"id":"singleton","secret":"****","otpAuth":{}}],"notFound":[]},"0"]""",
        )
        assertEquals(PasswordState(hasPassword = true, twoStepOn = false), readPasswordState(off))
    }

    @Test
    fun `an account with no password credential comes back as notFound`() {
        val none = parse("""["x:AccountPassword/get",{"list":[],"notFound":["singleton"]},"0"]""")
        assertEquals(PasswordState(hasPassword = false, twoStepOn = false), readPasswordState(none))
    }

    @Test
    fun `app passwords are listed oldest first with dates only`() {
        val list = parse(
            """["x:AppPassword/get",{"list":[
              {"id":"d","description":"Phone","secret":"****","createdAt":"2026-09-20T08:00:00Z","expiresAt":null,
               "permissions":{"@type":"Inherit"},"allowedIps":{}},
              {"id":"c","description":"Laptop","secret":"****","createdAt":"2026-09-01T08:00:00Z",
               "expiresAt":"2027-09-01T08:00:00Z","permissions":{"@type":"Inherit"}},
              {"description":"no id, so not a row"}
            ],"notFound":[]},"0"]""",
        )
        assertEquals(
            listOf(
                AppPasswordInfo("c", "Laptop", "2026-09-01", "2027-09-01"),
                AppPasswordInfo("d", "Phone", "2026-09-20", ""),
            ),
            readAppPasswords(list),
        )
    }

    @Test
    fun `a created app password hands back its id and the one-time secret`() {
        val made = parse(
            """["x:AppPassword/set",{"created":{"new":{"id":"e","secret":"appAbCdEf123"}}},"0"]""",
        )
        assertEquals(NewAppPassword("e", "appAbCdEf123"), readCreatedAppPassword(made))
    }

    @Test
    fun `a refused app password says why in a sentence`() {
        val refused = parse(
            """["x:AppPassword/set",{"notCreated":{"new":{"type":"overQuota",
            "description":"You have exceeded your quota of 5 app passwords."}}},"0"]""",
        )
        val e = assertFailsWith<JmapError> { readCreatedAppPassword(refused) }
        assertEquals("The app password was not made: you have exceeded your quota of 5 app passwords.", e.message)
    }

    @Test
    fun `a masked secret on create is treated as no secret at all`() {
        val masked = parse("""["x:AppPassword/set",{"created":{"new":{"id":"e","secret":"****"}}},"0"]""")
        assertFailsWith<JmapError> { readCreatedAppPassword(masked) }
    }

    @Test
    fun `a password update is null when it worked and a sentence when it did not`() {
        val ok = parse("""["x:AccountPassword/set",{"updated":{"singleton":null}},"0"]""")
        assertNull(readPasswordUpdate(ok, "The password was not changed"))

        val wrong = parse(
            """["x:AccountPassword/set",{"notUpdated":{"singleton":{"type":"forbidden",
            "description":"Current secret is incorrect."}}},"0"]""",
        )
        assertEquals(
            "The password was not changed: the current password is not right.",
            readPasswordUpdate(wrong, "The password was not changed"),
        )

        val weak = parse(
            """["x:AccountPassword/set",{"notUpdated":{"singleton":{"type":"invalidProperties",
            "properties":["secret"],"description":"Password is too short."}}},"0"]""",
        )
        assertEquals(
            "The password was not changed: password is too short.",
            readPasswordUpdate(weak, "The password was not changed"),
        )
    }

    @Test
    fun `the server's own wording is reworded where it names its internals`() {
        fun say(description: String?, type: String = "forbidden"): String {
            val body = if (description == null) """{"type":"$type"}""" else """{"type":"$type","description":"$description"}"""
            return refusalSentence(Json.parseToJsonElement(body).jsonObject, "No")
        }
        assertEquals(
            "No: the server wants the six digit code from your authenticator app as well.",
            say("Current OTP code is required to change the password or OTP auth."),
        )
        assertEquals("No: the server wants your current password.", say("Current secret must be provided to change the password or OTP auth."))
        assertEquals(
            "No: this account's password is kept in another directory, so it has to be changed there.",
            say("Operation not allowed."),
        )
        assertEquals("No: the server does not allow it for this account.", say(null))
        assertEquals("No: the account already has as many as the server allows.", say(null, "overQuota"))
        assertEquals("No: the server said somethingNew.", say(null, "somethingNew"))
    }

    @Test
    fun `revoking reads destroyed and notDestroyed`() {
        assertNull(readRevoke(parse("""["x:AppPassword/set",{"destroyed":["c"]},"0"]"""), "c"))
        val gone = readRevoke(parse("""["x:AppPassword/set",{"notDestroyed":{"c":{"type":"notFound"}}},"0"]"""), "c")
        assertEquals("The app password was not revoked: it no longer exists on the server.", gone)
        assertNotNull(readRevoke(parse("""["x:AppPassword/set",{},"0"]"""), "c"))
    }

    @Test
    fun `method-level failures become sentences that say what to do`() {
        assertEquals(
            "Could not list app passwords: this server does not let the account manage that itself. An administrator can allow it.",
            securityFailure(JmapError("The server refused the request: forbidden"), "Could not list app passwords"),
        )
        assertEquals(
            "No: the server no longer accepts the password Rampart signed in with. Sign in to this account again.",
            securityFailure(JmapError("The server answered HTTP 401 to x:AppPassword/get."), "No"),
        )
        assertEquals("No: timed out.", securityFailure(JmapError("Timed out."), "No"))
    }

    // ---- when the page is offered ---------------------------------------------------

    @Test
    fun `the page is for Stalwart over JMAP only, and says why otherwise`() {
        assertNull(securityUnavailable("jmap", "b"))
        val imap = securityUnavailable("imap", null)
        assertNotNull(imap)
        assertTrue("IMAP" in imap)
        val other = securityUnavailable("jmap", null)
        assertNotNull(other)
        assertTrue("not Stalwart" in other)
        assertNotNull(securityUnavailable(null, null))
        listOfNotNull(imap, other, securityUnavailable(null, null)).forEach {
            assertEquals(1, it.count { c -> c == '.' }, "One sentence: $it")
        }
    }

    @Test
    fun `a new password is checked before it is sent`() {
        assertEquals("Type your current password.", newPasswordProblem("", "a", "a"))
        assertEquals("Type a new password.", newPasswordProblem("old", "", ""))
        assertEquals("The two new passwords are not the same.", newPasswordProblem("old", "a", "b"))
        assertEquals("The new password is the same as the current one.", newPasswordProblem("same", "same", "same"))
        assertNull(newPasswordProblem("old", "new", "new"))
    }
}
