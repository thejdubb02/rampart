package org.rampart

import jakarta.mail.Session
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What Angus Mail actually puts on the wire with the OAuth properties, against a scripted
 * IMAP and SMTP server on the loopback address.
 *
 * Plain text rather than TLS, which the real connections never are, because what is being
 * checked is which mechanism is chosen and what it sends, and that happens the same way
 * inside TLS. The point is to prove the library does the XOAUTH2 exchange from the token
 * alone, and that OAUTHBEARER is only used where XOAUTH2 is not offered.
 */
class OAuthWireTest {

    private val user = "me@gmail.com"
    private val token = "ya29.test-token"

    /** Serves one connection with [reply] and records every line the client sent. */
    private fun server(greeting: String, reply: (String) -> String?): Pair<Int, List<String>> {
        val socket = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val lines = CopyOnWriteArrayList<String>()
        thread(isDaemon = true) {
            socket.use { listening ->
                listening.accept().use { client ->
                    val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val output = client.getOutputStream()
                    output.write(greeting.toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                    while (true) {
                        val line = input.readLine() ?: break
                        lines += line
                        val answer = reply(line) ?: break
                        output.write(answer.toByteArray(Charsets.ISO_8859_1))
                        output.flush()
                    }
                }
            }
        }
        return socket.localPort to lines
    }

    private fun imap(capabilities: String): List<String> {
        val (port, lines) = server("* OK [CAPABILITY IMAP4rev1 $capabilities] ready\r\n") { line ->
            val tag = line.substringBefore(' ')
            val command = line.substringAfter(' ').substringBefore(' ').uppercase()
            when (command) {
                "CAPABILITY" -> "* CAPABILITY IMAP4rev1 $capabilities\r\n$tag OK done\r\n"
                "AUTHENTICATE" -> "$tag OK [CAPABILITY IMAP4rev1] signed in\r\n"
                "LOGOUT" -> "* BYE\r\n$tag OK bye\r\n"
                else -> "$tag OK\r\n"
            }
        }
        val properties = Properties().apply {
            put("mail.imap.connectiontimeout", "5000")
            put("mail.imap.timeout", "5000")
            putAll(oauthMailProperties("imap"))
        }
        val store = Session.getInstance(properties).getStore("imap")
        store.connect("127.0.0.1", port, user, token)
        store.close()
        return lines.filter { it.contains("AUTHENTICATE", ignoreCase = true) || it.contains("LOGIN", ignoreCase = true) }
    }

    private fun smtp(mechanisms: String): List<String> {
        val (port, lines) = server("220 fake ESMTP\r\n") { line ->
            when (line.substringBefore(' ').uppercase()) {
                "EHLO" -> "250-fake\r\n250 AUTH $mechanisms\r\n"
                "AUTH" -> "235 2.7.0 accepted\r\n"
                "QUIT" -> "221 bye\r\n"
                else -> "250 ok\r\n"
            }
        }
        val properties = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "5000")
            put("mail.smtp.timeout", "5000")
            putAll(oauthMailProperties("smtp"))
        }
        val transport = Session.getInstance(properties).getTransport("smtp")
        transport.connect("127.0.0.1", port, user, token)
        transport.close()
        return lines.filter { it.startsWith("AUTH", ignoreCase = true) }
    }

    @Test
    fun `IMAP signs in with the library's XOAUTH2 where it is offered`() {
        val sent = imap("AUTH=XOAUTH2 AUTH=OAUTHBEARER SASL-IR")
        assertEquals(1, sent.size, sent.toString())
        assertTrue(sent.single().endsWith("AUTHENTICATE XOAUTH2 ${xoauth2Base64(user, token)}"), sent.single())
    }

    @Test
    fun `IMAP falls to OAUTHBEARER only where XOAUTH2 is not offered`() {
        val sent = imap("AUTH=OAUTHBEARER SASL-IR")
        assertEquals(1, sent.size, sent.toString())
        val encoded = sent.single().substringAfter("AUTHENTICATE OAUTHBEARER ")
        assertEquals(oauthBearer(user, token, "127.0.0.1"), String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
    }

    @Test
    fun `SMTP sends the token through XOAUTH2 and never as a plain password`() {
        val sent = smtp("PLAIN LOGIN XOAUTH2 OAUTHBEARER")
        assertEquals(listOf("AUTH XOAUTH2 ${xoauth2Base64(user, token)}"), sent)
    }

    @Test
    fun `SMTP uses OAUTHBEARER where it is the only OAuth mechanism`() {
        val sent = smtp("PLAIN LOGIN OAUTHBEARER")
        assertEquals(1, sent.size, sent.toString())
        val encoded = sent.single().substringAfter("AUTH OAUTHBEARER ")
        assertEquals(oauthBearer(user, token, "127.0.0.1"), String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
    }
}
