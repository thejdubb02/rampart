package org.rampart

import org.eclipse.angus.mail.imap.ACL
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.Rights

/*
 * Sharing a folder on an IMAP account, with the ACL commands of RFC 4314 (RAM-45).
 *
 * The fallback for an account signed in over IMAP, used only when the server advertises
 * ACL; on a JMAP account the share dialog uses `shareWith` instead. The letters for each
 * level are in SharingRights.kt, where the tests can reach them, and follow Stalwart's own
 * reading of them. docs/stalwart-inventory.md, "Sharing in Stalwart 0.16", has the table.
 */

/** Said when the server has no ACL, so the share dialog can explain rather than fail. */
internal const val IMAP_NO_ACL = "This server does not let folders be shared over IMAP."

/** One person a folder is shared with, as the server names them, and their letters. */
internal data class ImapGrant(val who: String, val letters: String) {
    val level: ShareLevel? get() = ShareLevel.ofImap(letters)
}

/** Who [folder] is shared with (GETACL), or null when the server has no ACL. */
internal fun imapShares(imap: Imap, folder: String): List<ImapGrant>? = imap.withAcl { store ->
    (store.getFolder(folder) as IMAPFolder).acl.map { ImapGrant(it.name, it.rights.toString()) }
}

/**
 * Gives [who] exactly [level] on [folder] (SETACL, replacing what they had), or takes their
 * access away when [level] is null (DELETEACL). [who] is what the server knows them by:
 * an address on Stalwart, which looks it up the same way JMAP does, a login name on most
 * other servers.
 */
internal fun imapShare(imap: Imap, folder: String, who: String, level: ShareLevel?) {
    imap.withAcl { store ->
        val target = store.getFolder(folder) as IMAPFolder
        if (level == null) target.removeACL(who.trim()) else target.addACL(ACL(who.trim(), Rights(level.imapLetters)))
    } ?: throw NotAllowedHere(IMAP_NO_ACL)
}
