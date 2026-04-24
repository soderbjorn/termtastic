/**
 * JS (browser) implementation of [createPlatformHttpClient].
 *
 * The browser owns TLS verification end-to-end: trust anchor selection,
 * hostname matching, and any per-origin warning interstitial happen in
 * Chrome/Safari/Firefox themselves. There is no fetch-level hook to install
 * a custom trust manager, so fingerprint pinning is not enforced from JS —
 * users instead click through the browser's self-signed-cert warning once
 * per origin.
 *
 * Both pinning arguments are accepted to keep the signature parallel with
 * the native targets, but are ignored here.
 *
 * @see HttpClientFactory
 */
package se.soderbjorn.termtastic.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

/**
 * Build a JS [HttpClient]. Both [pinnedFingerprintHex] and
 * [onPeerCertCaptured] are intentionally unused — the browser handles TLS.
 */
actual fun createPlatformHttpClient(
    pinnedFingerprintHex: String?,
    onPeerCertCaptured: (fingerprintHex: String) -> Unit,
): HttpClient = HttpClient(Js) {
    applyCommonClientConfig(this)
}
