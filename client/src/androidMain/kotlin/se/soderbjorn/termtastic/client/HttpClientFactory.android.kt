/**
 * Android (OkHttp) implementation of [createPlatformHttpClient] with
 * SSH-style TOFU fingerprint pinning.
 *
 * Installs a custom [javax.net.ssl.X509TrustManager] that operates in one of
 * two modes depending on whether a pin is provided:
 *
 *  - **Capture** (`pinnedFingerprintHex == null`): does not validate the cert
 *    chain (self-signed, no trust anchor). Computes SHA-256 of the leaf cert's
 *    DER, latches it via an [java.util.concurrent.atomic.AtomicBoolean] so the
 *    callback fires at most once, and dispatches [onPeerCertCaptured] off the
 *    network thread. The caller writes the value back to host storage.
 *  - **Verify**: computes the leaf's SHA-256, compares constant-time against
 *    the pin. Mismatch throws a [java.security.cert.CertificateException]
 *    whose message starts with `"pin-mismatch:"` so UI code can detect it via
 *    cause-chain inspection and show a "cert changed, re-pair?" dialog.
 *
 * OkHttp's own `CertificatePinner` is not suitable: it pins on top of a
 * successful default trust check, which we don't have for self-signed certs.
 *
 * @see HttpClientFactory
 */
package se.soderbjorn.termtastic.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Build an Android [HttpClient] backed by OkHttp with TLS fingerprint
 * pinning installed via a custom [X509TrustManager].
 */
actual fun createPlatformHttpClient(
    pinnedFingerprintHex: String?,
    onPeerCertCaptured: (fingerprintHex: String) -> Unit,
): HttpClient = HttpClient(OkHttp) {
    applyCommonClientConfig(this)
    engine {
        config {
            val tm = TermtasticPinningTrustManager(pinnedFingerprintHex, onPeerCertCaptured)
            val ctx = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<X509TrustManager>(tm), SecureRandom())
            }
            sslSocketFactory(ctx.socketFactory, tm)
            // Pinning supersedes hostname identity for self-signed certs
            // (the SAN list on the server side is for browser convenience).
            hostnameVerifier(HostnameVerifier { _, _ -> true })
        }
    }
}

/**
 * Two-mode trust manager. Verify when [pinnedFingerprintHex] is non-null;
 * capture and emit the observed leaf fingerprint via [onPeerCertCaptured]
 * (once per client lifetime) when null.
 */
private class TermtasticPinningTrustManager(
    private val pinnedFingerprintHex: String?,
    private val onPeerCertCaptured: (String) -> Unit,
) : X509TrustManager {

    private val captured = AtomicBoolean(false)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // Server-side authentication only — clients do not present certs.
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Empty server certificate chain")
        }
        val leafFingerprintHex = sha256Hex(chain[0].encoded)
        val pin = pinnedFingerprintHex
        if (pin == null) {
            if (captured.compareAndSet(false, true)) {
                // Hop off the TLS handshake thread: storage writes (DataStore
                // for hosts, etc.) must not block the network handshake.
                Thread {
                    runCatching { onPeerCertCaptured(leafFingerprintHex) }
                }.apply { isDaemon = true; name = "termtastic-tofu-capture" }.start()
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
