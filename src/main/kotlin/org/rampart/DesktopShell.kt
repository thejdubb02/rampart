package org.rampart

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.Window
import java.awt.image.BufferedImage

/**
 * The parts of the desktop Rampart talks to outside its own window: notifications that can
 * be clicked, and an unread count on the taskbar or dock.
 *
 * Compose Desktop's own Notification has no way to learn that it was clicked, which is the
 * reason this exists. Everything platform specific sits behind this interface, and [NoShell]
 * does nothing at all, so the tests and a Linux build with no desktop run the same code.
 */
internal interface DesktopShell {
    /**
     * Shows one notification. [onClick] runs, on some thread, if the person clicks it and
     * this platform can say so, and each of [actions] is a button where the platform has
     * them. Nothing else is promised: a platform that cannot report a click still shows the
     * words.
     */
    fun notify(title: String, body: String, actions: List<NoticeAction> = emptyList(), onClick: () -> Unit)

    /**
     * The tray icon was activated. Where the platform reports a click on a notification as
     * a click on the tray icon, which is Windows, this is where that click arrives.
     */
    fun trayActivated() {}

    /** The unread count, shown on the taskbar button or the dock. Zero clears it. */
    fun unread(count: Int, window: Window?) {}
}

/** A button on a notification. [run] is called on whatever thread the platform reports it on. */
internal class NoticeAction(val label: String, val run: () -> Unit)

/** Does nothing, for tests, for a desktop without a tray, and as the fallback for every failure. */
internal object NoShell : DesktopShell {
    override fun notify(title: String, body: String, actions: List<NoticeAction>, onClick: () -> Unit) {}
}

/**
 * Notifications through the tray icon, which is what Compose's Tray already owns.
 *
 * On Windows this is also how a click is heard, and it is the deliberate choice over the
 * WinRT toast API. A balloon from `TrayIcon.displayMessage` is drawn by Windows 10 and 11 as
 * an ordinary toast, and clicking it sends NIN_BALLOONUSERCLICK to the icon, which AWT turns
 * into the same ActionEvent as activating the icon: Compose passes that to `Tray(onAction)`.
 * The toast API proper would need a registered AppUserModelID and Start menu shortcut before
 * Windows shows anything at all, plus a COM activator implemented through JNA vtables for
 * the click, and an unpackaged or portable install has neither. The tray route needs
 * nothing, and the only thing lost is a click on the copy left in the Action Centre after
 * the toast has gone, which lands as opening the window rather than the message.
 *
 * Because the click and the icon share one event, a click is taken to mean the notification
 * only while that notification is plausibly still on screen, [window] milliseconds.
 */
internal class TrayShell(
    private val send: (String, String) -> Unit,
    private val window: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : DesktopShell {
    @Volatile private var pending: Pair<Long, () -> Unit>? = null

    override fun notify(title: String, body: String, actions: List<NoticeAction>, onClick: () -> Unit) {
        pending = clock() to onClick
        send(title, body)
    }

    override fun trayActivated() {
        val (shown, action) = pending ?: return
        pending = null
        if (clock() - shown <= window) action()
    }
}

/**
 * Notifications from one shell and the unread count from another, because the two are
 * solved differently on the same platform: on Linux the notification is libnotify and the
 * count has nowhere standard to go.
 */
internal class SplitShell(
    private val notices: DesktopShell,
    private val count: DesktopShell,
) : DesktopShell {
    override fun notify(title: String, body: String, actions: List<NoticeAction>, onClick: () -> Unit) = notices.notify(title, body, actions, onClick)
    override fun trayActivated() = notices.trayActivated()
    override fun unread(count: Int, window: Window?) = this.count.unread(count, window)
}

/**
 * The count through `java.awt.Taskbar`, on the platforms that support a badge.
 *
 * A number where the platform takes a number, which is the macOS dock. A picture on the
 * window's taskbar button where it only takes a picture, which is Windows through AWT's own
 * overlay support, used when [WindowsTaskbar] could not be started. Anything else, nothing.
 */
internal object AwtBadge : DesktopShell {
    override fun notify(title: String, body: String, actions: List<NoticeAction>, onClick: () -> Unit) {}

    override fun unread(count: Int, window: Window?) {
        runCatching {
            if (!Taskbar.isTaskbarSupported()) return
            val bar = Taskbar.getTaskbar()
            when {
                bar.isSupported(Taskbar.Feature.ICON_BADGE_NUMBER) ->
                    bar.setIconBadge(if (count > 0) badgeText(count) else null)
                window != null && bar.isSupported(Taskbar.Feature.ICON_BADGE_IMAGE_WINDOW) ->
                    bar.setWindowIconBadge(window, if (count > 0) badgeImage(count, 16) else null)
            }
        }
    }
}

/** "7", or "99+" past two digits, which is as much as a badge can hold legibly. */
internal fun badgeText(count: Int): String = if (count > 99) "99+" else count.toString()

/** A red disc with the count on it, in the same red as the tray badge. */
internal fun badgeImage(count: Int, size: Int): BufferedImage {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0xDB, 0x2D, 0x54)
        g.fillOval(0, 0, size, size)
        val text = badgeText(count)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, if (text.length > 2) size * 5 / 11 else size * 3 / 4)
        val metrics = g.fontMetrics
        g.color = Color.WHITE
        g.drawString(text, (size - metrics.stringWidth(text)) / 2, (size - metrics.height) / 2 + metrics.ascent)
    } finally {
        g.dispose()
    }
    return image
}

/**
 * The right shell for this computer, falling back step by step to [NoShell].
 *
 * [tray] sends a balloon through the tray icon, or is null where there is no tray. Every
 * native piece is started inside runCatching: a missing library or a refused COM call costs
 * the feature it was for, never the start of the app.
 */
internal fun desktopShell(tray: ((String, String) -> Unit)?): DesktopShell {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val balloons: DesktopShell = tray?.let { TrayShell(it) } ?: NoShell
    return when {
        "windows" in os -> SplitShell(balloons, runCatching { WindowsTaskbar.start() }.getOrNull() ?: AwtBadge)
        "linux" in os -> SplitShell(runCatching { LibNotify.start() }.getOrNull() ?: balloons, AwtBadge)
        else -> SplitShell(balloons, AwtBadge)
    }
}
