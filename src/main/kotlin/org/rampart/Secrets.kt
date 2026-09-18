package org.rampart

import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * Remembering a password, using the operating system's own store and nothing of our own.
 *
 * We do not encrypt anything ourselves. On Windows the bytes go through DPAPI, which ties
 * them to the logged in Windows account: another user on the same machine cannot read them
 * and the file is useless on any other machine. The account's own identity is passed as
 * DPAPI entropy, so a file copied over another account's file fails to decrypt rather than
 * quietly handing back the wrong password.
 *
 * On Linux this is the same secret service the desktop already uses for wifi and browser
 * passwords, through `secret-tool`. Where neither exists, [available] is false, nothing is
 * written anywhere, and Rampart asks for the password every time. That is the correct
 * failure: a password we cannot store safely is one we do not store.
 */
object Secrets {
    private const val SERVICE = "rampart"

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private val secretTool: String? by lazy {
        if (windows) null
        else System.getenv("PATH").orEmpty().split(':')
            .map { Path.of(it, "secret-tool") }
            .firstOrNull { it.exists() }
            ?.toString()
    }

    fun available(): Boolean = unavailableReason() == null

    /**
     * Why a password cannot be kept, in one sentence, or null when it can.
     *
     * This is shown to the person rather than logged. A credential store that quietly
     * does nothing is worse than one that is plainly absent: it looks like it worked,
     * and the failure only surfaces as "why am I typing this again" days later.
     */
    fun unavailableReason(): String? = when {
        windows -> dpapiProblem
        secretTool != null -> null
        else -> "No credential store was found. On Linux that is secret-tool, from libsecret."
    }

    /** Null when the password was kept, otherwise the reason it was not. */
    fun store(account: SavedAccount, password: String): String? {
        unavailableReason()?.let { return it }
        return runCatching {
            if (attempt(account, password)) null else "The credential store rejected the password."
        }.getOrElse { "The credential store failed: ${it.message ?: it::class.simpleName}" }
    }

    private fun attempt(account: SavedAccount, password: String): Boolean = runCatching {
        val id = id(account)
        if (windows) {
            val file = secretFile(id)
            file.parent?.createDirectories()
            file.writeBytes(Dpapi.protect(password.toByteArray(Charsets.UTF_8), id.toByteArray(Charsets.UTF_8)))
            true
        } else {
            val tool = secretTool ?: return false
            val process = ProcessBuilder(tool, "store", "--label=Rampart: ${account.email}", "service", SERVICE, "account", id)
                .redirectErrorStream(true)
                .start()
            process.outputStream.use { it.write(password.toByteArray(Charsets.UTF_8)) }
            process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0
        }
    }.getOrDefault(false)

