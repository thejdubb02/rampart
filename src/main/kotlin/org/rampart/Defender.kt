package org.rampart

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Asks Windows Defender about an attachment before it is opened or saved.
 *
 * Defender's real-time protection already watches every file written to disk, so this is
 * not the only line. What it adds is an answer Rampart can show: without it a blocked file
 * simply fails to open, or vanishes from Downloads, and nobody is told why.
 *
 * Every Windows machine has Defender, so this needs nothing installed and works with any
 * mail server. Elsewhere there is no scanner and the file is left as it is.
 */
internal object Defender {
    /** Scans [file], and deletes it and throws [AttachmentBlocked] when Defender finds a threat. */
    fun check(file: Path): Path = check(file, scanner)

    /**
     * Only exit code 2, a threat found, blocks. A scanner that fails, times out or is
     * missing must never stop a clean file, since real-time protection still runs underneath.
     */
    internal fun check(file: Path, scan: ((Path) -> Int)?): Path {
        val code = scan?.let { runCatching { it(file) }.getOrNull() }
        if (code == THREAT_FOUND) {
            runCatching { Files.deleteIfExists(file) }
            throw AttachmentBlocked(file.fileName.toString())
        }
        return file
    }

    private const val THREAT_FOUND = 2

    private val scanner: ((Path) -> Int)? by lazy {
        val exe = mpCmdRun() ?: return@lazy null
        { file: Path ->
            val process = ProcessBuilder(
                exe.toString(), "-Scan", "-ScanType", "3",
                "-File", file.toAbsolutePath().toString(), "-DisableRemediation",
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (process.waitFor(60, TimeUnit.SECONDS)) process.exitValue()
            else -1.also { process.destroyForcibly() }
        }
    }

    /** The current platform's copy first, since Defender updates itself there; the old fixed path otherwise. */
    private fun mpCmdRun(): Path? {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
        val platform = System.getenv("ProgramData")
            ?.let { Paths.get(it, "Microsoft", "Windows Defender", "Platform") }
            ?.takeIf { Files.isDirectory(it) }
            ?.let { dir -> Files.list(dir).use { s -> s.toList() } }
            .orEmpty()
            .sortedDescending()
            .map { it.resolve("MpCmdRun.exe") }
            .firstOrNull { Files.isRegularFile(it) }
        return platform ?: System.getenv("ProgramFiles")
            ?.let { Paths.get(it, "Windows Defender", "MpCmdRun.exe") }
            ?.takeIf { Files.isRegularFile(it) }
    }
}

internal class AttachmentBlocked(name: String) :
    IllegalStateException("Windows Defender found a threat in $name, so it was deleted and not opened or saved.")
