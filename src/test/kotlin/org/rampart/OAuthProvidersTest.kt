package org.rampart

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which provider an address belongs to, and whether this build can sign in to it. */
class OAuthProvidersTest {

    @Test
    fun `the well-known domains are recognised with no lookup`() {
        listOf("someone@gmail.com", "Someone@GoogleMail.com").forEach {
            assertEquals(OAuthProviders.GOOGLE, providerForDomain(it), it)
        }
        listOf("a@outlook.com", "a@hotmail.com", "a@live.com", "a@msn.com").forEach {
            assertEquals(OAuthProviders.MICROSOFT, providerForDomain(it), it)
        }
        assertNull(providerForDomain("someone@example.org"))
        assertNull(providerForDomain("gmail.com"))
        assertNull(providerForDomain(""))
        // A lookalike is not the provider.
        assertNull(providerForDomain("someone@notgmail.com"))
    }

    @Test
    fun `a custom domain is recognised from its MX records`() {
        val google = mapOf("example.org" to listOf("aspmx.l.google.com.", "alt1.aspmx.l.google.com."))
        assertEquals(OAuthProviders.GOOGLE, detectProvider("me@example.org") { google[it].orEmpty() })
        val workspace = mapOf("example.net" to listOf("smtp.google.com"))
        assertEquals(OAuthProviders.GOOGLE, detectProvider("me@example.net") { workspace[it].orEmpty() })
        val microsoft = mapOf("contoso.example" to listOf("contoso-example.mail.protection.outlook.com"))
        assertEquals(OAuthProviders.MICROSOFT, detectProvider("me@contoso.example") { microsoft[it].orEmpty() })
        val selfHosted = mapOf("example.com" to listOf("mail.example.com"))
        assertNull(detectProvider("me@example.com") { selfHosted[it].orEmpty() })
        // A host that merely ends in the same letters is not Google's.
        assertNull(providerForMx(listOf("mail.notgoogle.com")))
        // The known domains never need the lookup.
        assertEquals(OAuthProviders.GOOGLE, detectProvider("me@gmail.com") { error("no lookup expected") })
    }

    @Test
    fun `an MX record is read as the resolver prints it`() {
        assertEquals(10 to "aspmx.l.google.com", parseMx("10 aspmx.l.google.com."))
        assertNull(parseMx("10"))
        assertNull(parseMx(""))
    }

    @Test
    fun `the presets point at the providers' own mail servers over TLS`() {
        val google = OAuthAccounts.accountFor(OAuthProviders.GOOGLE, "me@gmail.com")
        assertEquals("imap.gmail.com", google.server)
        assertEquals("smtp.gmail.com:465", google.sendServer)
        assertEquals("imap", google.protocol)
        assertEquals("google", google.oauth)
        val work = OAuthAccounts.accountFor(OAuthProviders.MICROSOFT, "me@contoso.example")
        assertEquals("outlook.office365.com", work.server)
        assertEquals("smtp.office365.com", work.sendServer)
        val personal = OAuthAccounts.accountFor(OAuthProviders.MICROSOFT, "me@outlook.com")
        assertEquals("smtp-mail.outlook.com", personal.sendServer)
        OAuthProviders.all.forEach {
            assertTrue(it.authorizeUrl.startsWith("https://"))
            assertTrue(it.tokenUrl.startsWith("https://"))
        }
        assertEquals(OAuthProviders.GOOGLE, OAuthProviders.byId("google"))
        assertNull(OAuthProviders.byId("yahoo"))
    }

    @Test
    fun `a build with no client ID says so in one sentence`() {
        val empty = """{"google":{"clientId":""},"microsoft":{"clientId":"","tenant":"common"}}"""
        assertFalse(clientFrom(OAuthProviders.GOOGLE, empty, "").configured)
        assertFalse(clientFrom(OAuthProviders.MICROSOFT, "", "").configured)
        assertFalse(OAuthClient("REPLACE_WITH_YOUR_CLIENT_ID").configured)
        assertEquals("Gmail sign-in is not configured in this build.", notConfiguredSentence(OAuthProviders.GOOGLE))
        assertEquals(
            "Outlook and Microsoft 365 sign-in is not configured in this build.",
            notConfiguredSentence(OAuthProviders.MICROSOFT),
        )
    }

