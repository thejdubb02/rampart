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

    /** The manifest Windows reads to find the current package. Always names the newest one. */
    private const val APPINSTALLER =
        "https://github.com/thejdubb02/rampart/releases/latest/download/rampart.appinstaller"

    /** The package's name in the manifest, which is how Windows finds it to update. */
    private const val PACKAGE = "Rampart"

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
     * What to run to replace this copy with the published one and start it again.
     *
     * Windows will not replace a package while it is running, which is what
     * ForceTargetApplicationShutdown is for. If the install fails, Rampart is started again
     * anyway rather than leaving somebody with no app, and because the version will not have
     * changed its own check offers the update again within seconds. A failure that corrects
     * itself is better than a marker file nobody reads.
     *
     * The package family name is asked for rather than written down: it carries a hash of
     * the signing identity, and a hardcoded one would silently stop matching the day that
     * key is replaced.
     */
    internal fun updateCommand(): List<String> = listOf(
        "powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
        // Single quotes and concatenation rather than an interpolated double-quoted string.
        // The whole script crosses Java's Windows argument quoting as one argument, and a
        // double quote inside it is the thing most likely not to survive the trip.
        "try { Add-AppxPackage -AppInstallerFile '$APPINSTALLER' -ForceTargetApplicationShutdown } " +
            "catch { }; " +
            "\$f = (Get-AppxPackage -Name $PACKAGE).PackageFamilyName; " +
            "Start-Process ('shell:appsFolder\\' + \$f + '!$PACKAGE')",
    )

    /**
     * Fetches the published package and leaves it waiting, without disturbing the copy that
     * is running.
     *
     * The reason this exists: an update used to be fetched at the moment somebody pressed
     * the button, so pressing it meant a minute of waiting with the app shut. People leave
     * a mail client open for days, and the one moment they are willing to lose it is not
     * the moment to start a download. Fetched quietly instead, as soon as there is one, so
     * the button has nothing left to do but swap the files.
     *
     * `DeferRegistrationWhenPackagesAreInUse` is what makes it safe to do while the app is
     * open: Windows stages the new version and applies it when the app is next closed. So
     * doing nothing at all still ends with the update installed, which is the behaviour
     * somebody who never presses the button should get.
     *
     * The flag is not on every Windows this might run on, so a second attempt without it
     * follows. That one can refuse while the app is in use, and refusing is fine: nothing
     * is staged, the button falls back to fetching at the time it is pressed, and what is
     * lost is the head start rather than the update.
     *
     * Nothing here can close the app. Neither call carries a shutdown flag, which is the
     * property that makes a background fetch acceptable in the first place.
     */
    internal fun stageCommand(): List<String> = listOf(
        "powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
        "try { Add-AppxPackage -AppInstallerFile '$APPINSTALLER' " +
            "-DeferRegistrationWhenPackagesAreInUse -ErrorAction Stop; exit 0 } catch { }; " +
            "try { Add-AppxPackage -AppInstallerFile '$APPINSTALLER' -ErrorAction Stop; exit 0 } " +
            "catch { exit 1 }",
    )

    /**
     * Runs that fetch and says whether the package is now waiting. Blocks, so it belongs on
     * a background thread.
     *
     * A fetch that has not finished in half an hour is abandoned rather than left holding a
     * thread for the rest of the session. There is nothing to report when that happens: the
     * button still works, and the next check starts it again.
     */
    fun stage(): Boolean = runCatching {
        updater() ?: return false
        val process = ProcessBuilder(stageCommand()).start()
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.MINUTES)) {
            process.destroy()
            return false
        }
        process.exitValue() == 0
    }.getOrDefault(false)

    /**
     * Installs the published version and restarts into it. Returns false when this is not a
     * packaged copy, and the caller then just closes.
     *
     * It does not go through the package's own launcher. That launcher only checks for an
     * update when Conveyor is set to `aggressive`, which we deliberately are not, because
     * aggressive makes every cold start wait on the network before the window appears. Run
     * in background mode it prints "Not in aggressive mode, launching the app" and does
     * exactly that, so the restart button was restarting without updating. Read out of the
     * shipped binary, not guessed.
     */
    fun restartToUpdate(): Boolean = runCatching {
        updater() ?: return false
        ProcessBuilder(updateCommand()).start()
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
