package org.rampart

/** One version, and the one sentence saying what changed in it. */
internal data class Change(val version: String, val date: String, val what: String)

/**
 * What changed, read from a file the build wrote out of the git log.
 *
 * Every commit is a version, because the version is the commit count, and every commit
 * subject is already one plain sentence: that is the house style and this is what it was
 * for. So there is no changelog to maintain by hand and no second place for the two to
 * disagree.
 *
 * Baked into the build rather than fetched, so it works with no network and always
 * describes the copy it is inside rather than whatever has been released since.
 */
internal fun changelog(): List<Change> = runCatching {
    val text = object {}.javaClass.getResourceAsStream("/changelog.tsv")
        ?.bufferedReader()?.use { it.readText() } ?: return emptyList()
    text.lineSequence().mapNotNull(::changeOf).toList()
}.getOrDefault(emptyList())

/** One line, or null when it is blank or malformed. A bad line is skipped, never fatal. */
internal fun changeOf(line: String): Change? {
    val parts = line.split('\t')
    if (parts.size < 3) return null
    val version = parts[0].trim()
    val date = parts[1].trim()
    val what = parts.drop(2).joinToString("\t").trim()
    if (version.isEmpty() || what.isEmpty()) return null
    return Change(version, date, what)
}

/**
 * Whether this entry is the copy that is running.
 *
 * [Updates.current] is null outside a packaged build, and then nothing is marked rather
 * than the newest line being marked on the assumption that it is what somebody is on.
 */
internal fun isRunning(change: Change, running: String? = Updates.current): Boolean =
    running != null && change.version == running
