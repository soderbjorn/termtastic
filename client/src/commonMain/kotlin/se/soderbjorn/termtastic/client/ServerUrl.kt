package se.soderbjorn.termtastic.client

/**
 * Immutable descriptor of the server this client should talk to. Encapsulates
 * the scheme/host/port split so the various websocket and REST endpoints
 * (/window, /pty/{id}, /api/ui-settings) can be derived consistently.
 *
 *  - [useTls] controls ws(s) / http(s).
 *  - [port] is mandatory — there is no default.
 */
data class ServerUrl(
    val host: String,
    val port: Int,
    val useTls: Boolean = false,
) {
    val httpScheme: String get() = if (useTls) "https" else "http"
    val wsScheme: String get() = if (useTls) "wss" else "ws"

    fun httpUrl(path: String): String = "$httpScheme://$host:$port${ensureSlash(path)}"
    fun wsUrl(path: String): String = "$wsScheme://$host:$port${ensureSlash(path)}"

    private fun ensureSlash(path: String): String = if (path.startsWith("/")) path else "/$path"
}
