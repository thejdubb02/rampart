package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Most of this file is about not finding codes.
 *
 * Finding one is easy. A client that offers to copy an order number as a sign-in code is
 * worse than one that offers nothing, because the first time it is wrong nobody trusts it
 * again, so every case below that must come back null matters more than the ones that
 * come back with a code.
 */
class OneTimeCodeTest {

    private fun code(body: String, subject: String = "") = oneTimeCode(subject, body)

    // ---- the shapes these actually arrive in -------------------------------------------

    @Test
    fun `the ordinary ones`() {
        assertEquals("847291", code("Your verification code is 847291. It expires in 10 minutes."))
        assertEquals("4829", code("Your one-time passcode: 4829"))
        assertEquals("558310", code("Enter the code 558310 to finish signing in."))
        assertEquals("9021", code("Use the code 9021 to confirm your email address."))
        assertEquals("380145", code("Your security code is 380145"))
        assertEquals("77213", code("Your login code: 77213"))
        assertEquals("246813", code("Here is your OTP: 246813"))
        assertEquals("5599", code("Your PIN is 5599."))
    }

    @Test
    fun `Google's, which is the one everybody has seen`() {
        // The prefix is not part of what you type, so it is not part of what is copied.
        assertEquals("284917", code("G-284917 is your Google verification code."))
    }

    @Test
    fun `a code printed in groups is copied as one thing`() {
        // Nobody wants to paste a space into a code box.
        assertEquals("284917", code("Your verification code is 284 917"))
        assertEquals("284917", code("Your verification code is 284-917"))
    }

    @Test
    fun `a code with letters in it`() {
        assertEquals("8NUWJD", code("Your confirmation code is 8NUWJD"))
        // Lower case is excluded on purpose, or every six letter word is a code.
        assertNull(code("Your confirmation code is arrived"))
    }

    @Test
    fun `the subject is checked first, because half of them put it there`() {
        // And it is why the code can be read off a notification without opening anything.
        assertEquals("112233", code(subject = "112233 is your verification code", body = "Hello there."))
        assertEquals("445566", code(subject = "Your Slack confirmation code", body = "Your code is 445566"))
    }

    // ---- the half that matters: everything that is not a code --------------------------

    @Test
    fun `a message that never says it is sending a code has none`() {
        // The rule that keeps every receipt and every statement out. Checked before any
        // number is looked at.
        assertNull(code("Your order 847291 has shipped and will arrive Tuesday."))
        assertNull(code("Invoice 4021 for 1,240.00 is attached."))
        assertNull(code("The meeting is at 1430 in room 217."))
        assertNull(code("We have 5000 units in stock."))
    }

    @Test
    fun `a number the message named as something else is not the code`() {
        // These are the dangerous ones: the message does say "code" somewhere.
        assertNull(code("Your verification code has been sent. Order number 558310 for reference."))
        assertNull(code("To confirm, quote reference 4429 when you call."))
        assertNull(code("Your access code will follow. Tracking number 99213 in the meantime."))
    }

    @Test
    fun `a year in a footer is not a code`() {
        assertNull(code("Your verification code is on its way. Copyright 2026 Example Ltd."))
        // But a four digit code that is not a year still is one.
        assertEquals("4829", code("Your verification code is 4829. Copyright 2026 Example Ltd."))
    }

    @Test
    fun `a price is not a code`() {
        assertNull(code("Confirm your payment. Total: 1240.00"))
        assertNull(code("Please confirm. Amount 4829 due on the 3rd."))
    }

    @Test
    fun `a phone number is not a code`() {
        // Digits with more digits attached, which is what makes it not a code.
        assertNull(code("To verify, call us on 5551234567."))
        assertNull(code("Verification failed. Phone 020 7946 0958."))
    }

    @Test
    fun `a placeholder in an example is not somebody's code`() {
        assertNull(code("Your verification code will look like 000000."))
        assertNull(code("Enter the code, for example 111111, into the box."))
    }

    @Test
    fun `nothing at all is nothing, not a crash`() {
        assertNull(code(""))
        assertNull(code("   ", subject = "   "))
        assertNull(code("Your verification code is on its way."))
    }

    @Test
    fun `only the top of the message is searched`() {
        // A code is at the top or it is not the point of the message. Reading further finds
        // the order number in the footer of a receipt that happened to say "code".
        val buried = "Your verification code is coming.\n" + "filler. ".repeat(400) + "558310"
        assertNull(code(buried))
    }

    // ---- a real one, end to end ---------------------------------------------------------

    // ---- the open message, which has both parts of itself -------------------------------

    @Test
    fun `an HTML-only message has its markup taken off first`() {
        val html = "<p>Your verification code is <strong>847291</strong></p>"
        assertEquals("847291", oneTimeCode("Sign in", null, html))
        assertEquals("847291", oneTimeCode("Sign in", "", html))
    }

    @Test
    fun `a code the sender split across tags is still one code`() {
        // Senders do this to style the digits, and it is the case that makes stripping the
        // markup necessary rather than a tidiness: in the raw HTML this is five separate
        // things, none of them four characters long.
        val split = "<p>Your verification code is " +
            "<span>8</span><span>4</span><span>7</span><span>2</span><span>9</span><span>1</span></p>"
        assertNull(oneTimeCode("Sign in", split))
        assertEquals("847291", oneTimeCode("Sign in", null, split))
    }

    @Test
    fun `the text part is preferred when there is one`() {
        assertEquals(
            "112233",
            oneTimeCode("Sign in", "Your verification code is 112233", "<p>ignored</p>"),
        )
        // And neither part being there is not a crash.
        assertNull(oneTimeCode("Sign in", null, null))
    }

    @Test
    fun `the one this was built from`() {
        val slack = oneTimeCode(
            subject = "Slack confirmation code: 8NU-WJD",
            body = "Confirm your email address\n\nHere's your confirmation code:\n\n8NU-WJD\n\n" +
                "Enter it in the browser window where you started signing in.",
        )
        assertEquals("8NUWJD", slack)
    }
}
