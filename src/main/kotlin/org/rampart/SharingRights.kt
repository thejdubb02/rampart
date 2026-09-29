package org.rampart

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * What somebody may do in a folder, as JMAP names it (RFC 8621 `myRights`, RFC 9670
 * `shareWith`), and the three levels Rampart offers when you share a folder of your own.
 *
 * Pure on purpose. The mapping between the levels, the JMAP rights and the IMAP ACL letters
 * is the one place a mistake hands somebody more than was meant, so it is kept where a test
 * can reach every direction of it without a server. docs/stalwart-inventory.md, "Sharing in
 * Stalwart 0.16", has the table this follows and the file each row was read from.
 */

/** The JMAP mailbox rights, in the order Stalwart lists them. */
internal enum class MailRight(val jmap: String) {
    READ_ITEMS("mayReadItems"),
    ADD_ITEMS("mayAddItems"),
    REMOVE_ITEMS("mayRemoveItems"),
    SET_SEEN("maySetSeen"),
    SET_KEYWORDS("maySetKeywords"),
    CREATE_CHILD("mayCreateChild"),
    RENAME("mayRename"),
    DELETE("mayDelete"),
    SUBMIT("maySubmit"),
    SHARE("mayShare"),
    ;

    companion object {
        fun named(name: String): MailRight? = entries.firstOrNull { it.jmap == name }
    }
}

/**
 * The rights one person holds on one folder.
 *
 * [ALL] is what a folder of your own, or of a group you belong to, answers with: Stalwart
 * sends every right as true there, and a server that sends no `myRights` at all is treated
 * the same way, because refusing everything on a server that simply did not say would make
 * a working mailbox look locked.
 */
internal data class MailboxRights(val granted: Set<MailRight>) {
    fun may(right: MailRight): Boolean = right in granted

    val full: Boolean get() = granted.containsAll(MailRight.entries)

    companion object {
        val ALL = MailboxRights(MailRight.entries.toSet())
        val NONE = MailboxRights(emptySet())

        /** A `myRights` or one `shareWith` entry. Unknown names are ignored; a missing object is [ALL]. */
        fun from(value: JsonElement?): MailboxRights {
            val o = value as? JsonObject ?: return ALL
            return MailboxRights(
                o.entries.mapNotNull { (name, on) ->
                    MailRight.named(name)?.takeIf { (on as? JsonPrimitive)?.booleanOrNull == true }
                }.toSet(),
            )
        }
    }
}

/**
 * The three ways Rampart offers to share a folder, plus the two answers a folder can give
 * that are not one of them.
 *
 * Read is read and nothing else. It does not include `maySetSeen`, because on Stalwart that
 * right is stored as the same ACL as `maySetKeywords` (both are `ModifyItems`), so granting
 * "may mark read" would also grant "may star and tag", which is more than read.
 *
 * Read and write adds filing, deleting and flags. Manage adds folders under it, renaming,
 * deleting the folder and passing it on. `maySubmit` is in none of them: sending as the
 * folder's owner is a separate decision from reading their mail and Rampart does not make
 * it for anyone.
 */
internal enum class ShareLevel(val label: String, val rights: Set<MailRight>) {
    READ("Read", setOf(MailRight.READ_ITEMS)),
    READ_WRITE(
        "Read and write",
        setOf(
            MailRight.READ_ITEMS, MailRight.ADD_ITEMS, MailRight.REMOVE_ITEMS,
            MailRight.SET_SEEN, MailRight.SET_KEYWORDS,
        ),
    ),
    MANAGE(
        "Manage",
        setOf(
            MailRight.READ_ITEMS, MailRight.ADD_ITEMS, MailRight.REMOVE_ITEMS,
            MailRight.SET_SEEN, MailRight.SET_KEYWORDS,
            MailRight.CREATE_CHILD, MailRight.RENAME, MailRight.DELETE, MailRight.SHARE,
        ),
    ),
    ;

    /** The `shareWith` value for one grantee: every right named, so the server is left nothing to assume. */
    fun shareWithValue(): JsonObject = JsonObject(
        MailRight.entries.associate { it.jmap to JsonPrimitive(it in rights) },
    )

