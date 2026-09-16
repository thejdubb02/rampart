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

    fun available(): Boolean = if (windows) dpapiWorks else secretTool != null

    fun store(account: SavedAccount, password: String): Boolean = runCatching {
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

    private val dpapiWorks: Boolean by lazy {
        windows && runCatching { Dpapi.unprotect(Dpapi.protect(byteArrayOf(1), byteArrayOf(2)), byteArrayOf(2)) }.isSuccess
    }
}

/**
 * Loaded by name so the class is only touched on Windows. JNA's Crypt32 bindings fail to
 * initialise anywhere else, and a mail client must not refuse to start on Linux because a
 * Windows-only class was on the path.
 */
private object Dpapi {
    private val util: Class<*> by lazy { Class.forName("com.sun.jna.platform.win32.Crypt32Util") }

    fun protect(data: ByteArray, entropy: ByteArray): ByteArray =
        util.getMethod("cryptProtectData", ByteArray::class.java, ByteArray::class.java, Int::class.java, String::class.java, Any::class.java)
            .invoke(null, data, entropy, 0, "Rampart", null) as ByteArray

    fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray =
        util.getMethod("cryptUnprotectData", ByteArray::class.java, ByteArray::class.java, Int::class.java, Any::class.java)
            .invoke(null, data, entropy, 0, null) as ByteArray
}
