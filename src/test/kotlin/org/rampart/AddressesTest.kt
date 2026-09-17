package org.rampart

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddressesTest {
    private val book = listOf(
        Person("dana@example.org", "Dana Whitfield", seen = 12, last = 300),
        Person("jordan@example.org", "Jordan Reyes", seen = 40, last = 100),
        Person("danielle@other.test", "Danielle Okafor", seen = 2, last = 900),
        Person("accounts@hetzner.test", "Hetzner", seen = 3, last = 50),
    )

    @Test
    fun `two letters is the least that offers anything`() {
        assertEquals(emptyList(), suggest("d", book))
        assertTrue(suggest("da", book).isNotEmpty())
    }

    /* What typing three letters means: the one that starts that way, first. */
    @Test
    fun `a match at the start outranks one in the middle`() {
        val names = suggest("jor", book).map { it.email }
        assertEquals("jordan@example.org", names.first())
    }

    /*
     * Dana and Danielle both start with "dan", so they come first, most written to leading.
     * Jordan contains it in the middle and comes last despite being the most written to of
     * the three: matching inside a word is worth offering, and worth offering after.
     */
    @Test
    fun `starts-with beats most written to, and both beat a match in the middle`() {
        assertEquals(
            listOf("dana@example.org", "danielle@other.test", "jordan@example.org"),
            suggest("dan", book).map { it.email },
        )
    }

    @Test
    fun `a name matches as well as an address`() {
        assertEquals(listOf("dana@example.org"), suggest("whitfield", book).map { it.email })
    }

    @Test
    fun `case does not matter`() {
        assertEquals(suggest("DANA", book), suggest("dana", book))
    }

    @Test
    fun `remembering is by address, lowercased`() {
        val once = noted(emptyList(), "Dana@Example.org", "Dana")
        val twice = noted(once, "dana@example.org", "Dana")
        assertEquals(1, twice.size)
        assertEquals("dana@example.org", twice.single().email)
        assertEquals(2, twice.single().seen)
    }

    /* One bare address must not wipe the name off somebody written to for a year. */
    @Test
    fun `a known name survives a sighting with no name`() {
        val known = noted(emptyList(), "dana@example.org", "Dana Whitfield")
        val bare = noted(known, "dana@example.org", "")
        assertEquals("Dana Whitfield", bare.single().name)
    }

    @Test
    fun `the newest sighting wins the timestamp`() {
        val first = noted(emptyList(), "dana@example.org", "Dana", at = 500)
        val older = noted(first, "dana@example.org", "Dana", at = 100)
        assertEquals(500, older.single().last)
    }

    @Test
    fun `things that are not addresses are not kept`() {
        assertEquals(emptyList(), noted(emptyList(), "undisclosed recipients"))
        assertEquals(emptyList(), noted(emptyList(), "Dana Whitfield"))
        assertEquals(emptyList(), noted(emptyList(), "a@b"))
        assertFalse(looksLikeAddress("two@at@example.org"))
        assertTrue(looksLikeAddress("dana@example.org"))
    }

    @Test
    fun `completion applies to the part after the last comma`() {
        assertEquals("dan", typedRecipient("alex@example.org, dan"))
        assertEquals("alex", typedRecipient("alex"))
        assertEquals("", typedRecipient("alex@example.org, "))
    }

    @Test
    fun `choosing replaces only what was being typed`() {
        assertEquals(
            "alex@example.org, dana@example.org, ",
            completeRecipient("alex@example.org, dan", "dana@example.org"),
        )
        assertEquals("dana@example.org, ", completeRecipient("dan", "dana@example.org"))
    }

    @Test
    fun `choosing keeps what came after the caret`() {
        val field = "dan, zoe@example.org"
        assertEquals("dana@example.org, , zoe@example.org", completeRecipient(field, "dana@example.org", caret = 3))
    }

    @Test
    fun `a book survives a round trip through the file`() {
        val path = Files.createTempDirectory("rampart-book").resolve("addresses-test.json")
        try {
            AddressBook.write(book, path)
            assertEquals(book.sortedBy { it.email }, AddressBook.read(path).sortedBy { it.email })
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun `a missing or mangled file is no suggestions, not a crash`() {
        val dir = Files.createTempDirectory("rampart-book")
        assertEquals(emptyList(), AddressBook.read(dir.resolve("nothing.json")))
        val bad = dir.resolve("bad.json")
        bad.toFile().writeText("{ not json at all")
        assertEquals(emptyList(), AddressBook.read(bad))
    }

    /* The account key reaches a filename, so it must not be able to climb out of it. */
    @Test
    fun `an account key cannot escape the config directory`() {
        val path = AddressBook.file("../../etc/passwd")
        assertEquals(Accounts.file().parent, path.parent)
        assertFalse(path.toString().contains(".."))
    }
}
