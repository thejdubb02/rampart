package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Acting on a conversation rather than on whichever message of it happens to be open.
 *
 * A thread is the unit people actually think in. "I have read this" and "I am done with
 * this" are things somebody means about a conversation, and doing them one message at a
 * time is the part that makes a mail client feel like work. The list already collapses a
 * conversation into one row, so the row promising an action on twelve messages and
 * delivering it on one was the gap.
 */

/**
 * Which messages of a conversation an action may touch.
 *
 * Two are left alone, and both matter more than they look:
 *
 * - **Your own replies.** They are in Sent, and archiving a conversation that swept your
 *   Sent copies into Archive would take them out of the one folder that is supposed to
 *   hold everything you have written.
 * - **Drafts.** A half-written reply moved to Archive is a draft nobody finds again.
 *
 * [mine] is compared in lower case because an address is not case sensitive in the half
 * that matters and servers disagree about the other half.
 */
internal fun conversationIds(thread: List<Summary>, mine: Set<String>): List<String> {
    val ours = mine.map { it.lowercase() }.toSet()
    return thread
        .filterNot { it.fromEmail.lowercase() in ours }
        .filterNot { it.keywords.any { keyword -> keyword.equals("\$draft", ignoreCase = true) } }
        .map { it.id }
}

/**
 * Conversations told to stop asking for attention.
 *
 * A mailing list thread, or the one where fourteen people reply-all to say thanks. Mute
 * means: anything that arrives in it from now on is marked read, filed in Archive, and
 * does not raise a notification. Nothing is deleted, and opening the conversation still
 * shows all of it.
 *
 * **It needs Rampart running to apply**, because nothing on the server knows what a thread
 * is in the way this does. Sieve runs per message, at delivery, with no idea which
 * conversation a message joins; matching on a subject line would silently swallow mail
 * from a different thread that happened to share one. So a muted conversation quietens
 * when Rampart next sees it rather than the moment it lands, which is a wait rather than a
 * failure. Worth saying out loud on the screen, and it is.
 *
 * Kept per account as well as per thread: thread ids come from a server and two servers
 * have no reason to agree about them.
 */
internal object Muted {
    fun muted(account: String, threadId: String): Boolean =
        threadId.isNotBlank() && key(account, threadId) in all()

    fun set(account: String, threadId: String, on: Boolean) {
        if (threadId.isBlank()) return
        val one = key(account, threadId)
        // Taken out either way, so muting something again moves it to the newest end
        // rather than leaving it where it was when the trim below comes round.
        val kept = all() - one
        write(if (on) kept + one else kept)
    }

    /**
     * The oldest are dropped past [KEEP].
     *
     * A list nothing ever removes from is a file that grows for the life of the install,
     * and a conversation nobody has touched in a thousand threads is not one anybody is
     * still being bothered by.
     */
    internal fun trim(keys: List<String>): List<String> = keys.takeLast(KEEP)

    internal fun key(account: String, threadId: String) = "$account\u0000$threadId"

    private fun all(): Set<String> =
        (store.read()["threads"] as? JsonArray).orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .toSet()

    private fun write(keys: Set<String>) = store.write {
        put("threads", buildJsonArray { trim(keys.toList()).forEach { add(JsonPrimitive(it)) } })
    }

    private const val KEEP = 500

    private val store = JsonStore("muted.json")
}
