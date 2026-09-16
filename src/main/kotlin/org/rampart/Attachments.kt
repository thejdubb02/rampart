package org.rampart

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private val RESERVED_DEVICE = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    for (n in 1..9) {
        add("COM$n")
        add("LPT$n")
    }
}

private const val MAX_NAME = 120

/**
 * Turns a name chosen by the sender into something that can be written to a
 * folder the user picked. The sender is not on our side.
 */
fun safeFileName(proposed: String): String {
    // Only the last component is kept, so a name like ../../etc/passwd cannot
    // walk out of the folder the user chose.
    var name = proposed.replace('\\', '/').substringAfterLast('/')

    name = buildString(name.length) {
        for (ch in name) {
            if (ch.isISOControl()) continue
            if (ch in "<>:\"|?*") continue
            append(ch)
        }
    }

    // Windows silently drops trailing dots and spaces, so "evil.exe . . " would
    // otherwise land on disk as a different name than the one we thought we checked.
    name = name.trimEnd('.', ' ')

    if (name.isEmpty() || name == "." || name == "..") return "attachment"
    if (name.substringBefore('.').uppercase() in RESERVED_DEVICE) return "attachment"

    name = capKeepingExtension(name)
    if (name.isEmpty() || name == "." || name == "..") return "attachment"
    if (name.substringBefore('.').uppercase() in RESERVED_DEVICE) return "attachment"
    return name
}

private fun capKeepingExtension(name: String): String {
    if (name.length <= MAX_NAME) return name
    val dot = name.lastIndexOf('.')
    // The extension is what the OS uses to decide how to open the file, so a
    // long stem is truncated first.
    if (dot <= 0) return name.take(MAX_NAME).trimEnd('.', ' ')
    val ext = name.substring(dot)
    if (ext.length >= MAX_NAME) return name.take(MAX_NAME).trimEnd('.', ' ')
    val stem = name.substring(0, dot).take(MAX_NAME - ext.length).trimEnd('.', ' ')
    if (stem.isEmpty()) return "attachment"
    return stem + ext
}

/**
 * A second save of the same name must not clobber the file already on disk.
 */
fun uniqueIn(directory: Path, name: String): Path {
    val base = safeFileName(name)
    val first = directory.resolve(base)
    if (!Files.exists(first)) return first

    val dot = base.lastIndexOf('.')
    val stem: String
    val ext: String
    if (dot <= 0) {
        stem = base
        ext = ""
    } else {
        stem = base.substring(0, dot)
        ext = base.substring(dot)
    }
    // A hard ceiling, so a folder already full of copies cannot hang the save.
    for (n in 2..9999) {
        val candidate = directory.resolve("$stem ($n)$ext")
        if (!Files.exists(candidate)) return candidate
    }
    error("There are too many files with that name in the folder already.")
}

fun humanSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes bytes"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.1f GB", gb)
}
