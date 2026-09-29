package org.rampart

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Resumable progress tracking for Maildir exports.
 *
 * Each folder path maps to a list of message IDs that have been exported.
 */
@Serializable
internal data class ExportProgress(
    val exported: Map<String, List<String>> = emptyMap(),
)

internal const val PROGRESS_FILE = ".rampart-export-progress.json"

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

private val counterSequence = AtomicLong(0)

/** Loads existing export progress from the target export directory. */
internal fun loadExportProgress(root: Path): ExportProgress {
    val file = root.resolve(PROGRESS_FILE)
    if (!file.exists()) return ExportProgress()
    return runCatching {
        json.decodeFromString<ExportProgress>(file.readText())
    }.getOrDefault(ExportProgress())
}

/** Saves export progress safely to the target export directory. */
internal fun saveExportProgress(root: Path, progress: ExportProgress): Boolean {
    return runCatching {
        root.createDirectories()
        val file = root.resolve(PROGRESS_FILE)
        val temp = Files.createTempFile(root, ".progress.", ".tmp")
        try {
            temp.writeText(json.encodeToString(progress))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
        true
    }.getOrDefault(false)
}

/** Checks whether a message has already been recorded as exported for a folder. */
internal fun isMessageExported(progress: ExportProgress, folderKey: String, messageId: String): Boolean {
    return progress.exported[folderKey]?.contains(messageId) == true
}

/** Returns updated export progress with the message recorded. */
internal fun recordMessageExported(progress: ExportProgress, folderKey: String, messageId: String): ExportProgress {
    val existing = progress.exported[folderKey].orEmpty()
    if (existing.contains(messageId)) return progress
    val updated = progress.exported + (folderKey to (existing + messageId))
    return progress.copy(exported = updated)
}

/** Sanitizes a single folder name segment for Maildir++ naming. */
internal fun sanitizeMaildirSegment(name: String): String {
    val cleaned = buildString(name.length) {
        for (ch in name) {
            if (ch.isISOControl()) continue
            if (ch in "<>:\"|?*/\\.") {
                append('_')
            } else {
                append(ch)
            }
        }
    }.trim('.', ' ', '_')

    if (cleaned.isEmpty()) return "Folder"
    return cleaned
}

/**
 * Maps folder path segments to a Maildir++ directory name.
 *
 * The root Inbox maps to an empty string. Subfolders start with a dot and join levels with dots.
 */
internal fun maildirFolderName(segments: List<String>, isInbox: Boolean = false): String {
    if (isInbox) return ""
    val filtered = segments.map { sanitizeMaildirSegment(it) }.filter { it.isNotBlank() }
    if (filtered.isEmpty()) return ""
    return "." + filtered.joinToString(".")
}

/** Resolves the Maildir++ relative directory name for a mailbox in an account. */
internal fun maildirFolderForMailbox(mailbox: Mailbox, allMailboxes: List<Mailbox>): String {
    if (mailbox.role == "inbox" && mailbox.parentId == null) {
        return ""
    }
    val byId = allMailboxes.associateBy { it.id }
    val segments = mutableListOf<String>()
    var current: Mailbox? = mailbox
    val seen = mutableSetOf<String>()
    var isInbox = false

    while (current != null && seen.add(current.id)) {
        if (current.role == "inbox" && current.parentId == null) {
            if (segments.isEmpty()) {
                isInbox = true
            }
            break
        }
        segments.add(0, current.name)
        current = current.parentId?.let { byId[it] }
    }

    if (isInbox) return ""
    if (segments.isEmpty()) segments.add(mailbox.name)
    return maildirFolderName(segments, isInbox = false)
}

/** Reserved keywords that represent protocol flags or machinery. */
private val RESERVED_KEYWORDS = setOf(
    "\$seen", "seen", "\\seen",
    "\$flagged", "flagged", "\\flagged",
    "\$draft", "draft", "\\draft",
    "\$answered", "answered", "\\answered",
    "\$deleted", "deleted", "\\deleted",
    "\$junk", "junk", "\\junk",
    "\$notjunk", "notjunk", "\\notjunk",
    "\$phishing", "phishing", "\\phishing",
    "\$recent", "recent", "\\recent",
)

/** Builds mapping from custom user keywords to Dovecot lowercase flag letters. */
internal fun buildDovecotKeywordMap(keywords: Collection<String>): Map<String, Char> {
    val map = mutableMapOf<String, Char>()
    var nextChar = 'a'
    for (kw in keywords) {
        val trimmed = kw.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.lowercase() in RESERVED_KEYWORDS) continue
        if (trimmed.startsWith("\$snooze-", ignoreCase = true)) continue
        val key = trimmed.lowercase()
        if (key !in map && nextChar <= 'z') {
            map[key] = nextChar
            nextChar++
        }
    }
    return map
}

