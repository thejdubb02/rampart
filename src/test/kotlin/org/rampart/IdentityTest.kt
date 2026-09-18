package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
