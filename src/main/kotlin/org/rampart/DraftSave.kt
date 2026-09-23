package org.rampart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The saves of one draft, one at a time, each of which finishes even if the caller stops waiting.
 *
 * Autosave lives in a Compose effect that restarts on the next keystroke and when Send is
 * pressed. The restart cancels whatever that effect was in the middle of. A save that has
 * already reached the server has stored the new copy and removed the old one, and a
 * cancelled call throws away the id of the copy that now exists. The next save then
 * replaces an id the server no longer has, and Send and Discard do the same with the id
 * they can still see.
 *
 * [save] holds [gate] across the server call and writes [id] before it lets go, inside a
 * block cancellation does not interrupt. [awaitIdle] takes the same lock, so Send and
 * Discard wait until a save already underway has recorded its id, then read that.
 */
internal class DraftSaves(initialId: String? = null) {
    private val gate = Mutex()

    /** The id of the copy that exists on the server, or null when nothing has been saved. */
    var id: String? = initialId
        private set

    /**
     * Runs [block] to the end with the id it should replace, and records the id it returns.
     *
     * Waiting for an earlier save is cancellable: this one has not started, and the one
     * ahead of it records its own id. Once this one holds [gate], it runs to the end.
     */
    suspend fun save(block: suspend (replacing: String?) -> String): String = gate.withLock {
        withContext(NonCancellable + Dispatchers.IO) { block(id).also { id = it } }
    }

    /** Waits until a [save] that already holds [gate] has finished, then returns [id]. */
    suspend fun awaitIdle(): String? = gate.withLock { id }
}
