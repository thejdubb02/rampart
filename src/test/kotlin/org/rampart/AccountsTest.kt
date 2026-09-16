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
