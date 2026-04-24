/**
 * JVM (CIO) implementation of [createPlatformHttpClient] with TOFU pinning.
 *
 * Same two-mode behaviour as the Android actual — a custom
 * [javax.net.ssl.X509TrustManager] either captures the observed fingerprint
 * on first connect (when `pinnedFingerprintHex` is null) or verifies the
 * leaf cert against the pin (and throws `pin-mismatch:` on mismatch).
 *
 * The JVM target is used by the `:clientServer` test host and any future
 * Compose Desktop client; it intentionally does not delegate to the OS trust
 * store, since Termtastic talks to self-signed servers exclusively.
 *
 * @see HttpClientFactory
 */
package se.soderbjorn.termtastic.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.X509TrustManager

/**
 * Build a JVM [HttpClient] backed by Ktor CIO with TLS fingerprint pinning.
 */
actual fun createPlatformHttpClient(
    pinnedFingerprintHex: String?,
    onPeerCertCaptured: (fingerprintHex: String) -> Unit,
): HttpClient = HttpClient(CIO) {
    applyCommonClientConfig(this)
    engine {
        https {
            trustManager = JvmPinningTrustManager(pinnedFingerprintHex, onPeerCertCaptured)
        }
    }
}

/** See android counterpart for a more detailed comment. */
private class JvmPinningTrustManager(
    private val pinnedFingerprintHex: String?,
    private val onPeerCertCaptured: (String) -> Unit,
) : X509TrustManager {

    private val captured = AtomicBoolean(false)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // Server-side auth only.
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Empty server certificate chain")
        }
        val leafFingerprintHex = sha256Hex(chain[0].encoded)
        val pin = pinnedFingerprintHex
        if (pin == null) {
            if (captured.compareAndSet(false, true)) {
                Thread {
                    runCatching { onPeerCertCaptured(leafFingerprintHex) }
                }.apply { isDaemon = true; name = "termtastic-tofu-capture-jvm" }.start()
            }
            return
        }
        val pinBytes = pin.lowercase().toByteArray(Charsets.US_ASCII)
        val leafBytes = leafFingerprintHex.toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(pinBytes, leafBytes)) {
            throw CertificateException(
                "pin-mismatch: server presented $leafFingerprintHex but client pinned $pin"
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
