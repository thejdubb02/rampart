package org.rampart

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The client, against a server that is really there.
 *
 * A mocked HTTP layer would prove the code calls the mock. What is worth proving here is
 * that the bytes shown to the reader are the bytes that leave, and that every way this can
 * fail arrives as a sentence rather than as a stack trace in front of somebody's mail.
 */
class LlmTest {
    private fun serving(
        status: Int,
        body: String,
        seen: MutableList<Pair<String, String?>> = mutableListOf(),
        use: (AssistantConfig, MutableList<Pair<String, String?>>) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val sent = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            seen.add(sent to exchange.requestHeaders.getFirst("Authorization"))
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            use(AssistantConfig(baseUrl = "http://127.0.0.1:${server.address.port}/v1"), seen)
        } finally {
            server.stop(0)
        }
    }

    private fun answer(text: String, inTokens: Int = 11, outTokens: Int = 7) =
        """{"choices":[{"message":{"role":"assistant","content":"$text"}}],
           "usage":{"prompt_tokens":$inTokens,"completion_tokens":$outTokens}}"""

    @Test
    fun `the packet shown is the packet sent`() {
        val packet = Llm.packet("some/model", "be brief", "summarise this", maxTokens = 42)
        serving(200, answer("a summary")) { config, seen ->
            Llm.ask(config, key = null, packet = packet)
            assertEquals(packet, seen.single().first, "the body on the wire was not the packet")
        }
    }

    @Test
    fun `the same arguments always build the same packet`() {
        assertEquals(
            Llm.packet("m", "s", "u", 10),
            Llm.packet("m", "s", "u", 10),
        )
    }

    @Test
    fun `the answer and what it cost both come back`() {
        serving(200, answer("one paragraph", inTokens = 1200, outTokens = 90)) { config, _ ->
            val reply = Llm.ask(config, key = null, packet = Llm.packet("m", "s", "u"))
            assertEquals("one paragraph", reply.text)
            assertEquals(1200, reply.tokensIn)
            assertEquals(90, reply.tokensOut)
        }
    }

    @Test
    fun `a local model is not sent a key it has nothing to do with`() {
        serving(200, answer("hi")) { config, seen ->
            Llm.ask(config, key = null, packet = Llm.packet("m", "s", "u"))
            assertEquals(null, seen.single().second)
        }
        serving(200, answer("hi")) { config, seen ->
            Llm.ask(config, key = "   ", packet = Llm.packet("m", "s", "u"))
            assertEquals(null, seen.single().second, "a blank key is no key, not an empty header")
        }
    }

    @Test
    fun `a key that is there is sent as a bearer token`() {
        serving(200, answer("hi")) { config, seen ->
            Llm.ask(config, key = "sk-test", packet = Llm.packet("m", "s", "u"))
            assertEquals("Bearer sk-test", seen.single().second)
        }
    }

    @Test
    fun `a counter the provider left out is nothing, not a crash`() {
        serving(200, """{"choices":[{"message":{"content":"text"}}]}""") { config, _ ->
            val reply = Llm.ask(config, key = null, packet = Llm.packet("m", "s", "u"))
            assertEquals(0, reply.tokensIn)
            assertEquals(0, reply.tokensOut)
        }
    }

    @Test
    fun `every refusal arrives as a sentence`() {
        val expected = mapOf(
            401 to "key", 403 to "key", 404 to "model", 429 to "rate limiting", 400 to "would not take",
            500 to "could not be reached",
        )
        expected.forEach { (status, hint) ->
            serving(status, """{"error":"whatever"}""") { config, _ ->
                val thrown = assertFailsWith<LlmError> {
                    Llm.ask(config, key = "k", packet = Llm.packet("m", "s", "u"))
                }
                val said = thrown.message.orEmpty()
                assertTrue(said.contains(hint), "$status said: $said")
                assertTrue(said.endsWith("."), "$status did not say a sentence: $said")
                assertTrue(!said.contains("$status"), "$status put the number in front of somebody")
            }
        }
    }

    @Test
    fun `an answer with nothing in it is a failure, not an empty summary`() {
        serving(200, answer("")) { config, _ ->
            assertFailsWith<LlmError> { Llm.ask(config, key = null, packet = Llm.packet("m", "s", "u")) }
        }
    }

    @Test
    fun `nonsense back from a provider is a sentence too`() {
        serving(200, "<html>a proxy login page</html>") { config, _ ->
            val thrown = assertFailsWith<LlmError> {
                Llm.ask(config, key = null, packet = Llm.packet("m", "s", "u"))
            }
            assertTrue(thrown.message.orEmpty().endsWith("."))
        }
    }

    @Test
    fun `an address with nothing behind it says so`() {
        val config = AssistantConfig(baseUrl = "http://127.0.0.1:1/v1")
        val thrown = assertFailsWith<LlmError> {
            Llm.ask(config, key = null, packet = Llm.packet("m", "s", "u"))
        }
        assertTrue(thrown.message.orEmpty().endsWith("."), thrown.message.orEmpty())
    }
}
