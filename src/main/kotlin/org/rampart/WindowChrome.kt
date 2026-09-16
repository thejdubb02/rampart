package org.rampart

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Component

/**
 * Windows draws the title bar itself and colours it from the system theme, not ours, so a
 * dark Rampart on a light Windows gets a white bar sitting above a black window.
 *
 * Everything here is best effort. A title bar that stays light is a blemish; failing to
 * set it must never be anything a person sees, let alone something that stops the app.
 */
internal object WindowChrome {
    private val onWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    fun setDarkTitleBar(component: Component, dark: Boolean) {
        if (!onWindows) return
        runCatching { Dwm.setDark(component, dark) }
    }
}

private object Dwm {
    /**
     * DWMWA_USE_IMMERSIVE_DARK_MODE. It is 20 from Windows 10 build 18985 onwards and 19
     * before that, and the call simply fails on the wrong one, so both are tried.
     */
    private val DARK_MODE_ATTRIBUTES = intArrayOf(20, 19)

    private val library: Dwmapi by lazy { Native.load("dwmapi", Dwmapi::class.java) }

    fun setDark(component: Component, dark: Boolean) {
        val handle = Native.getComponentPointer(component) ?: return
        val value = IntByReference(if (dark) 1 else 0)
        for (attribute in DARK_MODE_ATTRIBUTES) {
            if (library.DwmSetWindowAttribute(handle, attribute, value, 4) == 0) {
                // The bar is only redrawn when the frame next changes, so nudge it: without
                // this the new colour appears the first time the window is moved or resized.
                component.repaint()
                return
            }
        }
    }
}

private interface Dwmapi : Library {
    fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
}