    @Test
    fun `the shipped file has no client ID in it`() {
        // Placeholders only: a real ID is added by whoever builds a release, not committed here.
        val shipped = OAuthClients.shipped()
        assertTrue(shipped.isNotBlank(), "oauth-clients.json should be on the classpath")
        OAuthProviders.all.forEach { assertFalse(clientFrom(it, shipped, "").configured, it.id) }
    }

    @Test
    fun `a person's own client ID overrides the build's, field by field`() {
        val shipped = """{"google":{"clientId":"build.apps","clientSecret":"build-secret"},"microsoft":{"clientId":"build-ms","tenant":"common"}}"""
        assertEquals(OAuthClient("build.apps", "build-secret"), clientFrom(OAuthProviders.GOOGLE, shipped, ""))

        val mine = """{"google":{"clientId":"mine.apps","clientSecret":"mine-secret"}}"""
        assertEquals(OAuthClient("mine.apps", "mine-secret"), clientFrom(OAuthProviders.GOOGLE, shipped, mine))
        // An own ID never borrows the build's secret.
        assertEquals(OAuthClient("mine.apps", ""), clientFrom(OAuthProviders.GOOGLE, shipped, """{"google":{"clientId":"mine.apps"}}"""))
        // Only the tenant: the build's ID stays.
        assertEquals(
            OAuthClient("build-ms", "", "consumers"),
            clientFrom(OAuthProviders.MICROSOFT, shipped, """{"microsoft":{"tenant":"consumers"}}"""),
        )
        // A tenant that could reshape the URL is not taken.
        assertEquals("common", clientFrom(OAuthProviders.MICROSOFT, shipped, """{"microsoft":{"tenant":"evil.example/x?"}}""").tenant)
        // The redirect host can be switched between the two loopback names and nothing else.
        assertEquals("127.0.0.1", clientFrom(OAuthProviders.MICROSOFT, shipped, """{"microsoft":{"redirectHost":"127.0.0.1"}}""").redirectHost)
        assertEquals("", clientFrom(OAuthProviders.MICROSOFT, shipped, """{"microsoft":{"redirectHost":"mail.example.com"}}""").redirectHost)
        // A broken override file is no override.
        assertEquals(OAuthClient("build.apps", "build-secret"), clientFrom(OAuthProviders.GOOGLE, shipped, "{not json"))
    }

    @Test
    fun `saving an override keeps the other provider's`() {
        val file = Files.createTempDirectory("rampart-oauth").resolve("oauth-clients.json")
        file.writeText("""{"microsoft":{"clientId":"ms-own","tenant":"organizations"}}""")
        OAuthClients.saveOverride(OAuthProviders.GOOGLE, OAuthClient("g-own.apps", "g-secret"), file)
        val text = file.readText()
        assertEquals(OAuthClient("g-own.apps", "g-secret"), clientFrom(OAuthProviders.GOOGLE, "", text))
        assertEquals(OAuthClient("ms-own", "", "organizations"), clientFrom(OAuthProviders.MICROSOFT, "", text))
    }

    @Test
    fun `an OAuth account is remembered with its provider, replacing a password entry`() {
        val file = Files.createTempDirectory("rampart-accounts").resolve("accounts.json")
        Accounts.write(listOf(SavedAccount("me@gmail.com", "imap.gmail.com", "me@gmail.com", "imap", "smtp.gmail.com:465")), file)
        val account = OAuthAccounts.accountFor(OAuthProviders.GOOGLE, "me@gmail.com")
        OAuthAccounts.remember(account, file)
        assertEquals(listOf(account), Accounts.read(file))
        assertTrue("\"oauth\": \"google\"" in file.readText())
        // A password account's entry is written exactly as before, with no provider field.
        Accounts.write(listOf(SavedAccount("w", "mail.example.org", "me@example.org")), file)
        assertFalse("oauth" in file.readText())
        assertEquals("", Accounts.read(file).single().oauth)
    }

    @Test
    fun `the token store is its own entry, never the password's`() {
        val account = OAuthAccounts.accountFor(OAuthProviders.GOOGLE, "me@gmail.com")
        val tokens = Secrets.oauthTokenAccount(account)
        assertTrue(tokens.email.startsWith("oauth:"))
        assertFalse(tokens == account)
    }
}
