import Foundation
import Client

/// Process-scoped singleton holding the live `LunamuxClient` and
/// `WindowSocket`. Mirrors the Android `ConnectionHolder` singleton.
@Observable
final class ConnectionHolder {
    static let shared = ConnectionHolder()

    private(set) var client: Client.LunamuxClient?
    private(set) var windowSocket: Client.WindowSocket?

    /// Whether a connection is fully established: the client is live **and** the
    /// server's initial config has arrived.
    ///
    /// Deliberately distinct from `client != nil`. `connectMulti` publishes the
    /// client *before* it waits for the config, so the approval-pending flow is
    /// observable while the server-side dialog is up — which means `client`
    /// becomes non-nil during a connect that may still be rejected. Navigation
    /// must key on this flag instead: keying on `client != nil` pushed the
    /// sessions screen the moment the socket opened, and a phase-2 rejection
    /// then cleared the client without ever popping that screen, stranding the
    /// user on an empty session list behind the failure alert.
    ///
    /// Read by `HostsView` to decide when to navigate to the sessions screen.
    private(set) var isReady: Bool = false

    private init() {
        client = nil
        windowSocket = nil
    }

    /// Whether the server is showing an approval dialog for this connection.
    private(set) var pendingApproval: Bool = false

    /// Set to `true` by `connect(...)` when the most recent attempt failed
    /// specifically because the server's leaf cert no longer matches the
    /// stored pin (verify-mode mismatch in the shared
    /// `PinnedHttpClientFactory`). Callers branch on this in their `catch`
    /// to show a cert-changed dialog instead of a generic error — Darwin's
    /// `URLSessionDelegate` cancels the challenge with no marker NSError,
    /// so the bridged Swift error is otherwise indistinguishable from a
    /// wrong-port / unreachable-host failure.
    private(set) var lastPinMismatch: Bool = false

    private var approvalObserver: Client.FlowObserver?

    /// Tear down any existing client and create a fresh one for the given URL.
    ///
    /// Retained for the built-in demo path only (see `HostsViewModel.connectDemo`);
    /// real hosts go through `connectMulti`, which walks every candidate
    /// endpoint a paired entry carries. Mirrors Android, where `connect` was
    /// narrowed to the demo for the same reason.
    ///
    /// `pinnedFingerprintHex` selects between the TOFU capture mode (nil,
    /// first-connect to a new host — the leaf cert's SHA-256 is observed
    /// during the handshake and exposed via `client.observedFingerprint`)
    /// and verify mode (non-nil — `URLSessionDelegate` constant-time compares
    /// the leaf against the pin and cancels the challenge on mismatch).
    /// A mismatch additionally sets `lastPinMismatch = true` so the caller's
    /// `catch` can show the cert-changed UX instead of a generic error.
    @MainActor
    func connect(
        serverUrl: Client.ServerUrl,
        authToken: String,
        pinnedFingerprintHex: String? = nil
    ) async throws {
        disconnect()
        pendingApproval = false
        lastPinMismatch = false
        let identity = Self.identity(
            demo: Client.DemoModeKt.isDemoHost(host: serverUrl.host)
        )
        let fresh = Client.LunamuxClientKt.createLunamuxClient(
            serverUrl: serverUrl,
            authToken: authToken,
            identity: identity,
            pinnedFingerprintHex: pinnedFingerprintHex,
            // Pairing tokens only ever ride the `connectMulti` path; this
            // single-endpoint connect is the demo's, which has no server to
            // pair with.
            pairingToken: nil
        )
        let socket = fresh.openWindowSocket()
        // Watch for PendingApproval so we can update the UI while
        // waiting for the server-side dialog to be answered.
        let observer = Client.FlowObserver()
        approvalObserver = observer
        observer.observe(flow: fresh.windowState.pendingApproval) { [weak self] value in
            DispatchQueue.main.async {
                self?.pendingApproval = (value as? Bool) == true
            }
        }
        do {
            // Phase 1: WebSocket handshake — 15 s is plenty.
            try await withTimeout(seconds: 15) {
                try await socket.awaitSessionReady()
            }
            // Phase 2: wait for the first Config envelope.  When approval
            // is pending this can take minutes, so use a generous timeout.
            try await withTimeout(seconds: 300) {
                try await socket.awaitInitialConfig()
            }
            pendingApproval = false
        } catch {
            pendingApproval = false
            observer.clear()
            approvalObserver = nil
            // Sample the side-channel set by the shared
            // PinnedHttpClientFactory.ios before we tear the client down —
            // the StateFlow value stays readable after close(), but reading
            // it here keeps the lifetime obvious.
            lastPinMismatch = (fresh.observedMismatch.value as? String) != nil
            let nsError = error as NSError
            NSLog(
                "[ConnectionHolder] connect failed: domain=%@ code=%ld desc=%@ userInfo=%@",
                nsError.domain, nsError.code,
                nsError.localizedDescription, String(describing: nsError.userInfo)
            )
            try? await socket.close()
            fresh.close()
            throw error
        }
        observer.clear()
        approvalObserver = nil
        self.client = fresh
        self.windowSocket = socket
        // Both phases passed — safe to navigate.
        self.isReady = true
    }

