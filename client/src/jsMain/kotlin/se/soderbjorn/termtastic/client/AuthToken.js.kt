package se.soderbjorn.termtastic.client

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlinx.browser.window

actual fun secureRandomBytes(n: Int): ByteArray {
    val u8 = Uint8Array(n)
    window.asDynamic().crypto.getRandomValues(u8)
    val out = ByteArray(n)
    for (i in 0 until n) {
        out[i] = u8[i]
    }
    return out
}
