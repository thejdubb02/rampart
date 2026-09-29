package org.rampart

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Window
import java.awt.image.BufferedImage
import java.util.concurrent.Executors

/**
 * The unread count on Rampart's taskbar button, through ITaskbarList3::SetOverlayIcon.
 *
 * The same small red disc the tray icon carries, drawn over the corner of the taskbar
 * button, which is where Windows mail clients put it and where the eye goes. AWT can do
 * this too, through `Taskbar.setWindowIconBadge`, and [AwtBadge] does exactly that when
 * this could not start. Calling the shell directly is preferred because it also sets the
 * accessible description ("3 unread messages") that a screen reader announces, which the
 * AWT route leaves blank, and because a refusal comes back as an HRESULT that can be acted
 * on rather than being swallowed inside the JDK.
 *
 * The taskbar object is apartment threaded, so it is created, used and released on one
 * thread of its own that has joined a single-threaded apartment. Nothing here touches the
 * AWT event thread.
 */
internal class WindowsTaskbar private constructor(
    private val thread: java.util.concurrent.ExecutorService,
    private val list: TaskbarList,
) : DesktopShell {

    /** The three methods of ITaskbarList3 this needs, by their place in its vtable. */
    private class TaskbarList(p: Pointer) : Unknown(p) {
        // IUnknown is 0 to 2, ITaskbarList adds HrInit at 3 up to SetActiveAlt at 7,
        // ITaskbarList2 adds MarkFullscreenWindow at 8, and ITaskbarList3 runs from
        // SetProgressValue at 9 to SetOverlayIcon at 18.
        fun hrInit(): Int = _invokeNativeInt(3, arrayOf(pointer))

        fun setOverlayIcon(window: WinDef.HWND, icon: WinDef.HICON?, description: WString?): Int =
            _invokeNativeInt(18, arrayOf(pointer, window, icon, description))
    }

    /** The icon last handed to the shell, destroyed once the next one has replaced it. */
    private var shown: WinDef.HICON? = null

    override fun notify(title: String, body: String, onClick: () -> Unit) {}

    override fun unread(count: Int, window: Window?) {
        if (window == null) return
        // Read on the caller's thread: the peer, and so the handle, belongs to AWT.
        val handle = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return
        thread.execute {
            val icon = if (count > 0) runCatching { iconOf(badgeImage(count, 16)) }.getOrNull() else null
            val words = if (count > 0) WString(if (count == 1) "1 unread message" else "$count unread messages") else null
            val result = list.setOverlayIcon(WinDef.HWND(handle), icon, words)
            if (result < 0) {
                // Most often the taskbar button does not exist yet, early in start up. The
                // next change of count tries again; AWT's route is the fallback meanwhile.
                icon?.let { User32.INSTANCE.DestroyIcon(it) }
                AwtBadge.unread(count, window)
                return@execute
            }
            shown?.let { User32.INSTANCE.DestroyIcon(it) }
            shown = icon
        }
    }

    /** jna-platform's User32 stops short of CreateIconIndirect, so the one function is declared here. */
    @Suppress("FunctionName")
    private interface Icons : StdCallLibrary {
        fun CreateIconIndirect(info: WinGDI.ICONINFO): WinDef.HICON?

        companion object {
            val INSTANCE: Icons = Native.load("user32", Icons::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    companion object {
        private val CLSID_TASKBAR_LIST: Guid.CLSID = Guid.CLSID("{56FDF344-FD6D-11d0-958A-006097C9A090}")
        private val IID_TASKBAR_LIST3: Guid.GUID = Guid.GUID.fromString("{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEE6F}")

        /** Null where the shell will not hand over the object, which is a server core install or an old Windows. */
        fun start(): WindowsTaskbar? {
            val thread = Executors.newSingleThreadExecutor { job -> Thread(job, "rampart-taskbar").apply { isDaemon = true } }
            val list = thread.submit<TaskbarList?> {
                Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
                val out = PointerByReference()
                val made = Ole32.INSTANCE.CoCreateInstance(
                    CLSID_TASKBAR_LIST,
                    null,
                    WTypes.CLSCTX_INPROC_SERVER,
                    IID_TASKBAR_LIST3,
                    out,
                )
                if (made.toInt() < 0 || out.value == null) return@submit null
                TaskbarList(out.value).takeIf { it.hrInit() >= 0 }
            }.get()
            if (list == null) {
                thread.shutdown()
                return null
            }
            return WindowsTaskbar(thread, list)
        }

        /**
         * An HICON from a picture with an alpha channel.
         *
         * A 32 bit top-down DIB section takes AWT's ARGB integers exactly as they are, since
         * both are BGRA in memory on a little-endian machine. The mask is required by
         * CreateIconIndirect and ignored by Windows for an icon whose colour bitmap has alpha.
         */
        private fun iconOf(image: BufferedImage): WinDef.HICON {
            val w = image.width
            val h = image.height
            val info = WinGDI.BITMAPINFO()
            info.bmiHeader.biWidth = w
            info.bmiHeader.biHeight = -h
            info.bmiHeader.biPlanes = 1
            info.bmiHeader.biBitCount = 32
            info.bmiHeader.biCompression = WinGDI.BI_RGB
            val bits = PointerByReference()
            val screen = User32.INSTANCE.GetDC(null)
            val colour = GDI32.INSTANCE.CreateDIBSection(screen, info, WinGDI.DIB_RGB_COLORS, bits, null, 0)
            val mask = GDI32.INSTANCE.CreateCompatibleBitmap(screen, w, h)
            User32.INSTANCE.ReleaseDC(null, screen)
            try {
                val pixels = image.getRGB(0, 0, w, h, null, 0, w)
                bits.value.write(0, pixels, 0, pixels.size)
                val icon = WinGDI.ICONINFO()
                icon.fIcon = true
                icon.hbmColor = colour
                icon.hbmMask = mask
                return Icons.INSTANCE.CreateIconIndirect(icon) ?: throw IllegalStateException("CreateIconIndirect refused the badge.")
            } finally {
                GDI32.INSTANCE.DeleteObject(colour)
                GDI32.INSTANCE.DeleteObject(mask)
            }
        }
    }
}
