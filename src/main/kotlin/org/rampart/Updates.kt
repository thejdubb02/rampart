package org.rampart

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Whether a newer Rampart has been published.
 *
 * Windows already updates the app on its own: the installed package's entry point is the
 * update checker, so every launch collects whatever is newest. This exists only so the
 * person running it can see that, rather than wondering. Nothing here downloads or
 * installs anything.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/thejdubb02/rampart/releases/latest"

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** Set by the packaged launcher. Null when running from a development build. */
    val current: String? = System.getProperty("app.version")?.takeIf { it.isNotBlank() }

    /**
     * The published version, when it is newer than this one. Null for every other outcome,
     * including no network: an update check that failed is not something to interrupt
     * someone reading their mail about.
     */
    fun newerVersion(): String? = runCatching {
        val running = current ?: return null
        val response = http.send(
            HttpRequest.newBuilder(URI.create(LATEST))
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() != 200) return null
        val latest = Json.parseToJsonElement(response.body()).jsonObject["tag_name"]
            ?.jsonPrimitive?.contentOrNull?.removePrefix("v") ?: return null
        latest.takeIf { isNewer(it, running) }
    }.getOrNull()

    /**
     * Compares dotted versions a segment at a time. A segment that is not a number sorts
     * as zero rather than throwing: a tag someone published by hand must not be able to
     * crash the app on startup.
     */
    internal fun isNewer(candidate: String, running: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = running.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}
