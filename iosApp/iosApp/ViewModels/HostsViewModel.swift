import Foundation
import Observation
import Client

/// Manages the hosts list and connection state. Wraps `HostsStore` for
/// persistence and `ConnectionHolder` for WebSocket lifecycle.
@Observable
final class HostsViewModel {
    var hosts: [HostEntryLocal] { HostsStore.shared.hosts }
    var connectingId: String?
    var errorMessage: String?
    var waitingForApproval: Bool { ConnectionHolder.shared.pendingApproval }

    /// True when the most recent connect attempt failed because the server
    /// presented a TLS certificate that doesn't match the stored pin. Views
    /// observe this to show a "Re-pair" alert instead of a generic error.
    var pinMismatchEntry: HostEntryLocal?

    func connect(entry: HostEntryLocal) {
        connectingId = entry.id
        errorMessage = nil
        pinMismatchEntry = nil
        Task {
            do {
                let token = Client.AuthTokenKt.getOrCreateToken(store: KeychainAuthTokenStore.shared)
                let serverUrl = Client.ServerUrl(
                    host: entry.host,
                    port: entry.port,
                    useTls: true,
                    pinnedFingerprintHex: entry.pinnedFingerprintHex
                )
                try await ConnectionHolder.shared.connect(serverUrl: serverUrl, authToken: token)
                // TOFU capture: if we connected without a pin, the trust
                // callback has now observed the leaf cert. Persist the
                // fingerprint back to the host entry so subsequent connects
                // run in verify mode.
                if entry.pinnedFingerprintHex == nil,
                   let captured = ConnectionHolder.shared.client?.observedFingerprint.value as? String {
                    var updated = entry
                    updated.pinnedFingerprintHex = captured
                    HostsStore.shared.update(updated)
                }
                // Fetch the user's theme settings so all views use the
                // selected theme from the start. Palette.settings is a
                // static var read by all colour accessors.
                if let client = ConnectionHolder.shared.client {
                    Palette.settings = try? await client.fetchUiSettings()
                }
                await MainActor.run {
                    connectingId = nil
                }
            } catch {
                await MainActor.run {
                    connectingId = nil
                    if Self.looksLikePinMismatch(error: error) {
                        pinMismatchEntry = entry
                    } else {
                        errorMessage = error.localizedDescription
                    }
                }
            }
        }
    }

    /// Clears the stored pin for [entry] so the next connect runs in capture
    /// mode and trusts whatever certificate the server presents next. Called
    /// from the "Re-pair" alert button.
    func acceptNewServerCertificate(entry: HostEntryLocal) {
        var cleared = entry
        cleared.pinnedFingerprintHex = nil
        HostsStore.shared.update(cleared)
        pinMismatchEntry = nil
    }

    /// Heuristic detector for the pin-mismatch case. The Darwin engine's
    /// challenge handler completes the TLS handshake by *cancelling* the
    /// auth challenge; URLSession surfaces that as `NSURLErrorDomain` code
    /// `-999` (`NSURLErrorCancelled`). For our purposes the only way that
    /// can happen is the pin check failing, so we treat that as the marker.
    private static func looksLikePinMismatch(error: Error) -> Bool {
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
            return true
        }
        // Underlying error chain — Ktor wraps cancels.
        if let underlying = nsError.userInfo[NSUnderlyingErrorKey] as? NSError,
           underlying.domain == NSURLErrorDomain && underlying.code == NSURLErrorCancelled {
            return true
        }
        return false
    }

    func addHost(label: String, host: String, port: Int32) {
        HostsStore.shared.add(label: label, host: host, port: port)
    }

    func updateHost(_ entry: HostEntryLocal) {
        HostsStore.shared.update(entry)
    }

    func deleteHost(id: String) {
        HostsStore.shared.delete(id: id)
    }
}
