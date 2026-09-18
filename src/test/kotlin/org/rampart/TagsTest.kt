package org.rampart

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tags are keywords the user chose, so the check that has to bite is that protocol
 * state like $seen never shows up as one, and that a keyword with a quote or a space
 * cannot be stored, because those are how a malformed IMAP command gets sent.
 */
class TagsTest {

    private val reserved = listOf(
        "\$seen", "\$flagged", "\$draft", "\$answered", "\$forwarded",
        "\$junk", "\$notjunk", "\$phishing", "\$deleted", "\$recent",
    )

    @Test
    fun reservedKeywordsAreNeverShown() {
        assertEquals(emptyList(), tagsOf(reserved))
        val shouty = reserved.map { it.uppercase() }
        assertEquals(emptyList(), tagsOf(shouty), "\$SEEN is still protocol state")
        val mixed = tagsOf(reserved + listOf("invoices", "\$SEEN", "\$Flagged"))
        assertEquals(listOf("invoices"), mixed.map { it.keyword })
    }

    @Test
    fun otherDollarKeywordsAreShown() {
        // Other clients store theirs as $label1. Hiding those would make a tag
        // set here look emptier than the same mailbox in Thunderbird.
        val got = tagsOf(listOf("\$label1", "\$important", "\$mdnsent"))
        assertEquals(listOf("\$important", "\$label1", "\$mdnsent"), got.map { it.keyword })
        assertEquals(listOf("Important", "Label1", "Mdnsent"), got.map { it.label })
    }

    @Test
    fun theLabelIsTheKeywordMadeReadable() {
        assertEquals("Invoices", tagsOf(listOf("invoices")).single().label)
        assertEquals("Label1", tagsOf(listOf("\$label1")).single().label)
        assertEquals("Foo bar baz", tagsOf(listOf("foo-bar_baz")).single().label)
        assertEquals("Work iOS", tagsOf(listOf("work-iOS")).single().label)
        assertEquals("Team iOS", tagsOf(listOf("team_iOS")).single().label)
        assertEquals("Already Cap", tagsOf(listOf("Already-Cap")).single().label)
    }

    @Test
    fun orderIsAlphabeticalByLabelIgnoringCase() {
        val got = tagsOf(listOf("zeta", "\$alpha", "Beta", "invoices"))
        assertEquals(listOf("Alpha", "Beta", "Invoices", "Zeta"), got.map { it.label })
        assertEquals(listOf("\$alpha", "Beta", "invoices", "zeta"), got.map { it.keyword })
    }

    @Test
    fun caseDuplicatesKeepTheFirstSpelling() {
        val first = tagsOf(listOf("Invoices", "invoices", "INVOICES")).single()
        assertEquals("Invoices", first.keyword)
        assertEquals("Invoices", first.label)

        val lower = tagsOf(listOf("invoices", "Invoices")).single()
        assertEquals("invoices", lower.keyword)
        assertEquals("Invoices", lower.label)
    }

    @Test
    fun theColourIsStableForAKeyword() {
        val a = tagsOf(listOf("invoices")).single().color
        val b = tagsOf(listOf("invoices")).single().color
        val c = tagsOf(listOf("INVOICES")).single().color
        assertEquals(a, b)
        assertEquals(a, c, "a different spelling of the same keyword must not change colour")
        assertNotEquals(a, tagsOf(listOf("receipts")).single().color)
        assertEquals(8, (0..200).map { tagsOf(listOf("k$it")).single().color }.toSet().size)
    }

    @Test
    fun whiteTextClearsContrastOnEveryColour() {
        val colours = (0..200).map { tagsOf(listOf("k$it")).single().color }.toSet()
        assertEquals(8, colours.size)
        for (colour in colours) {
            assertEquals(0xFFL, (colour ushr 24) and 0xFF, "a tag colour has to be opaque")
            assertTrue(
                contrastWithWhite(colour) >= 4.5,
                "white on ${colour.toString(16)} is ${contrastWithWhite(colour)}",
            )
        }
    }

    @Test
    fun validKeywordReturnsTheTrimmedKeyword() {
        assertEquals("invoices", validKeyword("invoices"))
        assertEquals("invoices", validKeyword("  invoices  "))
        assertEquals("\$label1", validKeyword("\$label1"))
        assertEquals("iOS", validKeyword("iOS"))
        assertEquals("a/b", validKeyword("a/b"))
        assertEquals("a".repeat(64), validKeyword("a".repeat(64)))
        assertEquals("a".repeat(64), validKeyword("  " + "a".repeat(64) + "  "))
    }

