package org.rampart

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertTrue

/**
 * The accounts file is meant to be written by other people and other people's assistants,
 * so the check that matters is that it cannot carry a secret however it is filled in.
 */
class AccountsTest {
    private fun tempFile() = Files.createTempDirectory("rampart").resolve("accounts.json")

    @Test
    fun `a password in the file is read as nothing and written back as nothing`() {
        val path = tempFile()
        path.writeText(
            """
            {"version":1,"accounts":[
              {"name":"Work","server":"mail.example.org","email":"you@example.org",
               "password":"hunter2","refreshToken":"abc"}
            ]}
            """.trimIndent(),
        )
        val read = Accounts.read(path)
        assertEquals(listOf(SavedAccount("Work", "mail.example.org", "you@example.org")), read)

        Accounts.write(read, path)
        val rewritten = path.readText()
        assertFalse(rewritten.contains("hunter2"), "the password survived a rewrite")
        assertFalse(rewritten.contains("password"), "a password field survived a rewrite")
        assertFalse(rewritten.contains("refreshToken"), "a token field survived a rewrite")
    }

    @Test
    fun `the shipped template parses`() {
        val accounts = Accounts.read(java.nio.file.Path.of("docs/accounts-template.json"))
        assertEquals(2, accounts.size)
        assertEquals("mail.example.org", accounts[0].server)
    }

    @Test
    fun `a missing or broken file is no accounts, not a crash`() {
        assertTrue(Accounts.read(tempFile()).isEmpty())
        val broken = tempFile().also { it.writeText("{ this is not json") }
        assertTrue(Accounts.read(broken).isEmpty())
    }

    @Test
    fun `an account with no name falls back to the address, and is not added twice`() {
        val path = tempFile()
        path.writeText("""{"accounts":[{"server":"mail.example.org","email":"you@example.org"}]}""")
        assertEquals("you@example.org", Accounts.read(path).single().name)

        Accounts.remember(SavedAccount("you@example.org", "mail.example.org", "you@example.org"), path)
        assertEquals(1, Accounts.read(path).size)
        Accounts.remember(SavedAccount("Other", "mail.example.net", "you@example.net"), path)
        assertEquals(2, Accounts.read(path).size)
    }

    @Test
    fun `a record with no protocol is a JMAP account`() {
        val path = tempFile()
        path.writeText(
            """{"accounts":[{"name":"Work","server":"mail.example.org","email":"you@example.org"}]}""",
        )
        val read = Accounts.read(path).single()
        assertEquals("jmap", read.protocol, "an old file must not be treated as IMAP")
        assertEquals("", read.sendServer)
    }

    @Test
    fun `protocol and send server round trip`() {
        val path = tempFile()
        val account = SavedAccount(
            "Work",
            "imap.example.org:1993",
            "you@example.org",
            "imap",
            "smtp.example.org:465",
        )
        Accounts.write(listOf(account), path)
        val written = path.readText()
        assertTrue(written.contains("imap"), "protocol was not written")
        assertTrue(written.contains("smtp.example.org:465"), "send server was not written")
        assertEquals(listOf(account), Accounts.read(path))
    }
}

class SignInWalkTest {
    private fun silentLookup(): (String) -> List<Srv> = { emptyList() }

    @Test
    fun `a refused password stops the walk`() {
        assertTrue(
            passwordRejected(JmapError("The server did not accept that email address and password.")),
            "a JMAP 401 must stop the walk",
        )
        assertTrue(
            passwordRejected(jakarta.mail.AuthenticationFailedException("LOGIN failed")),
            "an IMAP refusal must stop the walk",
        )
        assertTrue(
            passwordRejected(RuntimeException(jakarta.mail.AuthenticationFailedException("no"))),
            "a wrapped refusal is still a refusal",
        )
    }

