package org.rampart

/**
 * Why Rook is allowed to name a message.
 *
 * Search results and the message the person opened are separate grants. Keeping the source
 * explicit makes the open message a narrow allowance for one id in one account, rather than
 * an undocumented exception to the search rule.
 */
internal enum class MessageAllowance { SEARCH_RESULT, OPEN_MESSAGE }

internal data class AllowedMessage(
    val id: String,
    val subject: String,
    val allowance: MessageAllowance,
)

/** The ids one account's Rook conversation has been shown. */
internal class MessageAllowances {
    private val messages = linkedMapOf<String, AllowedMessage>()

    fun showSearch(found: List<Summary>) {
        found.forEach { messages[it.id] = AllowedMessage(it.id, it.subject, MessageAllowance.SEARCH_RESULT) }
    }

    fun showOpen(message: Summary) {
        // Only the message open now: the one opened before stops being allowed the moment
        // another takes its place, or Rook could still act on something no longer on screen.
        messages.values.removeAll { it.allowance == MessageAllowance.OPEN_MESSAGE }
        messages[message.id] = AllowedMessage(message.id, message.subject, MessageAllowance.OPEN_MESSAGE)
    }

    fun allowed(ids: List<String>): List<AllowedMessage> = ids.distinct().take(MOST).mapNotNull(messages::get)

    fun clear() = messages.clear()
}

/** Account-keyed transcript storage used when All inboxes changes the selected account. */
internal data class RookConversations(private val accounts: Map<String, List<Said>> = emptyMap()) {
    fun said(account: String): List<Said> = accounts[account].orEmpty()
    fun append(account: String, lines: List<Said>): RookConversations =
        copy(accounts = accounts + (account to (said(account) + lines)))
    fun clear(account: String): RookConversations = copy(accounts = accounts + (account to emptyList()))
}

internal enum class MailChangeKind { ARCHIVE, TRASH, MARK_READ, TAG }

/** A mail change Rook proposed, bound to the account and messages shown on its card. */
internal data class MailChangeCard(
    val number: Int,
    val account: String,
    val accountName: String,
    val kind: MailChangeKind,
    val messages: List<AllowedMessage>,
    val read: Boolean = true,
    val keyword: String = "",
    val on: Boolean = true,
    val status: CardStatus = CardStatus.WAITING,
    val outcome: String? = null,
) {
    val action: String get() = when (kind) {
        MailChangeKind.ARCHIVE -> "Archive"
        MailChangeKind.TRASH -> "Move to Trash"
        MailChangeKind.MARK_READ -> if (read) "Mark read" else "Mark unread"
        MailChangeKind.TAG -> if (on) "Add tag $keyword" else "Remove tag $keyword"
    }
}

/** Plain card transitions keep Confirm idempotent and Cancel unable to perform a write. */
internal data class MailChangeDesk(val cards: List<MailChangeCard> = emptyList()) {
    val nextNumber: Int get() = (cards.maxOfOrNull { it.number } ?: 0) + 1
    fun card(number: Int): MailChangeCard? = cards.firstOrNull { it.number == number }
    fun add(card: MailChangeCard): MailChangeDesk =
        if (cards.any { it.number == card.number }) this else copy(cards = cards + card)
    fun start(number: Int): MailChangeDesk = move(number, CardStatus.WAITING) { it.copy(status = CardStatus.APPLYING) }
    fun finish(number: Int, failure: String?): MailChangeDesk = move(number, CardStatus.APPLYING) {
        if (failure == null) it.copy(status = CardStatus.DONE, outcome = "Done.")
        else it.copy(status = CardStatus.FAILED, outcome = failure)
    }
    fun dismiss(number: Int): MailChangeDesk = move(number, CardStatus.WAITING) {
        it.copy(status = CardStatus.DISMISSED, outcome = "Cancelled. Nothing changed.")
    }
    fun clear(): MailChangeDesk = copy(cards = cards.filter { it.status == CardStatus.APPLYING })
    private fun move(number: Int, from: CardStatus, change: (MailChangeCard) -> MailChangeCard) =
        copy(cards = cards.map { if (it.number == number && it.status == from) change(it) else it })
}

/**
 * Collects mail changes requested during one model turn. It cannot apply them. The only
 * writer is the window's Confirm handler, which the model and message text cannot call.
 */
internal class MailChangeTools(
    private val account: String,
    private val accountName: String,
    private val allowances: MessageAllowances,
    nextNumber: Int,
) {
    private var number = nextNumber
    val proposed = mutableListOf<MailChangeCard>()

    fun propose(
        kind: MailChangeKind,
        ids: List<String>,
        read: Boolean = true,
        keyword: String = "",
        on: Boolean = true,
    ): String {
        val messages = allowances.allowed(ids)
        if (messages.isEmpty()) return "There is no message here with any of those ids."
        proposed += MailChangeCard(number++, account, accountName, kind, messages, read, keyword, on)
        return "A confirmation card is waiting for the person. Nothing has changed."
    }
}

/** Performs one confirmed card exactly once. Null means every requested write succeeded. */
internal fun applyMailChange(card: MailChangeCard, tools: MailTools): String? {
    val ids = card.messages.map { it.id }
    val changed = when (card.kind) {
        MailChangeKind.ARCHIVE -> tools.file(ids, "archive")
        MailChangeKind.TRASH -> tools.file(ids, "trash")
        MailChangeKind.MARK_READ -> tools.markRead(ids, card.read)
        MailChangeKind.TAG -> tools.tag(ids, card.keyword, card.on)
    }
    return if (changed == ids.size) null else "Only $changed of ${ids.size} messages could be changed."
}
