package org.rampart

import java.awt.Desktop
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface UpdateCheckResult {
    data class Newer(val version: String) : UpdateCheckResult
    data object Current : UpdateCheckResult
    data class Failed(val reason: UpdateCheckFailure) : UpdateCheckResult
}

enum class UpdateCheckFailure {
    NO_VERSION,
    HTTP_3XX,
    HTTP_403,
    HTTP_404,
    HTTP_429,
    HTTP_4XX,
    HTTP_5XX,
    HTTP_OTHER,
    NETWORK,
    PARSE,
}

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

    /** The manifest Windows reads to find the current package. Always names the newest one. */
    private const val APPINSTALLER =
        "https://github.com/thejdubb02/rampart/releases/latest/download/rampart.appinstaller"

    /**
     * Cold start does no update traffic. The first check waits, then [CHECK_INTERVAL_MS]
     * repeats it for as long as the window stays open.
     */
    internal const val FIRST_CHECK_DELAY_MS = 60_000L
    internal const val CHECK_INTERVAL_MS = 30 * 60_000L

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Set by the packaged launcher. Null when running from a development build. */
    val current: String? = System.getProperty("app.version")?.takeIf { it.isNotBlank() }

    /**
     * A development build, or any copy without the packaged launcher beside it, has
     * nothing to install over. Those builds never offer an update.
     */
    fun canUpdate(): Boolean = current != null && updater() != null

    /**
     * Why the last attempt did not work, in a sentence somebody can act on, or null.
     *
     * Set when the manifest cannot be fetched, or when Windows will not open App Installer.
     * Cleared when a fetch or a handoff succeeds.
     */
    @Volatile
    var lastProblem: String? = null
        private set

    /** The manifest saved by the last [stage] that found a newer build, or null. */
    @Volatile
    private var stagedManifest: Path? = null

    /** One directory for that file, created the first time a fetch needs it. */
    private val manifestDir: Path by lazy { Files.createTempDirectory("rampart-update") }

    /** Said when the release exists but its files are not being served yet. See [manifestReady]. */
    internal const val NOT_READY =
        "The new version is published but is not ready to download yet. Rampart will keep trying."

    /**
     * Whether the manifest Windows needs is actually being served yet.
     *
     * **A release's files are not downloadable the moment it is published.** Measured on
     * 2026-09-21 against our own release: the API reported rampart.appinstaller as uploaded
     * at 4198 bytes and signed in it downloaded, while the same anonymous URL that Windows
     * and every reader fetches answered 404. It answered 404 on the first try and 200
     * thirty seconds later. The release before it served throughout.
     *
     * That gap is exactly when Rampart notices a new version, so staging ran against a
     * manifest that did not exist yet and failed every time. Asked first, so the difference
     * between "not ready yet" and "did not install" is one Rampart can tell, and so the
     * answer to the first is to wait rather than to report a failure.
     */
    internal fun manifestReady(url: String = APPINSTALLER): Boolean = reachable(url) == true

    /**
     * True when the manifest is served, false when the server says it is not there yet, and
     * null when the question could not be asked at all.
     *
     * Three answers rather than two, because no network is not a release that has not landed
     * and telling somebody with the wifi off that their update "is not ready to download
     * yet" is a sentence about the wrong thing.
     */
    internal fun reachable(url: String = APPINSTALLER): Boolean? = runCatching {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        // The body is checked as well as the code, because a redirect to an error page is a
        // 200 carrying something that is not a manifest.
        response.statusCode() == 200 && response.body().contains("<AppInstaller")
    }.getOrNull()

    /** Returns the published-version outcome without retaining response bodies or exception text. */
    fun newerVersion(): UpdateCheckResult = newerVersion(current, LATEST, APPINSTALLER)

    internal fun newerVersion(
        running: String?,
        latestUrl: String,
        manifestUrl: String,
    ): UpdateCheckResult {
        if (running == null) return UpdateCheckResult.Failed(UpdateCheckFailure.NO_VERSION)
        val response = try {
            http.send(
                HttpRequest.newBuilder(URI.create(latestUrl))
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (_: Exception) {
            return UpdateCheckResult.Failed(UpdateCheckFailure.NETWORK)
        }
        if (response.statusCode() == 403 || response.statusCode() == 429) {
            return versionFromManifest(manifestUrl, running, httpFailure(response.statusCode()))
        }
        if (response.statusCode() != 200) {
            return UpdateCheckResult.Failed(httpFailure(response.statusCode()))
        }
        val latest = try {
            Json.parseToJsonElement(response.body()).jsonObject["tag_name"]
                ?.jsonPrimitive?.contentOrNull?.removePrefix("v")
                ?: return UpdateCheckResult.Failed(UpdateCheckFailure.PARSE)
        } catch (_: Exception) {
            return UpdateCheckResult.Failed(UpdateCheckFailure.PARSE)
        }
        return resultFor(latest, running)
    }

    private fun versionFromManifest(
        url: String,
        running: String,
        originalFailure: UpdateCheckFailure,
    ): UpdateCheckResult {
        val response = try {
            http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (_: Exception) {
            return UpdateCheckResult.Failed(UpdateCheckFailure.NETWORK)
        }
        if (response.statusCode() != 200) return UpdateCheckResult.Failed(originalFailure)
        val latest = parseManifestVersion(response.body())
            ?: return UpdateCheckResult.Failed(UpdateCheckFailure.PARSE)
        return resultFor(latest, running)
    }

    internal fun parseManifestVersion(manifest: String): String? {
        val tag = Regex("<MainPackage\\b[^>]*>", RegexOption.IGNORE_CASE).find(manifest)?.value
            ?: return null
        val version = Regex("\\bVersion\\s*=\\s*\"(\\d+\\.\\d+\\.\\d+\\.\\d+)\"", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1) ?: return null
        return version.removeSuffix(".0")
    }

    internal fun resultFor(candidate: String, running: String): UpdateCheckResult =
        if (isNewer(candidate, running)) UpdateCheckResult.Newer(candidate) else UpdateCheckResult.Current

    private fun httpFailure(status: Int): UpdateCheckFailure = when (status) {
        403 -> UpdateCheckFailure.HTTP_403
        404 -> UpdateCheckFailure.HTTP_404
        429 -> UpdateCheckFailure.HTTP_429
        in 300..399 -> UpdateCheckFailure.HTTP_3XX
        in 400..499 -> UpdateCheckFailure.HTTP_4XX
        in 500..599 -> UpdateCheckFailure.HTTP_5XX
        else -> UpdateCheckFailure.HTTP_OTHER
    }

    /**
     * The launcher Windows installs beside the app. Its presence is how we know this is a
     * packaged copy rather than one run from source, which is the only thing it is used for
     * now: running it does not update anything.
     *
     * Its location is searched for rather than assumed, because it belongs to the packaging
     * tool rather than to us.
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
     * Downloads the release manifest and remembers it only when it names a version
     * newer than the one running. Blocks, so it belongs on a background thread.
     *
     * The saved file is what Windows App Installer opens later. Nothing here starts
     * a process: the download happens while someone is reading their mail, and the
     * app stays open.
     *
     * A development build returns false without asking the network.
     */
    fun stage(): Boolean {
        val running = current ?: return false
        updater() ?: return false
        return try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create(APPINSTALLER))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val body = response.body()
            val version = parseManifestVersion(body)
            if (response.statusCode() == 200 && version != null && isNewer(version, running)) {
                val file = manifestDir.resolve("rampart.appinstaller")
                Files.writeString(file, body)
                stagedManifest = file
                lastProblem = null
                true
            } else {
                lastProblem = NOT_READY
                false
            }
        } catch (e: Exception) {
            lastProblem = if (e is HttpTimeoutException) {
                "The download did not finish."
            } else {
                "Rampart could not reach the download."
            }
            false
        }
    }

    /**
     * Opens the staged manifest in Windows App Installer. Returns false when this is
     * not a packaged copy, when the manifest is not a newer build, or when Windows
     * could not open the file. [lastProblem] says which.
     *
     * App Installer shows its own update window, closes Rampart, and starts the new
     * version. This returns as soon as that window has been asked to open, so Rampart
     * is still running on a true result. A cancelled window can be asked again.
     */
    fun restartToUpdate(): Boolean {
        if (current == null || updater() == null) return false
        if (stagedManifest == null && !stage()) return false
        val file = stagedManifest ?: return false
        return try {
            if (!Desktop.isDesktopSupported()) {
                lastProblem = "Windows could not open its App Installer."
                return false
            }
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                lastProblem = "Windows could not open its App Installer."
                return false
            }
            desktop.open(file.toFile())
            lastProblem = null
            true
        } catch (e: Exception) {
            val detail = e.message?.trim()?.takeIf { it.isNotBlank() }
            lastProblem = if (detail == null) {
                "Windows could not open its App Installer."
            } else {
                "Windows could not open its App Installer. $detail"
            }
            false
        }
    }

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

    /**
     * Which version a click should end on, given what is already [staged] and a [fresh]
     * check run at the moment of the click.
     *
     * "Regardless of how many versions behind you are" means a click must never quietly
     * install whatever happened to be staged hours or days ago without asking again first.
     * [fresh] is null when the check itself could not be answered, which is not the same as
     * nothing new being published: [staged] is the safest thing to install then, since it
     * is already known to exist and is already sitting on disk.
     */
    internal fun targetVersion(staged: String, fresh: UpdateCheckResult): String =
        (fresh as? UpdateCheckResult.Newer)?.version ?: staged

    /**
     * What the bar shows when a click did not install.
     *
     * A click asked to apply now, so [NOT_READY] is a failure here too: the new version
     * is not on disk, and the bar must not say it is ready. The message is shown as
     * given. The running version stays where it is, and clicking tries again.
     */
    internal fun clickFailure(version: String, problem: String?): UpdateBarState.Failed =
        UpdateBarState.Failed(version, problem?.takeIf { it.isNotBlank() } ?: "The update did not go in.")

    /**
     * Whether the package for [version] still needs fetching.
     *
     * Waiting means that version is already on disk, so fetching it again would only
     * repeat the download. A failure may be tried the next time this is asked: the bar
     * must not claim the package is ready before it has been fetched. Busy means a click
     * is already fetching or installing, and a second fetch would race it.
     */
    internal fun shouldPrefetch(state: UpdateBarState, version: String?): Boolean {
        if (version.isNullOrBlank()) return false
        if (state.busy) return false
        return (state as? UpdateBarState.Waiting)?.version != version
    }
}

