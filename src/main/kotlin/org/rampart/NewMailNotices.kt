package org.rampart

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.time.LocalTime

/**
 * New-mail notifications, from the moment the poll finds an arrival to the moment a click
 * opens the message.
 *
 * One object for the whole application because the pieces live in different places: the
 * tray and the taskbar belong to the application, the poll and the message list belong to
 * the window's content, and the click arrives on whatever thread the platform chooses.
 * Everything a thread can touch goes through [lock] or the [opens] channel.
 */
internal object NewMailNotices {
    /** Set once at start by the application, from [desktopShell]. [NoShell] until then and in tests. */
    @Volatile var shell: DesktopShell = NoShell

    private val lock = Any()
    private val bursts = Bursts()

    /**
     * Messages a notification was clicked for, waiting for the reader to open them.
     *
     * Conflated because only the latest click matters: two clicks in a row mean the second
     * message, not a queue of both.
     */
    val opens = Channel<MailRef>(Channel.CONFLATED)

    /** Called by the poll with what it found new in one account's inbox. */
    fun arrived(account: String, fresh: List<Summary>, now: Long = System.currentTimeMillis()) {
        if (fresh.isEmpty()) return
        synchronized(lock) { bursts.add(fresh.map { Arrival(account, it) }, now) }
    }

    private fun due(now: Long): List<Arrival>? = synchronized(lock) { bursts.due(now) }

    /**
     * Runs for as long as the window does, releasing each burst when it is ready.
     *
     * [inboxNow] reads one account's inbox as it stands, or null if it cannot. It is asked
     * again here rather than trusting what the poll saw, because the whole point of waiting
     * for a burst to settle is that a filter or another client may have acted meanwhile.
     */
    suspend fun run(inboxNow: suspend (String) -> List<Summary>?) {
        while (true) {
            delay(1_000)
            val burst = due(System.currentTimeMillis()) ?: continue
            val current = burst.map { it.account }.distinct().associateWith { inboxNow(it) }
            val notice = noticeFor(
                burst = burst,
                inboxNow = current,
                enabled = Settings.notifyOnArrival(),
                silenced = Settings.silencedAccounts(),
                quiet = QuietHours.parse(Settings.quietHours()),
                time = LocalTime.now(Regional.zone()),
            ) ?: continue
            show(notice)
        }
    }

    fun show(notice: MailNotice) {
        shell.notify(notice.title, notice.body) { notice.opens?.let { opens.trySend(it) } }
    }
}
