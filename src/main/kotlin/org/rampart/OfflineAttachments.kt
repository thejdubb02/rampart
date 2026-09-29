package org.rampart

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Attachments kept for offline use (RAM-110).
 *
 * The local store already keeps bodies and pictures; this keeps the files as well, for the
 * Inbox's last 30 days, so a PDF on a train opens without a connection. **This one is local
 * by nature**: the point is a copy on this computer, and the server already holds the
 * original. The switch is per account and per computer for the same reason, and it lives
 * in a small settings file here rather than in synced settings: a laptop with room to spare
 * and a small one should not have to agree.
 *
 * **Nothing is written in the clear.** The store is SQLCipher under [Secrets.mailKey]; each
 * file here is sealed with AES-256-GCM under a key derived from that same mail key and a
 * label, as the briefing cache is (Briefing.kt). The index of what is kept (names, sizes,
 * dates) is sealed the same way, and even the file names are a keyed hash of the blob id,
 * so the folder says nothing about the mail without the key. Where there is no mail key
 * there is no store either, and the switch is unavailable with a sentence saying why.
 *
 * **Opening prefers the kept copy, online or not.** A JMAP blob id names fixed bytes, and
 * an IMAP one names a part of a message, which IMAP never changes; the GCM tag proves the
 * file is the one written. So the kept copy is the same file the server would send, and
 * using it saves the download. When it will not decrypt, the server is asked as usual.
 *
 * **What goes when it is full: the oldest message first**, by its received time, and among
 * the files of one message the one opened least recently. The promise is "the last 30 days
 * of your Inbox", so what is kept is decided by how recent the mail is; a file opened once
 * last month does not push out this morning's. A single file over the per-file cap is
 * skipped, never truncated.
 *
 * **Gentle.** One file at a time, a pause between them, on the IO threads, and the whole
 * pass stops at the first sign of no connection and waits for the next one. A failure is
 * one sentence in the setting, never a popup: nobody asked for this download just now.
 */

/** Per file, by default. A larger file is skipped rather than cut. */
internal const val OFFLINE_FILE_CAP = 25L * 1024 * 1024

/** For the whole account, by default. Past this the oldest go. */
internal const val OFFLINE_TOTAL_CAP = 2L * 1024 * 1024 * 1024

/** How far back the Inbox is kept. */
internal const val OFFLINE_WINDOW_DAYS = 30L

/** The per-file caps the setting offers. Kept well under what fits in memory, since a file is sealed whole. */
internal val OFFLINE_FILE_CHOICES = listOf(5L, 10L, 25L, 50L, 100L).map { it * 1024 * 1024 }

/** The totals the setting offers. */
internal val OFFLINE_TOTAL_CHOICES = listOf(500L, 1024L, 2048L, 5120L, 10240L).map { it * 1024 * 1024 }

/** One account's choice. Off by default. */
internal data class OfflinePrefs(
    val on: Boolean = false,
    val perFile: Long = OFFLINE_FILE_CAP,
    val total: Long = OFFLINE_TOTAL_CAP,
)

/** A file that could be kept: an attachment of an Inbox message. [receivedAt] is epoch millis. */
internal data class OfflineCandidate(
    val blobId: String,
    val emailId: String,
    val name: String,
    val type: String,
    val size: Long,
    val receivedAt: Long,
)

/** A file that is kept. [file] is its name in the account's folder; [lastUsed] is epoch millis. */
internal data class KeptAttachment(
    val blobId: String,
    val emailId: String,
    val name: String,
    val type: String,
    val size: Long,
    val receivedAt: Long,
    val lastUsed: Long,
    val file: String,
)

/** What one pass should do: fetch these, newest first, and delete those. */
internal data class OfflinePlan(
    val fetch: List<OfflineCandidate>,
    val drop: List<KeptAttachment>,
    /** How many files were left out for being over the per-file cap. */
    val tooBig: Int,
)

