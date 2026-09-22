package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdentityTest {

    private val mine = listOf(
        "justin@example.com",
        "justin@example.org",
        "justin@another.example",
    )
    private val fallback = mine.first()

    private fun body(to: List<String> = emptyList(), cc: List<String> = emptyList()) =
        Body(html = null, text = null, to = to, cc = cc)

    @Test
    fun `a reply goes out as the address it was sent to`() {
        // The whole point. One account with several identities answering everything as the
        // first of them is a mistake the recipient sees and the sender does not.
        assertEquals(
            "justin@another.example",
            identityFor(body(to = listOf("justin@another.example")), mine, fallback),
        )
    }

    @Test
    fun `a name in front of the address does not stop it matching`() {
        assertEquals(
            "justin@example.org",
            identityFor(body(to = listOf("Justin Willhite <justin@example.org>")), mine, fallback),
        )
    }

    @Test
    fun `case is not part of an address`() {
        assertEquals(
            "justin@example.org",
            identityFor(body(to = listOf("JUSTIN@EXAMPLE.ORG")), mine, fallback),
        )
    }

    @Test
    fun `being written to beats being copied`() {
        assertEquals(
            "justin@example.org",
            identityFor(
                body(to = listOf("justin@example.org"), cc = listOf("justin@another.example")),
                mine,
                fallback,
            ),
        )
    }

    @Test
    fun `a copy still picks the address that was copied`() {
        assertEquals(
            "justin@another.example",
            identityFor(body(to = listOf("someone@else.example"), cc = listOf("justin@another.example")), mine, fallback),
        )
    }

    @Test
    fun `mail that reached you some other way falls back rather than guessing`() {
        // An alias, a mailing list, a forward. Nothing here says which of yours it was for,
        // so the account's own first identity is the honest answer.
        assertEquals(fallback, identityFor(body(to = listOf("list@example.net")), mine, fallback))
        assertEquals(fallback, identityFor(null, mine, fallback))
        assertEquals(fallback, identityFor(body(), emptyList(), fallback))
    }

    @Test
    fun `the first of several of yours on the same message wins, in the order you hold them`() {
        assertEquals(
            "justin@example.com",
            identityFor(body(to = listOf("justin@example.com", "justin@example.org")), mine, fallback),
        )
    }

    @Test
    fun `a sub-addressed copy is still yours`() {
        // Mail to justin+newsletters@ lands in justin@'s mailbox, so it is answered as
        // justin@. A client that cannot see that replies as the wrong identity.
        assertEquals(
            "justin@example.org",
            identityFor(body(to = listOf("justin+newsletters@example.org")), mine, fallback),
        )
        assertEquals(
            "justin@another.example",
            identityFor(body(to = listOf("Justin <justin+bills@another.example>")), mine, fallback),
        )
    }

    @Test
    fun `a plus in the domain, or no address at all, does not confuse it`() {
        assertEquals(fallback, identityFor(body(to = listOf("justin@ex+ample.org")), mine, fallback))
        assertEquals(fallback, identityFor(body(to = listOf("not an address")), mine, fallback))
    }

    @Test
    fun `an alias on a domain you send as is answered as that identity`() {
        // sales@ was never configured. example.org was, so the reply goes out as that
        // identity rather than as whichever address happens to be first.
        assertEquals(
            "justin@example.org",
            identityFor(body(to = listOf("sales@example.org")), mine, fallback, exactOnly = false),
        )
        assertEquals(
            "justin@example.com",
            identityFor(body(to = listOf("billing@example.com")), mine, fallback, exactOnly = false),
        )
    }

    @Test
    fun `exact identities ignore an alias that was never configured`() {
        assertEquals(
            fallback,
            identityFor(body(to = listOf("sales@example.org")), mine, fallback, exactOnly = true),
        )
        assertTrue(
            countsAsMine("sales@example.org", mine, exactOnly = false),
        )
        assertFalse(countsAsMine("sales@example.org", mine, exactOnly = true))
    }

    @Test
    fun `the delimiter is what separates a tag, and plus is not special otherwise`() {
        assertEquals("user@example.com", forMatching("user-news@example.com", '-'))
        assertEquals("user-news@example.com", forMatching("user-news@example.com", '+'))
        assertEquals("user@example.com", forMatching("User <user-news@Example.com>", '-'))
    }
}