    @Test
    fun validKeywordRefusesWhatCannotBeStored() {
        assertNull(validKeyword(""), "empty")
        assertNull(validKeyword("   "), "blank")
        assertNull(validKeyword("\t\n"), "whitespace only")
        for (kw in reserved) {
            assertNull(validKeyword(kw), kw)
            assertNull(validKeyword(kw.uppercase()), kw)
            assertNull(validKeyword("  $kw  "), kw)
        }
        assertNull(validKeyword("foo bar"), "a space is how an IMAP command splits")
        assertNull(validKeyword("foo,bar"), "a comma")
        assertNull(validKeyword("foo\\bar"), "a backslash")
        assertNull(validKeyword("foo\"bar"), "a double quote")
        assertNull(validKeyword("foo'bar"), "a single quote")
        assertNull(validKeyword("foo\u0000bar"), "a NUL")
        assertNull(validKeyword("foo\nbar"), "a control")
        assertNull(validKeyword("foo\u007f"), "DEL is not printable ASCII")
        assertNull(validKeyword("café"), "outside ASCII")
        assertNull(validKeyword("🏷️"), "outside ASCII")
        assertNull(validKeyword("a".repeat(65)), "over 64")
    }

    @Test
    fun aPileOfJunkDoesNotThrow() {
        val junk = buildList {
            add("")
            add("   ")
            add("\u0000")
            add("<<<<>>>>;;;;,,,,")
            add("foo,bar")
            add("foo\"bar")
            add("foo\\bar")
            add("foo'bar")
            add("foo bar")
            add("日本語")
            add("$")
            add("\$")
            add("a".repeat(10_000))
            add(String(CharArray(128) { it.toChar() }))
            addAll(reserved)
            add("invoices")
            add("\$label1")
        }
        val got = tagsOf(junk)
        assertTrue(got.any { it.keyword == "invoices" })
        assertTrue(got.any { it.keyword == "\$label1" })
        assertTrue(got.none { tag -> reserved.any { tag.keyword.equals(it, ignoreCase = true) } })
        for (s in junk) {
            validKeyword(s)
        }
        validKeyword("\u0000".repeat(70))
        tagsOf(emptyList())
        validKeyword("\n\r\t")
    }

    @Test
    fun `a slash makes a tag live inside another one`() {
        val rows = tagRows(listOf("clients/acme", "clients/borde", "invoices"))

        assertEquals(
            listOf("clients" to 0, "clients/acme" to 1, "clients/borde" to 1, "invoices" to 0),
            rows.map { it.keyword to it.depth },
        )
        assertEquals(listOf("Clients", "Clients/Acme", "Clients/Borde", "Invoices"), rows.map { it.label })
    }

    @Test
    fun `a level nobody tagged anything with is a heading, not a tag`() {
        // Tagging one message clients/acme without ever making clients is the normal way
        // this happens, and a tree that skipped the middle would draw Acme at the top.
        val rows = tagRows(listOf("clients/acme"))
        assertEquals(false, rows.first { it.keyword == "clients" }.real)
        assertTrue(rows.first { it.keyword == "clients/acme" }.real)
    }

    @Test
    fun `one branch, however the other client spelled it`() {
        val rows = tagRows(listOf("Clients/Acme", "clients/borde"))
        assertEquals(1, rows.count { it.depth == 0 }, "two spellings of one parent made two branches")
    }

    @Test
    fun `a chosen colour wins, and putting it back is putting it back`() {
        val derived = colorOf("invoices")
        assertEquals(0xFF2F5D96L, colorOf("invoices", mapOf("invoices" to 0xFF2F5D96L)))
        // Stored lowercased, so it applies whichever spelling a message carries.
        assertEquals(0xFF2F5D96L, colorOf("Invoices", mapOf("invoices" to 0xFF2F5D96L)))
        assertEquals(derived, colorOf("invoices", mapOf("other" to 0xFF2F5D96L)))
        assertEquals(derived, colorOf("invoices"))
    }

    @Test
    fun `a chosen colour reaches the chips too`() {
        val tags = tagsOf(setOf("invoices"), mapOf("invoices" to 0xFF7A2E2EL))
        assertEquals(0xFF7A2E2EL, tags.single().color)
    }

    @Test
    fun `a path with a hole in it is not a keyword`() {
        assertNull(validKeyword("/acme"))
        assertNull(validKeyword("clients/"))
        assertNull(validKeyword("clients//acme"))
        assertEquals("clients/acme", validKeyword("clients/acme"))
    }

    @Test
    fun `protocol flags never become a branch`() {
        val rows = tagRows(listOf("\$seen", "\$flagged", "invoices"))
        assertEquals(listOf("invoices"), rows.map { it.keyword })
    }

    private fun contrastWithWhite(argb: Long): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        val luminance = 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
        return 1.05 / (luminance + 0.05)
    }
}
