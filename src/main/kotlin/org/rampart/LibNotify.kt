package org.rampart

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Clickable notifications on Linux, through libnotify and the freedesktop notification
 * service that GNOME, KDE, XFCE and most others run.
 *
 * A click arrives as the "default" action, and only where the service advertises the
 * "actions" capability; elsewhere the same notification shows and a click simply does
 * nothing, which is the best any client can do there. Signals are delivered by GLib's main
 * loop, which a JVM does not run, so one is started on a daemon thread of its own.
 *
 * Nothing here is linked at build time. JNA opens the libraries when [start] is called, and
 * a machine without libnotify, or without a notification service running, gets null and
 * the tray balloon instead.
 *
 * The loop runs a GLib context of its own, never the default one. JavaFX's WebKit, which
 * draws message bodies, keeps sources on the default context that may only run on its own
 * thread; a second loop there ran one on this thread and WebKit aborted the whole process,
 * on every Linux desktop with a notification service (found 2026-10-07). libnotify delivers
 * a click to whichever context was the thread default when it first reached the service,
 * so that first call is made on this thread, with this context pushed.
 */
internal class LibNotify private constructor(
    private val notify: Notify,
    private val gobject: GObject,
    private val clickable: Boolean,
) : DesktopShell {

    @Suppress("FunctionName")
    private interface Notify : Library {
        fun notify_init(appName: String): Boolean
        fun notify_get_server_caps(): Pointer?
        fun notify_notification_new(summary: String, body: String?, icon: String?): Pointer?
        fun notify_notification_add_action(
            notification: Pointer,
            action: String,
            label: String,
            callback: ActionCallback,
            userData: Pointer?,
            freeFunc: Pointer?,
        )
        fun notify_notification_set_hint_string(notification: Pointer, key: String, value: String)
        fun notify_notification_show(notification: Pointer, error: PointerByReference?): Boolean
    }

    @Suppress("FunctionName")
    private interface GObject : Library {
        fun g_signal_connect_data(
            instance: Pointer,
            signal: String,
            handler: Callback,
            data: Pointer?,
            destroy: Pointer?,
            flags: Int,
        ): NativeLong
        fun g_object_unref(instance: Pointer)
        fun g_object_get(instance: Pointer, vararg args: Any?)
    }

    @Suppress("FunctionName")
    private interface GLib : Library {
        fun g_main_context_new(): Pointer
        fun g_main_context_push_thread_default(context: Pointer)
        fun g_main_loop_new(context: Pointer?, isRunning: Boolean): Pointer
        fun g_main_loop_run(loop: Pointer)
        fun g_list_free_full(list: Pointer, free: Pointer)
    }

    private interface ActionCallback : Callback {
        fun invoke(notification: Pointer?, action: String?, userData: Pointer?)
    }

    private interface ClosedCallback : Callback {
        fun invoke(notification: Pointer?, userData: Pointer?)
    }

    /*
     * JNA callbacks are only as alive as the Java objects behind them. Each notification's
     * pair is held here until the service says it closed, or a click could land on a
     * callback the collector has already taken, which crashes the process.
     */
    private val live = ConcurrentHashMap<Pointer, Pair<ActionCallback, ClosedCallback>>()

    override fun notify(title: String, body: String, actions: List<NoticeAction>, onClick: () -> Unit) {
        val n = notify.notify_notification_new(title, body, "mail-message-new") ?: return
        // Lets the service group these under Rampart and use its icon, where it knows the
        // desktop file. Harmless where it does not.
        notify.notify_notification_set_hint_string(n, "desktop-entry", "rampart")
        val action = object : ActionCallback {
            override fun invoke(notification: Pointer?, action: String?, userData: Pointer?) {
                // "default" is a click on the body; a button reports the index it was added under.
                action?.toIntOrNull()?.let { actions.getOrNull(it)?.run?.invoke() } ?: onClick()
            }
        }
        val closed = object : ClosedCallback {
            override fun invoke(notification: Pointer?, userData: Pointer?) {
                live.remove(n)
                gobject.g_object_unref(n)
            }
        }
        live[n] = action to closed
        if (clickable) {
            notify.notify_notification_add_action(n, "default", "Open", action, null, null)
            actions.forEachIndexed { i, a -> notify.notify_notification_add_action(n, "$i", a.label, action, null, null) }
        }
        gobject.g_signal_connect_data(n, "closed", closed, null, null, 0)
        if (!notify.notify_notification_show(n, null)) {
            live.remove(n)
            gobject.g_object_unref(n)
            return
        }
        /*
         * A service that restarts numbers from 1 again and never says the old ones closed, so
         * an earlier notification still listening under this number is stale. Left alone, a
         * click on this one ran its buttons too: one Archive filed two messages (2026-10-07).
         */
        val id = id(n)
        live.keys.filter { it != n && id(it) == id }.forEach {
            live.remove(it)
            gobject.g_object_unref(it)
        }
    }

    private fun id(n: Pointer) = IntByReference().also { gobject.g_object_get(n, "id", it, null) }.value

    companion object {
        /** Opened by name, then by the versioned file a desktop install has without the -dev package. */
        private fun <T : Library> open(names: List<String>, type: Class<T>): T {
            var last: Throwable? = null
            for (name in names) {
                try {
                    return Native.load(name, type)
                } catch (e: UnsatisfiedLinkError) {
                    last = e
                }
            }
            throw last ?: UnsatisfiedLinkError(names.first())
        }

        /** Null where there is no libnotify or no notification service to talk to. */
        fun start(): LibNotify? {
            val notify = open(listOf("notify", "libnotify.so.4"), Notify::class.java)
            val gobject = open(listOf("gobject-2.0", "libgobject-2.0.so.0"), GObject::class.java)
            val glib = open(listOf("glib-2.0", "libglib-2.0.so.0"), GLib::class.java)
            val context = glib.g_main_context_new()
            val ready = CompletableFuture<Boolean?>()
            Thread({
                glib.g_main_context_push_thread_default(context)
                val clickable = runCatching { serverCaps(notify, glib)?.let { "actions" in it } }.getOrNull()
                ready.complete(clickable)
                if (clickable != null) glib.g_main_loop_run(glib.g_main_loop_new(context, false))
            }, "rampart-glib").apply { isDaemon = true }.start()
            val clickable = ready.get() ?: return null
            return LibNotify(notify, gobject, clickable)
        }

        /** What the service can do, or null when there is none to answer. */
        private fun serverCaps(notify: Notify, glib: GLib): List<String>? {
            if (!notify.notify_init("Rampart")) return null
            // Asking what the service can do is also how to learn there is one: no service,
            // no answer, and the tray balloon is the better choice.
            val caps = notify.notify_get_server_caps() ?: return null
            val names = buildList {
                var node: Pointer? = caps
                while (node != null) {
                    node.getPointer(0)?.getString(0)?.let(::add)
                    node = node.getPointer(Native.POINTER_SIZE.toLong())
                }
            }
            val free = runCatching {
                NativeLibrary.getInstance("glib-2.0").getFunction("g_free")
            }.recoverCatching { NativeLibrary.getInstance("libglib-2.0.so.0").getFunction("g_free") }.getOrNull()
            if (free != null) glib.g_list_free_full(caps, free)
            return names
        }
    }
}
