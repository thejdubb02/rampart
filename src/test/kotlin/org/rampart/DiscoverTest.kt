package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoverTest {

    /** The resolver, replaced by a map, so the ordering can be checked without a network. */
    private fun resolver(vararg records: Pair<String, List<Srv>>): (String) -> List<Srv> {
        val published = records.toMap()
        return { name -> published[name].orEmpty() }
    }

    @Test
    fun `an address with no domain is not guessed at`() {
        assertEquals(emptyList<Route>(), routesFor("nobody", resolver()))
        assertEquals(emptyList<Route>(), routesFor("", resolver()))
        // A bare hostname with no dot is a local name, not a mail domain.
        assertEquals(emptyList<Route>(), routesFor("someone@localhost", resolver()))
    }

    @Test
    fun `a domain publishing nothing still gets the conventional guesses`() {
        val routes = routesFor("someone@example.com", resolver())
        assertEquals(Route.Jmap("example.com"), routes.first())
        assertTrue(Route.Imap("imap.example.com", 993, "smtp.example.com", 587) in routes)
        assertTrue(Route.Imap("mail.example.com", 993, "smtp.example.com", 587) in routes)
    }

    @Test
    fun `JMAP is tried before IMAP even when both are published`() {
        val routes = routesFor(
            "someone@example.com",
            resolver(
                "_jmap._tcp.example.com" to listOf(Srv("jmap.example.com", 443, 0, 1)),
                "_imaps._tcp.example.com" to listOf(Srv("imap.example.com", 993, 0, 1)),
            ),
        )
        assertEquals(Route.Jmap("jmap.example.com"), routes.first())
        assertTrue(routes.indexOfFirst { it is Route.Jmap } < routes.indexOfFirst { it is Route.Imap })
    }

    @Test
    fun `a published submission server is used for sending, not the reading host`() {
        val routes = routesFor(
            "someone@example.com",
            resolver(
                "_imaps._tcp.example.com" to listOf(Srv("in.mailhost.net", 993, 0, 1)),
                "_submission._tcp.example.com" to listOf(Srv("out.mailhost.net", 587, 0, 1)),
            ),
        )
        assertEquals(Route.Imap("in.mailhost.net", 993, "out.mailhost.net", 587), routes.first { it is Route.Imap })
    }

    @Test
    fun `a submission record on a non-default port keeps it`() {
        val routes = routesFor(
            "someone@example.com",
            resolver("_submission._tcp.example.com" to listOf(Srv("out.example.com", 465, 0, 1))),
        )
        val imap = routes.filterIsInstance<Route.Imap>().first()
        assertEquals(465, imap.sendPort)
        assertEquals("out.example.com", imap.sendHost)
    }

    @Test
    fun `a JMAP record on 443 does not carry the port into the URL`() {
        assertEquals("jmap.example.com", Srv("jmap.example.com", 443, 0, 0).url())
        assertEquals("jmap.example.com:8443", Srv("jmap.example.com", 8443, 0, 0).url())
    }

    @Test
    fun `lower priority wins, and weight breaks a tie`() {
        val records = listOf(
            "20 10 993 slow.example.com.",
            "10 1 993 second.example.com.",
            "10 50 993 first.example.com.",
        ).mapNotNull(::parseSrv).sortedWith(compareBy({ it.priority }, { -it.weight }))
        assertEquals(listOf("first.example.com", "second.example.com", "slow.example.com"), records.map { it.host })
    }

    @Test
    fun `a target of a single dot means the service is not offered`() {
        // RFC 2782. Read as a hostname it becomes an empty name that is then guessed at,
        // which is the opposite of what the domain went to the trouble of saying.
        assertNull(parseSrv("0 0 0 ."))
        assertNull(parseSrv("0 0 587 ."))
    }

    @Test
    fun `a record that is not four fields is ignored rather than half read`() {
        assertNull(parseSrv(""))
        assertNull(parseSrv("10 0 993"))
        assertNull(parseSrv("10 0 notaport imap.example.com."))
    }

    @Test
    fun `the trailing dot of a DNS name is not part of the hostname`() {
        assertEquals("imap.example.com", parseSrv("10 5 993 imap.example.com.")?.host)
    }

    @Test
    fun `the same route is never tried twice`() {
        // A domain publishing its own name is the ordinary case for a small self-hosted
        // server, and trying it three times is three sign-in attempts against one server.
        val routes = routesFor(
            "someone@example.com",
            resolver("_imaps._tcp.example.com" to listOf(Srv("example.com", 993, 0, 1))),
        )
        assertEquals(routes.size, routes.distinct().size)
    }

    @Test
    fun `implicit TLS submission wins over the STARTTLS label`() {
        // Not a theoretical preference. Our own server has 465 open and 587 closed, so a
        // client reading only the older label finds where to read, learns nothing about
        // where to send and fails on a port nothing is listening on.
        val routes = routesFor(
            "someone@example.com",
            resolver(
                "_submissions._tcp.example.com" to listOf(Srv("out.example.com", 465, 0, 1)),
                "_submission._tcp.example.com" to listOf(Srv("old.example.com", 587, 0, 1)),
            ),
        )
        val imap = routes.filterIsInstance<Route.Imap>().first()
        assertEquals("out.example.com", imap.sendHost)
        assertEquals(465, imap.sendPort)
    }

    @Test
    fun `the older submission label is still read when it is the only one`() {
        val routes = routesFor(
            "someone@example.com",
            resolver("_submission._tcp.example.com" to listOf(Srv("old.example.com", 587, 0, 1))),
        )
        val imap = routes.filterIsInstance<Route.Imap>().first()
        assertEquals("old.example.com", imap.sendHost)
        assertEquals(587, imap.sendPort)
    }

    @Test
    fun `every guessed route carries the published send server, not just the first`() {
        // The last fallback dropped the port and sent to 587 on a server that publishes 465.
        val routes = routesFor(
            "someone@example.com",
            resolver("_submissions._tcp.example.com" to listOf(Srv("out.example.com", 465, 0, 1))),
        )
        routes.filterIsInstance<Route.Imap>().forEach {
            assertEquals(465, it.sendPort, "sendPort on ${it.host}")
            assertEquals("out.example.com", it.sendHost, "sendHost on ${it.host}")
        }
    }
}
