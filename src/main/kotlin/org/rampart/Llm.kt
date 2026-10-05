package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * One model, reached one way.
 *
 * Chat completions, the shape OpenAI defined and everybody else copied, with the address
 * configurable. OpenRouter is the default because it fronts every model worth having;
 * pointing it at an Ollama on this machine has to work on the same code path, and does,
 * because the request is the same request. One client, one interface: see
 * `docs/assistant.md`.
 *
 * **The packet is built separately from being sent**, and that is not tidiness. The
 * promise this feature makes is that the exact text about to leave can be looked at
 * first, and a promise like that is only worth anything if the thing shown and the thing
 * sent are the same object rather than two renderings of the same intent.
 */
internal data class LlmReply(
    val text: String,
    val tokensIn: Int,
    val tokensOut: Int,
)

internal object Llm {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    /**
     * The exact JSON that will be sent, pretty-printed for a person to read.
     *
     * Deterministic: the same arguments give the same bytes, so what the viewer shows is
     * what [ask] posts.
     */
    fun packet(model: String, system: String, user: String, maxTokens: Int = 700): String =
        packetOf(model, system, listOf(Said("user", user)), maxTokens)

    /**
     * The same thing for a conversation rather than a single question.
     *
     * Roles the rest of Rampart uses are mapped here rather than at every call site: a
     * tool call the model made goes back as its own words, and what the app answered goes
     * back as the user's, because that is the only pair of roles every provider agrees
     * about.
     *
     * The packet is redacted before it is built: secrets that would reach the model are
     * replaced with placeholders so the viewer sees exactly what is removed. This applies
     * to local models too: none of these are things a summary or a draft needs, and
     * redacting in one place is safer than threading the mode through every feature.
     */
    fun packetOf(model: String, system: String, said: List<Said>, maxTokens: Int = 700): String {
        val json = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", Redact.text(system)) })
                said.forEach { line ->
                    add(buildJsonObject {
                        put("role", if (line.role == "assistant" || line.role == "call") "assistant" else "user")
                        put("content", Redact.text(line.text))
                    })
                }
            })
        }
        return readable.encodeToString(JsonElement.serializer(), json)
    }

    private val readable = Json { prettyPrint = true }

    /**
     * Post a packet and read the answer back.
     *
     * @param key null for a local model, which has nothing to authenticate to.
     * @throws LlmError with a sentence a person can act on. Never a stack trace and never
     *   a status code on its own: "the key was refused" is a thing somebody can fix and
     *   "401" is a thing they have to look up.
     */
    fun ask(config: AssistantConfig, key: String?, packet: String): LlmReply {
        try {
            val url = config.baseUrl.trimEnd('/') + "/chat/completions"
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
            if (!key.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $key")
            }
            val request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(packet)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val body = response.body()
                val json = try {
                    Json.parseToJsonElement(body).jsonObject
                } catch (e: Exception) {
                    throw LlmError("The answer did not make sense.")
                }
                val choices = json["choices"]?.jsonArray
                if (choices.isNullOrEmpty()) {
                    throw LlmError("The answer did not make sense.")
                }
                val choice = choices[0].jsonObject
                val message = choice["message"]?.jsonObject
                val content = message?.get("content")?.jsonPrimitive?.contentOrNull
                if (content.isNullOrBlank()) {
                    throw LlmError("The model sent an empty answer back.")
                }
                val usage = json["usage"]?.jsonObject
                val tokensIn = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0
                val tokensOut = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0
                return LlmReply(text = content, tokensIn = tokensIn, tokensOut = tokensOut)
            } else {
                val msg = when (response.statusCode()) {
                    401, 403 -> "The key was refused. Check it in Settings."
                    404 -> "That model name was not found at this address."
                    429 -> "The provider is rate limiting or out of credit. Try again shortly."
                    400 -> "The provider would not take the request."
                    else -> "The provider could not be reached."
                }
                throw LlmError(msg)
            }
        } catch (e: LlmError) {
            throw e
        } catch (e: ConnectException) {
            throw LlmError("Nothing answered at that address.")
        } catch (e: HttpConnectTimeoutException) {
            throw LlmError("Nothing answered at that address.")
        } catch (e: UnknownHostException) {
            throw LlmError("Nothing answered at that address.")
        } catch (e: HttpTimeoutException) {
            throw LlmError("The model took too long to answer.")
        } catch (e: Exception) {
            throw LlmError("Something went wrong talking to the model.")
        }
    }
}

/**
 * Takes out of a packet the things in mail that must never reach a model: sign-in and reset
 * links, one-time codes, card numbers, IBANs, and US and UK national numbers.
 *
 * None of them help a summary or a draft, and each is something a person would be alarmed
 * to find in a provider's log. Card numbers are only taken when they pass the Luhn check,
 * so order and tracking numbers survive. The packet viewer shows the result, so a person can
 * see what was removed before anything is sent.
 */
internal object Redact {
    private val url = Regex("""https?://[^\s<>"']+?(?=[).,\]]*(?:[\s<>"']|$))""")
    private val secretParam = Regex("""[?&](token|code|key|sig|signature|auth|magic|login|otp|reset|access_token)=""", RegexOption.IGNORE_CASE)
    private val randomSegment = Regex("""/(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{24,}(?=[/?#]|$)""")
    private val oneTimeCode = Regex("""(\b(?:verification code|code|otp|passcode|pin)\b[^\d\n]{0,40})(?<!\d)(\d{2,4}[ -]?\d{2,4})(?!\d)""", RegexOption.IGNORE_CASE)
    private val card = Regex("""(?<![\d -])\d(?:[ -]?\d){12,18}(?![\d])""")
    private val iban = Regex("""\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]{1,4}){3,8}\b""")
    private val ssn = Regex("""\b\d{3}-\d{2}-\d{4}\b""")
    private val ni = Regex("""\b[A-CEGHJ-PR-TW-Z]{2} ?\d{2} ?\d{2} ?\d{2} ?[A-D]\b""")

    fun text(s: String): String = s
        .replace(url) { if (secretParam.containsMatchIn(it.value) || randomSegment.containsMatchIn(it.value)) "[link removed]" else it.value }
        .replace(oneTimeCode) { m -> if (m.groupValues[2].count(Char::isDigit) in 4..8) m.groupValues[1] + "[code removed]" else m.value }
        .replace(card) { if (luhn(it.value.filter(Char::isDigit))) "[card number removed]" else it.value }
        .replace(iban) { if (it.value.count { c -> c != ' ' } in 15..34) "[IBAN removed]" else it.value }
        .replace(ssn, "[SSN removed]")
        .replace(ni, "[NI number removed]")

    private fun luhn(digits: String): Boolean = digits.reversed().mapIndexed { i, c ->
        val d = c.digitToInt() * (if (i % 2 == 1) 2 else 1)
        if (d > 9) d - 9 else d
    }.sum() % 10 == 0
}

/** A failure with a sentence in it, which is the only kind worth showing to anybody. */
internal class LlmError(message: String) : Exception(message)
