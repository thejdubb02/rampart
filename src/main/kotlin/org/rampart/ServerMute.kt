package org.rampart

import java.util.concurrent.ConcurrentHashMap

/**
 * Muting a conversation as a Sieve rule on the server, so it holds with Rampart closed.
 *
 * The local list in [Muted] quietens a conversation only when Rampart next sees a message
 * from it, and only on the computer that muted it. The server can do better, because a
 * reply names the conversation it belongs to: every reply carries the first message's
 * Message-ID in References, and a direct answer to it carries it in In-Reply-To. So a rule
 * matching that one id in either header catches the replies at delivery, in every client,
 * with nothing running here. [Muted] stays for accounts with no Sieve, and says so.
 *
 * **The rules are not in the builder's model.** The format Bulwark and the filters page
 * share has no field for an arbitrary header, and a rule it cannot read would make the
 * whole script read-only on both. So a mute is written below the builder's rules, in the
 * part of the script both clients keep verbatim, between two marker comments. The markers
 * are how unmute finds exactly its own block and nothing else, and the thread id in the
 * first one is how Rampart shows the conversation as muted without asking again.
 *
 *     # Rampart mute: thread Tabc123
 *     if header :contains ["References", "In-Reply-To"] "<root@example.org>" {
 *         fileinto :flags "\\Seen" :specialuse "\\Archive" "Archive";
 *     }
 *     # Rampart mute end
 *
 * The thread id is the server's own, and it is safe to keep here because the script lives
 * on the same account that numbered the thread.
 */

internal const val MUTE_MARK = "# Rampart mute: thread "
internal const val MUTE_END = "# Rampart mute end"

/**
 * What a mute rule needs declared at the top of the script.
 *
 * `fileinto` to file it, `imap4flags` for the `:flags` that marks it read, and
 * `special-use` for filing by the Archive role rather than by a name. Stalwart 0.16
 * advertises all three.
 */
internal val MUTE_NEEDS = listOf("fileinto", "imap4flags", "special-use")

/** Thrown for a conversation that cannot be written into a rule. The message is the reason. */
internal class MuteRefused(reason: String) : Exception(reason)

/**
 * The block for one muted conversation.
 *
 * Archive is targeted with `:specialuse "\\Archive"` and a name as the fallback, rather
 * than by name or by mailbox id alone. A name breaks the day somebody renames the folder
 * or uses a server that calls it "Archives". A mailbox id survives a rename but not the
 * folder being deleted and made again. The role survives both, and Stalwart resolves it
 * first, then tries the name, then files into the Inbox rather than losing the message.
 *
 * `:flags` rather than a separate `addflag`, so marking read belongs to this one filing
 * and does not leak into whatever a later rule does with the same message.
 *
 * The root goes in with its angle brackets, because the headers carry them and without
 * them `abc@example.org` would also match `xabc@example.org`. Quotes and backslashes in it
 * are escaped by [sieveQuote]; a line break cannot be, so an id holding one is refused.
 */
internal fun muteBlock(threadId: String, root: String, archiveName: String = "Archive"): String {
    requireSafeThread(threadId)
    val id = root.trim().removePrefix("<").removeSuffix(">").trim()
    if (id.isEmpty()) {
        throw MuteRefused("This conversation has no Message-ID for the server to match its replies against.")
    }
    if (id.any { it.isISOControl() }) {
        throw MuteRefused("This conversation's first message has an id a filter rule cannot hold.")
    }
    val folder = archiveName.trim().takeIf { name -> name.isNotEmpty() && name.none { it.isISOControl() } }
        ?: "Archive"
    return MUTE_MARK + threadId + "\n" +
        "if header :contains [\"References\", \"In-Reply-To\"] " + sieveQuote("<$id>") + " {\n" +
        "    fileinto :flags " + sieveQuote("\\Seen") + " :specialuse " + sieveQuote("\\Archive") + " " +
        sieveQuote(folder) + ";\n" +
        "}\n" +
        MUTE_END
}

/**
 * A thread id goes into a comment line, where only a line break could end it early, so it
 * is held to the characters server ids are actually made of rather than escaped.
 */
private fun requireSafeThread(threadId: String) {
    if (threadId.isEmpty() || !threadId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
        throw MuteRefused("This conversation has no id the server's filters can refer to.")
    }
}

/** The conversations a script mutes, by thread id. */
internal fun mutedThreads(script: String): Set<String> =
    script.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(MUTE_MARK) }
        .map { it.removePrefix(MUTE_MARK).trim() }
        .filter { it.isNotEmpty() }
        .toSet()

