package org.rampart

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A cancelled coroutine is not a fault, and the red bar at the top of the window is for
 * faults. This is the guard on the difference.
 */
class WhyFailedTest {
    @Test
    fun `a real failure gives a message to show`() {
        assertEquals("the server said no", whyFailed(IllegalStateException("the server said no")))
    }

    @Test
    fun `a failure with no message still gives something rather than nothing`() {
        assertTrue(whyFailed(IOException()).isNotBlank())
    }

    @Test
    fun `a cancellation is rethrown rather than shown`() {
        assertFailsWith<CancellationException> { whyFailed(CancellationException("moved on")) }
    }

    @Test
    fun `the wording Compose uses for a disposed scope never reaches the screen`() {
        // The literal message from the report: closing a screen mid-request painted
        // Compose's internal wording into the same bar that reports a refused send.
        assertFailsWith<CancellationException> {
            whyFailed(CancellationException("The coroutine scope left the composition"))
        }
    }

    @Test
    fun `cancelling a job that catches Exception still cancels it`() = runBlocking {
        var shown: String? = null
        var ran = false
        val job: Job = launch {
            try {
                kotlinx.coroutines.delay(10_000)
            } catch (e: Exception) {
                shown = whyFailed(e)
            }
            ran = true
        }
        job.cancelAndJoin()
        assertEquals(null, shown, "a cancellation was put on screen")
        assertTrue(!ran, "the coroutine carried on after being cancelled")
    }
}
