package org.rampart

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerMuteTest {
    private val bulwarkScript = """
        /* @metadata:begin
        {"version":1,"rules":[{"id":"r1","name":"DMARC","enabled":true,"matchType":"all","conditions":[{"field":"from","comparator":"contains","value":"dmarc@example.org"}],"actions":[{"type":"move","value":"Deleted Items"},{"type":"mark_read"}],"stopProcessing":false}]}
        @metadata:end */

        require ["fileinto", "imap4flags"];

        # Rule: DMARC
        if header :contains "From" "dmarc@example.org" {
            fileinto "Deleted Items";
            addflag "\\Seen";
        }

        # Hand written, and nobody's builder knows about it.
        if address :matches "to" "zz-canary@*" {
            discard;
            stop;
        }
    """.trimIndent() + "\n"

    // --- the rule itself ------------------------------------------------------------

    @Test
    fun `the rule is exactly this`() {
        assertEquals(
            """
            # Rampart mute: thread Tabc123
            if header :contains ["References", "In-Reply-To"] "<root@example.org>" {
                fileinto :flags "\\Seen" :specialuse "\\Archive" "Archive";
            }
            # Rampart mute end
            """.trimIndent(),
            muteBlock("Tabc123", "root@example.org"),
        )
    }

    @Test
    fun `a root given with its brackets is not bracketed twice`() {
        assertContains(muteBlock("T1", "<root@example.org>"), "\"<root@example.org>\"")
    }

    /*
     * A Message-ID is whatever the sender's software put there. One written to close the
     * string early and add a rule of its own must end up as a harmless literal.
     */
    @Test
    fun `a hostile Message-ID is escaped rather than obeyed`() {
        val block = muteBlock("T1", "a\"b\\c@x\" { discard; } if true { \"")
        assertEquals(
            """if header :contains ["References", "In-Reply-To"] "<a\"b\\c@x\" { discard; } if true { \">" {""",
            block.lines()[1],
        )
        assertEquals(5, block.lines().size)
    }

    @Test
    fun `an id with a line break in it is refused`() {
        assertFailsWith<MuteRefused> { muteBlock("T1", "root@example.org\n}\ndiscard;") }
    }

    @Test
    fun `a conversation with no root cannot be muted on the server`() {
        assertFailsWith<MuteRefused> { muteBlock("T1", "  ") }
        assertFailsWith<MuteRefused> { muteBlock("T1", "<>") }
    }

    @Test
    fun `a thread id that could break out of its comment is refused`() {
        assertFailsWith<MuteRefused> { muteBlock("T1\nstop;", "root@example.org") }
        assertFailsWith<MuteRefused> { muteBlock("", "root@example.org") }
    }

    @Test
    fun `the archive folder name is the fallback and is escaped too`() {
        assertContains(muteBlock("T1", "r@x", "My \"Old\" Mail"), ":specialuse \"\\\\Archive\" \"My \\\"Old\\\" Mail\";")
        assertContains(muteBlock("T1", "r@x", ""), "\"Archive\";")
    }

    // --- adding and removing, around somebody else's rules ---------------------------

    @Test
    fun `muting adds the block at the end and keeps every other line`() {
        val muted = withMute(bulwarkScript, "T42", "root@example.org")
        assertTrue(muted.startsWith(bulwarkScript.substringBefore("require")))
        assertContains(muted, "require [\"fileinto\", \"imap4flags\", \"special-use\"];")
        val body = bulwarkScript.substringAfter("];")
        assertContains(muted, body.trimEnd())
        assertTrue(muted.trimEnd().endsWith(MUTE_END))
        assertEquals(setOf("T42"), mutedThreads(muted))
    }

    @Test
    fun `unmuting takes out exactly its own block`() {
        val two = withMute(withMute(bulwarkScript, "T1", "one@example.org"), "T2", "two@example.org")
        assertEquals(setOf("T1", "T2"), mutedThreads(two))
        val one = withoutMute(two, "T1")
        assertEquals(setOf("T2"), mutedThreads(one))
        assertFalse(one.contains("one@example.org"))
        assertContains(one, "two@example.org")
        assertContains(one, "zz-canary@*")
        assertContains(one, "# Rule: DMARC")
        val none = withoutMute(one, "T2")
        assertEquals(emptySet(), mutedThreads(none))
        // What is left is the original, apart from the capabilities the mute added.
        assertEquals(
            bulwarkScript.replace("[\"fileinto\", \"imap4flags\"]", "[\"fileinto\", \"imap4flags\", \"special-use\"]").trimEnd(),
            none.trimEnd(),
        )
    }

    @Test
    fun `unmuting a conversation that was never muted changes nothing`() {
        val crlf = bulwarkScript.replace("\n", "\r\n")
        assertEquals(crlf, withoutMute(crlf, "T9"))
    }

    @Test
    fun `muting twice leaves one block`() {
        val twice = withMute(withMute(bulwarkScript, "T1", "a@example.org"), "T1", "b@example.org")
        assertEquals(1, twice.lines().count { it.trim() == MUTE_MARK + "T1" })
        assertContains(twice, "<b@example.org>")
        assertFalse(twice.contains("<a@example.org>"))
    }

    @Test
    fun `a block whose end marker was edited away still comes out at its closing brace`() {
        val muted = withMute(bulwarkScript, "T1", "a@example.org").replace(MUTE_END, "")
        val back = withoutMute(muted, "T1")
        assertFalse(back.contains("a@example.org"))
        assertContains(back, "zz-canary@*")
    }

    @Test
    fun `an empty account gets a script the filters page can edit`() {
        val fresh = withMute("", "T1", "a@example.org")
        val script = scriptOf(fresh)
        assertTrue(script.builderMade)
        assertTrue(script.rules.isEmpty())
        assertContains(fresh, "require [\"fileinto\", \"imap4flags\", \"special-use\"];")
        assertEquals(setOf("T1"), mutedThreads(script.tail))
    }

    /*
     * The filters page rebuilds the require line from its rules every time it saves. If it
     * forgot the mute's capabilities the server would refuse the save, and the person would
     * be unable to edit a filter because they once muted a conversation.
     */
    @Test
    fun `saving a rule from the filters page keeps what the mute needs`() {
        val muted = withMute(bulwarkScript, "T1", "a@example.org")
        val script = scriptOf(muted)
        assertTrue(script.builderMade)
        assertEquals(listOf("DMARC"), script.rules.map { it.name })
        assertEquals(setOf("T1"), mutedThreads(script.tail))
        val saved = sieveOf(script.copy(rules = emptyList()))
        assertContains(saved, "require [\"fileinto\", \"imap4flags\", \"special-use\"];")
        assertContains(saved, "<a@example.org>")
        assertContains(saved, "zz-canary@*")
    }

    @Test
    fun `a hand written script without a require gets one before its first command`() {
        val raw = "# my rules\nif header :contains \"Subject\" \"x\" { discard; }\n"
        val muted = withMute(raw, "T1", "a@example.org")
        assertTrue(muted.startsWith("# my rules\nrequire [\"fileinto\", \"imap4flags\", \"special-use\"];\n\nif header"))
        assertFalse(scriptOf(muted).builderMade)
    }

    @Test
    fun `requires spread over several commands are read as one set`() {
        val raw = "require \"fileinto\";\nrequire [\"imap4flags\", \"special-use\"];\nkeep;\n"
        assertEquals(raw, withRequires(raw, MUTE_NEEDS))
    }

    // --- choosing a script, and what the menu says -----------------------------------

    @Test
    fun `an inactive script is never switched on for a mute`() {
        val theirs = Jmap.SieveInfo("1", "old", active = false, blobId = "b1")
        assertNull(muteTarget(listOf(theirs)))
        val active = Jmap.SieveInfo("2", "live", active = true, blobId = "b2")
        assertEquals(active, muteTarget(listOf(theirs, active)))
        // Not even one called rampart: switched off is switched off.
        val ours = Jmap.SieveInfo("3", "rampart", active = false, blobId = "b3")
        assertNull(muteTarget(listOf(ours)))
    }

    @Test
    fun `a new script never takes the name of one already there`() {
        assertEquals("rampart", newScriptName(emptyList()))
        val ours = Jmap.SieveInfo("3", "rampart", active = false, blobId = "b3")
        assertEquals("rampart-mute", newScriptName(listOf(ours)))
        val both = listOf(ours, ours.copy(id = "4", name = "rampart-mute"))
        assertEquals("rampart-mute-2", newScriptName(both))
    }

    @Test
    fun `the note says where the mute lives`() {
        assertContains(muteNote(canServer = true, onServer = true, muted = true)!!, "even with Rampart closed")
        assertContains(muteNote(canServer = false, onServer = false, muted = true)!!, "only while Rampart is open on this computer")
        assertContains(muteNote(canServer = false, onServer = false, muted = false)!!, "only while Rampart is open on this computer")
        // Muted here before the server could hold it: still local, and it says so.
        assertContains(muteNote(canServer = true, onServer = false, muted = true)!!, "only while Rampart is open")
        assertNull(muteNote(canServer = true, onServer = false, muted = false))
    }

    @Test
    fun `the archive fallback is the top level archive folder`() {
        val boxes = listOf(
            Mailbox("1", "Inbox", "inbox", 0),
            Mailbox("2", "Archives", "archive", 0),
        )
        assertEquals("Archives", archiveFallback(boxes))
        assertEquals("Archive", archiveFallback(listOf(Mailbox("3", "Old", "archive", 0, parentId = "9"))))
        assertEquals("Archive", archiveFallback(emptyList()))
    }
}