/**
 * The script with [threadId] muted.
 *
 * An empty script becomes one in the builder's format with no rules of its own, so the
 * filters page opens it as an ordinary editable script rather than as raw text. Anything
 * else keeps every byte it had: the block goes on the end, below every rule already there,
 * and the only other change is the capabilities it needs being added to the `require` at
 * the top. Sieve wants those declared before the first command, which is why they cannot
 * travel inside the block.
 *
 * Muting a conversation twice replaces its block rather than adding a second.
 */
internal fun withMute(script: String, threadId: String, root: String, archiveName: String = "Archive"): String {
    val block = muteBlock(threadId, root, archiveName)
    val rest = withoutMute(script, threadId)
    if (rest.isBlank()) return sieveOf(Script(emptyList(), tail = block))
    return withRequires(rest.trimEnd() + "\n\n" + block + "\n", MUTE_NEEDS)
}

/**
 * The script with [threadId]'s block taken out, and nothing else touched.
 *
 * The block runs from its marker to the end marker. If somebody has edited the end marker
 * away by hand, it runs to the first line that is only a closing brace, which is where the
 * block as written ends. A marker with neither after it is left alone, because removing an
 * unknown amount of somebody's script to undo a mute is the wrong trade.
 *
 * The `require` line is left as it is. Declaring a capability nothing uses is harmless, and
 * another rule may since have come to need it.
 */
internal fun withoutMute(script: String, threadId: String): String {
    // Returned as it came when there is nothing of ours in it, so a script with Windows
    // line endings is not rewritten, and saved, for the sake of a mute it never had.
    if (script.lines().none { it.trim() == MUTE_MARK + threadId }) return script
    val lines = script.lines()
    val out = ArrayList<String>(lines.size)
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trim() == MUTE_MARK + threadId) {
            val end = (i + 1 until lines.size).firstOrNull { lines[it].trim() == MUTE_END }
                ?: (i + 1 until lines.size).firstOrNull { lines[it].trim() == "}" }
            if (end != null) {
                i = end + 1
                // One blank line was put above the block when it was added; take it away
                // again so muting and unmuting leaves the script the shape it was.
                if (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.size - 1)
                continue
            }
        }
        out += line
        i++
    }
    return out.joinToString("\n")
}

/**
 * [script] with each of [needs] declared at the top, adding only what is missing.
 *
 * Reads the run of `require` commands before the first other command, skipping comments,
 * including the builder's metadata block. Anything missing is added to the first of them,
 * or, when there is none, a new one goes in where the first command starts. Everything
 * else in the text is left exactly as it was.
 */
internal fun withRequires(script: String, needs: List<String>): String {
    var at = skipComments(script, 0)
    var first: IntRange? = null
    val have = mutableListOf<String>()
    while (script.startsWith("require", at) && at + 7 < script.length &&
        (script[at + 7].isWhitespace() || script[at + 7] == '[' || script[at + 7] == '"')
    ) {
        val semi = script.indexOf(';', at)
        if (semi < 0) break
        val span = at..semi
        if (first == null) first = span
        have += QUOTED.findAll(script.substring(span)).map { unquote(it.groupValues[1]) }
        at = skipComments(script, semi + 1)
    }
    val missing = needs.filter { it !in have }
    if (missing.isEmpty()) return script
    val range = first
    if (range == null) {
        val line = "require [" + missing.joinToString(", ") { sieveQuote(it) } + "];\n\n"
        return script.substring(0, at) + line + script.substring(at)
    }
    val existing = QUOTED.findAll(script.substring(range)).map { unquote(it.groupValues[1]) }.toList()
    val line = "require [" + (existing + missing).joinToString(", ") { sieveQuote(it) } + "];"
    return script.substring(0, range.first) + line + script.substring(range.last + 1)
}

/** Which capabilities the verbatim part of a script needs for the mute blocks it holds. */
internal fun muteNeeds(tail: String): List<String> =
    if (mutedThreads(tail).isEmpty()) emptyList() else MUTE_NEEDS

private val QUOTED = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

private fun unquote(value: String): String = value.replace(Regex("\\\\(.)"), "$1")

/** The index of the first thing after [from] that is not white space or a comment. */
private fun skipComments(text: String, from: Int): Int {
    var i = from
    while (i < text.length) {
        when {
            text[i].isWhitespace() -> i++
            text[i] == '#' -> {
                val nl = text.indexOf('\n', i)
                i = if (nl < 0) text.length else nl + 1
            }
            text.startsWith("/*", i) -> {
                val close = text.indexOf("*/", i + 2)
                i = if (close < 0) text.length else close + 2
            }
            else -> return i
        }
    }
    return i
}

