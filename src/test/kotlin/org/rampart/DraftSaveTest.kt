package org.rampart

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Two saves of one draft must not both create a copy, and a caller that gives up
 * must not take the new id with it.
 */
class DraftSaveTest {
    @Test
    fun `two overlapping saves leave one draft and record the last id`() = runBlocking {
        withTimeout(5_000) {
            val saves = DraftSaves()
            val live = mutableSetOf<String>()
            val replaced = mutableListOf<String?>()
            var n = 0
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()

            val first = async {
                saves.save { replacing ->
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    n += 1
                    val id = "d$n"
                    replaced += replacing
                    if (replacing != null) live.remove(replacing)
                    live.add(id)
                    id
                }
            }
            firstEntered.await()
            val second = async {
                saves.save { replacing ->
                    n += 1
                    val id = "d$n"
                    replaced += replacing
                    if (replacing != null) live.remove(replacing)
                    live.add(id)
                    id
                }
            }
            // The second save is waiting on the first. Releasing before this point would
            // not show that the two were kept apart.
            assertEquals(0, live.size)
            releaseFirst.complete(Unit)
            assertEquals("d1", first.await())
            assertEquals("d2", second.await())
            assertEquals(listOf(null, "d1"), replaced)
            assertEquals(setOf("d2"), live)
            assertEquals("d2", saves.id)
            assertEquals("d2", saves.awaitIdle())
        }
    }

    @Test
    fun `a cancelled caller still records the id the server returned`() = runBlocking {
        withTimeout(5_000) {
            val saves = DraftSaves()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val job = launch {
                saves.save {
                    entered.complete(Unit)
                    release.await()
                    "kept"
                }
            }
            entered.await()
            job.cancel()
            release.complete(Unit)
            job.join()
            assertEquals("kept", saves.id)
            assertEquals("kept", saves.awaitIdle())
        }
    }

    @Test
    fun `a save that fails keeps the previous id and the next save can run`() = runBlocking {
        withTimeout(5_000) {
            val saves = DraftSaves("old")
            assertFailsWith<IllegalStateException> {
                saves.save { throw IllegalStateException("refused") }
            }
            assertEquals("old", saves.id)
            val next = saves.save { replacing ->
                assertEquals("old", replacing)
                "new"
            }
            assertEquals("new", next)
            assertEquals("new", saves.id)
        }
    }
}
