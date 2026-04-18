package se.soderbjorn.termtastic.android.net

import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import se.soderbjorn.termtastic.client.ClientIdentity
import se.soderbjorn.termtastic.client.ServerUrl
import se.soderbjorn.termtastic.client.TermtasticClient
import se.soderbjorn.termtastic.client.WindowSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Process-scoped holder for the live [TermtasticClient]. The app is tiny
 * enough that a plain singleton is cleaner than pulling in Hilt or Koin.
 * [TermtasticApp] rebuilds this whenever the user commits a new host/port
 * from the Connect screen.
 */
object ConnectionHolder {
    @Volatile
    private var currentClient: TermtasticClient? = null

    @Volatile
    private var currentWindowSocket: WindowSocket? = null

    /**
     * Returns the currently connected [TermtasticClient], or null if disconnected.
     *
     * @return the active client instance, or null.
     */
    fun client(): TermtasticClient? = currentClient

    /**
     * Returns the open [WindowSocket] for the current connection, or null if disconnected.
     *
     * @return the active window socket, or null.
     */
    fun windowSocket(): WindowSocket? = currentWindowSocket

    /**
     * Reflects whether the server is currently showing a device-approval
     * dialog for this connection. UI can observe this to show
     * "Waiting for approval…" instead of a bare spinner.
     */
    val pendingApproval: StateFlow<Boolean>?
        get() = currentClient?.windowState?.pendingApproval

    /**
     * Tears down any existing client and creates a fresh one for [serverUrl].
     *
     * Called from [se.soderbjorn.termtastic.android.ui.HostsScreen] when the
     * user taps a host to connect. Performs a two-phase handshake: first the
     * WebSocket session (15 s timeout), then waits for the server's initial
     * Config envelope (up to 5 min to allow for device-approval dialogs).
     *
     * @param serverUrl the server URL containing host and port.
     * @param authToken the authentication token for this client.
     * @return the connected [TermtasticClient] instance.
     * @throws Throwable if the connection or handshake fails.
     */
    suspend fun connect(serverUrl: ServerUrl, authToken: String): TermtasticClient {
        disconnect()
        Log.i("ConnectionHolder", "connect: ${serverUrl.wsUrl("/window")}")
        val fresh = TermtasticClient(
            serverUrl = serverUrl,
            authToken = authToken,
            // Android devices present as "Computer" in the settings UI so the
            // user can distinguish a native client from a browser tab. We do
            // a best-effort hostname + first non-loopback IPv4 lookup here;
            // both fields are advisory so a failure just means blanks.
            identity = ClientIdentity(
                type = "Computer",
                hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrNull(),
                selfReportedIp = runCatching { firstNonLoopbackIpv4() }.getOrNull(),
            ),
        )
        val socket = fresh.openWindowSocket()
        try {
            // Phase 1: WebSocket handshake — 15 s is plenty.
            withTimeout(15_000) {
                socket.awaitSessionReady()
            }
            // Phase 2: wait for the first Config envelope. When device
            // approval is pending, the server-side dialog can take minutes
            // to be answered, so use a generous timeout. The client
            // receives a PendingApproval envelope immediately (observable
            // via [pendingApproval]) so the UI can show feedback.
            withTimeout(300_000) {
                socket.awaitInitialConfig()
            }
        } catch (t: Throwable) {
            Log.w("ConnectionHolder", "connect failed", t)
            runCatching { socket.close() }
            runCatching { fresh.close() }
            throw t
        }
        currentClient = fresh
        currentWindowSocket = socket
        return fresh
    }

    /**
     * Scans all network interfaces for the first non-loopback, non-link-local
     * IPv4 address to use as the advisory self-reported IP in the client identity.
     *
     * @return the IP address string, or null if none found.
     */
    private fun firstNonLoopbackIpv4(): String? {
        val nics = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nic in nics) {
            if (!nic.isUp || nic.isLoopback) continue
            for (addr in nic.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }

    /**
     * Closes the current window socket and client, resetting both to null.
     * Safe to call even when already disconnected.
     */
    suspend fun disconnect() {
        currentWindowSocket?.close()
        currentWindowSocket = null
        currentClient?.close()
        currentClient = null
    }
}
