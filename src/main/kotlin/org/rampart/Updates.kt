package org.rampart

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Whether a newer Rampart has been published, and pulling it in when asked.
 *
 * The packaged launcher is deliberately not set to check before the window opens: that
 * makes every start wait on a network round trip. The check happens here instead, after
 * the app is already on screen, and the update is applied only when someone presses the
 * button. Windows also installs it in the background on its own schedule, so doing nothing
 * is a valid answer.
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
     * The package's own updater, which Windows installs beside the app. Running it applies
     * whatever is published and starts Rampart again.
     *
     * Its location is searched for rather than assumed, because it belongs to the packaging
     * tool rather than to us, and a wrong guess here would be a button that silently does
     * nothing.
     */
    private fun updater(): Path? {
        val candidates = buildList {
            System.getProperty("app.dir")?.let { add(Path.of(it)) }
            runCatching {
                val here = Path.of(
                    Updates::class.java.protectionDomain.codeSource.location.toURI(),
                )
                add(here.parent)
                add(here.parent?.parent)
            }
        }
        return candidates.filterNotNull()
            .flatMap { listOf(it.resolve("updatecheck.exe"), it.resolve("bin").resolve("updatecheck.exe")) }
            .firstOrNull { Files.isRegularFile(it) }
    }

    /**
     * Applies the update and restarts. Returns false when the updater is not where it
     * should be, and the caller then just closes: Windows will pick the new version up on
     * its own, only later.
     */
    fun restartToUpdate(): Boolean = runCatching {
        val updater = updater() ?: return false
        ProcessBuilder(updater.toString())
            .directory(updater.parent.toFile())
            .start()
        true
    }.getOrDefault(false)

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
