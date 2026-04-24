/**
 * Server URL resolution for the Termtastic client.
 *
 * [ServerUrl] is the single source of truth for constructing HTTP and WebSocket
 * URLs to the Ktor backend. Every endpoint the client calls -- `/window`,
 * `/pty/{id}`, `/api/ui-settings` -- is derived through [ServerUrl.httpUrl] or
 * [ServerUrl.wsUrl] so scheme, host, and port are always consistent.
 *
 * @see TermtasticClient
 */
package se.soderbjorn.termtastic.client

/**
 * Immutable descriptor of the server this client should talk to. Encapsulates
 * the scheme/host/port split so the various websocket and REST endpoints
 * (`/window`, `/pty/{id}`, `/api/ui-settings`) can be derived consistently.
 *
 * @property host                 hostname or IP address of the Termtastic server.
 * @property port                 TCP port the server listens on (no default).
 * @property useTls               if `true`, use `https`/`wss`; otherwise `http`/`ws`.
 *   Defaults to `true` — every Termtastic server now serves TLS only. Set to
 *   `false` only when targeting an explicitly insecure dev setup.
 * @property pinnedFingerprintHex lowercase hex SHA-256 of the server's leaf
 *   certificate. When `null`, the underlying client runs in TOFU capture mode
 *   on first connect and surfaces the observed fingerprint via
 *   [TermtasticClient.observedFingerprint] so the caller can persist it.
 *   When set, the platform `createPlatformHttpClient` verifies the leaf cert
 *   matches and refuses the connection on mismatch.
 */
data class ServerUrl(
    val host: String,
    val port: Int,
    val useTls: Boolean = true,
    val pinnedFingerprintHex: String? = null,
) {
    /** `"https"` or `"http"` depending on [useTls]. */
    val httpScheme: String get() = if (useTls) "https" else "http"
    /** `"wss"` or `"ws"` depending on [useTls]. */
    val wsScheme: String get() = if (useTls) "wss" else "ws"

    /**
     * Build a fully qualified HTTP URL for the given server [path].
     *
     * @param path the endpoint path, e.g. `"/api/ui-settings"`.
     * @return the complete URL string.
     */
    fun httpUrl(path: String): String = "$httpScheme://$host:$port${ensureSlash(path)}"

    /**
     * Build a fully qualified WebSocket URL for the given server [path].
     *
     * @param path the endpoint path, e.g. `"/window"`.
     * @return the complete URL string.
     */
    fun wsUrl(path: String): String = "$wsScheme://$host:$port${ensureSlash(path)}"

    private fun ensureSlash(path: String): String = if (path.startsWith("/")) path else "/$path"
}
