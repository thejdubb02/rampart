package org.rampart

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Asking the companion server what has been fetched.
 *
 * One call, one question, and the answer is written into the local store. Rampart never
 * tells the server anything: it does not register a send, it does not name a recipient, it
 * does not say which ids it is interested in. The server holds a list of fetches and hands
 * over the ones since a moment; matching them to messages happens here, where the mapping
 * lives.
 */
internal object TrackingClient {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /**
     * Everything fetched since [since].
     *
     * Throws on anything that is not a clean answer, and the caller decides whether that is
     * worth telling somebody about. A tracking server being down is not a reason to
     * interrupt whoever is reading their mail.
     */
    fun since(base: String, token: String, since: Instant): List<Fetch> {
        val url = base.trim().trimEnd('/') + "/opens?since=" + since.toEpochMilli()
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer $token")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() == 401) {
            throw TrackingError("The tracking server did not accept the token.")
        }
        if (response.statusCode() != 200) {
            throw TrackingError("The tracking server answered ${response.statusCode()}.")
        }
        return Json.parseToJsonElement(response.body()).jsonObject["fetches"]?.jsonArray.orEmpty().map {
            val o = it.jsonObject
            Fetch(
                id = o["id"]?.jsonPrimitive?.content.orEmpty(),
                at = Instant.ofEpochMilli(o["at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L),
                userAgent = o["userAgent"]?.jsonPrimitive?.content.orEmpty(),
                network = o["network"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }

    /**
     * Whether the server is there and the token is right, for the Test button in settings.
     *
     * Returns the problem in words, or null when it worked. A setting that silently does
     * not work is the worst version of this: every message goes out tracked and nothing is
     * ever recorded.
     */
    fun check(base: String, token: String): String? {
        trackingProblem(base)?.let { return it }
        if (token.isBlank()) return "Give Rampart the same token the server was started with."
        return try {
            since(base, token, Instant.now())
            null
        } catch (e: TrackingError) {
            e.message
        } catch (e: Exception) {
            "Could not reach it: " + whyFailed(e)
        }
    }
}

internal class TrackingError(message: String) : Exception(message)
