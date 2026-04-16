package se.soderbjorn.termtastic.client

import java.security.SecureRandom

private val rng = SecureRandom()

actual fun secureRandomBytes(n: Int): ByteArray {
    val out = ByteArray(n)
    rng.nextBytes(out)
    return out
}
