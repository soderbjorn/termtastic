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

    fun client(): TermtasticClient? = currentClient

    fun windowSocket(): WindowSocket? = currentWindowSocket

    /**
     * Reflects whether the server is currently showing a device-approval
     * dialog for this connection. UI can observe this to show
     * "Waiting for approval…" instead of a bare spinner.
     */
    val pendingApproval: StateFlow<Boolean>?
        get() = currentClient?.windowState?.pendingApproval

    /**
     * Tear down any existing client and create a fresh one for [serverUrl].
     * Called from ConnectScreen's "Connect" button.
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

    suspend fun disconnect() {
        currentWindowSocket?.close()
        currentWindowSocket = null
        currentClient?.close()
        currentClient = null
    }
}
