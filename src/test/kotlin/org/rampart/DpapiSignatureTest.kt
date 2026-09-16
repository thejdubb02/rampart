package org.rampart

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Windows' credential encryption is reached by reflection, so a wrong signature is not a
 * compile error: it fails on the machine of whoever installed the app, and only there.
 * That is exactly what shipped in 0.1.16, where the last argument was written as Object
 * when it is a prompt struct, and every password silently went unsaved.
 *
 * Resolving the methods needs no Windows and no native library, so this check runs
 * everywhere, including the Linux box this is built on.
 */
class DpapiSignatureTest {
    private val prompt = Class.forName("com.sun.jna.platform.win32.WinCrypt\$CRYPTPROTECT_PROMPTSTRUCT")
    private val crypt32 = Class.forName("com.sun.jna.platform.win32.Crypt32Util")

    @Test
    fun `the exact methods Secrets reflects on exist`() {
        val protect = crypt32.getMethod(
            "cryptProtectData",
            ByteArray::class.java, ByteArray::class.java, Int::class.javaPrimitiveType, String::class.java, prompt,
        )
        assertTrue(protect.returnType == ByteArray::class.java)

        val unprotect = crypt32.getMethod(
            "cryptUnprotectData",
            ByteArray::class.java, ByteArray::class.java, Int::class.javaPrimitiveType, prompt,
        )
        assertTrue(unprotect.returnType == ByteArray::class.java)
    }
}