    @Test
    fun `any other failure keeps walking`() {
        assertFalse(
            passwordRejected(JmapError("The server answered HTTP 404 instead of a session.")),
            "a missing JMAP endpoint is the wrong host, not a bad password",
        )
        val refuseClear =
            JmapError("Rampart will not send a password over an unencrypted connection. Use https.")
        assertFalse(
            passwordRejected(refuseClear),
            "refusing to send the password in the clear is not a rejected password",
        )
        assertFalse(passwordRejected(java.net.ConnectException("Connection refused")))
        assertFalse(passwordRejected(java.net.UnknownHostException("nope.example")))
        assertFalse(passwordRejected(IllegalStateException("disk on fire")))
    }

    @Test
    fun `a typed hostname is tried as itself, never as a discovered host`() {
        val published: (String) -> List<Srv> = {
            listOf(Srv("published.example.com", 443, 0, 1))
        }
        val routes = routesToTry("you@example.com", "mail.mine.org", lookup = published)
        assertEquals(Route.Jmap("mail.mine.org"), routes.first())
        assertTrue(routes.any { it is Route.Imap && it.host == "mail.mine.org" })
        assertTrue(
            routes.none { it is Route.Jmap && it.server.contains("published") },
            "discovery must not run once a hostname has been typed",
        )
        assertTrue(routes.none { it is Route.Imap && it.host == "imap.example.com" })
    }

    @Test
    fun `a saved IMAP account is not first tried as JMAP`() {
        val routes = routesToTry("you@example.com", "imap.example.com", protocol = "imap")
        assertEquals(listOf(Route.Imap("imap.example.com")), routes)
    }

    @Test
    fun `an empty server uses discovery, best first`() {
        val routes = routesToTry("you@example.com", "", lookup = silentLookup())
        assertEquals(routesFor("you@example.com", silentLookup()), routes)
        assertTrue(routes.first() is Route.Jmap)
    }

    @Test
    fun `a non-default port lives in the host string`() {
        val account = accountFor(
            Route.Imap("imap.example.org", 1993, "smtp.example.org", 465),
            "you@example.org",
        )
        assertEquals("imap.example.org:1993", account.server)
        assertEquals("smtp.example.org:465", account.sendServer)
        assertEquals("imap", account.protocol)
    }

    @Test
    fun `default IMAP ports are not written into the host`() {
        val account = accountFor(
            Route.Imap("imap.example.org", 993, "smtp.example.org", 587),
            "you@example.org",
        )
        assertEquals("imap.example.org", account.server)
        assertEquals("smtp.example.org", account.sendServer)
    }

    @Test
    fun `exhausting the walk names the address and asks for the server`() {
        val said = noServerFor("you@example.com")
        assertTrue(said.contains("you@example.com"), said)
        assertTrue(said.contains("Server settings"), said)
        assertFalse(said.contains("Exception"), "a Java class name is not an error message")
    }

    @Test
    fun `the status line names the host being tried`() {
        assertEquals("Looking for a server at example.com", lookingFor(Route.Jmap("example.com")))
        assertEquals("Looking for a server at imap.example.com", lookingFor(Route.Imap("imap.example.com")))
    }
}

class JmapTest {
    @Test
    fun `a plain http server is refused rather than leaking the password`() {
        val thrown = kotlin.runCatching { Jmap.connect("http://mail.example.org", "you@example.org", "hunter2") }
        val error = thrown.exceptionOrNull()
        assertTrue(error is JmapError, "expected a refusal, got: $error")
        assertTrue(error.message!!.contains("https"), "the refusal should say what to do instead")
    }
}

/**
 * The property that matters is fail-closed: where the operating system offers nowhere safe
 * to keep a password, Rampart keeps it nowhere at all rather than inventing a place.
 */
class SecretsTest {
    @Test
    fun `with no credential store, nothing is stored and nothing comes back`() {
        if (Secrets.available()) return
        val account = SavedAccount("Work", "mail.example.org", "you@example.org")
        // The refusal has to say why, because a silent one reads as success.
        val reason = Secrets.store(account, "hunter2")
        assertTrue(reason != null && reason.isNotBlank(), "storing failed without saying why")
        assertEquals(null, Secrets.load(account))
    }
}
