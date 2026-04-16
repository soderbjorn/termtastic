package se.soderbjorn.termtastic.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import se.soderbjorn.termtastic.windowJson

/**
 * Top-level facade every client uses. Holds the server URL, the device auth
 * token, a long-lived Ktor `HttpClient` (with WebSockets), and the coroutine
 * scope that sockets live in. Factory methods [windowSocket] / [ptySocket]
 * return reusable wrappers for the two live endpoints.
 *
 * Ktor engine selection is done via Gradle dependencies per target:
 *   - :client/androidMain → OkHttp
 *   - :client/jsMain      → JS (fetch + browser WebSocket)
 *   - :client/jvmMain     → CIO (for :electron or tests)
 *   - :client/iosMain     → Darwin
 * `HttpClient { install(WebSockets) { ... } }` in common code picks up the
 * per-target engine automatically.
 */
/**
 * Self-reported metadata the client shares with the server so the new-device
 * approval dialog and the settings dialog can show which sort of client is
 * connecting (and from where it thinks it's coming — handy under NAT). All
 * fields are advisory: the server also records the observed TCP remote
 * address, which is what any real authorisation decision is based on.
 */
data class ClientIdentity(
    val type: String,
    val hostname: String? = null,
    val selfReportedIp: String? = null,
)

class TermtasticClient(
    val serverUrl: ServerUrl,
    val authToken: String,
    val identity: ClientIdentity,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    internal val scope: CoroutineScope = scope

    /**
     * Process-lifetime cache of the latest window config + per-session state
     * map. [WindowSocket] writes to this on every envelope; UI code reads
     * from it so that tearing down and rebuilding a screen always gets the
     * last-known snapshot instantly, independent of socket lifecycle.
     */
    val windowState: WindowStateRepository = WindowStateRepository()

    /**
     * Shared serializer configuration — must stay byte-for-byte compatible
     * with what the server emits (see [windowJson] in :clientServer).
     */
    internal val json: Json = windowJson

    internal val httpClient: HttpClient = HttpClient {
        install(WebSockets)
        // Without this, an unreachable host (device on a different subnet,
        // Wi-Fi client isolation, wrong port) makes the WebSocket handshake
        // hang in kernel SYN retransmit for ~75 seconds before OkHttp gives
        // up. Cap it at 8 s so the UI surfaces the failure quickly.
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 15_000
        }
        // Note: we don't install HttpCookies here because ws(s):// upgrades
        // in Ktor don't always thread cookies through all engines reliably.
        // Instead, every socket URL gets `?auth=<token>` appended — this is
        // the same belt-and-braces channel the server-side readAuthToken
        // helper already recognises (see Application.kt:readAuthToken).
    }

    /**
     * Open (or return) a websocket to `/window`. The returned [WindowSocket]
     * is hot: the `config` / `states` StateFlows start emitting as soon as the
     * server pushes the first envelopes. Call [WindowSocket.close] to tear
     * down the socket and its coroutine.
     */
    fun openWindowSocket(): WindowSocket =
        WindowSocket(client = this, path = "/window")

    /**
     * Open a websocket to `/pty/{sessionId}`. Emits the 64 KB ring-buffer
     * replay as the first items on `output`, then live frames as they arrive.
     */
    fun openPtySocket(sessionId: String): PtySocket =
        PtySocket(client = this, sessionId = sessionId)

    /**
     * URL helper that appends `?auth=<token>` for websocket endpoints. The
     * server's readAuthToken looks at cookie → query → header in that order,
     * so this wins even in environments where cookie jars don't cooperate
     * with upgrade requests.
     */
    internal fun wsUrlWithAuth(path: String): String {
        val sb = StringBuilder(serverUrl.wsUrl(path))
        sb.append("?auth=").append(urlEncode(authToken))
        sb.append("&clientType=").append(urlEncode(identity.type))
        identity.hostname?.takeIf { it.isNotBlank() }?.let {
            sb.append("&clientHost=").append(urlEncode(it))
        }
        identity.selfReportedIp?.takeIf { it.isNotBlank() }?.let {
            sb.append("&clientIp=").append(urlEncode(it))
        }
        return sb.toString()
    }

    /**
     * Headers to attach to REST requests so the server sees the same client
     * metadata it gets from WebSocket upgrades (which can't set headers).
     * Returned as a plain map so callers pass it through ktor's
     * [io.ktor.client.request.header] without pulling in HTTP types here.
     */
    internal fun clientInfoHeaders(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        out += "X-Termtastic-Client-Type" to identity.type
        identity.hostname?.takeIf { it.isNotBlank() }?.let {
            out += "X-Termtastic-Client-Host" to it
        }
        identity.selfReportedIp?.takeIf { it.isNotBlank() }?.let {
            out += "X-Termtastic-Client-Ip" to it
        }
        return out
    }

    fun close() {
        httpClient.close()
    }
}

/**
 * Minimal URL-encoder sufficient for the alphabetic client-info values we
 * pass as query params (token, client type, hostname, IP). Keeps us from
 * pulling a platform-specific URL util into `commonMain` just for this.
 */
private fun urlEncode(value: String): String {
    val sb = StringBuilder(value.length)
    for (c in value) {
        when {
            c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' -> sb.append(c)
            else -> {
                val bytes = c.toString().encodeToByteArray()
                for (b in bytes) {
                    sb.append('%')
                    sb.append(HEX[(b.toInt() ushr 4) and 0x0f])
                    sb.append(HEX[b.toInt() and 0x0f])
                }
            }
        }
    }
    return sb.toString()
}

private val HEX = "0123456789ABCDEF".toCharArray()

/**
 * Factory for platforms (iOS) where Kotlin default parameters are not
 * exported. Creates a [TermtasticClient] with a default coroutine scope.
 */
fun createTermtasticClient(
    serverUrl: ServerUrl,
    authToken: String,
    identity: ClientIdentity,
): TermtasticClient = TermtasticClient(serverUrl, authToken, identity)