    /// Try every candidate endpoint of a host entry in order and keep the
    /// first that connects. Used by the hosts screen for saved entries — a
    /// QR-paired entry carries every address the server advertised, and even
    /// manually-added ones benefit from the same code path.
    ///
    /// Phase 1 (per candidate, inside the shared `CandidateConnector`):
    /// WebSocket handshake with a 12 s budget. Phase 2 (winner only): wait for
    /// the initial Config envelope, up to 5 min so a server-side approval
    /// dialog can be answered. The client is published before phase 2 so
    /// `pendingApproval` is observable while waiting.
    ///
    /// Mirrors the Android `ConnectionHolder.connectMulti`, down to the
    /// phase-1/phase-2 error split: only an unreachable phase 1 is blamed on
    /// the network.
    ///
    /// - Parameters:
    ///   - addresses: ordered endpoints to try — typically a host entry's
    ///     `addresses` verbatim. See `HostEntryLocal.addresses`.
    ///   - authToken: the device-auth token.
    ///   - pinnedFingerprintHex: TLS pin (verify mode), or `nil` for TOFU
    ///     capture on first connect.
    ///   - pairingToken: one-time QR pairing token, or `nil` outside pairing.
    ///   - onAttempt: invoked with each endpoint just before it is tried, so
    ///     the UI can name what it is waiting on. Passes the endpoint rather
    ///     than a formatted string: the hosts screen matches it against the
    ///     walk to show "address 2 of 3", which a string would force it to
    ///     re-parse.
    /// - Returns: the winning `CandidateConnection`; the caller promotes its
    ///   endpoint to the head of the entry's address list.
    /// - Throws: the shared `ServerUnreachableException` when no address
    ///   answered, `DeviceAuthRejectedException` when the server refused the
    ///   device, or the underlying failure otherwise — all typed by shared code
    ///   and classified for the user by `ConnectFailureCopy`. Pin mismatches
    ///   propagate so the caller can check `lastPinMismatch`.
    @MainActor
    @discardableResult
    func connectMulti(
        addresses: [Client.HostPort],
        authToken: String,
        pinnedFingerprintHex: String? = nil,
        pairingToken: String? = nil,
        onAttempt: @escaping (Client.HostPort) -> Void = { _ in }
    ) async throws -> Client.CandidateConnection {
        disconnect()
        pendingApproval = false
        lastPinMismatch = false

        // Real hosts only — the demo never reaches `connectMulti`.
        let identity = Self.identity(demo: false)

        // Phase 1 — reach an address. A pin mismatch is re-surfaced through
        // `lastPinMismatch` so the UI can show the cert-changed dialog. Every
        // other failure is already typed by the shared connector — an
        // unreachable walk arrives as `ServerUnreachableException` — so it is
        // rethrown untouched rather than re-wrapped here; wrapping it in a
        // Swift type was what forced the hosts screen to re-derive, in Swift,
        // a fact shared code had already established.
        let connection: Client.CandidateConnection
        do {
            connection = try await Client.CandidateConnector.shared.connectFirstReachable(
                endpoints: addresses,
                authToken: authToken,
                identity: identity,
                pinnedFingerprintHex: pinnedFingerprintHex,
                pairingToken: pairingToken,
                perCandidateTimeoutMs: Client.CandidateConnector.shared
                    .DEFAULT_PER_CANDIDATE_TIMEOUT_MS,
                onAttempt: onAttempt
            )
        } catch {
            if Self.isPinMismatch(error) {
                lastPinMismatch = true
            }
            throw error
        }

        // Publish before the config wait so the approval-pending flow is
        // observable while the server-side dialog (if any) is up.
        let observer = Client.FlowObserver()
        approvalObserver = observer
        observer.observe(flow: connection.client.windowState.pendingApproval) { [weak self] value in
            DispatchQueue.main.async {
                self?.pendingApproval = (value as? Bool) == true
            }
        }
        self.client = connection.client
        self.windowSocket = connection.windowSocket

        do {
            try await withTimeout(seconds: 300) {
                try await connection.windowSocket.awaitInitialConfig()
            }
        } catch {
            NSLog("[ConnectionHolder] connectMulti failed awaiting config: %@",
                  error.localizedDescription)
            pendingApproval = false
            observer.clear()
            approvalObserver = nil
            disconnect()
            throw error
        }
        pendingApproval = false
        observer.clear()
        approvalObserver = nil
        // The config landed — this connection is real. Only now may the UI
        // leave the hosts screen.
        isReady = true
        return connection
    }