/** Formats user keywords for the dovecot-keywords file. */
internal fun formatDovecotKeywords(keywords: List<String>): String {
    val valid = keywords.take(26)
    return valid.mapIndexed { index, kw -> "$index $kw" }.joinToString("\n")
}

/** Parses the dovecot-keywords file into a list of keyword names. */
internal fun parseDovecotKeywords(content: String): List<String> {
    val lines = content.lines()
    val result = mutableListOf<String>()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val parts = trimmed.split(" ", limit = 2)
        if (parts.size == 2) {
            result.add(parts[1].trim())
        }
    }
    return result
}

/**
 * Computes standard Maildir flag characters in sorted order.
 *
 * Supported flags are D (draft), F (flagged), R (answered), S (seen), T (deleted),
 * followed by any Dovecot keyword letters.
 */
internal fun maildirFlags(
    seen: Boolean,
    flagged: Boolean,
    keywords: Collection<String> = emptySet(),
    keywordMap: Map<String, Char> = emptyMap(),
): String {
    val chars = mutableSetOf<Char>()

    val isDraft = keywords.any { it.equals("\$draft", ignoreCase = true) || it.equals("draft", ignoreCase = true) || it.equals("\\draft", ignoreCase = true) }
    val isFlagged = flagged || keywords.any { it.equals("\$flagged", ignoreCase = true) || it.equals("flagged", ignoreCase = true) || it.equals("\\flagged", ignoreCase = true) }
    val isAnswered = keywords.any { it.equals("\$answered", ignoreCase = true) || it.equals("answered", ignoreCase = true) || it.equals("\\answered", ignoreCase = true) }
    val isSeen = seen || keywords.any { it.equals("\$seen", ignoreCase = true) || it.equals("seen", ignoreCase = true) || it.equals("\\seen", ignoreCase = true) }
    val isDeleted = keywords.any { it.equals("\$deleted", ignoreCase = true) || it.equals("deleted", ignoreCase = true) || it.equals("\\deleted", ignoreCase = true) }

    if (isDraft) chars.add('D')
    if (isFlagged) chars.add('F')
    if (isAnswered) chars.add('R')
    if (isSeen) chars.add('S')
    if (isDeleted) chars.add('T')

    for (kw in keywords) {
        val letter = keywordMap[kw.lowercase().trim()]
        if (letter != null) {
            chars.add(letter)
        }
    }

    return chars.sorted().joinToString("")
}

/** Computes the standard Maildir info suffix carrying flags. */
internal fun maildirInfoSuffix(
    seen: Boolean,
    flagged: Boolean,
    keywords: Collection<String> = emptySet(),
    keywordMap: Map<String, Char> = emptyMap(),
): String {
    return ":2," + maildirFlags(seen, flagged, keywords, keywordMap)
}

/** Generates a unique base filename for a Maildir message. */
internal fun maildirBaseName(emailId: String, receivedAt: String? = null, counter: Long = counterSequence.incrementAndGet()): String {
    val timestamp = runCatching {
        if (!receivedAt.isNullOrBlank()) Instant.parse(receivedAt).epochSecond else Instant.now().epochSecond
    }.getOrDefault(Instant.now().epochSecond)
    val pid = ProcessHandle.current().pid()
    val nano = System.nanoTime() % 1_000_000
    val safeId = safeFileName(emailId).replace('.', '_')
    return "${timestamp}.M${nano}P${pid}Q${counter}.${safeId}.rampart"
}

