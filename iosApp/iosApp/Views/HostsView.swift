import SwiftUI

/// Host list screen — add, edit, delete saved servers and connect.
/// Mirrors the Android `HostsScreen` composable.
struct HostsView: View {
    @Bindable var viewModel: HostsViewModel
    var onConnected: () -> Void

    @State private var editTarget: HostEntryLocal?
    @State private var showAddSheet = false
    @State private var deleteTarget: HostEntryLocal?

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()

            if viewModel.hosts.isEmpty {
                emptyState
            } else {
                hostsList
            }
        }
        .navigationTitle("Hosts")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Palette.surface, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showAddSheet = true } label: {
                    Image(systemName: "plus")
                        .foregroundStyle(Palette.textPrimary)
                }
            }
        }
        .sheet(isPresented: $showAddSheet) {
            HostEditSheet(initial: nil) { label, host, port in
                viewModel.addHost(label: label, host: host, port: port)
                showAddSheet = false
            }
        }
        .sheet(item: $editTarget) { entry in
            HostEditSheet(initial: entry) { label, host, port in
                var updated = entry
                updated.label = label
                updated.host = host
                updated.port = port
                viewModel.updateHost(updated)
                editTarget = nil
            }
        }
        .alert("Delete host?", isPresented: .init(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("Cancel", role: .cancel) { deleteTarget = nil }
            Button("Delete", role: .destructive) {
                if let entry = deleteTarget {
                    viewModel.deleteHost(id: entry.id)
                    deleteTarget = nil
                }
            }
        } message: {
            if let entry = deleteTarget {
                Text("\"\(entry.label)\" will be removed from this device.")
            }
        }
        .alert("Connection failed", isPresented: .init(
            get: { viewModel.errorMessage != nil },
            set: { if !$0 { viewModel.errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { viewModel.errorMessage = nil }
        } message: {
            if let msg = viewModel.errorMessage {
                Text(msg)
            }
        }
        .alert("Server certificate changed", isPresented: .init(
            get: { viewModel.pinMismatchEntry != nil },
            set: { if !$0 { viewModel.pinMismatchEntry = nil } }
        )) {
            Button("Cancel", role: .cancel) { viewModel.pinMismatchEntry = nil }
            Button("Re-pair", role: .destructive) {
                if let entry = viewModel.pinMismatchEntry {
                    viewModel.acceptNewServerCertificate(entry: entry)
                }
            }
        } message: {
            if let entry = viewModel.pinMismatchEntry {
                Text("The server at \(entry.host):\(entry.port) is presenting a different TLS certificate than the one you paired with previously. This may mean the server was reinstalled, or that someone is intercepting your connection. Re-pair only if you trust this network.")
            }
        }
        .onChange(of: ConnectionHolder.shared.client != nil) { _, connected in
            if connected { onConnected() }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "server.rack")
                .font(.system(size: 40))
                .foregroundStyle(Palette.textSecondary.opacity(0.6))
            Text("No hosts yet")
                .foregroundStyle(Palette.textSecondary)
                .font(.subheadline)
            Text("Add a server to get started")
                .foregroundStyle(Palette.textSecondary.opacity(0.7))
                .font(.caption)
            Button { showAddSheet = true } label: {
                Text("Add host")
                    .font(.body)
                    .fontWeight(.medium)
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.headerAccent)
        }
    }

    private var hostsList: some View {
        List {
            ForEach(viewModel.hosts) { entry in
                HostRow(
                    entry: entry,
                    connecting: viewModel.connectingId == entry.id,
                    waitingForApproval: viewModel.waitingForApproval,
                    enabled: viewModel.connectingId == nil,
                    onConnect: { viewModel.connect(entry: entry) },
                    onEdit: { editTarget = entry },
                    onDelete: { deleteTarget = entry }
                )
                .listRowBackground(Palette.background)
                .listRowSeparatorTint(Palette.surface)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }
}

// MARK: - Host Row

private struct HostRow: View {
    let entry: HostEntryLocal
    let connecting: Bool
    let waitingForApproval: Bool
    let enabled: Bool
    let onConnect: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        Button(action: { if enabled { onConnect() } }) {
            HStack(spacing: 12) {
                TerminalGlyph()
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.label)
                        .font(.headline)
                        .foregroundStyle(Palette.textPrimary)
                        .lineLimit(1)
                    Text("\(entry.host):\(entry.port)")
                        .font(.subheadline)
                        .foregroundStyle(Palette.textSecondary)
                        .lineLimit(1)
                }
                Spacer()
                if connecting {
                    if waitingForApproval {
                        Text("Waiting for approval…")
                            .font(.caption)
                            .foregroundStyle(Palette.textSecondary)
                    }
                    ProgressView()
                        .scaleEffect(0.8)
                        .tint(Palette.textSecondary)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) { onDelete() } label: { Text("Delete") }
            Button { onEdit() } label: { Text("Edit") }
                .tint(.blue)
        }
    }
}

// MARK: - Terminal Glyph (matches Android's TerminalGlyph Canvas)

private struct TerminalGlyph: View {
    var body: some View {
        Canvas { context, size in
            let w = size.width
            let px = w / 16.0
            let stroke = StrokeStyle(lineWidth: 1.3 * px, lineCap: .round, lineJoin: .round)
            let tint = Palette.textSecondary.opacity(0.7)

            // Rounded rect
            let rect = CGRect(x: 1*px, y: 2*px, width: 14*px, height: 12*px)
            context.stroke(RoundedRectangle(cornerRadius: 1.5*px).path(in: rect), with: .color(tint), style: stroke)

            // Chevron
            var chevron = Path()
            chevron.move(to: CGPoint(x: 4*px, y: 7*px))
            chevron.addLine(to: CGPoint(x: 6*px, y: 5*px))
            chevron.addLine(to: CGPoint(x: 4*px, y: 3*px))
            context.stroke(chevron, with: .color(tint), style: stroke)

            // Cursor line
            var line = Path()
            line.move(to: CGPoint(x: 7*px, y: 7*px))
            line.addLine(to: CGPoint(x: 11*px, y: 7*px))
            context.stroke(line, with: .color(tint), style: StrokeStyle(lineWidth: 1.2*px, lineCap: .round))
        }
        .frame(width: 16, height: 16)
        .accessibilityLabel("Terminal")
    }
}

// MARK: - Host Edit Sheet

private struct HostEditSheet: View {
    let initial: HostEntryLocal?
    let onSave: (String, String, Int32) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var label: String
    @State private var host: String
    @State private var portText: String

    init(initial: HostEntryLocal?, onSave: @escaping (String, String, Int32) -> Void) {
        self.initial = initial
        self.onSave = onSave
        _label = State(initialValue: initial?.label ?? "")
        _host = State(initialValue: initial?.host ?? "")
        _portText = State(initialValue: initial.map { String($0.port) } ?? "")
    }

    private var canSave: Bool {
        !label.trimmingCharacters(in: .whitespaces).isEmpty
            && !host.trimmingCharacters(in: .whitespaces).isEmpty
            && Int32(portText) != nil
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Label", text: $label)
                TextField("Host", text: $host)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                TextField("Port", text: $portText)
                    .keyboardType(.numberPad)
            }
            .navigationTitle(initial == nil ? "Add host" : "Edit host")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if let port = Int32(portText), canSave {
                            onSave(label.trimmingCharacters(in: .whitespaces),
                                   host.trimmingCharacters(in: .whitespaces),
                                   port)
                        }
                    }
                    .disabled(!canSave)
                }
            }
        }
        .presentationDetents([.medium])
    }
}
