/**
 * Core Termtastic client facade and networking entry point.
 *
 * [TermtasticClient] is the top-level object every platform creates at startup.
 * It owns the Ktor [HttpClient] (with WebSockets and timeout configuration),
 * the device auth token, client identity metadata, and the process-lifetime
 * [WindowStateRepository]. Factory methods [TermtasticClient.openWindowSocket]
 * and [TermtasticClient.openPtySocket] return hot socket wrappers for the two
 * live server endpoints.
 *
 * Ktor engine selection is automatic via Gradle per-target dependencies (OkHttp
 * on Android, CIO on JVM, JS fetch in the browser, Darwin on iOS).
 *
 * @see WindowSocket
 * @see PtySocket
 * @see ServerUrl
 */
package se.soderbjorn.termtastic.client

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * Central client facade for communicating with a Termtastic server.
 *
 * Holds the [serverUrl], [authToken], and [identity] needed to authenticate
 * every HTTP and WebSocket request. Use [openWindowSocket] to subscribe to
 * window layout changes and [openPtySocket] to stream terminal I/O.
 *
 * @param serverUrl the server endpoint descriptor.
 * @param authToken the base64url device-auth token (see [getOrCreateToken]).
 * @param identity  self-reported client metadata sent to the server.
 * @param scope     coroutine scope for socket reader loops; defaults to a
 *   [SupervisorJob] on [Dispatchers.Default].
 *
 * @see createTermtasticClient
 */
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

    /**
     * Lowercase hex SHA-256 of the server's leaf TLS certificate, observed
     * during the most recent successful TLS handshake.
     *
     * - In **capture mode** (no pin set on [serverUrl]), the platform HTTP
     *   client emits the captured fingerprint here on first connect; consumers
     *   subscribe and persist it back to their host-list storage so subsequent
     *   connections enter verify mode.
     * - In **verify mode** (pin set), the value mirrors the pin once a connect
     *   succeeds — useful for diagnostics but no write-back is required.
     *
     * Stays `null` until the first successful TLS handshake. Network thread
     * must not call back into storage; see platform `actual` implementations
     * for the off-thread emission contract.
     */
    private val _observedFingerprint: MutableStateFlow<String?> =
        MutableStateFlow(serverUrl.pinnedFingerprintHex)
    val observedFingerprint: StateFlow<String?> = _observedFingerprint.asStateFlow()

    internal val httpClient: HttpClient = createPlatformHttpClient(
        pinnedFingerprintHex = serverUrl.pinnedFingerprintHex,
        onPeerCertCaptured = { fp -> _observedFingerprint.value = fp },
    )
    // Note: we don't install HttpCookies here because ws(s):// upgrades
    // in Ktor don't always thread cookies through all engines reliably.
    // Instead, every socket URL gets `?auth=<token>` appended — this is
    // the same belt-and-braces channel the server-side readAuthToken
    // helper already recognises (see Application.kt:readAuthToken).

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

    /**
     * Shut down the underlying Ktor [HttpClient], releasing connection pools
     * and any associated resources. After this call the client is no longer
     * usable.
     */
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
