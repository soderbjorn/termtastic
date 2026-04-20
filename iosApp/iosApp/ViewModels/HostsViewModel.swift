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

    func connect(entry: HostEntryLocal) {
        connectingId = entry.id
        errorMessage = nil
        Task {
            do {
                let token = Client.AuthTokenKt.getOrCreateToken(store: KeychainAuthTokenStore.shared)
                let serverUrl = Client.ServerUrl(host: entry.host, port: entry.port, useTls: false)
                try await ConnectionHolder.shared.connect(serverUrl: serverUrl, authToken: token)
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
                    errorMessage = error.localizedDescription
                }
            }
        }
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
