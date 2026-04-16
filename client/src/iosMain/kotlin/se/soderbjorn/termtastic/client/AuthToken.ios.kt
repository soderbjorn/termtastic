package se.soderbjorn.termtastic.client

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(n: Int): ByteArray {
    val out = ByteArray(n)
    out.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, n.toULong(), pinned.addressOf(0))
    }
    return out
}