/**
 * What to keep, given what could be kept and what already is.
 *
 * The newest files that fit, and nothing else: inside the window, each under the per-file
 * cap, added newest message first until the total cap is reached. Anything kept that is
 * not in that set goes, which is how the oldest are evicted and how a message that left the
 * Inbox or aged out takes its files with it.
 *
 * [complete] false means the listing stopped early (the connection went). Then nothing is
 * dropped for being absent from it, only for being past the window or over the cap, so a
 * failed listing never empties the folder.
 */
internal fun planOffline(
    candidates: List<OfflineCandidate>,
    kept: List<KeptAttachment>,
    prefs: OfflinePrefs,
    now: Instant,
    complete: Boolean,
): OfflinePlan {
    val since = now.minus(Duration.ofDays(OFFLINE_WINDOW_DAYS)).toEpochMilli()
    val used = kept.associateBy { it.blobId }
    val inWindow = candidates.filter { it.receivedAt >= since }.distinctBy { it.blobId }
    val tooBig = inWindow.count { it.size > prefs.perFile }
    val ranked = inWindow.filter { it.size <= prefs.perFile }
        .sortedWith(
            compareByDescending<OfflineCandidate> { it.receivedAt }
                .thenByDescending { used[it.blobId]?.lastUsed ?: 0L }
                .thenBy { it.blobId },
        )
    val wanted = LinkedHashSet<String>()
    var total = 0L
    for (c in ranked) {
        if (total + c.size > prefs.total) continue
        wanted += c.blobId
        total += c.size
    }
    val fetch = ranked.filter { it.blobId in wanted && it.blobId !in used }
    val drop = if (complete) {
        kept.filter { it.blobId !in wanted }
    } else {
        // Only what is certainly unwanted: aged out, or too big for the cap as it now is,
        // then the oldest until what is left fits.
        val stale = kept.filter { it.receivedAt < since || it.size > prefs.perFile }
        stale + offlineEvictions(kept - stale.toSet(), prefs.total)
    }
    return OfflinePlan(fetch, drop, tooBig)
}

/**
 * The files to delete so the rest fit in [cap]: the oldest message first, and among the
 * files of one message the least recently opened first. Under the cap, nothing.
 */
internal fun offlineEvictions(kept: List<KeptAttachment>, cap: Long): List<KeptAttachment> {
    var total = kept.sumOf { it.size }
    if (total <= cap) return emptyList()
    val drop = ArrayList<KeptAttachment>()
    for (k in kept.sortedWith(compareBy<KeptAttachment> { it.receivedAt }.thenBy { it.lastUsed }.thenBy { it.blobId })) {
        if (total <= cap) break
        drop += k
        total -= k.size
    }
    return drop
}

/**
 * One account's sealed folder.
 *
 * Every write is to a temporary file in the same folder, holding ciphertext only, moved
 * over the real name: an interrupted write leaves the old file or none, never half of one
 * and never plaintext. Anything that will not open reads as absent.
 *
 * @param dir The account's folder. A parameter so the tests can use a temporary one.
 * @param mailKey The store's key, [Secrets.mailKey], from which both keys here are derived.
 */
internal class OfflineVault(val dir: Path, mailKey: String) {
    private val sealKey = SecretKeySpec(sha256("rampart offline attachments 1\n$mailKey"), "AES")
    private val nameKey = SecretKeySpec(sha256("rampart offline attachment names 1\n$mailKey"), "HmacSHA256")

    /** The file a blob is kept in: a keyed hash, so the name says nothing without the key. */
    fun fileFor(blobId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(nameKey)
        return hex(mac.doFinal(blobId.toByteArray(Charsets.UTF_8))).take(40) + ".bin"
    }

    /** Seals and keeps [bytes] for [blobId]. Returns the file name. */
    fun write(blobId: String, bytes: ByteArray): String {
        val name = fileFor(blobId)
        atomically(dir.resolve(name), seal(bytes, blobId))
        return name
    }