/**
 * Which script a mute goes into: the active one, because that is the one filtering mail.
 *
 * Unlike [theOneRunning], an inactive script is never picked up and activated for the sake
 * of a mute, not even Rampart's own. A mailbox whose owner switched their filters off has
 * them off on purpose, and muting one conversation must not switch them all back on. With
 * no active script, null, and the mute goes into a new one named by [newScriptName].
 */
internal fun muteTarget(all: List<Jmap.SieveInfo>): Jmap.SieveInfo? = all.firstOrNull { it.active }

/** A name for a new script that no script on the server already has. */
internal fun newScriptName(all: List<Jmap.SieveInfo>): String {
    val taken = all.map { it.name }.toSet()
    return sequenceOf("rampart", "rampart-mute").plus(generateSequence(2) { it + 1 }.map { "rampart-mute-$it" })
        .first { it !in taken }
}

/** The name to file under when the server has no folder with the Archive role. */
internal fun archiveFallback(boxes: List<Mailbox>): String =
    boxes.firstOrNull { it.role == "archive" && it.parentId == null }?.name ?: "Archive"

/**
 * The sentence under the mute menu item, or null when there is nothing worth saying.
 *
 * [canServer] is whether this account has Sieve at all; [onServer] is whether this
 * conversation's mute is actually a rule there. A conversation muted locally before this
 * existed stays local until it is unmuted, and saying the server has it would be untrue.
 */
internal fun muteNote(canServer: Boolean, onServer: Boolean, muted: Boolean): String? = when {
    muted && onServer ->
        "Your mail server marks new replies in this conversation read and files them in Archive, even with Rampart closed."
    muted -> "This mute works only while Rampart is open on this computer."
    !canServer -> "Muting works only while Rampart is open on this computer."
    else -> null
}

/**
 * The server side of muting: reading and writing the rules, and remembering which
 * conversations they cover so the reading pane does not ask the server on every click.
 */
internal object ServerMute {
    /** Thread ids muted on the server, per account, as of the last read or write. */
    private val known = ConcurrentHashMap<String, Set<String>>()

    /** What is already known, without asking. For drawing, where a network call cannot go. */
    fun knownMuted(account: String, threadId: String): Boolean =
        threadId in known[account].orEmpty()

    /**
     * Whether [threadId] is muted on this account's server. Reads the script once per
     * account per run, then answers from memory. Throws if the server could not be read.
     */
    fun muted(backend: MailBackend, account: String, threadId: String): Boolean {
        if (threadId.isBlank() || !backend.hasSieve()) return false
        val threads = known[account] ?: run {
            val script = muteTarget(backend.sieveScripts())
            mutedThreads(if (script == null) "" else backend.sieveText(script)).also { known[account] = it }
        }
        return threadId in threads
    }

    /**
     * Mutes or unmutes [message]'s conversation on the server.
     *
     * The script is read fresh rather than from memory, because a filter saved from the
     * filters page or from Bulwark since the last read has to survive this write. Throws a
     * [JmapError] with the reason on any failure, and on failure nothing is remembered as
     * muted: a conversation shown as muted that is not is worse than one that asks again.
     */
    fun set(backend: MailBackend, account: String, message: Summary, on: Boolean, boxes: List<Mailbox>) {
        if (!backend.hasSieve()) throw JmapError("This account's server has no filters to hold a mute.")
        val all = backend.sieveScripts()
        val chosen = muteTarget(all)
        val text = if (chosen == null) "" else backend.sieveText(chosen)
        // sieveText answers an empty string when the download fails, which is
        // indistinguishable from an empty script. Writing a mute on top of that would
        // replace every rule the account has with one line, so a script that has a body on
        // the server and came back empty is left alone.
        if (chosen != null && chosen.blobId.isNotBlank() && text.isBlank()) {
            throw JmapError("The filter script on the server could not be read, so Rampart left it alone.")
        }
        val next = try {
            if (on) {
                val root = rootOf(backend.body(message.id))
                withMute(text, message.threadId, root, archiveFallback(boxes))
            } else {
                withoutMute(text, message.threadId)
            }
        } catch (refused: MuteRefused) {
            throw JmapError(refused.message ?: "This conversation cannot be muted on the server.")
        }
        if (next != text) backend.saveSieve(chosen?.name ?: newScriptName(all), next, chosen)
        known[account] = mutedThreads(next)
    }
}