    /**
     * The IMAP ACL letters (RFC 4314) for this level.
     *
     * Read is `lr` for the same reason it has no `maySetSeen`: `s` on Stalwart is the
     * `ModifyItems` ACL. Manage has no letter for renaming, because IMAP has none that
     * Stalwart maps to its `Modify` ACL, so a folder shared over IMAP at Manage can be
     * reorganised but not renamed by the person it was shared with.
     */
    val imapLetters: String
        get() = when (this) {
            READ -> "lr"
            READ_WRITE -> "lrswite"
            MANAGE -> "lrswitekxa"
        }

    companion object {
        /**
         * Which level a set of rights is, or null when it is none of them: a grant somebody
         * made in another client with a mix Rampart does not offer. The dialog shows that as
         * "Custom" and leaves it alone unless it is changed.
         *
         * Extra rights beyond a level do not lift it into the next one. A grant is only
         * called a level when it is exactly that level, so changing it here can never quietly
         * take away a right the person was not shown.
         */
        fun of(rights: MailboxRights): ShareLevel? =
            entries.firstOrNull { it.rights == rights.granted }

        /** The level a set of IMAP ACL letters is, or null when it is not exactly one of them. */
        fun ofImap(letters: String): ShareLevel? {
            val given = imapRights(letters)
            return entries.firstOrNull { imapRights(it.imapLetters) == given }
        }
    }
}

/**
 * What IMAP ACL letters mean as JMAP rights, following Stalwart's own mapping
 * (`impl From<Rights> for Acl` in `crates/imap-proto/src/protocol/acl.rs`, then back out
 * through `MailboxRight::to_acl`).
 *
 * `l` alone is not `mayReadItems`: Stalwart needs both `Read` (`l`) and `ReadItems` (`r`).
 * The obsolete RFC 2086 letters `c` and `d` are read the way Stalwart reads them.
 */
internal fun imapRights(letters: String): Set<MailRight> {
    val has = letters.toSet()
    return buildSet {
        if ('l' in has && 'r' in has) add(MailRight.READ_ITEMS)
        if ('i' in has) add(MailRight.ADD_ITEMS)
        if ('t' in has || 'e' in has || 'd' in has) add(MailRight.REMOVE_ITEMS)
        if ('s' in has || 'w' in has) { add(MailRight.SET_SEEN); add(MailRight.SET_KEYWORDS) }
        if ('k' in has || 'c' in has) add(MailRight.CREATE_CHILD)
        if ('x' in has) add(MailRight.DELETE)
        if ('p' in has) add(MailRight.SUBMIT)
        if ('a' in has) add(MailRight.SHARE)
    }
}

/** Something a person might try in a folder, and the right it needs. */
internal enum class FolderAction(val needs: MailRight, val refused: String) {
    MARK_READ(MailRight.SET_SEEN, "You can read this folder but not mark its messages read or unread."),
    TAG(MailRight.SET_KEYWORDS, "You can read this folder but not star or tag its messages."),
    FILE_INTO(MailRight.ADD_ITEMS, "You cannot put messages into this folder."),
    TAKE_OUT(MailRight.REMOVE_ITEMS, "You can read this folder but not move or delete its messages."),
    NEW_FOLDER(MailRight.CREATE_CHILD, "You cannot make folders inside this one."),
    RENAME(MailRight.RENAME, "Only its owner, or someone they let manage it, can rename this folder."),
    DELETE_FOLDER(MailRight.DELETE, "Only its owner, or someone they let manage it, can delete this folder."),
    SHARE(MailRight.SHARE, "Only its owner, or someone they let manage it, can share this folder."),
}

/** Null when [rights] allow [action], otherwise the one sentence saying why not. */
internal fun refusal(action: FolderAction, rights: MailboxRights): String? =
    if (rights.may(action.needs)) null else action.refused

/**
 * The line shown above a shared folder's message list, naming what it will not let you do,
 * or null when it lets you do everything a message list offers.
 */
internal fun sharedFolderNote(rights: MailboxRights): String? {
    val canChange = rights.may(MailRight.SET_KEYWORDS)
    val canMove = rights.may(MailRight.REMOVE_ITEMS)
    return when {
        !rights.may(MailRight.READ_ITEMS) -> "This folder is shared with you, but not its messages."
        !canChange && !canMove -> "Shared with you to read only: starring, marking read, moving and deleting are off here."
        !canChange -> "Shared with you: starring, tagging and marking read are off here."
        !canMove -> "Shared with you: moving and deleting are off here."
        else -> null
    }
}