    fun load(account: SavedAccount): String? = runCatching {
        val id = id(account)
        if (windows) {
            val file = secretFile(id)
            if (!file.exists()) return null
            String(Dpapi.unprotect(file.readBytes(), id.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
        } else {
            val tool = secretTool ?: return null
            val process = ProcessBuilder(tool, "lookup", "service", SERVICE, "account", id).start()
            val out = process.inputStream.readBytes()
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            String(out, Charsets.UTF_8).trimEnd('\n').ifBlank { null }
        }
    }.getOrNull()

    /**
     * The key the local copy of this account's mail is encrypted with.
     *
     * Made the first time it is asked for and kept in the same place as the password, which
     * is the operating system's own store. Returns null where there is nowhere safe to keep
     * it, and the caller then runs without a local store: a key written beside the file it
     * encrypts protects nothing, so that is the right answer rather than a fallback.
     *
     * 256 bits of randomness rendered as hex, so it is a password nobody has to type and
     * nothing in a URL or a file has to escape.
     */
    fun mailKey(account: SavedAccount): String? {
        if (!available()) return null
        val key = SavedAccount("${'$'}{account.name} (local mail)", account.server, "db:${'$'}{account.email}")
        load(key)?.let { return it }
        val made = java.security.SecureRandom().generateSeed(32).joinToString("") { "%02x".format(it) }
        return if (store(key, made) == null) made else null
    }

    /**
     * The token Rampart uses to read opens back from the companion server.
     *
     * In the operating system's store rather than the settings file, for the same reason a
     * password is: a settings file gets pasted into a bug report. Keyed through a made-up
     * account the same way [mailKey] is, because the store is keyed by account and this
     * belongs to the install rather than to any one mailbox.
     */
    private val TRACKING = SavedAccount("Rampart tracking server", "companion", "tracking-token")

    fun trackingToken(): String? = load(TRACKING)

    /** Null when it was kept, otherwise the reason it was not. Blank forgets it. */
    fun setTrackingToken(value: String): String? {
        if (value.isBlank()) {
            forget(TRACKING)
            return null
        }
        return store(TRACKING, value)
    }

    fun forget(account: SavedAccount) {
        runCatching {
            val id = id(account)
            if (windows) {
                secretFile(id).deleteIfExists()
            } else {
                secretTool?.let {
                    ProcessBuilder(it, "clear", "service", SERVICE, "account", id).start().waitFor(20, TimeUnit.SECONDS)
                }
            }
        }
    }

    /** Stable, and never the address itself: the file name is not a place to publish who uses this machine. */
    private fun id(account: SavedAccount): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${account.email}@${account.server}".lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun secretFile(id: String): Path = Accounts.file().resolveSibling("secrets").resolve("$id.bin")

    /**
     * DPAPI reached through JNA, which unpacks a native library at runtime. Inside a
     * packaged app that unpack is the part that fails, so it is tried once, for real, and
     * the reason is kept rather than collapsed into a boolean.
     */
    private val dpapiProblem: String? by lazy {
        if (!windows) return@lazy "Not running on Windows."
        // JNA unpacks its native library to the temp directory. In a packaged app that
        // directory may not be writable, so it is pointed at the app's own data directory,
        // which by definition is.
        runCatching {
            val scratch = Accounts.file().resolveSibling("native")
            scratch.createDirectories()
            System.setProperty("jna.tmpdir", scratch.toString())
        }
        runCatching {
            val probe = Dpapi.protect(byteArrayOf(1, 2, 3), byteArrayOf(4))
            if (!Dpapi.unprotect(probe, byteArrayOf(4)).contentEquals(byteArrayOf(1, 2, 3))) {
                return@lazy "Windows returned a different value than it was given."
            }
            null
        }.getOrElse { "Windows' credential encryption could not be reached: ${it.cause?.message ?: it.message ?: it::class.simpleName}" }
    }
}

/**
 * Loaded by name so the class is only touched on Windows. JNA's Crypt32 bindings fail to
 * initialise anywhere else, and a mail client must not refuse to start on Linux because a
 * Windows-only class was on the path.
 */
private object Dpapi {
    private val util: Class<*> by lazy { Class.forName("com.sun.jna.platform.win32.Crypt32Util") }

    /**
     * The last argument of both calls is a prompt struct, not an Object, and asking for the
     * wrong signature fails at lookup rather than at call time. It is resolved by name for
     * the same reason the rest of this is: the class must not be touched off Windows.
     */
    private val prompt: Class<*> by lazy {
        Class.forName("com.sun.jna.platform.win32.WinCrypt\$CRYPTPROTECT_PROMPTSTRUCT")
    }

    fun protect(data: ByteArray, entropy: ByteArray): ByteArray =
        util.getMethod("cryptProtectData", ByteArray::class.java, ByteArray::class.java, Int::class.java, String::class.java, prompt)
            .invoke(null, data, entropy, 0, "Rampart", null) as ByteArray

    fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray =
        util.getMethod("cryptUnprotectData", ByteArray::class.java, ByteArray::class.java, Int::class.java, prompt)
            .invoke(null, data, entropy, 0, null) as ByteArray
}
