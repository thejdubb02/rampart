package org.rampart

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Locale
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal const val IMPORT_PROGRESS_FILE = ".rampart-import-progress.json"

@Serializable
internal data class ImportProgress(val processed: Set<String> = emptySet())

internal data class ImportFlags(val keywords: Set<String> = emptySet())

internal data class ImportResult(
    val imported: Int = 0,
    val skippedDuplicates: Int = 0,
    val skippedOversize: Int = 0,
    val failed: Int = 0,
)

private val importJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

internal fun loadImportProgress(root: Path): ImportProgress {
    val file = root.resolve(IMPORT_PROGRESS_FILE)
    if (!file.exists()) return ImportProgress()
    return runCatching { importJson.decodeFromString<ImportProgress>(file.readText()) }
        .getOrDefault(ImportProgress())
}

internal fun saveImportProgress(root: Path, progress: ImportProgress): Boolean = runCatching {
    root.createDirectories()
    val target = root.resolve(IMPORT_PROGRESS_FILE)
    val temporary = Files.createTempFile(root, ".import-progress.", ".tmp")
    try {
        temporary.writeText(importJson.encodeToString(progress))
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(temporary)
    }
    true
}.getOrDefault(false)

/** Removes the one quoting layer added by the current mbox container. */
internal fun unquoteMboxLine(line: String): String =
    if (line.matches(Regex("^>+From .*"))) line.substring(1) else line

/** A conservative envelope test keeps an ordinary body line beginning with From intact. */
internal fun isMboxEnvelope(line: String): Boolean =
    Regex("^From\\s+\\S+\\s+(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\s+.+", RegexOption.IGNORE_CASE).matches(line)

/** Splits mboxo and mboxrd text. The streaming importer uses the same line rules. */
internal fun splitMbox(content: String): List<String> {
    val messages = mutableListOf<String>()
    var current: StringBuilder? = null
    content.lineSequence().forEach { line ->
        if (isMboxEnvelope(line)) {
            current?.let { messages += it.toString().trimEnd('\n') + "\n" }
            current = StringBuilder()
        } else if (current != null) {
            current!!.append(unquoteMboxLine(line)).append('\n')
        }
    }
    current?.let { if (it.isNotEmpty()) messages += it.toString() }
    return messages
}

/** Streams one mbox message at a time and never retains the complete archive. */
internal fun forEachMboxMessage(
    path: Path,
    isCancelled: () -> Boolean = { false },
    action: (index: Int, bytes: ByteArray) -> Unit,
) {
    Files.newInputStream(path).use { input ->
        BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1)).use { reader ->
            var current: StringBuilder? = null
            var index = 0
            while (!isCancelled()) {
                val line = reader.readLine() ?: break
                if (isMboxEnvelope(line)) {
                    current?.let { action(index++, it.toString().toByteArray(Charsets.ISO_8859_1)) }
                    current = StringBuilder()
                } else if (current != null) {
                    current!!.append(unquoteMboxLine(line)).append("\r\n")
                }
            }
            if (!isCancelled()) current?.let { if (it.isNotEmpty()) action(index, it.toString().toByteArray(Charsets.ISO_8859_1)) }
        }
    }
}

/** Reads the standard Maildir flags and Dovecot keyword letters from an info suffix. */
internal fun parseMaildirInfo(name: String, dovecotKeywords: Map<Char, String> = emptyMap()): ImportFlags {
    val info = name.substringAfterLast(":2,", "")
    val keywords = linkedSetOf<String>()
    if ('S' in info) keywords += "\$seen"
    if ('F' in info) keywords += "\$flagged"
    if ('R' in info) keywords += "\$answered"
    if ('D' in info) keywords += "\$draft"
    info.filter { it in 'a'..'z' }.forEach { dovecotKeywords[it]?.let(keywords::add) }
    return ImportFlags(keywords)
}

/** Maps Dovecot keyword indexes to the lowercase letters used in Maildir filenames. */
internal fun parseDovecotKeywordMapping(content: String): Map<Char, String> = buildMap {
    content.lineSequence().forEach { line ->
        val pieces = line.trim().split(Regex("\\s+"), limit = 2)
        val index = pieces.firstOrNull()?.toIntOrNull()
        if (pieces.size == 2 && index != null && index in 0..25) put(('a'.code + index).toChar(), pieces[1])
    }
}

