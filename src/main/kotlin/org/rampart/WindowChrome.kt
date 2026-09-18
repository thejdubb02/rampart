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

    /**
     * Paints the title bar in the theme's own colours.
     *
     * Windows 11 takes the real colours; Windows 10 ignores these two attributes entirely
     * and keeps the dark or light chrome set above, which is why both are sent and neither
     * is checked. A default title bar is not worth telling anybody about.
     *
     * The bar is part of the window, and a black strip above eighteen themes is the one
     * piece of the app that never matched the rest of it.
     */
    fun setTitleBarColour(component: Component, caption: Int, text: Int, dark: Boolean) {
        if (!onWindows) return
        runCatching { Dwm.setDark(component, dark) }
        runCatching { Dwm.setColours(component, caption, text) }
    }
}

internal object Dwm {
    /**
     * DWMWA_USE_IMMERSIVE_DARK_MODE. It is 20 from Windows 10 build 18985 onwards and 19
     * before that, and the call simply fails on the wrong one, so both are tried.
     */
    private val DARK_MODE_ATTRIBUTES = intArrayOf(20, 19)

    /** DWMWA_CAPTION_COLOR and DWMWA_TEXT_COLOR, both Windows 11 and after. */
    private const val CAPTION_COLOR = 35
    private const val TEXT_COLOR = 36

    private val library: Dwmapi by lazy { Native.load("dwmapi", Dwmapi::class.java) }

    fun setColours(component: Component, caption: Int, text: Int) {
        val handle = Native.getComponentPointer(component) ?: return
        library.DwmSetWindowAttribute(handle, CAPTION_COLOR, IntByReference(colorRef(caption)), 4)
        library.DwmSetWindowAttribute(handle, TEXT_COLOR, IntByReference(colorRef(text)), 4)
        // The bar is only redrawn when the frame next changes, so nudge it, the same way
        // the dark flag has to be nudged above.
        component.repaint()
    }

    /**
     * 0xRRGGBB to a Windows COLORREF, which is 0x00BBGGRR.
     *
     * Red and blue swap. Getting this wrong does not fail, it just paints the wrong colour,
     * which is why there is a test for it rather than a comment saying to be careful.
     */
    fun colorRef(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return r or (g shl 8) or (b shl 16)
    }

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
