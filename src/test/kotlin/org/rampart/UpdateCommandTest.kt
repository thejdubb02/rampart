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
        assertTrue("catch {" in script)
        assertTrue(script.indexOf("Start-Process") > script.indexOf("catch {"))
    }

    /**
     * The catch used to be empty, and a refusal left nothing to read: the bar could not
     * tell a failed install apart from one that had never been staged.
     */
    @Test
    fun theRefusalReasonIsCapturedRatherThanSwallowed() {
        assertTrue("Write-Output" in script && "Exception.Message" in script, script)
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

    @Test
    fun aFailureBeforePowerShellStartsIsReported() {
        val previous = System.getProperty("app.dir")
        try {
            System.setProperty("app.dir", "\u0000")
            assertFalse(Updates.stage())
            assertTrue(Updates.lastProblem?.isNotBlank() == true)
        } finally {
            if (previous == null) System.clearProperty("app.dir") else System.setProperty("app.dir", previous)
        }
    }

    /** Each generated task name must be unique so multiple attempts never collide. */
    @Test
    fun taskNamesAreUnique() {
        val names = (1..50).map { Updates.generateTaskName() }.toSet()
        assertEquals(50, names.size)
        names.forEach { assertTrue(it.startsWith("RampartUpdate_")) }
    }

    /** The package name must be dynamically inserted into all package lookups and paths. */
    @Test
    fun scriptInsertsPackageName() {
        val scriptText = Updates.updateScriptText(
            appinstaller = "https://example.com/test.appinstaller",
            packageName = "CustomMailPackage",
            taskName = "CustomTaskName",
        )
        assertTrue("Get-AppxPackage -Name 'CustomMailPackage'" in scriptText)
        assertTrue("\$_.ProcessName -eq 'CustomMailPackage'" in scriptText)
        assertTrue("[System.IO.Path]::Combine(\$env:LOCALAPPDATA, 'CustomMailPackage')" in scriptText)
        assertTrue("!CustomMailPackage')" in scriptText)
    }

    /** Paths and arguments must be wrapped in single quotes without any double quotes. */
    @Test
    fun scriptQuotesPathsAndAvoidsDoubleQuotes() {
        val scriptText = Updates.updateScriptText(
            appinstaller = "https://example.com/test.appinstaller",
            packageName = "Rampart",
            taskName = "Task_123",
        )
        assertTrue("Add-AppxPackage -AppInstallerFile 'https://example.com/test.appinstaller'" in scriptText)
        assertTrue("schtasks /Delete /TN 'Task_123' /F" in scriptText)
        assertFalse('"' in scriptText, "the script should never contain double quotes")
    }

    /** The scheduled task command must be pointed at PowerShell with bypass and hidden window. */
    @Test
    fun taskCreationCommandUsesHiddenBypassPowerShell() {
        val cmd = Updates.createTaskCommand("MyTask", "C:\\temp\\update.ps1")
        assertEquals("schtasks", cmd[0])
        assertEquals("/Create", cmd[1])
        assertEquals("MyTask", cmd[cmd.indexOf("/TN") + 1])
        val action = cmd[cmd.indexOf("/TR") + 1]
        assertTrue(action.startsWith("powershell -nop -ep bypass -w hidden -enc "))
        val encoded = action.substringAfter("-enc ")
        assertEquals("& 'C:\\temp\\update.ps1'", String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_16LE))
        assertTrue(action.length <= 261, "schtasks refuses an action longer than 261 characters")
        assertTrue("/IT" in cmd)
    }

    /** Update log errors newer than the last start must be read and cleaned up. */
    @Test
    fun checkPreviousUpdateErrorReadsNewerMessageAndDeletesLog() {
        val tempDir = java.nio.file.Files.createTempDirectory("rampart-log-test")
        val logFile = tempDir.resolve("update.log")
        try {
            java.nio.file.Files.writeString(logFile, "Something failed during update.")
            val lastStart = System.currentTimeMillis() - 10000L
            val readError = Updates.checkPreviousUpdateError(lastStart, logFile)
            assertEquals("Something failed during update.", readError)
            assertFalse(java.nio.file.Files.exists(logFile))

            val secondRead = Updates.checkPreviousUpdateError(lastStart, logFile)
            assertEquals(null, secondRead)
        } finally {
            runCatching { java.nio.file.Files.deleteIfExists(logFile) }
            runCatching { java.nio.file.Files.deleteIfExists(tempDir) }
        }
    }
}
