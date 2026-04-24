/**
 * Per-platform [HttpClient] factory with TLS fingerprint pinning.
 *
 * Each Kotlin Multiplatform target supplies an `actual` implementation that
 * wires the platform's HTTP engine (OkHttp on Android, Darwin on iOS, CIO on
 * the JVM, browser fetch on JS) and installs a custom trust callback used for
 * SSH-style Trust-On-First-Use (TOFU) certificate pinning.
 *
 * Two operating modes:
 *   - **Capture** (`pinnedFingerprintHex == null`): no chain validation; the
 *     leaf cert's SHA-256 is computed, exposed via [onPeerCertCaptured], and
 *     persisted by the caller (typically off the network thread).
 *   - **Verify** (`pinnedFingerprintHex != null`): the leaf's SHA-256 is
 *     constant-time compared against the pin; mismatch aborts the handshake
 *     and surfaces an exception the UI catches as "cert changed, re-pair?".
 *
 * Web/JS does not pin — the browser owns TLS verification and pinning is not
 * exposed to fetch. Both arguments are ignored on that target.
 *
 * @see TermtasticClient
 */
package se.soderbjorn.termtastic.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Apply the configuration shared by every platform: WebSockets and a strict
 * connect/request timeout so that an unreachable host fails fast rather than
 * hanging on SYN retransmits for ~75 seconds.
 *
 * @param config the platform's [HttpClientConfig] builder; mutated in place.
 */
fun applyCommonClientConfig(config: HttpClientConfig<*>) {
    config.install(WebSockets)
    config.install(HttpTimeout) {
        connectTimeoutMillis = 8_000
        requestTimeoutMillis = 15_000
    }
}

/**
 * Build a Ktor [HttpClient] for the current platform.
 *
 * @param pinnedFingerprintHex lowercase hex SHA-256 of the server's leaf
 *   certificate that the client should pin against, or `null` to run in TOFU
 *   capture mode.
 * @param onPeerCertCaptured invoked at most once per client when the leaf
 *   cert is observed during the handshake — only fires in capture mode (i.e.
 *   when [pinnedFingerprintHex] is `null`). The callback receives the same
 *   lowercase hex SHA-256 the caller is expected to persist.
 * @return a configured [HttpClient]. The caller owns its lifecycle and must
 *   call `close()` to release the engine.
 */
expect fun createPlatformHttpClient(
    pinnedFingerprintHex: String?,
    onPeerCertCaptured: (fingerprintHex: String) -> Unit,
): HttpClient
