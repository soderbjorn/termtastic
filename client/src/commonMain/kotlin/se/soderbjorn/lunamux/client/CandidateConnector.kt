/**
 * Multi-candidate connection walker: tries each advertised server endpoint in
 * order and hands back the first one that completes a WebSocket handshake.
 *
 * A Lunamux server usually has several addresses (multiple LAN interfaces
 * today; VPN and IPv6 candidates in the future), and the pairing QR advertises
 * all of them. [CandidateConnector.connectFirstReachable] encapsulates the
 * "try them in order, keep the winner" logic in shared code so Android and
 * (later) iOS get identical behaviour.
 *
 * Lives in `commonMain` next to [LunamuxClient]; the platform connection
 * holders remain the owners of connection lifecycle — this object only
 * *establishes* connections.
 *
 * @see LunamuxClient
 * @see se.soderbjorn.lunamux.HostPort
 */
package se.soderbjorn.lunamux.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import se.soderbjorn.lunamux.HostPort

/**
 * The successful outcome of [CandidateConnector.connectFirstReachable]: a
 * live client + window socket pair and the endpoint that won the race.
 *
 * The caller owns both handles — typically it finishes the second handshake
 * phase (`awaitInitialConfig`) and then stores them in its connection holder,
 * promoting [endpoint] to the head of the host entry's address list (see
 * [HostEntry.promoting]) so the next connect tries the known-good address
 * first.
 *
 * @property client the connected [LunamuxClient]; close on failure/teardown.
 * @property windowSocket the `/window` socket whose handshake completed.
 * @property endpoint the candidate that connected.
 */
data class CandidateConnection(
    val client: LunamuxClient,
    val windowSocket: WindowSocket,
    val endpoint: HostPort,
)

/**
 * Raised by [CandidateConnector.connectFirstReachable] when a candidate was
 * rejected for presenting a leaf certificate that does not match the entry's
 * pin, on a platform whose HTTP engine does not preserve the
 * `"pin-mismatch:"` marker in the failure's cause chain (iOS Darwin cancels
 * the auth challenge with a generic error).
 *
 * The connector detects that case from the client's [LunamuxClient.observedMismatch]
 * side-channel, but that flow belongs to a client the connector owns and closes
 * before rethrowing — so the signal has to travel *with* the exception or the
 * caller loses it. Wrapping restores the one invariant every UI depends on:
 * [isPinMismatch] is true for anything this function throws after a mismatch,
 * on every platform.
 *
 * JVM-backed targets already throw a marker-bearing `CertificateException`, so
 * their failure propagates unwrapped and never becomes one of these.
 *
 * @property observedFingerprintHex the leaf fingerprint the server actually
 *   presented, or `null` if the engine did not surface one.
 * @param cause the underlying transport failure.
 * @see isPinMismatch
 */
/**
 * Thrown by [CandidateConnector.connectFirstReachable] when no candidate
 * endpoint answered — every address timed out or failed at the transport level.
 * This is the one failure for which "check your Wi-Fi" advice is correct.
 *
 * Deliberately *not* thrown for a pin mismatch (the server answered; see
 * [PinMismatchException]) nor for a post-handshake device-auth rejection (the
 * server answered and refused us; see [DeviceAuthRejectedException]) — those
 * would each be misdiagnosed as a network problem.
 *
 * @param cause the last endpoint's underlying transport failure.
 * @see se.soderbjorn.lunamux.client.viewmodel.ConnectFailureCopy.classify
 */
class ServerUnreachableException(
    cause: Throwable,
) : Exception("no candidate endpoint could be reached", cause)

class PinMismatchException(
    val observedFingerprintHex: String?,
    cause: Throwable?,
) : Exception("pin-mismatch: server certificate does not match the stored pin", cause)

/**
 * Stateless helper that walks candidate endpoints sequentially. Shared by the
 * Android and iOS `ConnectionHolder.connectMulti` paths, and the home of the
 * pin-mismatch cause-chain scan that used to live in the Android connection
 * holder.
 */
object CandidateConnector {

    /**
     * Default handshake budget per candidate. 12 s covers a TLS + WS handshake
     * on a sleepy Wi-Fi radio while keeping a multi-candidate walk under a
     * minute.
     *
     * Public because it is not only this file's business: the walk spends this
     * long on every address that doesn't answer, so the UI showing a progress
     * bar over an attempt has to agree with it or the bar lies. Callers that
     * display attempt progress should size it from this rather than restating
     * the number.
     *
     * @see connectFirstReachable
     */
    const val DEFAULT_PER_CANDIDATE_TIMEOUT_MS: Long = 12_000

