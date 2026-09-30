package org.rampart

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A development build has no packaged launcher, so the update path must refuse before it
 * asks the network. The install itself is Windows App Installer opening a staged manifest,
 * which cannot be proved here. What is proved is that the old shell-out is gone.
 */
class UpdateCommandTest {
    @Test
    fun `a dev build does not stage or hand off, and does not ask the network`() {
        assertNull(Updates.current)
        assertFalse(Updates.stage())
        assertFalse(Updates.restartToUpdate())
    }

    @Test
    fun `the update source never mentions powershell or schtasks`() {
        val source = Path.of("src/main/kotlin/org/rampart/Updates.kt")
        assertTrue(Files.isRegularFile(source), source.toAbsolutePath().toString())
        val text = Files.readString(source).lowercase()
        assertFalse("powershell" in text)
        assertFalse("schtasks" in text)
    }
}