/** Writes raw email bytes safely to tmp first, then atomically moves into cur. */
internal fun writeMaildirMessage(
    folderDir: Path,
    rawContent: String,
    baseName: String,
    infoSuffix: String,
): Path {
    val tmpDir = folderDir.resolve("tmp")
    val curDir = folderDir.resolve("cur")
    val newDir = folderDir.resolve("new")
    tmpDir.createDirectories()
    curDir.createDirectories()
    newDir.createDirectories()

    val tmpFile = tmpDir.resolve(baseName)
    val curFile = curDir.resolve("$baseName$infoSuffix")

    tmpFile.writeText(rawContent, Charsets.UTF_8)
    return try {
        Files.move(tmpFile, curFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
        Files.move(tmpFile, curFile, StandardCopyOption.REPLACE_EXISTING)
    }
}

/** Formats progress string for display during export. */
internal fun formatExportProgress(folderName: String, current: Int, total: Int): String {
    return String.format(Locale.US, "Exporting %s: %,d of %,d", folderName, current, total)
}

/** Exports a single folder to a Maildir target. */
internal suspend fun exportMailbox(
    backend: MailBackend,
    mailbox: Mailbox,
    allMailboxes: List<Mailbox>,
    exportRoot: Path,
    onProgress: (folderName: String, current: Int, total: Int) -> Unit,
    isCancelled: () -> Boolean,
    progress: ExportProgress = loadExportProgress(exportRoot),
    singleFolderExport: Boolean = false,
): ExportProgress {
    var currentProgress = progress
    if (isCancelled()) return currentProgress

    val relPath = if (singleFolderExport) "" else maildirFolderForMailbox(mailbox, allMailboxes)
    val folderDir = if (relPath.isEmpty()) exportRoot else exportRoot.resolve(relPath)
    folderDir.resolve("cur").createDirectories()
    folderDir.resolve("new").createDirectories()
    folderDir.resolve("tmp").createDirectories()

    if (relPath.isNotEmpty()) {
        val maildirFile = folderDir.resolve("maildirfolder")
        if (!maildirFile.exists()) {
            runCatching { maildirFile.writeText("") }
        }
    }

    val allSummaries = mutableListOf<Summary>()
    var from = 0
    val pageSize = 100
    while (!isCancelled()) {
        val page = runCatching {
            backend.emails(mailbox.id, limit = pageSize, from = from)
        }.getOrDefault(emptyList())
        if (page.isEmpty()) break
        allSummaries.addAll(page)
        if (page.size < pageSize) break
        from += page.size
    }

    val total = if (allSummaries.isNotEmpty()) allSummaries.size else mailbox.total
    if (allSummaries.isEmpty()) {
        onProgress(mailbox.name, 0, total)
        return currentProgress
    }

    val userKeywords = allSummaries.flatMap { it.keywords }
        .map { it.trim() }
        .filter { it.isNotBlank() && it.lowercase() !in RESERVED_KEYWORDS && !it.startsWith("\$snooze-", ignoreCase = true) }
        .distinct()

    val keywordMap = buildDovecotKeywordMap(userKeywords)
    if (userKeywords.isNotEmpty()) {
        val dovecotFile = folderDir.resolve("dovecot-keywords")
        runCatching {
            dovecotFile.writeText(formatDovecotKeywords(keywordMap.keys.toList()))
        }
    }

    val folderKey = if (relPath.isEmpty()) "INBOX" else relPath

    for ((index, summary) in allSummaries.withIndex()) {
        if (isCancelled()) break
        val currentCount = index + 1
        onProgress(mailbox.name, currentCount, total)

        if (isMessageExported(currentProgress, folderKey, summary.id)) {
            continue
        }

        val raw = runCatching {
            backend.raw(summary.id, limit = 100L * 1024 * 1024)
        }.getOrNull()

        if (raw != null) {
            val flags = maildirFlags(summary.seen, summary.flagged, summary.keywords, keywordMap)
            val infoSuffix = ":2,$flags"
            val baseName = maildirBaseName(summary.id, summary.receivedAt)
            writeMaildirMessage(folderDir, raw, baseName, infoSuffix)
            currentProgress = recordMessageExported(currentProgress, folderKey, summary.id)
            saveExportProgress(exportRoot, currentProgress)
        }
    }

    return currentProgress
}

/** Exports every mailbox in an account to a Maildir++ directory tree. */
internal suspend fun exportAccount(
    backend: MailBackend,
    mailboxes: List<Mailbox>,
    exportRoot: Path,
    onProgress: (folderName: String, current: Int, total: Int) -> Unit,
    isCancelled: () -> Boolean,
): ExportProgress {
    var progress = loadExportProgress(exportRoot)
    exportRoot.createDirectories()

    val orderedBoxes = mailboxes.sortedWith(
        compareBy({ if (it.role == "inbox" || it.name.equals("inbox", ignoreCase = true)) 0 else 1 }, { it.name.lowercase() }),
    )

    for (box in orderedBoxes) {
        if (isCancelled()) break
        progress = exportMailbox(
            backend = backend,
            mailbox = box,
            allMailboxes = mailboxes,
            exportRoot = exportRoot,
            onProgress = onProgress,
            isCancelled = isCancelled,
            progress = progress,
            singleFolderExport = false,
        )
    }

    return progress
}
