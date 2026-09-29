package org.rampart

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cheap check that decides whether "Add to calendar" is offered. It runs on every message
 * that opens and costs no model call, so it has to be right often and quiet when unsure.
 */
class DateMentionsTest {
    private fun yes(text: String, subject: String = "") =
        assertTrue(mentionsDateAndTime(subject, text), "should offer the chip for: $text")

    private fun no(text: String, subject: String = "") =
        assertFalse(mentionsDateAndTime(subject, text), "should not offer the chip for: $text")

    @Test
    fun `meetings, appointments and flights are found`() {
        yes("Can we meet on Tuesday at 3pm to go over the numbers?")
        yes("Your appointment is on 3 October 2026 at 14:30 with Dr Patel.")
        yes("Flight BA117 departs London Heathrow 09:15, 12 Nov, arriving New York 12:05.")
        yes("Let's do October 5th, 10:30 am, in the small room.")
        yes("Rendez-vous le 2026-10-03 a 14h30.")
        yes("How about tomorrow at noon?")
        yes("Kick-off call Wed 11:00 to 11:45")
        yes("Booking confirmed for 03/10/2026. Check-in from 15:00.")
    }

    @Test
    fun `the subject counts on its own`() {
        yes("See you there.", subject = "Lunch Friday 12:30")
    }

    @Test
    fun `a date alone or a time alone is not an event`() {
        no("Your invoice for October is attached.")
        no("The report is due on 3 October.")
        no("I will call you back at 3pm.")
        no("The build finished in 12:04 minutes.")
        no("Thanks, all sorted.")
        no("")
    }

    @Test
    fun `numbers that only look like times are ignored`() {
        no("Version 2.10 is out on 3 October.")
        no("Invoice dated 03.10.2026, total 14.30 pounds.")
        no("I sat down in the sun with 3 amazing books on Monday.")
    }

    @Test
    fun `a date and a time far apart are not one mention`() {
        val filler = "Lorem ipsum dolor sit amet. ".repeat(10)
        no("Newsletter for October. $filler Our office opens at 9am.")
    }

    @Test
    fun `the quoted original and its attribution are not looked at`() {
        no(
            """
            Thanks, that sounds good.

            On Mon, 28 Sep 2026 at 10:15, Sam Smith <sam@example.com> wrote:
            > Shall we meet on Thursday at 2pm?
            """.trimIndent(),
        )
        no(
            """
            Please see below.

            ---------- Forwarded message ----------
            From: Sam <sam@example.com>
            Date: Mon, 28 Sep 2026 10:15
            Subject: Hello
            """.trimIndent(),
        )
    }
}
