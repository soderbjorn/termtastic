package se.soderbjorn.termtastic.client

/**
 * Minimal storage abstraction for the device-auth token. Implementations:
 *   - Web: localStorage-backed (see jsMain).
 *   - Android: DataStore<Preferences>-backed (lives in :androidApp).
 *
 * The token is a base64url-encoded 32-byte random value. It goes into the
 * `termtastic_auth` cookie / `auth` query param / `X-Termtastic-Auth` header
 * on every request to the server — see [TermtasticClient] for how the client
 * attaches it.
 */
interface AuthTokenStore {
    fun load(): String?
    fun save(token: String)
}

/**
 * Generate [n] cryptographically-strong random bytes. Implemented per
 * platform: SecureRandom on JVM/Android, window.crypto.getRandomValues on
 * JS, SecRandomCopyBytes on Darwin.
 */
expect fun secureRandomBytes(n: Int): ByteArray

/**
 * Encode [bytes] as base64url with no padding. Matches the output of the
 * pre-refactor web client's `btoa(...).replace('+', '-').replace('/', '_').trimEnd('=')`
 * so a token minted by the Kotlin client is indistinguishable from one the
 * JS client used to mint, and server-side approvals persist across the
 * migration.
 */
fun encodeBase64Url(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val sb = StringBuilder((bytes.size * 4 + 2) / 3)
    var i = 0
    while (i + 3 <= bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = bytes[i + 1].toInt() and 0xFF
        val b2 = bytes[i + 2].toInt() and 0xFF
        sb.append(alphabet[b0 ushr 2])
        sb.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
        sb.append(alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
        sb.append(alphabet[b2 and 0x3F])
        i += 3
    }
    val remaining = bytes.size - i
    if (remaining == 1) {
        val b0 = bytes[i].toInt() and 0xFF
        sb.append(alphabet[b0 ushr 2])
        sb.append(alphabet[(b0 and 0x03) shl 4])
    } else if (remaining == 2) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = bytes[i + 1].toInt() and 0xFF
        sb.append(alphabet[b0 ushr 2])
        sb.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
        sb.append(alphabet[(b1 and 0x0F) shl 2])
    }
    return sb.toString()
}

/**
 * Load the stored auth token, or mint a fresh 32-byte random token and
 * persist it. Idempotent after the first call on a given device.
 */
fun getOrCreateToken(store: AuthTokenStore): String {
    store.load()?.takeIf { it.isNotBlank() }?.let { return it }
    val token = encodeBase64Url(secureRandomBytes(32))
    store.save(token)
    return token
}