/**
 * What the bottom bar says about updates right now.
 *
 * One value rather than the separate booleans this used to be (`installing`, `waiting`,
 * `putOff`, `installNote`). Those could combine into a state nobody meant to reach, such as
 * a card that was somehow both installing and put off, because nothing stopped them being
 * set independently. A sealed type only allows the states that are actually distinct.
 */
internal sealed class UpdateBarState {
    /** No newer version known, or nothing staged yet. The bar says nothing about updates. */
    object Hidden : UpdateBarState()

    /** [version] is fetched and waiting on disk. A click starts the install. */
    data class Waiting(val version: String) : UpdateBarState()

    /** Re-checking what is actually newest and fetching it, because a click must never
     *  install something that stopped being current while it sat staged. */
    data class Staging(val version: String) : UpdateBarState()

    /** The staged manifest is being handed to Windows App Installer. */
    data class Installing(val version: String) : UpdateBarState()

    /** [message] is why the last attempt did not work. Clicking tries again for [version]. */
    data class Failed(val version: String, val message: String) : UpdateBarState()
}

/** True while a click is being worked through, which is when the bar must not be clicked again. */
internal val UpdateBarState.busy: Boolean
    get() = this is UpdateBarState.Staging || this is UpdateBarState.Installing

/**
 * The one line the bar shows for each state, kept beside the states themselves so the
 * wording cannot drift from whatever caused it.
 */
internal val UpdateBarState.label: String
    get() = when (this) {
        UpdateBarState.Hidden -> ""
        is UpdateBarState.Waiting -> "Update to $version"
        is UpdateBarState.Staging -> "Fetching Rampart $version"
        is UpdateBarState.Installing -> "Installing Rampart $version"
        is UpdateBarState.Failed -> message
    }
