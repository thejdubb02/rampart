package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An unsubscribe target is chosen by the sender, so the check that has to bite is that
 * a javascript: or file: entry cannot become the thing we open, and that one-click
 * never POSTs the identifying token over plain http.
 */
class UnsubscribeTest {

    @Test
    fun aTypicalHeaderGivesALinkAndAMailto() {
        val got = unsubscribeFrom(
            "<https://x.test/u?id=9>, <mailto:leave@x.test?subject=unsubscribe>",
            null,
        )
        assertEquals(
            Unsubscribe(
                url = "https://x.test/u?id=9",
                mailto = "leave@x.test",
                mailtoSubject = "unsubscribe",
                oneClick = false,
            ),
            got,
        )
    }

    @Test
    fun foldedWhitespaceBetweenEntriesStillParses() {
        val folded = "<https://x.test/u?id=9>,\r\n\t<mailto:leave@x.test?subject=unsubscribe>"
        val got = unsubscribeFrom(folded, null)!!
        assertEquals("https://x.test/u?id=9", got.url)
        assertEquals("leave@x.test", got.mailto)
        assertEquals("unsubscribe", got.mailtoSubject)
    }

    @Test
    fun httpsIsPreferredOverHttp() {
        val got = unsubscribeFrom(
            "<http://x.test/u>, <https://x.test/secure>",
            null,
        )!!
        assertEquals("https://x.test/secure", got.url, "the token must not ride the cleartext link when a tls one exists")
    }

    @Test
    fun plainHttpIsStillAUrl() {
        val got = unsubscribeFrom("<http://x.test/u>", null)!!
        assertEquals("http://x.test/u", got.url)
        assertFalse(got.oneClick)
    }

    @Test
    fun aPercentEncodedSubjectIsDecoded() {
        val got = unsubscribeFrom(
            "<mailto:leave@x.test?subject=un%20subscribe>",
            null,
        )!!
        assertEquals("leave@x.test", got.mailto)
        assertEquals("un subscribe", got.mailtoSubject)
        assertNull(got.url)
    }

    @Test
    fun mailtoBodyAndOtherParametersAreIgnored() {
        val got = unsubscribeFrom(
            "<mailto:leave@x.test?body=please%20go&Subject=unsubscribe&cc=boss@x.test>",
            null,
        )!!
        assertEquals("leave@x.test", got.mailto)
        assertEquals("unsubscribe", got.mailtoSubject)
        assertFalse("please" in (got.mailtoSubject ?: ""), "body is not a subject")
        assertEquals(null, got.url)
    }

    @Test
    fun oneClickNeedsThePostHeaderAndHttps() {
        val header = "<https://x.test/u?id=9>, <mailto:leave@x.test?subject=unsubscribe>"
        val with = unsubscribeFrom(header, "List-Unsubscribe=One-Click")!!
        assertTrue(with.oneClick)
        assertEquals("https://x.test/u?id=9", with.url)

        val without = unsubscribeFrom(header, null)!!
        assertFalse(without.oneClick, "no Post header means the reader has to open the link")

        val mailtoOnly = unsubscribeFrom("<mailto:leave@x.test>", "List-Unsubscribe=One-Click")!!
        assertFalse(mailtoOnly.oneClick, "one-click is a POST to the https URL, not a mail")
    }

    @Test
    fun oneClickOverPlainHttpIsNotOneClick() {
        val got = unsubscribeFrom(
            "<http://x.test/u?id=9>",
            "List-Unsubscribe=One-Click",
        )!!
        assertEquals("http://x.test/u?id=9", got.url)
        assertFalse(got.oneClick, "POSTing the identifying token in the clear is not one-click")
    }

    @Test
    fun oneClickMatchingIsCaseAndWhitespaceTolerant() {
        val header = "<https://x.test/u>"
        val folded = unsubscribeFrom(header, "  List-Unsubscribe = One-Click  \r\n")!!
        assertTrue(folded.oneClick)
        val shouty = unsubscribeFrom(header, "LIST-UNSUBSCRIBE=ONE-CLICK")!!
        assertTrue(shouty.oneClick)
        val other = unsubscribeFrom(header, "click here")!!
        assertFalse(other.oneClick)
    }

    @Test
    fun nothingUsableReturnsNull() {
        assertNull(unsubscribeFrom(null, null), "no header")
        assertNull(unsubscribeFrom("", null), "empty header")
        assertNull(unsubscribeFrom("   \r\n", null), "whitespace is not an entry")
        assertNull(unsubscribeFrom("<ftp://x.test/u>", null), "ftp is not a url we will open")
        assertNull(unsubscribeFrom("<javascript:alert(1)>", null), "javascript is dropped entirely")
        assertNull(unsubscribeFrom("<unsubscribe>", null), "a bare word with no scheme")
        assertNull(unsubscribeFrom("https://x.test/u", null), "an unbracketed value is not the header format")
    }

    @Test
    fun hostileSchemesAreDroppedEvenBesideAGoodUrl() {
        val got = unsubscribeFrom(
            "<javascript:alert(1)>, <file:///etc/passwd>, <data:text/html,hi>, <https://x.test/u>",
            null,
        )!!
        assertEquals("https://x.test/u", got.url)
        assertNull(got.mailto)
        assertFalse(got.oneClick)
    }

    @Test
    fun quotesWhitespaceAndBracketsInsideAUrlAreDropped() {
        assertNull(
            unsubscribeFrom("<https://x.test/u?q=\"id\">", null),
            "quotes inside a url are not stripped, the entry is dropped",
        )
        assertNull(
            unsubscribeFrom("<https://x.test/u path>", null),
            "whitespace inside a url is not stripped, the entry is dropped",
        )
        assertNull(
            unsubscribeFrom("<https://x.test/u?x=<id>>", null),
            "a second angle bracket inside a url is not stripped, the entry is dropped",
        )
        val kept = unsubscribeFrom(
            "<https://x.test/\"q\">, <https://x.test/good>",
            null,
        )!!
        assertEquals("https://x.test/good", kept.url, "the bad entry must not poison the good one")
    }

    @Test
    fun aCommaInsideAUrlIsNotASeparator() {
        val got = unsubscribeFrom("<https://x.test/u?ids=1,2,3>", null)!!
        assertEquals("https://x.test/u?ids=1,2,3", got.url)
    }

    @Test
    fun anUnclosedBracketIsAPartialResultNotAThrow() {
        val partial = unsubscribeFrom("<https://x.test/u>, <mailto:oops", null)!!
        assertEquals("https://x.test/u", partial.url)
        assertNull(partial.mailto, "an unclosed mailto is not half-read")
        assertNull(unsubscribeFrom("<https://x.test/u", null))
    }

    @Test
    fun aPileOfJunkDoesNotThrow() {
        val junk = buildString {
            append("<<<<>>>>;;;;,,,,\n\r\t")
            append('\u0000')
            append("<javascript:alert(1)>")
            append("<data:text/html,hi>")
            append("<file:///etc/passwd>")
            append("not a url, ftp://x.test, //x.test")
            append("<http://>")
            append("<mailto:>")
            append("<mailto:?subject=x>")
            append("<https://x.test/\"q\">")
            append("<https://x.test/a b>")
            append("<%00https://x.test>")
            append("<https://x.test/\u0000>")
            append("List-Unsubscribe: yes")
            repeat(80) { append('<') }
            append("%zz% %")
        }
        val result = unsubscribeFrom(junk, "\u0000 List-Unsubscribe = ???")
        assertNull(result, "junk must not throw, and this pile has nothing usable")
    }
}
