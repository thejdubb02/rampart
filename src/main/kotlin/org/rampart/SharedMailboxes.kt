package org.rampart

import kotlinx.serialization.json.JsonArray

/*
 * Turning a login's session into the shared mailboxes drawn beside it, and sharing a folder
 * of your own (RAM-45). The requests are built in SharedAccounts.kt, the rights in
 * SharingRights.kt, and the guard on every action in SharedBackend.kt; this file only puts
 * them together for the window.
 *
 * Class 3 in docs/self-hosting.md: it depends on what the mail server offers, and an
 * account whose server offers nothing simply has no shared mailboxes and a share dialog
 * that says why. It is all done as the signed-in person. No admin token, ever.
 */

/**
 * One [Session] per shared mail account in each signed-in JMAP login's session.
 *
 * Kept apart from the list of signed-in accounts on purpose: that list feeds snooze,
 * tracking, Rook, tasks, settings sync and more, none of which was written with somebody
 * else's mailbox in mind. A shared mailbox reaches only the places that were.
 */
internal fun sharedSessionsOf(logins: List<Session>): List<Session> = logins.flatMap { login ->
    val jmap = login.jmap as? Jmap ?: return@flatMap emptyList()
    sharedMailAccounts(jmap.sessionAccounts, jmap.accountId).map { account ->
        val view = jmap.forAccount(account.id)
        Session(
            sharedSavedAccount(login.account, account),
            SharedBackend(view, account.id, account.owner) { calls -> view.sharingCall(*calls) },
        )
    }
}

/**
 * The accounts that feed the cross-account views: every login, and each shared mailbox that
 * turned out to hold a folder you can see and that has not been switched out of them.
 */
internal fun unifiedSessions(
    logins: List<Session>,
    shared: List<Session>,
    mailboxes: Map<String, List<Mailbox>>,
    prefs: Map<String, String>,
): List<Session> = logins + shared.filter { !mailboxes[it.key].isNullOrEmpty() && includedInUnified(it.key, prefs) }

/** Reads one of the cross-account views from [sources]. Blocking; call it off the main thread. */
internal fun readUnified(
    view: UnifiedView,
    sources: List<Session>,
    mailboxes: Map<String, List<Mailbox>>,
    prefs: Map<String, String>,
): UnifiedResult {
    val byKey = sources.associateBy { it.key }
    return composeUnified(
        view,
        sources.map { UnifiedSource(it.key, mailboxes[it.key].orEmpty()) },
        feedOf(view, prefs),
    ) { key, folder, filters ->
        runCatching { byKey.getValue(key).jmap.emails(folder, limit = 100, filters = filters) }.getOrNull()
    }
}

/** One row of the share dialog: who, and what they may do. */
internal data class ShareRow(
    /** What the server knows them by: an account id over JMAP, a name over IMAP. */
    val id: String,
    val label: String,
    /** Null for a mix of rights Rampart does not offer, shown as "Custom". */
    val level: ShareLevel?,
)

/**
 * Sharing one account's folders, whichever protocol it speaks. [unavailable] is the
 * sentence to show instead of the dialog's controls, or null when it can be done.
 */
internal interface FolderSharing {
    val unavailable: String?
    fun current(mailboxId: String): List<ShareRow>
    /** Shares with somebody typed in by address. */
    fun shareWith(mailboxId: String, address: String, level: ShareLevel)
    /** Changes or removes (null) a row already listed. */
    fun change(mailboxId: String, row: ShareRow, level: ShareLevel?)
}

/**
 * Over JMAP. [accountId] is the account the folder is in, your own or one you manage;
 * [login] is the signed-in session that speaks for it, and whose own account asks the
 * directory who an address is. Every request names [accountId] itself, so sharing a
 * folder in a mailbox you manage never touches your own.
 */
