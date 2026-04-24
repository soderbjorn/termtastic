/**
 * iOS (Darwin) implementation of [createPlatformHttpClient] with TOFU
 * fingerprint pinning.
 *
 * Hooks into Ktor-Darwin's `engine { handleChallenge { ... } }` block. When
 * iOS's URL loading system invokes the server-trust authentication challenge,
 * the handler:
 *
 *   - Extracts the leaf certificate via `SecTrustGetCertificateAtIndex(trust, 0)`.
 *   - Computes its SHA-256 via `CC_SHA256` from CommonCrypto.
 *   - In capture mode (no pin): emits the fingerprint via
 *     [onPeerCertCaptured] off the network thread, then accepts the cert with
 *     `challenge.proposedCredential`.
 *   - In verify mode (pin set): constant-time-compares against the pin and
 *     either accepts the credential (match) or cancels the challenge (mismatch).
 *
 * @see HttpClientFactory
 */
package se.soderbjorn.termtastic.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.Foundation.*
import platform.Security.*
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import kotlin.concurrent.AtomicReference

/**
 * Build an iOS [HttpClient] using Ktor Darwin with a TLS fingerprint
 * pinning challenge handler.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun createPlatformHttpClient(
    pinnedFingerprintHex: String?,
    onPeerCertCaptured: (fingerprintHex: String) -> Unit,
): HttpClient {
    val captureLatch = AtomicReference(false)
    return HttpClient(Darwin) {
        applyCommonClientConfig(this)
        engine {
            handleChallenge { _, _, challenge, completionHandler ->
                handleServerTrust(
                    challenge = challenge,
                    pinnedFingerprintHex = pinnedFingerprintHex,
                    onPeerCertCaptured = onPeerCertCaptured,
                    captureLatch = captureLatch,
                    completionHandler = completionHandler,
                )
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun handleServerTrust(
    challenge: NSURLAuthenticationChallenge,
    pinnedFingerprintHex: String?,
    onPeerCertCaptured: (String) -> Unit,
    captureLatch: AtomicReference<Boolean>,
    completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit,
) {
    val protectionSpace = challenge.protectionSpace
    if (protectionSpace.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
        completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
        return
    }
    val serverTrust: SecTrustRef? = protectionSpace.serverTrust
    if (serverTrust == null) {
        completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
        return
    }
    val leafFingerprintHex = leafSha256Hex(serverTrust)
    if (leafFingerprintHex == null) {
        completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
        return
    }

    if (pinnedFingerprintHex == null) {
        // Capture mode — accept whatever the server presents and emit the
        // fingerprint to the caller off the TLS handshake thread.
        if (captureLatch.compareAndSet(expected = false, newValue = true)) {
            val captured = leafFingerprintHex
            dispatch_async(
                dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u),
            ) {
                runCatching { onPeerCertCaptured(captured) }
            }
        }
        // Use the system-proposed credential, which iOS provides for ServerTrust
        // challenges and which `URLSession` understands as "accept this cert".
        completionHandler(
            NSURLSessionAuthChallengeUseCredential,
            challenge.proposedCredential,
        )
    } else {
        if (constantTimeEquals(leafFingerprintHex, pinnedFingerprintHex.lowercase())) {
            completionHandler(
                NSURLSessionAuthChallengeUseCredential,
                challenge.proposedCredential,
            )
        } else {
            completionHandler(
                NSURLSessionAuthChallengeCancelAuthenticationChallenge,
                null,
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun leafSha256Hex(serverTrust: SecTrustRef): String? {
    val count = SecTrustGetCertificateCount(serverTrust)
    if (count <= 0) return null
    val leaf = SecTrustGetCertificateAtIndex(serverTrust, 0) ?: return null
    val der = SecCertificateCopyData(leaf) ?: return null
    try {
        val length = CFDataGetLength(der).toInt()
        if (length <= 0) return null
        val ptr = CFDataGetBytePtr(der) ?: return null
        val bytes = ptr.readBytes(length)
        return sha256Hex(bytes)
    } finally {
        CFRelease(der)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun sha256Hex(input: ByteArray): String {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    input.usePinned { inPinned ->
        digest.usePinned { outPinned ->
            CC_SHA256(
                inPinned.addressOf(0),
                input.size.convert(),
                outPinned.addressOf(0).reinterpret<UByteVar>(),
            )
        }
    }
    return digest.joinToString("") { ((it.toInt() and 0xff).toString(16).padStart(2, '0')) }
}

private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) {
        diff = diff or (a[i].code xor b[i].code)
    }
    return diff == 0
}