    /** The bytes kept for [blobId] in [file], or null when missing or not ours. */
    fun read(file: String, blobId: String): ByteArray? {
        val path = dir.resolve(file)
        if (!Files.exists(path)) return null
        return runCatching { open(Files.readAllBytes(path), blobId) }.getOrNull()
    }

    fun delete(file: String) {
        runCatching { Files.deleteIfExists(dir.resolve(file)) }
    }

    fun readIndex(): List<KeptAttachment> {
        val path = dir.resolve(INDEX)
        if (!Files.exists(path)) return emptyList()
        val plain = runCatching { open(Files.readAllBytes(path), INDEX) }.getOrNull() ?: return emptyList()
        val list = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(String(plain, Charsets.UTF_8)) }
            .getOrNull() as? JsonArray ?: return emptyList()
        return list.mapNotNull { (it as? JsonObject)?.let(::keptFrom) }
    }

    fun writeIndex(kept: List<KeptAttachment>) {
        val json = JsonArray(kept.map(::keptJson)).toString()
        atomically(dir.resolve(INDEX), seal(json.toByteArray(Charsets.UTF_8), INDEX))
    }

    /** Bytes on disk, sealed files and index together, for the setting. */
    fun usedBytes(): Long = runCatching {
        Files.list(dir).use { files -> files.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum() }
    }.getOrDefault(0L)

    private fun atomically(path: Path, sealed: ByteArray) {
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, "kept.", ".new")
        try {
            Files.write(temp, sealed)
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * AES-256-GCM, a fresh 96-bit nonce per file, and the blob id as associated data, so a
     * sealed file renamed onto another blob's name fails its tag instead of opening as the
     * wrong attachment.
     */
    private fun seal(bytes: ByteArray, bound: String): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sealKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(bound.toByteArray(Charsets.UTF_8))
        return MAGIC + iv + cipher.doFinal(bytes)
    }

    private fun open(bytes: ByteArray, bound: String): ByteArray? {
        if (bytes.size < MAGIC.size + 12 + 16 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val iv = bytes.copyOfRange(MAGIC.size, MAGIC.size + 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, sealKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(bound.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(MAGIC.size + 12, bytes.size))
    }

    private companion object {
        val MAGIC = "ROA1".toByteArray()
        const val INDEX = "index.bin"
    }
}

private fun keptJson(k: KeptAttachment): JsonObject = buildJsonObject {
    put("blobId", k.blobId)
    put("emailId", k.emailId)
    put("name", k.name)
    put("type", k.type)
    put("size", k.size)
    put("receivedAt", k.receivedAt)
    put("lastUsed", k.lastUsed)
    put("file", k.file)
}

private fun keptFrom(o: JsonObject): KeptAttachment? {
    fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
    fun number(key: String) = (o[key] as? JsonPrimitive)?.longOrNull
    return KeptAttachment(
        blobId = text("blobId") ?: return null,
        emailId = text("emailId").orEmpty(),
        name = text("name").orEmpty(),
        type = text("type").orEmpty(),
        size = number("size") ?: return null,
        receivedAt = number("receivedAt") ?: 0L,
        lastUsed = number("lastUsed") ?: 0L,
        file = text("file") ?: return null,
    )
}

private fun sha256(text: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

/** Where an account's kept files live: beside its store, named by a hash of the account. */
internal fun offlineDir(account: String): Path =
    Accounts.file().resolveSibling("offline-" + hex(sha256(account.trim().lowercase())).take(24))

/** One account as the background pass needs it. Built fresh each pass from the window's state. */
internal class OfflineAccount(
    val key: String,
    val backend: MailBackend,
    /** [Secrets.mailKey] for the account, or null when there is nowhere safe to keep one. */
    val mailKey: () -> String?,
    /** The Inbox's id, or null while the folders have not been read. */
    val inboxId: String?,
    /** Whether the server can be asked for only the messages with an attachment (JMAP can, IMAP cannot). */
    val canFilterAttachment: Boolean,
)

/** What the setting shows for one account. [available] null means not checked yet. */
internal data class OfflineStatus(
    val available: Boolean? = null,
    val usedBytes: Long = 0L,
    val files: Int = 0,
    val working: Boolean = false,
    /** The last thing that went wrong, as one sentence, or null. */
    val problem: String? = null,
)

/** The sentence shown in place of the switch where there is no store key. */
internal const val OFFLINE_UNAVAILABLE =
    "Not available for this account: there is no safe place on this computer to keep the key " +
        "the files would be encrypted with, and Rampart does not keep mail on disk unencrypted."

/** The background downloads and the setting's state, for the whole application. */
internal object OfflineAttachments {
    private val prefsFile = JsonStore("offline-attachments.json")
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val locks = ConcurrentHashMap<String, Any>()
    private val keys = ConcurrentHashMap<String, String>()

    /** What each message's files are, by account and message id. A message's files never change. */
    private val listed = ConcurrentHashMap<String, List<OfflineCandidate>>()

    /** Blobs the server refused, and when, so a refused file is not asked for on every pass. */
    private val refused = ConcurrentHashMap<String, Long>()

    private val _status = MutableStateFlow<Map<String, OfflineStatus>>(emptyMap())
    val status: StateFlow<Map<String, OfflineStatus>> = _status

    private fun lock(account: String) = locks.getOrPut(account) { Any() }

    private fun publish(account: String, change: OfflineStatus.() -> OfflineStatus) {
        synchronized(_status) {
            val now = _status.value
            _status.value = now + (account to (now[account] ?: OfflineStatus()).change())
        }
    }

    fun prefs(account: String): OfflinePrefs {
        val o = prefsFile.read()[account] as? JsonObject ?: return OfflinePrefs()
        fun number(key: String) = (o[key] as? JsonPrimitive)?.longOrNull
        return OfflinePrefs(
            on = (o["on"] as? JsonPrimitive)?.contentOrNull == "true",
            perFile = number("perFile")?.takeIf { it > 0 } ?: OFFLINE_FILE_CAP,
            total = number("total")?.takeIf { it > 0 } ?: OFFLINE_TOTAL_CAP,
        )
    }

    /**
     * Saves the choice. Turning it off deletes every kept file for the account there and
     * then; any download in flight notices before it writes and throws its bytes away.
     */
    fun setPrefs(account: String, prefs: OfflinePrefs) {
        prefsFile.write {
            put(account, buildJsonObject {
                put("on", prefs.on)
                put("perFile", prefs.perFile)
                put("total", prefs.total)
            })
        }
        if (!prefs.on) wipe(account)
        wake.trySend(Unit)
    }

    /** Deletes everything kept for [account]. */
    fun wipe(account: String, dir: Path = offlineDir(account)) {
        synchronized(lock(account)) {
            runCatching { dir.toFile().deleteRecursively() }
            listed.keys.removeIf { it.startsWith("$account\n") }
        }
        publish(account) { copy(usedBytes = 0L, files = 0, working = false, problem = null) }
    }

    /**
     * The kept copy of [attachment], written in the clear to [into] because the person asked
     * to open or save it, or null when there is none. Marks it as used, which is what keeps
     * it ahead of its siblings when the folder is full.
     */
    fun restore(account: String, mailKey: () -> String?, attachment: Attachment, into: Path): Path? {
        val dir = offlineDir(account)
        // The folder first: nothing kept means no reason to ask the keychain for anything.
        if (!Files.exists(dir)) return null
        val key = keyFor(account, mailKey) ?: return null
        return synchronized(lock(account)) {
            val vault = OfflineVault(dir, key)
            val index = vault.readIndex()
            val entry = index.firstOrNull { it.blobId == attachment.blobId } ?: return@synchronized null
            val bytes = vault.read(entry.file, entry.blobId) ?: return@synchronized null
            val dest = uniqueIn(into, attachment.name)
            Files.write(dest, bytes)
            val now = System.currentTimeMillis()
            vault.writeIndex(index.map { if (it.blobId == entry.blobId) it.copy(lastUsed = now) else it })
            dest
        }
    }

    /** The account's store key, asked of the keychain once and then remembered for the run. */
    private fun keyFor(account: String, mailKey: () -> String?): String? =
        keys[account] ?: runCatching { mailKey() }.getOrNull()?.also { keys[account] = it }

    /**
     * Finds out whether [account] can keep files at all, for the setting, before any pass
     * has run. Off the UI thread: it may ask the operating system's keychain.
     */
    fun noteKey(account: String, mailKey: () -> String?) {
        val key = keyFor(account, mailKey)
        publish(account) { copy(available = key != null) }
    }

    /**
     * Runs for as long as the window does. A pass every quarter of an hour, or sooner when
     * the setting changes; a pass that finds no connection stops and waits for the next.
     */
    suspend fun run(accounts: () -> List<OfflineAccount>) {
        while (true) {
            // Read on the caller's thread, which is where the window's state lives; every
            // file and network touch below is on the IO threads.
            val now = accounts()
            withContext(Dispatchers.IO) {
                runCatching { sweepRemoved() }
                for (account in now) {
                    try {
                        pass(account)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        publish(account.key) { copy(working = false, problem = "Stopped: " + (e.message ?: e.toString())) }
                    }
                }
            }
            withTimeoutOrNull(15 * 60 * 1000L) { wake.receive() }
        }
    }

    /**
     * Deletes the folders of accounts that are no longer signed in on this computer.
     *
     * Rampart has no remove-account button today; an account goes by its line leaving the
     * accounts file. Checked against that file rather than the sessions open now, so an
     * account that simply failed to sign in this time keeps its files. An unreadable or
     * empty accounts file deletes nothing.
     */
    private fun sweepRemoved() {
        val known = Accounts.read().map { "${it.email}@${it.server}" }
        if (known.isEmpty()) return
        val wanted = known.map { offlineDir(it).fileName.toString() }.toSet()
        val parent = Accounts.file().parent ?: return
        Files.list(parent).use { entries ->
            entries.filter { it.fileName.toString().startsWith("offline-") && Files.isDirectory(it) }
                .filter { it.fileName.toString() !in wanted }
                .forEach { runCatching { it.toFile().deleteRecursively() } }
        }
    }

    private suspend fun pass(account: OfflineAccount) {
        val key = keyFor(account.key, account.mailKey)
        publish(account.key) { copy(available = key != null) }
        if (key == null) return
        val prefs = prefs(account.key)
        val dir = offlineDir(account.key)
        if (!prefs.on) {
            // Off, including off since before this run began: nothing may be left behind.
            if (Files.exists(dir)) wipe(account.key, dir)
            return
        }
        val inbox = account.inboxId ?: return
        val vault = OfflineVault(dir, key)
        val kept = withContext(Dispatchers.IO) { synchronized(lock(account.key)) { vault.readIndex() } }
        publish(account.key) { copy(working = true, usedBytes = vault.usedBytes(), files = kept.size) }
        val now = Instant.now()
        val listing = withContext(Dispatchers.IO) { listCandidates(account, inbox, now) }
        val plan = planOffline(listing.candidates, kept, prefs, now, listing.complete)
        withContext(Dispatchers.IO) {
            synchronized(lock(account.key)) {
                if (plan.drop.isNotEmpty()) {
                    plan.drop.forEach { vault.delete(it.file) }
                    val gone = plan.drop.map { it.blobId }.toSet()
                    vault.writeIndex(vault.readIndex().filterNot { it.blobId in gone })
                }
            }
        }
        var problem: String? = listing.problem
        for (c in plan.fetch) {
            if (!prefs(account.key).on) return
            val refusedAt = refused["${account.key}\n${c.blobId}"]
            if (refusedAt != null && now.toEpochMilli() - refusedAt < 24 * 3600 * 1000L) continue
            val bytes = try {
                withContext(Dispatchers.IO) {
                    account.backend.blob(Attachment(c.blobId, c.name, c.type, c.size), prefs.perFile)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                problem = "Paused until the server can be reached again: " + (e.message ?: e.toString())
                break
            } catch (e: Exception) {
                refused["${account.key}\n${c.blobId}"] = now.toEpochMilli()
                problem = "The server would not hand over one of the files: " + (e.message ?: e.toString())
                continue
            }
            if (bytes == null) {
                // Null is a refusal or a file larger than it said, not a lost connection.
                refused["${account.key}\n${c.blobId}"] = now.toEpochMilli()
                problem = "The server would not hand over one of the files, or it was over the size limit."
                continue
            }
            val stored = withContext(Dispatchers.IO) {
                synchronized(lock(account.key)) {
                    // Turned off while this file was on its way: throw the bytes away.
                    if (!prefs(account.key).on) return@synchronized false
                    val file = vault.write(c.blobId, bytes)
                    val index = vault.readIndex().filterNot { it.blobId == c.blobId } +
                        KeptAttachment(c.blobId, c.emailId, c.name, c.type, bytes.size.toLong(), c.receivedAt, 0L, file)
                    val over = offlineEvictions(index, prefs.total).toSet()
                    over.forEach { vault.delete(it.file) }
                    vault.writeIndex(index - over)
                    true
                }
            }
            if (!stored) return
            val count = withContext(Dispatchers.IO) { synchronized(lock(account.key)) { vault.readIndex().size } }
            publish(account.key) { copy(usedBytes = vault.usedBytes(), files = count) }
            // One at a time with a breath between, so this never competes with what the
            // person is doing for the connection.
            delay(1_000)
        }
        val count = withContext(Dispatchers.IO) { synchronized(lock(account.key)) { vault.readIndex().size } }
        publish(account.key) { copy(working = false, usedBytes = vault.usedBytes(), files = count, problem = problem) }
    }

    private class Listing(val candidates: List<OfflineCandidate>, val complete: Boolean, val problem: String?)

    /**
     * The files of Inbox messages from the window, newest first.
     *
     * JMAP is asked only for messages with an attachment. IMAP cannot be, so every message
     * in the window is looked at once and remembered, since a message's files never change.
     */
    private fun listCandidates(account: OfflineAccount, inbox: String, now: Instant): Listing {
        val since = now.minus(Duration.ofDays(OFFLINE_WINDOW_DAYS)).toEpochMilli()
        val filters = QuickFilters(attachment = account.canFilterAttachment)
        val found = ArrayList<OfflineCandidate>()
        var from = 0
        try {
            while (from < 1_000) {
                val page = account.backend.emails(inbox, limit = 50, from = from, filters = filters)
                var older = false
                for (m in page) {
                    val at = runCatching { Instant.parse(m.receivedAt).toEpochMilli() }.getOrNull() ?: continue
                    if (at < since) { older = true; continue }
                    val cacheKey = "${account.key}\n${m.id}"
                    val files = listed[cacheKey] ?: account.backend.attachments(m.id)
                        .filter { !it.inline && it.blobId.isNotBlank() }
                        .map { OfflineCandidate(it.blobId, m.id, it.name, it.type, it.size, at) }
                        .also { listed[cacheKey] = it }
                    found += files
                }
                if (older || page.size < 50) return Listing(found, true, null)
                from += page.size
            }
            return Listing(found, true, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Listing(found, false, "Paused until the server can be reached again: " + (e.message ?: e.toString()))
        }
    }
}