internal fun mozillaKeywords(status: String?, status2: String?): Set<String> = buildSet {
    val first = status?.trim()?.toLongOrNull(16) ?: 0L
    val second = status2?.trim()?.toLongOrNull(16) ?: 0L
    if (first and 0x0001L != 0L) add("\$seen")
    if (first and 0x0002L != 0L) add("\$answered")
    if (first and 0x0004L != 0L) add("\$flagged")
    if (first and 0x0400L != 0L || second and 0x0400L != 0L) add("\$draft")
}

internal fun chooseImportDate(messageDate: Instant?, fileTime: Instant): Instant = messageDate ?: fileTime

internal fun shouldSkipDuplicate(messageId: String?, existing: Set<String>): Boolean =
    messageId?.let(::normalMessageId)?.takeIf { it.isNotBlank() }?.let { wanted ->
        existing.any { normalMessageId(it) == wanted }
    } == true

private fun normalMessageId(value: String): String = value.trim().removePrefix("<").removeSuffix(">").lowercase()

internal fun formatImportProgress(folderName: String, current: Int, total: Int): String =
    String.format(Locale.US, "Importing %s: %,d of %,d", folderName, current, total)

private data class SourceMessage(
    val key: String,
    val folder: List<String>,
    val path: Path,
    val flags: Set<String>,
    val fileTime: Instant,
    val temporary: Boolean = false,
)

private fun maildirFolders(root: Path): List<Pair<Path, List<String>>> {
    val folders = mutableListOf<Pair<Path, List<String>>>()
    if (root.resolve("cur").isDirectory() || root.resolve("new").isDirectory()) folders += root to emptyList()
    Files.list(root).use { paths ->
        paths.filter { it.isDirectory() && it.name.startsWith(".") && it.name !in setOf(".", "..") }
            .forEach { directory ->
                if (directory.resolve("cur").isDirectory() || directory.resolve("new").isDirectory()) {
                    folders += directory to directory.name.removePrefix(".").split('.').filter(String::isNotBlank)
                }
            }
    }
    return folders
}

private fun maildirMessages(root: Path): List<SourceMessage> = buildList {
    val rootKeywordFile = root.resolve("dovecot-keywords")
    val rootKeywordMap = if (rootKeywordFile.exists()) parseDovecotKeywordMapping(rootKeywordFile.readText()) else emptyMap()
    for ((directory, folder) in maildirFolders(root)) {
        val keywordFile = directory.resolve("dovecot-keywords")
        val keywordMap = if (keywordFile.exists()) parseDovecotKeywordMapping(keywordFile.readText()) else rootKeywordMap
        for (part in listOf("cur", "new")) {
            val messages = directory.resolve(part)
            if (!messages.isDirectory()) continue
            Files.list(messages).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { file ->
                    val flags = parseMaildirInfo(file.name, keywordMap).keywords.toMutableSet()
                    if (part == "new") flags.remove("\$seen")
                    add(SourceMessage("maildir:${root.relativize(file)}", folder, file, flags, Files.getLastModifiedTime(file).toInstant()))
                }
            }
        }
    }
}

private fun messageDetails(path: Path, baseFlags: Set<String>, fallback: Instant): Triple<String?, Set<String>, Instant> {
    val message = Files.newInputStream(path).use { MimeMessage(Session.getInstance(Properties()), it) }
    val id = message.getHeader("Message-ID")?.firstOrNull()
    val flags = baseFlags + mozillaKeywords(
        message.getHeader("X-Mozilla-Status")?.firstOrNull(),
        message.getHeader("X-Mozilla-Status2")?.firstOrNull(),
    )
    return Triple(id, flags, chooseImportDate(message.sentDate?.toInstant(), fallback))
}

private fun existingMessageIds(backend: MailBackend, mailboxId: String): MutableSet<String> {
    val result = mutableSetOf<String>()
    var offset = 0
    while (true) {
        val page = backend.emails(mailboxId, limit = 250, from = offset)
        page.mapNotNull { it.messageId.takeIf(String::isNotBlank) }.mapTo(result, ::normalMessageId)
        if (page.size < 250) break
        offset += page.size
    }
    return result
}

