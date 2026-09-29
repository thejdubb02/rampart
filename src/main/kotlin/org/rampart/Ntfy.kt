package org.rampart

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal object Ntfy {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun send(topic: String, token: String?, title: String, message: String, tags: String = "envelope") {
        val request = HttpRequest.newBuilder(URI.create(topic.trim()))
            .timeout(Duration.ofSeconds(8))
            .header("Title", title)
            .header("Tags", tags)
            .header("Priority", "default")
            .apply { token?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(message))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) error("ntfy answered ${response.statusCode()}.")
    }

    fun check(topic: String, token: String?): String? = try {
        if (topic.isBlank()) return "Give Rampart the full ntfy topic URL."
        val uri = URI.create(topic.trim())
        val local = uri.host in setOf("localhost", "127.0.0.1", "::1")
        if (uri.host == null || (uri.scheme != "https" && !local)) return "The topic URL has to use https."
        send(topic, token, "Rampart test", "Phone alerts are working.", "white_check_mark")
        null
    } catch (e: Exception) {
        "Could not send the test: ${whyFailed(e)}"
    }
}
