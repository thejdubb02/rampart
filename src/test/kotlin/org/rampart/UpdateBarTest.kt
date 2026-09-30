package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        assertEquals(
            "0.1.176",
            Updates.targetVersion(staged = "0.1.174", fresh = UpdateCheckResult.Newer("0.1.176")),
        )
    }

    @Test
    fun `a click that finds nothing has changed keeps the staged version`() {
        assertEquals("0.1.174", Updates.targetVersion(staged = "0.1.174", fresh = UpdateCheckResult.Current))
    }

    @Test
    fun `a check that could not be answered installs what is already on disk rather than nothing`() {
        val failed = UpdateCheckResult.Failed(UpdateCheckFailure.NETWORK)
        assertEquals("0.1.174", Updates.targetVersion(staged = "0.1.174", fresh = failed))
    }

    @Test
    fun `a click while the release is still landing is a failure, not a ready update`() {
        val outcome = Updates.clickFailure("0.1.174", Updates.NOT_READY)
        assertEquals(UpdateBarState.Failed("0.1.174", Updates.NOT_READY), outcome)
    }

    @Test
    fun `a click that fails is shown as a failure that can be tried again`() {
        val outcome = Updates.clickFailure("0.1.174", "Windows would not stage the update.")
        assertEquals(UpdateBarState.Failed("0.1.174", "Windows would not stage the update."), outcome)
    }

    @Test
    fun `a click that fails without a reason still reads as a failure, not silence`() {
        val outcome = Updates.clickFailure("0.1.174", null)
        assertEquals(UpdateBarState.Failed("0.1.174", "The update did not go in."), outcome)
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
        assertEquals("Update to 0.1.300", UpdateBarState.Waiting("0.1.300").label)
        assertTrue("0.1.174" in UpdateBarState.Staging("0.1.174").label)
        assertTrue("0.1.174" in UpdateBarState.Installing("0.1.174").label)
        // A failure is read from the message alone, not templated around the version.
        assertEquals("could not reach it", UpdateBarState.Failed("0.1.174", "could not reach it").label)
    }

    @Test
    fun `the first check waits a minute and later checks are half an hour apart`() {
        assertEquals(60_000L, Updates.FIRST_CHECK_DELAY_MS)
        assertEquals(30 * 60_000L, Updates.CHECK_INTERVAL_MS)
    }

    @Test
    fun `a build with no running version cannot update`() {
        assertNull(Updates.current)
        assertFalse(Updates.canUpdate())
    }

    @Test
    fun `a version already waiting is not fetched again, and a failure is`() {
        assertFalse(Updates.shouldPrefetch(UpdateBarState.Waiting("0.1.176"), "0.1.176"))
        assertTrue(Updates.shouldPrefetch(UpdateBarState.Hidden, "0.1.176"))
        assertTrue(Updates.shouldPrefetch(UpdateBarState.Failed("0.1.176", "no"), "0.1.176"))
        assertTrue(Updates.shouldPrefetch(UpdateBarState.Waiting("0.1.174"), "0.1.176"))
        assertFalse(Updates.shouldPrefetch(UpdateBarState.Staging("0.1.176"), "0.1.176"))
        assertFalse(Updates.shouldPrefetch(UpdateBarState.Hidden, null))
        assertFalse(Updates.shouldPrefetch(UpdateBarState.Hidden, "  "))
    }
}
