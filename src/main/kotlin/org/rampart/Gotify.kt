package org.rampart

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object Gotify {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun send(serverUrl: String, token: String, title: String, message: String, priority: Int = 5) {
        val endpoint = URI.create(serverUrl.trim().trimEnd('/') + "/message")
        val payload = buildJsonObject {
            put("title", title)
            put("message", message)
            put("priority", priority)
        }.toString()
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(8))
            .header("X-Gotify-Key", token.trim())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) error("Gotify answered ${response.statusCode()}.")
    }

    fun check(serverUrl: String, token: String): String? = try {
        if (serverUrl.isBlank()) return "Give Rampart the Gotify server URL."
        if (token.isBlank()) return "Give Rampart the Gotify application token."
        val uri = URI.create(serverUrl.trim())
        val local = uri.host in setOf("localhost", "127.0.0.1", "::1")
        if (uri.host == null || (uri.scheme != "https" && !local)) return "The server URL has to use https."
        send(serverUrl, token, "Rampart test", "Phone alerts are working.")
        null
    } catch (e: Exception) {
        "Could not send the test: ${whyFailed(e)}"
    }
}