    /// Whether `error` is the shared connector's pin-mismatch signal.
    ///
    /// Kotlin exceptions crossing into Swift arrive as an `NSError` carrying
    /// the original throwable under `KotlinException`, so the cause-chain scan
    /// that Android runs directly is reachable here too — but only because
    /// `CandidateConnector` wraps the Darwin case in a `PinMismatchException`
    /// first. Darwin's `URLSessionDelegate` cancels the auth challenge with a
    /// generic error, so without that wrapper there would be nothing to match.
    ///
    /// - Parameter error: a failure thrown out of `connectFirstReachable`.
    /// - Returns: `true` when the server's leaf cert failed the pin check.
    private static func isPinMismatch(_ error: Error) -> Bool {
        let nsError = error as NSError
        if let thrown = nsError.userInfo["KotlinException"] as? Client.KotlinThrowable,
           Client.CandidateConnector.shared.isPinMismatch(t: thrown) {
            return true
        }
        // Fallback: the boxed throwable is the documented carrier, but the
        // marker also survives into the bridged description.
        return nsError.localizedDescription.contains("pin-mismatch:")
    }

    @MainActor
    func disconnect() {
        let ws = windowSocket
        isReady = false
        windowSocket = nil
        client?.close()
        client = nil
        Task { try? await ws?.close() }
    }

    /// Self-reported identity for this device.
    ///
    /// Reports type "iOS" so the settings UI can tell this apart from Android
    /// and browser tabs, and the running app version (CFBundleShortVersionString)
    /// so the server can gate newer pane kinds (agent consoles, 1.5+) to
    /// clients able to render them. Both host/IP fields are advisory.
    ///
    /// The host/IP lookups are skipped entirely in demo mode, and not merely
    /// as an optimisation: `ProcessInfo.hostName` resolves the device's own
    /// `.local` name over mDNS, which trips the iOS local-network permission
    /// alert. The demo runs against the in-process `DemoServer` and opens no
    /// socket at all, so prompting for LAN access there asks the user to grant
    /// a permission nothing will use — and it lands before they have any
    /// reason to trust the app. There is also no server on the far end to
    /// report an identity to. Real connects still resolve both: the prompt is
    /// then answered in a context where the app genuinely needs the LAN.
    ///
    /// - Parameter demo: whether this connect targets the built-in demo.
    /// - Returns: the identity to hand to the shared client.
    /// - SeeAlso: `HostsViewModel.connectDemo`
    private static func identity(demo: Bool) -> Client.ClientIdentity {
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
        return Client.ClientIdentity(
            type: "iOS",
            hostname: demo ? nil : ProcessInfo.processInfo.hostName,
            selfReportedIp: demo ? nil : Self.firstNonLoopbackIPv4(),
            version: appVersion
        )
    }

    /// Best-effort first non-loopback IPv4 address.
    private static func firstNonLoopbackIPv4() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(first) }

        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let ifa = cursor {
            let sa = ifa.pointee.ifa_addr
            if sa?.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: ifa.pointee.ifa_name)
                if name != "lo0" {
                    var addr = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(sa, socklen_t(sa!.pointee.sa_len),
                                   &addr, socklen_t(addr.count),
                                   nil, 0, NI_NUMERICHOST) == 0 {
                        return String(cString: addr)
                    }
                }
            }
            cursor = ifa.pointee.ifa_next
        }
        return nil
    }
}

/// Swift-friendly timeout wrapper for async operations.
private func withTimeout<T>(seconds: TimeInterval, operation: @escaping () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw NSError(domain: "ConnectionHolder", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "Connection timed out"])
        }
        let result = try await group.next()!
        group.cancelAll()
        return result
    }
}
