package org.rampart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The restart button was restarting without updating, because it ran the package's own
 * launcher and that launcher only checks for an update in Conveyor's aggressive mode,
 * which we deliberately do not use. These are the properties of what replaced it. The
 * script itself can only be proved on Windows; what is checked here is that the command is
 * the one intended and that it cannot be mangled on the way across.
 */
class UpdateCommandTest {
    private val command = Updates.updateCommand()
    private val script = command.last()

    @Test
    fun itAsksWindowsToInstallThePublishedPackage() {
        assertEquals("powershell", command.first())
        assertTrue("Add-AppxPackage" in script)
        assertTrue("rampart.appinstaller" in script, "the manifest that always names the newest build")
        assertTrue(
            "-ForceTargetApplicationShutdown" in script,
            "Windows will not replace a package while it is running",
        )
    }

    /** A failed install must still leave the person with a running app. */
    @Test
    fun itStartsRampartAgainEvenIfTheInstallFails() {
        assertTrue("catch { }" in script)
        assertTrue(script.indexOf("Start-Process") > script.indexOf("catch { }"))
    }

    /**
     * The family name carries a hash of the signing identity. Written down rather than
     * asked for, it would silently stop matching the day that key is replaced.
     */
    @Test
    fun theFamilyNameIsAskedForRatherThanWrittenDown() {
        assertTrue("Get-AppxPackage -Name Rampart" in script)
        assertFalse("Rampart_" in script, "no hardcoded package family name")
    }

    /**
     * The whole script crosses Java's Windows argument quoting as a single argument. A
     * double quote in it is the thing most likely not to survive that.
     */
    @Test
    fun theScriptCarriesNoDoubleQuotes() {
        assertFalse('"' in script, script)
    }

    /*
     * The background fetch, which runs while somebody is reading their mail. The one thing
     * it must never do is close the app to get the update in, so that is what is checked.
     */
    @Test
    fun theBackgroundFetchCannotCloseTheApp() {
        val staging = Updates.stageCommand().last()
        assertTrue("Add-AppxPackage" in staging)
        assertTrue("rampart.appinstaller" in staging)
        assertFalse("Shutdown" in staging, "a fetch behind a running app never closes it")
    }

    /**
     * Staged rather than applied, so closing Rampart later is enough on its own. The flag
     * is not on every Windows, so there is a second attempt without it, and that one is
     * allowed to fail.
     */
    @Test
    fun itAsksWindowsToHoldTheUpdateUntilTheAppIsClosed() {
        val staging = Updates.stageCommand().last()
        assertTrue("-DeferRegistrationWhenPackagesAreInUse" in staging)
        assertEquals(2, Regex("Add-AppxPackage").findAll(staging).count(), "a fallback without the flag")
        assertTrue("exit 1" in staging, "a refusal is reported rather than counted as staged")
    }

    @Test
    fun theStagingScriptCarriesNoDoubleQuotes() {
        assertFalse('"' in Updates.stageCommand().last())
    }

    @Test
    fun runningFromSourceHasNothingStagedAndNothingToUpdate() {
        assertFalse(Updates.stage())
    }

    @Test
    fun runningFromSourceHasNothingToUpdate() {
        // No packaged launcher beside a development build, so there is nothing to install
        // and the button says so by doing nothing rather than by failing.
        assertFalse(Updates.restartToUpdate())
    }
}
