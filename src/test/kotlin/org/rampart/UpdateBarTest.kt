package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two pieces of pure logic behind the bottom bar: which version a click should end on,
 * and what the bar shows once a stage or install attempt has answered.
 *
 * Everything here runs with no network and no Compose, which is the point: a click's
 * outcome should be provable without a Windows box to run PowerShell on.
 */
class UpdateBarTest {
    @Test
    fun `a click installs the freshly checked version when it differs from what was staged`() {
        assertEquals("0.1.176", Updates.targetVersion(staged = "0.1.174", fresh = "0.1.176"))
    }

    @Test
    fun `a click that finds nothing has changed keeps the staged version`() {
        assertEquals("0.1.174", Updates.targetVersion(staged = "0.1.174", fresh = "0.1.174"))
    }

    @Test
    fun `a check that could not be answered installs what is already on disk rather than nothing`() {
        // Null means the network call failed, not that nothing new was published. What is
        // already staged is known to exist, so it is the safe thing to fall back on.
        assertEquals("0.1.174", Updates.targetVersion(staged = "0.1.174", fresh = null))
    }

    @Test
    fun `a release still landing keeps the bar waiting rather than reporting a failure`() {
        val outcome = Updates.afterFailure("0.1.174", Updates.NOT_READY)
        assertEquals(UpdateBarState.Waiting("0.1.174"), outcome)
    }

    @Test
    fun `a real refusal is shown as a failure that can be tried again`() {
        val outcome = Updates.afterFailure("0.1.174", "Windows would not stage the update.")
        assertEquals(UpdateBarState.Failed("0.1.174", "Windows would not stage the update."), outcome)
    }

    @Test
    fun `a refusal with nothing said still reads as a failure, not silence`() {
        val outcome = Updates.afterFailure("0.1.174", null)
        assertTrue(outcome is UpdateBarState.Failed)
        assertTrue(outcome.message.isNotBlank())
    }

    @Test
    fun `only staging and installing count as busy`() {
        assertFalse(UpdateBarState.Hidden.busy)
        assertFalse(UpdateBarState.Waiting("0.1.174").busy)
        assertTrue(UpdateBarState.Staging("0.1.174").busy)
        assertTrue(UpdateBarState.Installing("0.1.174").busy)
        assertFalse(UpdateBarState.Failed("0.1.174", "no").busy)
    }

    @Test
    fun `the label names the version everywhere except hidden and failed`() {
        assertEquals("", UpdateBarState.Hidden.label)
        assertTrue("0.1.174" in UpdateBarState.Waiting("0.1.174").label)
        assertTrue("0.1.174" in UpdateBarState.Staging("0.1.174").label)
        assertTrue("0.1.174" in UpdateBarState.Installing("0.1.174").label)
        // A failure is read from the message alone, not templated around the version.
        assertEquals("could not reach it", UpdateBarState.Failed("0.1.174", "could not reach it").label)
    }
}