    /**
     * Try [endpoints] in order and return the first whose WebSocket handshake
     * completes within [perCandidateTimeoutMs].
     *
     * Each attempt builds a fresh [LunamuxClient] (same [authToken],
     * [identity], [pinnedFingerprintHex], and [pairingToken] for every
     * endpoint — they all point at the same server) and awaits
     * [WindowSocket.awaitSessionReady]. A failed attempt is torn down and the
     * walk moves on; the caller still has to run the second handshake phase
     * ([WindowSocket.awaitInitialConfig]) on the returned socket.
     *
     * When every endpoint fails, a pin-mismatch failure is rethrown in
     * preference to a generic one: a stale address that now points at a
     * *stranger's* server (DHCP reuse, changed network) must not bury the
     * one signal the user has to act on.
     *
     * @param endpoints ordered addresses to try — typically a host entry's
     *   [HostEntry.addresses] verbatim. Duplicates are collapsed.
     * @param authToken the device-auth token (see
     *   [se.soderbjorn.lunamux.client.storage.LocalRepository.getOrCreateAuthToken]).
     * @param identity self-reported client metadata for every attempt.
     * @param pinnedFingerprintHex the entry's TLS pin, or `null` for TOFU
     *   capture (manually-added hosts only — QR entries always have a pin).
     * @param pairingToken one-time pairing token, or `null` outside pairing.
     * @param perCandidateTimeoutMs handshake budget per endpoint. 12 s
     *   covers a TLS + WS handshake on a sleepy Wi-Fi radio while keeping a
     *   multi-address walk under a minute.
     * @param onAttempt invoked on the calling coroutine with each endpoint
     *   just before it is tried, so the UI can name what it is waiting on.
     *   Without it a multi-address walk is a mute spinner for up to
     *   [perCandidateTimeoutMs] per dead address, which reads as a hang rather
     *   than as progress. Must not block: it runs inline in the walk.
     * @return the winning [CandidateConnection]; the caller owns its handles.
     * @throws IllegalArgumentException when [endpoints] is empty.
     * @throws PinMismatchException when an endpoint's cert failed the pin
     *   check on a platform that drops the marker (see the class doc).
     * @throws Throwable the pin-mismatch failure if any endpoint saw one,
     *   otherwise the last endpoint's failure. Whatever comes out of a
     *   mismatch satisfies [isPinMismatch] on every platform.
     * @see isPinMismatch
     * @see PinMismatchException
     */
    // Every failure path here ends in a `throw`, and this function is called
    // straight from Swift (`ConnectionHolder.connectMulti`). Kotlin/Native only
    // bridges the exception types named here into an NSError; an undeclared one
    // aborts the process instead of reaching the caller's `catch`. Without
    // `Exception::class` a plain unreachable endpoint — the normal outcome of a
    // stale VPN address — is fatal rather than an error message.
    @Throws(CancellationException::class, Exception::class)
    suspend fun connectFirstReachable(
        endpoints: List<HostPort>,
        authToken: String,
        identity: ClientIdentity,
        pinnedFingerprintHex: String? = null,
        pairingToken: String? = null,
        perCandidateTimeoutMs: Long = DEFAULT_PER_CANDIDATE_TIMEOUT_MS,
        onAttempt: (HostPort) -> Unit = {},
    ): CandidateConnection {
        val ordered = endpoints.distinct()
        require(ordered.isNotEmpty()) { "a host entry needs at least one address to connect to" }

        var pinMismatch: Throwable? = null
        var lastFailure: Throwable? = null
        for (endpoint in ordered) {
            onAttempt(endpoint)
            val client = LunamuxClient(
                serverUrl = ServerUrl(host = endpoint.host, port = endpoint.port),
                authToken = authToken,
                identity = identity,
                pinnedFingerprintHex = pinnedFingerprintHex,
                pairingToken = pairingToken,
            )
            val socket = client.openWindowSocket()
            try {
                withTimeout(perCandidateTimeoutMs) { socket.awaitSessionReady() }
                return CandidateConnection(client = client, windowSocket = socket, endpoint = endpoint)
            } catch (t: TimeoutCancellationException) {
                closeQuietly(socket, client)
                lastFailure = t
            } catch (c: kotlinx.coroutines.CancellationException) {
                // The *caller* was cancelled (not our per-candidate timeout,
                // which is caught above) — clean up and propagate.
                closeQuietly(socket, client)
                throw c
            } catch (t: Throwable) {
                closeQuietly(socket, client)
                // iOS Darwin swallows the rejection cause, so also honour the
                // uniform observedMismatch signal the client publishes. When
                // that side-channel is the only evidence, wrap: it lives on a
                // client we are about to drop, so an unwrapped rethrow would
                // strand the caller with an unclassifiable error.
                val observed = client.observedMismatch.value
                if (isPinMismatch(t)) {
                    pinMismatch = t
                } else if (observed != null) {
                    pinMismatch = PinMismatchException(observed, t)
                } else {
                    lastFailure = t
                }
            }
        }
        // A pin mismatch outranks reachability: the server answered, it just
        // presented the wrong cert, and the UI owes the user the cert-changed
        // dialog rather than "check your Wi-Fi".
        pinMismatch?.let { throw it }
        // Every endpoint failed for a non-pin reason — which *is* the
        // definition of "server unreachable", and this is where that becomes
        // known. Typing it here is what lets the UI layers dispatch on the
        // failure instead of each platform re-deriving it by wrapping at its
        // own phase-1 catch (which is how the two hosts screens ended up with
        // separate ServerUnreachable types and duplicate copy).
        lastFailure?.let { throw ServerUnreachableException(it) }
        throw IllegalStateException("no candidates attempted")
    }

    /**
     * Walk a throwable's cause chain looking for the `"pin-mismatch:"` marker
     * thrown by [createPinnedHttpClient] when the server's leaf cert no
     * longer matches the stored pin. UI layers use this to choose between a
     * generic error snackbar and the cert-changed re-pair dialog.
     *
     * @param t the throwable to inspect (typically a connect failure).
     * @return `true` if any link in the cause chain is a pin-mismatch.
     */
    fun isPinMismatch(t: Throwable): Boolean {
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth < 16) {
            val msg = c.message
            if (msg != null && msg.startsWith("pin-mismatch:")) return true
            c = c.cause
            depth++
        }
        return false
    }

    /** Best-effort teardown of a failed attempt; failures here are moot. */
    private suspend fun closeQuietly(socket: WindowSocket, client: LunamuxClient) {
        runCatching { socket.close() }
        runCatching { client.close() }
    }
}