internal class JmapFolderSharing(
    private val login: Jmap,
    private val accountId: String,
) : FolderSharing {
    private val account = login.sessionAccounts.firstOrNull { it.id == accountId }

    override val unavailable: String? =
        if (account != null && !account.canShare) "This server does not offer sharing folders." else null

    private fun send(invocation: JsonArray, also: String? = null): JsonArray = login.sharingCall(invocation, also = also).first()

    override fun current(mailboxId: String): List<ShareRow> {
        val folder = rightsFoldersIn(send(mailboxesWithRights(accountId, listOf(mailboxId)))).firstOrNull()
            ?: throw NotAllowedHere("That folder is not on the server any more.")
        val grants = folder.shareWith ?: throw NotAllowedHere(FolderAction.SHARE.refused)
        if (grants.isEmpty()) return emptyList()
        // Names are a nicety. A closed directory still leaves the grants readable by id.
        val names = runCatching {
            principalsIn(send(principalsNamed(login.accountId, grants.keys), also = PRINCIPALS)).associateBy { it.id }
        }.getOrDefault(emptyMap())
        return grants.map { (id, rights) ->
            val who = names[id]
            ShareRow(id, who?.let { if (it.email.isNotBlank() && it.email != it.name) "${it.name} (${it.email})" else it.name } ?: id, ShareLevel.of(rights))
        }.sortedBy { it.label.lowercase() }
    }

    override fun shareWith(mailboxId: String, address: String, level: ShareLevel) {
        val found = try {
            principalIdsIn(send(principalByAddress(login.accountId, address), also = PRINCIPALS))
        } catch (e: JmapError) {
            throw NotAllowedHere(principalLookupRefusal(e.message.orEmpty()))
        }
        val id = found.singleOrNull() ?: throw NotAllowedHere("No account on this server has the address ${address.trim()}.")
        if (id == accountId) throw NotAllowedHere("That is the account this folder is already in.")
        set(mailboxId, id, level)
    }

    override fun change(mailboxId: String, row: ShareRow, level: ShareLevel?) {
        set(mailboxId, row.id, level)
    }

    private fun set(mailboxId: String, grantee: String, level: ShareLevel?) {
        shareRefusal(send(shareFolder(accountId, mailboxId, grantee, level)), mailboxId)?.let { throw NotAllowedHere(it) }
    }
}

/** Over IMAP, with the ACL commands. See ImapSharing.kt. */
internal class ImapFolderSharing(private val imap: Imap) : FolderSharing {
    override val unavailable: String? = null

    override fun current(mailboxId: String): List<ShareRow> =
        (imapShares(imap, mailboxId) ?: throw NotAllowedHere(IMAP_NO_ACL))
            .map { ShareRow(it.who, it.who, it.level) }
            .sortedBy { it.label.lowercase() }

    override fun shareWith(mailboxId: String, address: String, level: ShareLevel) {
        imapShare(imap, mailboxId, address, level)
    }

    override fun change(mailboxId: String, row: ShareRow, level: ShareLevel?) {
        imapShare(imap, mailboxId, row.id, level)
    }
}

/** No way to share from this account, with the reason. */
internal class NoFolderSharing(override val unavailable: String) : FolderSharing {
    override fun current(mailboxId: String): List<ShareRow> = emptyList()
    override fun shareWith(mailboxId: String, address: String, level: ShareLevel) { throw NotAllowedHere(unavailable) }
    override fun change(mailboxId: String, row: ShareRow, level: ShareLevel?) { throw NotAllowedHere(unavailable) }
}

/**
 * How folders in the account under [key] are shared: a login of yours, or a shared mailbox,
 * whose folders go through the login it came in on with the shared account's own id.
 */
internal fun folderSharingFor(key: String, logins: List<Session>): FolderSharing {
    val login = logins.firstOrNull { it.key == loginKeyOf(key) }
        ?: return NoFolderSharing("That account is not signed in any more.")
    return when (val backend = login.jmap) {
        is Jmap -> JmapFolderSharing(backend, sharedAccountIdOf(key) ?: backend.accountId)
        is Imap -> ImapFolderSharing(backend)
        else -> NoFolderSharing("This account cannot share folders.")
    }
}
