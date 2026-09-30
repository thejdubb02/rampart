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

    /** The package's name in the manifest, which is how Windows finds it to update. */
    private const val PACKAGE = "Rampart"

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Set by the packaged launcher. Null when running from a development build. */
    val current: String? = System.getProperty("app.version")?.takeIf { it.isNotBlank() }

    /**
     * Why the last attempt did not work, in a sentence somebody can act on, or null.
     *
     * It used to be nothing at all. Both PowerShell commands swallowed their own errors and
     * the process output was never read, so a failed update produced a card saying Windows
     * would fetch it in the background whether or not anything had been staged, and there
     * was no way to find out otherwise.
     */
    @Volatile
    var lastProblem: String? = null
        private set

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
        //
        // The catch writes the exception's message rather than swallowing it. A refusal
        // used to leave nothing to read, which is how "Windows will fetch it in the
        // background instead" got said about an install that was never staged: there was
        // no way to tell that failure apart from one that had actually gone in.
        "try { Add-AppxPackage -AppInstallerFile '$APPINSTALLER' -ForceTargetApplicationShutdown " +
            "-ErrorAction Stop } catch { Write-Output \$_.Exception.Message }; " +
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
    /**
     * One outcome of running a PowerShell install step: the exit code and everything it
     * printed, or [timedOut] when it was still running after the time given to it and was
     * killed rather than waited on further.
     */
    private data class Ran(val exitCode: Int, val output: String, val timedOut: Boolean)

    /**
     * Runs one of the two install commands and reports what happened, without deciding what
     * it means: [stage] and [restartToUpdate] disagree about that, in particular about
     * whether reaching a return at all is itself the failure (see the KDoc on
     * [restartToUpdate]), so that judgement stays with each caller.
     *
     * Null, with [lastProblem] already set, when nothing ran at all: not a packaged build,
     * the manifest is not being served yet, or the network could not be reached.
     *
     * **Into a file, not down a pipe.** Both ways of reading a pipe hang here. A pipe holds
     * a few tens of kilobytes. Wait for the process first and a refusal long enough to fill
     * it leaves PowerShell blocked on a write nobody is reading while this side waits for an
     * exit that cannot come. Read the pipe first instead and a process that hangs without
     * closing its output blocks the read for ever, which quietly skips past the timeout
     * below. A file has neither end of that: the process writes as much as it likes to
     * somewhere with no reader, the timeout is the only thing that decides how long this
     * waits, and the output is read afterwards when there is nothing left to deadlock
     * against. Deleted on every path, including the one where starting the process throws,
     * because a file left behind every failed attempt is a slow leak in the temp directory.
     */
    private fun runPowerShell(command: List<String>, timeoutMinutes: Long): Ran? = try {
        updater() ?: return null
        // Asked before PowerShell is started at all. A manifest that is not being served yet
        // is a reason to come back in a minute, not a failed install, and running the command
        // anyway turns the first into the second.
        when (reachable()) {
            true -> Unit
            false -> {
                lastProblem = NOT_READY
                return null
            }
            null -> {
                lastProblem = "Rampart could not reach the download."
                return null
            }
        }
        val log = Files.createTempFile("rampart-update", ".log")
        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()
            if (!process.waitFor(timeoutMinutes, java.util.concurrent.TimeUnit.MINUTES)) {
                process.destroyForcibly()
                Ran(exitCode = -1, output = "", timedOut = true)
            } else {
                // Read rather than thrown away. What Windows refused for is the only thing
                // that makes a failure here fixable by anybody.
                val said = runCatching { Files.readString(log) }.getOrDefault("").trim()
                Ran(process.exitValue(), said, timedOut = false)
            }
        } finally {
            runCatching { Files.deleteIfExists(log) }
        }
    } catch (e: Exception) {
        lastProblem = whyFailed(e)
        null
    }

    fun stage(): Boolean {
        val ran = runPowerShell(stageCommand(), timeoutMinutes = 30) ?: return false
        return when {
            ran.timedOut -> {
                lastProblem = "The download did not finish."
                false
            }
            ran.exitCode == 0 -> {
                lastProblem = null
                true
            }
            else -> {
                lastProblem = ran.output.lines().firstOrNull { it.isNotBlank() }
                    ?: "Windows would not stage the update and did not say why."
                false
            }
        }
    }

    internal var isWindows: () -> Boolean = {
        System.getProperty("os.name", "").lowercase().contains("win")
    }

    internal var exitHandler: () -> Unit = {
        kotlin.system.exitProcess(0)
    }

    internal fun generateTaskName(): String =
        "RampartUpdate_" + java.util.UUID.randomUUID().toString().replace("-", "")

    internal fun updateScriptText(
        appinstaller: String = APPINSTALLER,
        packageName: String = PACKAGE,
        taskName: String,
    ): String = buildString {
        appendLine("\$family = (Get-AppxPackage -Name '$packageName').PackageFamilyName")
        appendLine("\$deadline = (Get-Date).AddSeconds(30)")
        appendLine("while ((Get-Date) -lt \$deadline) {")
        appendLine("    \$procs = Get-Process | Where-Object { \$_.PackageFamilyName -eq \$family -or \$_.ProcessName -eq '$packageName' }")
        appendLine("    if (-not \$procs) { break }")
        appendLine("    Start-Sleep -Milliseconds 500")
        appendLine("}")
        appendLine("\$logDir = [System.IO.Path]::Combine(\$env:LOCALAPPDATA, '$packageName')")
        appendLine("if (-not (Test-Path -Path \$logDir)) {")
        appendLine("    New-Item -ItemType Directory -Path \$logDir -Force | Out-Null")
        appendLine("}")
        appendLine("\$logFile = [System.IO.Path]::Combine(\$logDir, 'update.log')")
        appendLine("try {")
        appendLine("    Add-AppxPackage -AppInstallerFile '$appinstaller' -ForceTargetApplicationShutdown -ErrorAction Stop")
        appendLine("} catch {")
        appendLine("    [System.IO.File]::WriteAllText(\$logFile, \$_.Exception.Message)")
        appendLine("}")
        appendLine("Start-Process ('shell:appsFolder\\' + \$family + '!$packageName')")
        appendLine("schtasks /Delete /TN '$taskName' /F")
        appendLine("Remove-Item -LiteralPath \$PSCommandPath -ErrorAction SilentlyContinue")
    }

    /*
     * The action runs the script through -EncodedCommand. The task scheduler hands /TR to
     * powershell without a shell, so single quotes around a path would be read as part of the
     * name, and double quotes would have to survive Java's Windows argument quoting and then
     * schtasks' own parsing. Base64 has neither quotes nor spaces, so nothing can go wrong in
     * between. /IT runs it in the signed-in user's session, which is where the relaunched
     * window has to appear; tested on a real install on 2026-09-29.
     */
    internal fun createTaskCommand(taskName: String, scriptPath: String): List<String> = listOf(
        "schtasks", "/Create", "/SC", "ONCE", "/ST", "00:00", "/TN", taskName,
        "/TR", "powershell -nop -ep bypass -w hidden -enc " +
            encodedCommand("& '" + scriptPath.replace("'", "''") + "'"),
        "/IT", "/F",
    )

    /** PowerShell's -EncodedCommand form: base64 of the UTF-16LE text. */
    internal fun encodedCommand(command: String): String =
        java.util.Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_16LE))

    internal fun runTaskCommand(taskName: String): List<String> = listOf(
        "schtasks", "/Run", "/TN", taskName,
    )

    internal fun updateLogPath(): Path {
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        val base = if (localAppData != null) {
            Path.of(localAppData, PACKAGE)
        } else {
            val userHome = System.getProperty("user.home", ".")
            Path.of(userHome, "AppData", "Local", PACKAGE)
        }
        return base.resolve("update.log")
    }

    /**
     * Checks if update.log has an error recorded after the previous start.
     * Reads the message and removes the log so it is shown only once.
     */
    internal fun checkPreviousUpdateError(lastStart: Long, logFile: Path = updateLogPath()): String? = runCatching {
        if (!Files.isRegularFile(logFile)) return null
        val modTime = Files.getLastModifiedTime(logFile).toMillis()
        if (modTime <= lastStart) return null
        val message = Files.readString(logFile).trim()
        runCatching { Files.deleteIfExists(logFile) }
        message.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Installs the published version and restarts into it. Returns false when this is not a
     * packaged copy, when the manifest is not being served yet, or when the swap did not
     * happen; [lastProblem] says which, in a sentence rather than a code.
     *
     * On Windows, this launches the install via a scheduled task outside the MSIX
     * app container, so that shutting down Rampart does not kill the installer.
     * Rampart exits itself cleanly after triggering the task. On non-Windows platforms,
     * this falls back to running the update command directly.
     */
    fun restartToUpdate(): Boolean {
        updater() ?: return false
        when (reachable()) {
            true -> Unit
            false -> {
                lastProblem = NOT_READY
                return false
            }
            null -> {
                lastProblem = "Rampart could not reach the download."
                return false
            }
        }

        if (isWindows()) {
            return restartToUpdateWindows()
        }

        val ran = runPowerShell(updateCommand(), timeoutMinutes = 3) ?: return false
        lastProblem = if (ran.timedOut) {
            "The install did not finish. Rampart is still on the version you had."
        } else {
            ran.output.lines().firstOrNull { it.isNotBlank() }
                ?: "The install did not go in. Rampart is still on the version you had."
        }
        return false
    }

    private fun restartToUpdateWindows(): Boolean = try {
        val taskName = generateTaskName()
        val scriptText = updateScriptText(APPINSTALLER, PACKAGE, taskName)
        val scriptFile = Files.createTempFile("rampart-update-", ".ps1")
        Files.writeString(scriptFile, scriptText)

        val createCmd = createTaskCommand(taskName, scriptFile.toAbsolutePath().toString())
        val createProc = ProcessBuilder(createCmd).redirectErrorStream(true).start()
        val created = createProc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        if (!created || createProc.exitValue() != 0) {
            val err = if (created) createProc.inputStream.bufferedReader().readText().trim() else ""
            lastProblem = err.ifBlank { "Could not register update task." }
            runCatching { Files.deleteIfExists(scriptFile) }
            false
        } else {
            val runCmd = runTaskCommand(taskName)
            val runProc = ProcessBuilder(runCmd).redirectErrorStream(true).start()
            val ran = runProc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!ran || runProc.exitValue() != 0) {
                val err = if (ran) runProc.inputStream.bufferedReader().readText().trim() else ""
                lastProblem = err.ifBlank { "Could not start update task." }
                false
            } else {
                exitHandler()
                true
            }
        }
    } catch (e: Exception) {
        lastProblem = whyFailed(e)
        false
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
     * What the bar should show after a stage or install attempt has returned false.
     *
     * [NOT_READY] is not a failure: it means the release is still landing, and the honest
     * answer is to wait and stay clickable rather than to alarm somebody about a state that
     * clears itself on its own within a minute. Everything else is shown as what it is, and
     * the bar stays clickable either way so it can be tried again.
     */
    internal fun afterFailure(version: String, problem: String?): UpdateBarState =
        if (problem == NOT_READY) UpdateBarState.Waiting(version)
        else UpdateBarState.Failed(version, problem ?: "The update did not go in.")

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

    /**
     * What the bar shows when a check finds [version].
     *
     * The check only records that a newer build exists. The bar says it is ready after
     * the package has been fetched, and not before: saying so here is what left the
     * download until the moment of the restart.
     */
    internal fun stateAfterNewer(state: UpdateBarState, version: String): UpdateBarState {
        if (version.isEmpty()) return state
        return state
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

    /** Handed to Windows. The app is expected to close during this and come back as
     *  [version]; still being here after a while means the swap did not happen. */
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
        is UpdateBarState.Waiting -> "Rampart $version is ready"
        is UpdateBarState.Staging -> "Fetching Rampart $version"
        is UpdateBarState.Installing -> "Installing Rampart $version"
        is UpdateBarState.Failed -> message
    }