/** Imports selected EML files, a Maildir tree, or mbox files into one account. */
internal fun importMail(
    backend: MailBackend,
    sources: List<Path>,
    destination: Mailbox,
    recreateFolders: Boolean,
    progressRoot: Path,
    onProgress: (folderName: String, current: Int, total: Int) -> Unit,
    isCancelled: () -> Boolean,
): ImportResult {
    var progress = loadImportProgress(progressRoot)
    var result = ImportResult()
    val mailboxIds = mutableMapOf<List<String>, String>(emptyList<String>() to destination.id)
    val duplicateCache = mutableMapOf<String, MutableSet<String>>()

    fun mailboxFor(parts: List<String>): Pair<String, String> {
        if (!recreateFolders || parts.isEmpty()) return destination.id to destination.name
        var parent = destination.id
        val path = mutableListOf<String>()
        for (part in parts) {
            path += part
            parent = mailboxIds.getOrPut(path.toList()) {
                backend.mailboxes().firstOrNull { it.parentId == parent && it.name.equals(part, true) }?.id
                    ?: backend.createMailbox(part, parent)
            }
        }
        return parent to parts.last()
    }

    fun importOne(source: SourceMessage, current: Int, total: Int) {
        if (isCancelled()) return
        val (mailboxId, folderName) = mailboxFor(source.folder)
        onProgress(folderName, current, total)
        if (source.key in progress.processed) return
        try {
            val size = Files.size(source.path)
            if (backend.maxSizeUpload in 1 until size) {
                result = result.copy(skippedOversize = result.skippedOversize + 1)
            } else {
                val (messageId, flags, receivedAt) = messageDetails(source.path, source.flags, source.fileTime)
                val existing = duplicateCache.getOrPut(mailboxId) { existingMessageIds(backend, mailboxId) }
                if (shouldSkipDuplicate(messageId, existing)) {
                    result = result.copy(skippedDuplicates = result.skippedDuplicates + 1)
                } else {
                    backend.importMessage(source.path, mailboxId, ImportedMessage(flags, receivedAt))
                    messageId?.let(::normalMessageId)?.takeIf(String::isNotBlank)?.let(existing::add)
                    result = result.copy(imported = result.imported + 1)
                }
            }
            progress = progress.copy(processed = progress.processed + source.key)
            saveImportProgress(progressRoot, progress)
        } catch (_: Exception) {
            result = result.copy(failed = result.failed + 1)
        } finally {
            if (source.temporary) Files.deleteIfExists(source.path)
        }
    }

    for (source in sources) {
        if (isCancelled()) break
        when {
            source.isDirectory() -> {
                val messages = maildirMessages(source)
                val totals = messages.groupingBy { it.folder }.eachCount()
                val counts = mutableMapOf<List<String>, Int>()
                messages.forEach { message ->
                    val current = counts.getOrDefault(message.folder, 0) + 1
                    counts[message.folder] = current
                    importOne(message, current, totals.getValue(message.folder))
                }
            }
            source.extension.equals("eml", true) -> {
                importOne(
                    SourceMessage("eml:${source.toAbsolutePath()}", emptyList(), source, emptySet(), Files.getLastModifiedTime(source).toInstant()),
                    1,
                    1,
                )
            }
            else -> {
                var total = 0
                forEachMboxMessage(source, isCancelled) { _, _ -> total++ }
                val folder = if (recreateFolders) listOf(source.name.substringBeforeLast('.')) else emptyList()
                forEachMboxMessage(source, isCancelled) { index, bytes ->
                    if (!isCancelled()) {
                        val temporary = Files.createTempFile("rampart-import-", ".eml")
                        Files.write(temporary, bytes)
                        importOne(
                            SourceMessage(
                                "mbox:${source.toAbsolutePath()}:$index",
                                folder,
                                temporary,
                                emptySet(),
                                Files.getLastModifiedTime(source).toInstant(),
                                temporary = true,
                            ),
                            index + 1,
                            total,
                        )
                    }
                }
            }
        }
    }
    return result
}
