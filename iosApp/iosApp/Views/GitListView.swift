import SwiftUI
import Client

/// Lists changed files from a remote git pane, grouped by directory.
/// Mirrors the pattern of `MarkdownListView`.
struct GitListView: View {
    let paneId: String
    var onOpenFile: (String) -> Void
    var onBack: () -> Void

    @State private var entries: [Client.GitFileEntry]?
    @State private var errorMessage: String?

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()

            if let list = entries {
                if list.isEmpty {
                    Text(errorMessage ?? "No changed files")
                        .foregroundStyle(Palette.textSecondary)
                        .font(.subheadline)
                        .padding()
                } else {
                    fileList(list)
                }
            } else {
                ProgressView()
                    .tint(Palette.textSecondary)
            }
        }
        .navigationTitle("GIT")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Palette.surface, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .task { await loadEntries() }
    }

    private func fileList(_ list: [Client.GitFileEntry]) -> some View {
        let groups = groupByDirectory(list)
        return List {
            ForEach(groups, id: \.0) { directory, entries in
                Section {
                    ForEach(entries, id: \.filePath) { entry in
                        Button { onOpenFile(entry.filePath) } label: {
                            HStack(spacing: 8) {
                                GitStatusBadge(status: entry.status)
                                Text(basename(entry.filePath))
                                    .foregroundStyle(Palette.textSecondary)
                                    .font(.body)
                                    .lineLimit(1)
                                Spacer()
                            }
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(Palette.background)
                    }
                } header: {
                    Text(directory.isEmpty ? "(ROOT)" : directory.uppercased())
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.textPrimary)
                        .tracking(0.5)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private func loadEntries() async {
        guard let socket = ConnectionHolder.shared.windowSocket else {
            errorMessage = "Not connected"
            entries = []
            return
        }
        do {
            let list = try await socket.gitList(paneId: paneId, timeoutMs: 10_000)
            entries = list as? [Client.GitFileEntry] ?? []
        } catch {
            errorMessage = "Failed to load file list"
            entries = []
        }
    }

    private func groupByDirectory(_ list: [Client.GitFileEntry]) -> [(String, [Client.GitFileEntry])] {
        var order: [String] = []
        var map: [String: [Client.GitFileEntry]] = [:]
        for entry in list {
            let dir = entry.directory
            if map[dir] == nil { order.append(dir) }
            map[dir, default: []].append(entry)
        }
        return order.map { ($0, map[$0]!) }
    }

    private func basename(_ path: String) -> String {
        path.components(separatedBy: "/").last ?? path
    }
}

// MARK: - Git Status Badge

struct GitStatusBadge: View {
    let status: Client.GitFileStatus

    var body: some View {
        Text(statusLetter)
            .font(.system(size: 10, weight: .bold, design: .monospaced))
            .foregroundStyle(.white)
            .frame(width: 18, height: 18)
            .background(
                RoundedRectangle(cornerRadius: 4)
                    .fill(statusColor)
            )
    }

    private var statusLetter: String {
        switch status {
        case .modified:  return "M"
        case .added:     return "A"
        case .deleted:   return "D"
        case .renamed:   return "R"
        case .untracked: return "U"
        default:         return "?"
        }
    }

    private var statusColor: Color {
        switch status {
        case .modified:  return .yellow.opacity(0.85)
        case .added:     return .green.opacity(0.85)
        case .deleted:   return .red.opacity(0.85)
        case .renamed:   return .blue.opacity(0.85)
        case .untracked: return .gray.opacity(0.85)
        default:         return .gray.opacity(0.85)
        }
    }
}
