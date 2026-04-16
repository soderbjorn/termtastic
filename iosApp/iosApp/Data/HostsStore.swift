import Foundation
import Observation

/// Codable mirror of the shared KMP `HostEntry` for JSON file persistence.
struct HostEntryLocal: Codable, Identifiable, Equatable {
    let id: String
    var label: String
    var host: String
    var port: Int32
}

/// File-based JSON persistence for the saved hosts list, following the
/// framnafolk `StorageManager` pattern. Reads/writes to the app's documents
/// directory so data survives app updates.
@Observable
final class HostsStore {
    static let shared = HostsStore()

    var hosts: [HostEntryLocal] = []

    private let fileURL: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        fileURL = docs.appendingPathComponent("hosts.json")
        load()
    }

    func add(label: String, host: String, port: Int32) {
        let entry = HostEntryLocal(
            id: UUID().uuidString,
            label: label,
            host: host,
            port: port
        )
        hosts.append(entry)
        save()
    }

    func update(_ entry: HostEntryLocal) {
        if let idx = hosts.firstIndex(where: { $0.id == entry.id }) {
            hosts[idx] = entry
            save()
        }
    }

    func delete(id: String) {
        hosts.removeAll { $0.id == id }
        save()
    }

    private func load() {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        do {
            let data = try Data(contentsOf: fileURL)
            hosts = try decoder.decode([HostEntryLocal].self, from: data)
        } catch {
            print("HostsStore: failed to load: \(error)")
        }
    }

    private func save() {
        do {
            let data = try encoder.encode(hosts)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            print("HostsStore: failed to save: \(error)")
        }
    }
}
