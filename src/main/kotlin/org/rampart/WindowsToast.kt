package org.rampart

import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Actionable toasts on Windows, for the installed MSIX build.
 *
 * A tray balloon cannot carry buttons, and Archive and Mark read are buttons, so the
 * installed build shows a real toast. The buttons are links in the [SCHEME] scheme.
 * Windows opens a link by starting a new rampart.exe with the link as its argument.
 * That second copy loses [SingleInstance.claim] and passes the link back over the
 * loopback socket, and [opened] runs the button. This is protocol activation. A COM
 * activator would mean a WinRT class implemented through JNA vtables, which this
 * program does not do, and protocol activation needs only the scheme the package
 * registers.
 *
 * [start] succeeds only when Conveyor has set `app.windows.userModelID`. It sets that
 * inside the installed package and nowhere else, which is also the only time the scheme
 * is registered. A portable build, a test, or any machine that is not that package
 * keeps the tray balloon passed in as [fallback]. A toast that fails to show falls
 * back the same way, so a notice is never dropped.
 *
 * [TrayShell] used to avoid the toast API entirely, because an unpackaged install has
 * neither an AppUserModelID nor a way to hear a click. The installed build now uses
 * this class. Balloons remain the fallback everywhere else.
 */
internal class WindowsToast(
    private val aumid: String,
    private val fallback: DesktopShell,
    show: ((String) -> Boolean)? = null,
) : DesktopShell {
    private val show: (String) -> Boolean = show ?: ::powershellShow

    private var nextId = 1

    /** The last few notices, so a click can find the button it belongs to. */
    private val pending = object : LinkedHashMap<Int, Pair<() -> Unit, List<NoticeAction>>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<() -> Unit, List<NoticeAction>>>?): Boolean =
            size > 50
    }

    /**
     * The link a second copy handed over. `scheme:open/ID` opens the message,
     * `scheme:act/ID/INDEX` presses that button, and either is forgotten afterwards
     * so a second press does not do it again. Anything else, including a button whose
     * notice is gone because Rampart was restarted, is false: the caller then raises
     * the window.
     */
    override fun opened(link: String): Boolean {
        // Trimmed of a trailing slash, which some launchers add to a link that has none.
        val trimmed = link.trim().trimEnd('/')
        if (!trimmed.startsWith("$SCHEME:")) return false
        val parts = trimmed.substring("$SCHEME:".length).split('/')
        val id = parts.getOrNull(1)?.toIntOrNull() ?: return false
        val run = synchronized(pending) {
            val entry = pending[id] ?: return@synchronized null
            val chosen: (() -> Unit)? = when (parts[0]) {
                "open" -> if (parts.size == 2) entry.first else null
                "act" -> if (parts.size == 3) parts[2].toIntOrNull()?.let { entry.second.getOrNull(it)?.run } else null
                else -> null
            }
            if (chosen != null) pending.remove(id)
            chosen
        } ?: return false
        run()
        return true
    }

    override fun notify(title: String, body: String, actions: List<NoticeAction>, onClick: () -> Unit) {
        val id = synchronized(pending) {
            val current = nextId
            nextId += 1
            pending[current] = onClick to actions
            current
        }
        val xml = toastXml(id, title, body, actions.map { it.label })
        // Off this thread: starting PowerShell is slow, and a notice must not stall the poll.
        thread(isDaemon = true, name = "rampart-toast") {
            if (!runCatching { show(xml) }.getOrDefault(false)) fallback.notify(title, body, actions, onClick)
        }
    }

    /** A balloon click still arrives on the tray icon, which [fallback] owns. */
    override fun trayActivated() = fallback.trayActivated()

    // ponytail: one PowerShell start per notice (about a second). Upgrade path is calling
    // WinRT in process if that ever matters.
    private fun powershellShow(xml: String): Boolean = runCatching {
        // The here-string is literal, and it is safe because escaping removed every
        // apostrophe from the XML, so the text cannot close the string early.
        val script = buildString {
            appendLine("\$ErrorActionPreference = 'Stop'")
            appendLine("[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > \$null")
            appendLine("[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] > \$null")
            appendLine("\$x = New-Object Windows.Data.Xml.Dom.XmlDocument")
            appendLine("\$x.LoadXml(@'")
            appendLine(xml)
            appendLine("'@)")
            appendLine("[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('$aumid').Show([Windows.UI.Notifications.ToastNotification]::new(\$x))")
        }
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            "-",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroy()
            false
        } else {
            process.exitValue() == 0
        }
    }.getOrDefault(false)

    companion object {
        /**
         * The scheme registered in conveyor.conf. A toast button is a link under it,
         * and Windows only launches our app for a scheme the package registered.
         */
        const val SCHEME = "io.github.thejdubb02.rampart"

        /**
         * The installed package, or null. Conveyor sets `app.windows.userModelID` only
         * there, and that is also the only place the URL scheme is registered, so a
         * toast anywhere else would have buttons Windows could not open.
         */
        fun start(fallback: DesktopShell): WindowsToast? {
            val id = System.getProperty("app.windows.userModelID")
            if (id.isNullOrBlank()) return null
            return WindowsToast(id, fallback)
        }
    }
}

/** One toast. No actions element when there is nothing to press. */
internal fun toastXml(id: Int, title: String, body: String, actionLabels: List<String>): String {
    val scheme = WindowsToast.SCHEME
    val head = "<toast launch=\"$scheme:open/$id\" activationType=\"protocol\">" +
        "<visual><binding template=\"ToastGeneric\">" +
        "<text>${xmlEscape(title)}</text><text>${xmlEscape(body)}</text>" +
        "</binding></visual>"
    if (actionLabels.isEmpty()) return "$head</toast>"
    val buttons = actionLabels.mapIndexed { index, label ->
        "<action content=\"${xmlEscape(label)}\" activationType=\"protocol\" arguments=\"$scheme:act/$id/$index\"/>"
    }.joinToString("")
    return "$head<actions>$buttons</actions></toast>"
}

/**
 * The five characters that change meaning in XML. The apostrophe is one of them: a
 * quoted attribute would otherwise end on it, and the PowerShell here-string would too.
 */
private fun xmlEscape(text: String): String = buildString(text.length) {
    for (c in text) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(c)
    }
}
