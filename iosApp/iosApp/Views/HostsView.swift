import SwiftUI
import Client

extension Client.HostPort {
    /// This endpoint the way a user would type it: the `:port` suffix appears
    /// only when the port is *not* the one every Lunamux server listens on.
    ///
    /// The single formatting rule for every address this app shows — list rows,
    /// the address picker, the connecting sheet, the cert-changed warning, and
    /// the host editor. `:8443` on every row distinguishes nothing, so hiding it
    /// turns a visible port into a signal ("this one is unusual") rather than
    /// decoration, and makes the same address read identically wherever it
    /// appears. Mirrors the Android `HostPort.display()`.
    var display: String {
        toCandidateString(defaultPort: KotlinInt(int: ConstantsKt.SERVER_TLS_PORT))
    }
}

/// Servers list screen — add, edit, delete saved servers and connect.
/// Mirrors the Android `HostsScreen` composable.
///
/// The screen is also the QR pairing entry point: the toolbar scanner (and the
/// `lunamux://pair` deep link relayed through `PendingPairingUri`) parses a
/// `PairingPayload`, saves or updates the host entry, and connects immediately
/// — scan → connected, with no approval dialog.
///
/// Unlike the workspace screens (tree, terminal, …), this screen renders
/// before any server connection exists, so no server-driven theme can apply
/// yet. It therefore uses native system colors and standard iOS list styling
/// instead of the `Palette` workspace theme.
struct HostsView: View {
    @Bindable var viewModel: HostsViewModel
    var onConnected: () -> Void
    var onOpenNews: () -> Void

    /// Roomier (iPad) vs compact (iPhone) sizing.
    @Environment(\.horizontalSizeClass) private var hSize

    /// Set when a long-press asks which address to use; only offered for
    /// entries that actually have a choice.
    @State private var addressPickerTarget: HostEntryLocal?
    @State private var editTarget: HostEntryLocal?
    @State private var showAddSheet = false
    @State private var showScanSheet = false
    @State private var deleteTarget: HostEntryLocal?

    /// Deep-linked pairing URIs parked by `LunamuxApp.onOpenURL`.
    private let pendingPairing = PendingPairingUri.shared

    var body: some View {
        baseView
            .modifier(HostsAlertsModifier(viewModel: viewModel, deleteTarget: $deleteTarget))
            // The in-flight connect owns the screen while it runs: a walk can
            // take the per-address budget several times over, so it needs room
            // to say what it is doing and a way to be called off.
            // `onDismiss` is where a failed connect surfaces: the alert cannot
            // be presented while this sheet is still on screen, so the view
            // model holds the failure back until the dismissal completes.
            .sheet(item: $viewModel.connectingEntry, onDismiss: {
                viewModel.presentPendingFailure()
            }) { entry in
                ConnectingSheet(
                    entry: entry,
                    walk: viewModel.connectingWalk,
                    current: viewModel.attemptingAddress,
                    waitingForApproval: viewModel.waitingForApproval,
                    onCancel: { viewModel.cancelConnect() }
                )
            }
            // Navigate on `isReady`, never on `client != nil`: the client is
            // published before the initial-config wait, so the latter is true
            // for a connect the server may still reject.
            .onChange(of: ConnectionHolder.shared.isReady) { _, ready in
                if ready { onConnected() }
            }
            // Pairing deep link (system camera / browser → lunamux://pair).
            // `onAppear` catches a cold-launch link posted before this screen
            // existed; `onChange` catches one that arrives while it is up.
            // `consume()` clears the slot either way, so a re-render cannot
            // pair twice.
            .onAppear { consumePendingPairing() }
            .onChange(of: pendingPairing.uri) { _, uri in
                if uri != nil { consumePendingPairing() }
            }
    }

    /// Drain the pairing mailbox into the view model, if anything is waiting.
    private func consumePendingPairing() {
        if let uri = pendingPairing.consume() {
            viewModel.handlePairingUri(uri)
        }
    }

    /// Base list/empty-state view plus toolbar and sheets. Split out so the
    /// alerts (which SwiftUI's type checker struggles to resolve when chained
    /// on one body) live in a separate `ViewModifier`.
    @ViewBuilder
    private var baseView: some View {
        Group {
            if viewModel.hosts.isEmpty {
                emptyState
            } else {
                hostsList
            }
        }
        // Keep the list/empty state in a readable column on iPad instead of
        // stretching edge-to-edge, left-aligned so it lines up under the large
        // "Servers" title rather than floating centred. A no-op on iPhone (< 700 pt).
        .frame(maxWidth: 700)
        .frame(maxWidth: .infinity, alignment: .leading)
        .safeAreaInset(edge: .bottom) {
            // Discreet, always-visible entry into the built-in demo, pinned to
            // the bottom of the screen below both the empty state and the host
            // list so it never competes with the user's own servers.
            DemoFooter(
                connecting: viewModel.connectingId == HostsViewModel.demoConnectingId,
                enabled: viewModel.connectingId == nil,
                onConnect: { viewModel.connectDemo() }
            )
        }
        .navigationTitle("Servers")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NewsBellButton(action: onOpenNews)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showScanSheet = true } label: {
                    Label("Scan QR Code", systemImage: "qrcode.viewfinder")
                }
                .tint(Palette.headerAccent)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showAddSheet = true } label: {
                    Label("Add Server", systemImage: "plus")
                }
                .tint(Palette.headerAccent)
            }
            // Shared info menu → support forum, website, legal pages. Mirrored
            // in the Sessions toolbar so both primary screens expose the same
            // links from the same place.
            ToolbarItem(placement: .topBarTrailing) {
                AboutMenu()
            }
        }
        .sheet(isPresented: $showAddSheet) {
            HostEditSheet(initial: nil) { label, addresses in
                viewModel.addHost(label: label, addresses: addresses)
                showAddSheet = false
            }
        }
        .sheet(isPresented: $showScanSheet) {
            // Dismiss first, then pair: the connect drives this screen's
            // spinner and, on success, the push to the tree — none of which
            // is visible under a presented sheet.
            QRScannerSheet { uri in
                showScanSheet = false
                viewModel.handlePairingUri(uri)
            }
        }
        .sheet(item: $addressPickerTarget) { entry in
            AddressPickerSheet(entry: entry) { address in
                addressPickerTarget = nil
                viewModel.connect(entry: entry, only: address)
            }
        }
        .sheet(item: $editTarget) { entry in
            HostEditSheet(initial: entry) { label, addresses in
                var updated = entry
                updated.label = label
                updated.addresses = addresses
                viewModel.updateHost(updated)
                editTarget = nil
            }
        }
    }

    /// Empty state. Pairing by QR is the primary path (scan → connected, no
    /// typing, no approval dialog); manual entry is demoted to a secondary
    /// action. Mirrors the Android `EmptyState` composable.
    private var emptyState: some View {
        ContentUnavailableView {
            Label("No servers yet", systemImage: "qrcode.viewfinder")
        } description: {
            Text("On your Mac, in Lunamux, go to \"Settings > Server & Security… > Devices\" "
                 + "and tick \"Allow connections from other devices\" and then press "
                 + "\"Pair via QR Code\" - and then scan the code here.")
        } actions: {
            Button { showScanSheet = true } label: {
                Text("Scan QR Code")
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.headerAccent)

            Button { showAddSheet = true } label: {
                Text("Add Manually")
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
        }
    }

    private var hostsList: some View {
        List {
            ForEach(viewModel.hosts) { entry in
                HostRow(
                    entry: entry,
                    enabled: viewModel.connectingId == nil,
                    onConnect: { viewModel.connect(entry: entry) },
                    onPickAddress: { addressPickerTarget = entry },
                    onEdit: { editTarget = entry },
                    onDelete: { deleteTarget = entry }
                )
            }
        }
        .listStyle(.insetGrouped)
    }
}

// MARK: - Demo Footer

/// Discreet, bottom-pinned entry into the built-in demo. Tapping it connects
/// to the shared client's in-process demo simulation — instant, offline, and
/// stateless, so it carries no edit/delete affordances.
///
/// Rendered as a single muted row beneath a hairline divider, staying out of the
/// way of the user's own servers. External links (support forum, website, legal
/// pages) now live in the top bar's `AboutMenu` rather than here, keeping this
/// footer to the one demo affordance. Mirrors the Android `DemoFooter`
/// composable.
private struct DemoFooter: View {
    let connecting: Bool
    let enabled: Bool
    let onConnect: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Divider()
            HStack {
                // Live demo — the footer's sole affordance.
                Button(action: { if enabled { onConnect() } }) {
                    HStack(spacing: 6) {
                        if connecting {
                            ProgressView()
                                .scaleEffect(0.7)
                        } else {
                            Image(systemName: "play.circle")
                                .font(.system(size: 13))
                        }
                        Text("Try the live demo")
                            .font(.footnote)
                    }
                    .foregroundStyle(.secondary)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!enabled)
                .accessibilityLabel("Try the live demo, no server needed")

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 6)
        }
        // Opaque background that matches the list base (black in dark mode)
        // rather than the lighter `.bar` material. `.bar` filled the bottom
        // safe area with a visibly lighter band, reading as a big chunk of
        // padding beneath the text; a matching colour makes that region blend
        // into the screen. (A *transparent* background instead caused SwiftUI
        // to ghost the footer up under the status bar, so an opaque fill is
        // required.)
        .background(Color(.systemGroupedBackground))
    }
}

// MARK: - Host Row

/// A single row in the hosts list: label, preferred address, and the
/// edit/delete affordances.
///
/// Deliberately carries no connect progress. A walk can spend the full
/// per-candidate budget on each dead address, and a row-sized spinner cannot
/// say which address it is on or how long is left — that is `ConnectingSheet`'s
/// job, and it covers this row anyway while it is up.
private struct HostRow: View {
    let entry: HostEntryLocal
    let enabled: Bool
    let onConnect: () -> Void
    /// Opens the address picker.
    ///
    /// Offered for every host, including one-address ones. It used to be
    /// hidden when there was "no choice to offer", which made sense while it
    /// lived behind a long-press — but in a visible menu an item that comes and
    /// goes by row reads as a bug, and the picker is also the only place the
    /// full address list can be seen before committing to a connect.
    let onPickAddress: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void
    @Environment(\.horizontalSizeClass) private var hSize

    var body: some View {
        // Two tap targets side by side rather than one row-wide button: the
        // menu has to sit outside the connect button or tapping it would also
        // fire a connect.
        HStack(spacing: hSize.scaled(12)) {
            Button(action: { if enabled { onConnect() } }) {
                HStack(spacing: hSize.scaled(12)) {
                    TerminalGlyph()
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.label)
                            .font(hSize.pick(.headline, .title3))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        // The preferred address — the one a tap tries first.
                        // Which address is being tried *during* a connect
                        // belongs to ConnectingSheet.
                        //
                        // verbatim: interpolating an Int32 through Text's
                        // LocalizedStringKey path runs it through a locale-aware
                        // number formatter, rendering ports like "8 443".
                        Text(verbatim: entry.primary?.display
                            ?? "No address")
                            .font(hSize.pick(.subheadline, .body))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!enabled)

            // Every row action lives here, in the open. This used to be a
            // long-press context menu, which hid the address picker behind a
            // gesture with nothing on screen to suggest it — and the picker had
            // no other route to it at all, unlike Edit/Delete which at least
            // had a swipe.
            //
            // Title-cased ("Move Up") where the Android menu is sentence-cased
            // ("Move up"). That is deliberate, not drift: the HIG specifies
            // title case for menu items and buttons, Material specifies
            // sentence case. Matching the two platforms to each other would put
            // one of them out of spec.
            Menu {
                Button { onPickAddress() } label: {
                    Label("Connect Using…", systemImage: "network")
                }
                Button { onEdit() } label: {
                    Label("Edit", systemImage: "pencil")
                }
                Button(role: .destructive) { onDelete() } label: {
                    Label("Delete", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
                    .font(.body)
                    .foregroundStyle(Palette.headerAccent)
                    // Padding, not just the glyph: an 18pt icon is well under
                    // the 44pt minimum tap target on its own.
                    .padding(.vertical, 8)
                    .padding(.leading, 8)
                    .contentShape(Rectangle())
            }
            .disabled(!enabled)
            .accessibilityLabel("\(entry.label) options")
        }
        // Kept alongside the menu: swipe is muscle memory for a list row, and
        // it costs nothing now that neither action depends on it.
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) { onDelete() } label: {
                Label("Delete", systemImage: "trash")
            }
            Button { onEdit() } label: {
                Label("Edit", systemImage: "pencil")
            }
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
            let tint = Color.secondary.opacity(0.7)

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

/// One editable address row.
///
/// Carries its own identity rather than leaning on its array index: rows move
/// and get deleted, and an index-keyed `ForEach` would hand SwiftUI a fresh
/// identity to a row that merely shifted — dropping keyboard focus and
/// animating the wrong row.
private struct AddressField: Identifiable {
    let id = UUID()
    var text: String
}

private struct HostEditSheet: View {
    let initial: HostEntryLocal?
    let onSave: (String, [Client.HostPort]) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var label: String
    /// The entry's addresses as editable text, in walk order.
    ///
    /// Held as text rather than parsed `HostPort`s because a half-typed row
    /// ("192.168.1." on the way to an IP) is not parseable and must not be
    /// dropped mid-edit; the parse happens once, on save.
    @State private var addresses: [AddressField]
    /// Drives the "Discard changes?" confirmation on an interactive dismiss.
    @State private var confirmDiscard = false
    @FocusState private var focusedField: FocusTarget?

    /// What the form looked like when it opened, so a swipe-down can tell
    /// "changed my mind" from "about to lose work". Compared as text, not
    /// parsed addresses: a half-typed row is a change worth protecting even
    /// though it would not save.
    private let initialLabel: String
    private let initialRows: [String]

    private enum FocusTarget: Hashable {
        case label
        case address(UUID)
    }

    /// Whether anything has been typed since the sheet opened.
    private var dirty: Bool {
        label != initialLabel || addresses.map(\.text) != initialRows
    }

    init(initial: HostEntryLocal?, onSave: @escaping (String, [Client.HostPort]) -> Void) {
        self.initial = initial
        self.onSave = onSave
        _label = State(initialValue: initial?.label ?? "")
        // Render each address the way it would be typed — the port suffix is
        // omitted when it is the one every server listens on, so the common
        // row reads as a bare host.
        let rows = (initial?.addresses ?? []).map {
            AddressField(text: $0.display)
        }
        // A new host opens with one blank row: this list is the only place an
        // address can be typed, so starting empty would present a form with
        // nothing to fill in.
        let opening = rows.isEmpty ? [AddressField(text: "")] : rows
        _addresses = State(initialValue: opening)
        initialLabel = initial?.label ?? ""
        initialRows = opening.map(\.text)
    }

    /// The port a bare `host` row means, and the one whose suffix is hidden.
    private var defaultPort: Int32 { ConstantsKt.SERVER_TLS_PORT }

    /// Parse one row's text into an endpoint.
    ///
    /// - Parameter text: the row's raw contents.
    /// - Returns: the endpoint, or nil when blank or malformed.
    private func parse(_ text: String) -> Client.HostPort? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        return Client.HostPort.companion.parseCandidate(entry: trimmed, defaultPort: defaultPort)
    }

    /// Whether a row is safe to show in the normal colour: blank rows are
    /// mid-edit rather than wrong, so only a non-empty unparseable one is
    /// flagged.
    private func isValid(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty || parse(trimmed) != nil
    }

    /// Every non-blank row parsed, in order, or nil when the form cannot be
    /// saved as-is.
    ///
    /// Blank rows are how a user clears one by hand, so they are skipped
    /// rather than rejected. A non-blank row that will not parse *is* rejected
    /// — silently dropping it would lose an address the user believes they
    /// saved.
    private var parsedAddresses: [Client.HostPort]? {
        let filled = addresses.filter {
            !$0.text.trimmingCharacters(in: .whitespaces).isEmpty
        }
        guard !filled.isEmpty else { return nil }
        let parsed = filled.compactMap { parse($0.text) }
        guard parsed.count == filled.count else { return nil }
        return parsed
    }

    private var canSave: Bool {
        !label.trimmingCharacters(in: .whitespaces).isEmpty && parsedAddresses != nil
    }

    /// Swap a row with its neighbour. Order is meaningful — it is the order the
    /// connect walk tries addresses — so this is the user's control over which
    /// network is attempted first.
    ///
    /// - Parameters:
    ///   - index: the row to move.
    ///   - offset: -1 for up, 1 for down.
    private func move(_ index: Int, by offset: Int) {
        let target = index + offset
        guard addresses.indices.contains(index), addresses.indices.contains(target) else { return }
        addresses.swapAt(index, target)
    }

    private func remove(_ index: Int) {
        guard addresses.indices.contains(index) else { return }
        addresses.remove(at: index)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    // Hint inside the row, right under the field it describes.
                    // As a section footer it rendered a full section-gap away —
                    // closer to the next header than to its own input, which
                    // read as belonging to the wrong thing.
                    VStack(alignment: .leading, spacing: 4) {
                        TextField("Name", text: $label)
                            .focused($focusedField, equals: .label)
                            .submitLabel(.next)
                            .onSubmit {
                                if let first = addresses.first { focusedField = .address(first.id) }
                            }
                            .foregroundStyle(Palette.textPrimary)
                        Text("A name for this server, shown in the server list.")
                            .font(.caption)
                            .foregroundStyle(Palette.textSecondary)
                    }
                }
                .listRowBackground(Palette.surface)
                Section {
                    ForEach(Array(addresses.enumerated()), id: \.element.id) { index, address in
                        HStack(spacing: 8) {
                            TextField("host or host:port", text: $addresses[index].text)
                                .focused($focusedField, equals: .address(address.id))
                                .keyboardType(.URL)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                                .foregroundStyle(isValid(address.text) ? Palette.textPrimary : Color.red)
                            Menu {
                                Button { move(index, by: -1) } label: {
                                    Label("Move Up", systemImage: "arrow.up")
                                }
                                .disabled(index == 0)
                                Button { move(index, by: 1) } label: {
                                    Label("Move Down", systemImage: "arrow.down")
                                }
                                .disabled(index == addresses.count - 1)
                                Button(role: .destructive) { remove(index) } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            } label: {
                                Image(systemName: "ellipsis.circle")
                                    .foregroundStyle(Palette.headerAccent)
                            }
                            .accessibilityLabel("Address \(index + 1) options")
                        }
                    }
                    Button {
                        let row = AddressField(text: "")
                        addresses.append(row)
                        focusedField = .address(row.id)
                    } label: {
                        Label("Add Address", systemImage: "plus.circle.fill")
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Addresses")
                        // Both rules the rows need, said where the list starts
                        // rather than in a footer the user has to scroll past
                        // the rows to find. textCase(nil) because Form headers
                        // uppercase their content, which would shout this line.
                        Text("Tried in order, top first. Port "
                             + "\(String(ConstantsKt.SERVER_TLS_PORT)) unless you type \"host:port\".")
                            .font(.caption)
                            .textCase(nil)
                    }
                    .foregroundStyle(Palette.textSecondary)
                }
                .listRowBackground(Palette.surface)
            }
            // The stock grouped-Form gap between Name and Addresses is sized
            // for sections that are each several rows deep; over a one-row
            // Name field it reads as a hole rather than a separation.
            .listSectionSpacing(.compact)
            // Theme the sheet to match the rest of the app: hide the system
            // grouped-list backdrop, paint the palette background behind it, and
            // tint the cursor / Cancel & Save actions with the theme accent.
            .scrollContentBackground(.hidden)
            .background(Palette.background)
            .tint(Palette.headerAccent)
            .navigationTitle(initial == nil ? "Add Server" : "Edit Server")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    // Asks when there is something to lose. Swipe-to-dismiss is
                    // refused outright while dirty (see below), which would
                    // otherwise leave Cancel as the one exit that still
                    // discarded silently.
                    Button("Cancel") {
                        if dirty { confirmDiscard = true } else { dismiss() }
                    }
                    .tint(Palette.headerAccent)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if let parsed = parsedAddresses, canSave {
                            onSave(label.trimmingCharacters(in: .whitespaces), parsed)
                        }
                    }
                    .disabled(!canSave)
                    .tint(Palette.headerAccent)
                }
            }
            .onAppear {
                if initial == nil { focusedField = .label }
            }
        }
        // On iPad a `.medium` detent renders as a small floating card; present
        // the full form sheet there instead. iPhone keeps the half-sheet.
        .presentationDetents(hSize.pick([.medium, .large], [.large]))
        // Swiping the sheet away used to discard silently. Refused once the
        // form has been touched; an untouched sheet still swipes away freely,
        // since there is nothing to lose and a prompt there would only train
        // the user to dismiss prompts.
        //
        // Refused rather than prompted because SwiftUI exposes no hook for an
        // interactive-dismiss *attempt* — this modifier can only veto it. The
        // ask therefore lives on Cancel, which is where a blocked swipe sends
        // the user next. (Android's ModalBottomSheet does give an
        // onDismissRequest callback, so there the outside-tap prompts directly.)
        .interactiveDismissDisabled(dirty)
        // An alert, not a confirmationDialog: the latter renders as a popover
        // here, and a popover deliberately hides its `.cancel` button because
        // tapping outside is the cancel — leaving "Discard" as the only thing
        // on screen, which reads as the only choice. An alert always shows both.
        .alert("Discard changes?", isPresented: $confirmDiscard) {
            Button("Keep Editing", role: .cancel) { }
            Button("Discard", role: .destructive) { dismiss() }
        } message: {
            Text("Your edits to this server won't be saved.")
        }
    }
}

/// Long-press escape hatch: connect using one specific address rather than
/// walking them.
///
/// Tapping a host is the normal path and asks nothing — it tries the preferred
/// address, falls back through the rest, and promotes whatever answered. That
/// walk costs the per-candidate budget for every dead address, though, and the
/// phone cannot know which network it is on. When the user does know, this
/// skips straight to the right one. Deliberately not the default: it is an
/// override for the case the automatic path handles slowly, not a question
/// worth asking on every connect.
///
/// Only presented for entries with more than one address, so it never offers a
/// choice of one. Mirrors the Android `AddressPickerDialog`.
private struct AddressPickerSheet: View {
    let entry: HostEntryLocal
    /// Invoked with the chosen endpoint.
    let onPick: (Client.HostPort) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Array(entry.addresses.enumerated()), id: \.offset) { index, address in
                        Button { onPick(address) } label: {
                            // Inline rather than a sentence underneath: the head
                            // of the list only needs marking, not explaining,
                            // and a two-line gloss on one row made the list read
                            // as though the rows were unevenly grouped.
                            HStack(spacing: 6) {
                                Text(verbatim: address.display)
                                    .foregroundStyle(Palette.textPrimary)
                                if index == 0 {
                                    Text("(last used)")
                                        .font(.caption)
                                        .foregroundStyle(Palette.textSecondary)
                                }
                            }
                        }
                    }
                    .listRowBackground(Palette.surface)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Palette.background)
            .tint(Palette.headerAccent)
            .navigationTitle("Connect Using")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .tint(Palette.headerAccent)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

/// Modal progress sheet for an in-flight connect.
///
/// A connect is not a moment: the walk tries each address in turn and spends up
/// to `CandidateConnector.DEFAULT_PER_CANDIDATE_TIMEOUT_MS` on every one that
/// does not answer, so a host with three stale addresses is over half a minute
/// of waiting. A row-sized spinner cannot carry that — it cannot say which
/// address is being tried, how many are left, or how long this one has before
/// the walk moves on. This sheet says all three, and offers a way out that
/// isn't force-quitting the app.
///
/// Dismissed by the view model the moment the connect resolves: on success the
/// screen pushes to the tree, on failure an alert takes over. Mirrors the
/// Android `ConnectingDialog`.
private struct ConnectingSheet: View {
    let entry: HostEntryLocal
    /// The addresses being tried, in order — the basis for "address 2 of 3". A
    /// one-element walk (the picker's path) drops the counter, since there is
    /// no sequence to place it in.
    let walk: [Client.HostPort]
    /// The address being tried right now, or nil before the first attempt.
    let current: Client.HostPort?
    /// True once a server has been reached and is showing its device-approval
    /// dialog. Phase 1 is over by then, so the countdown is meaningless and the
    /// bar goes indeterminate.
    let waitingForApproval: Bool
    let onCancel: () -> Void

    /// When the current attempt began — the basis for the bar, reset whenever
    /// the walk moves on.
    @State private var attemptStart = Date()

    /// Seconds each attempt is allowed, read from the shared connector so the
    /// bar cannot drift from the real deadline.
    private var budget: Double {
        Double(Client.CandidateConnector.shared.DEFAULT_PER_CANDIDATE_TIMEOUT_MS) / 1000
    }

    private var index: Int? { current.flatMap { walk.firstIndex(of: $0) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Connecting to \(entry.label)")
                .font(.headline)
                .foregroundStyle(Palette.textPrimary)

            if waitingForApproval {
                Text("Waiting for approval…")
                    .foregroundStyle(Palette.textPrimary)
                ProgressView().progressViewStyle(.linear)
                Text("Approve this device in Lunamux on your Mac to finish connecting.")
                    .font(.caption)
                    .foregroundStyle(Palette.textSecondary)
            } else if let current {
                Text(verbatim: "Trying \(current.display)")
                    .foregroundStyle(Palette.textPrimary)
                    .lineLimit(1)
                // The value is recomputed from elapsed time on every tick, not
                // animated towards a target. `withAnimation` does not
                // interpolate a ProgressView's value — it snapped straight to
                // full, so the bar arrived complete the instant an attempt
                // started. Ticking once a second also makes the bar read as
                // seconds running out rather than generic "busy" motion.
                TimelineView(.periodic(from: attemptStart, by: 1)) { context in
                    ProgressView(value: elapsedFraction(at: context.date))
                        .progressViewStyle(.linear)
                }
                if walk.count > 1, let index {
                    Text("Address \(index + 1) of \(walk.count)")
                        .font(.caption)
                        .foregroundStyle(Palette.textSecondary)
                }
            } else {
                Text("Starting…")
                    .foregroundStyle(Palette.textPrimary)
                ProgressView().progressViewStyle(.linear)
            }

            HStack {
                Spacer()
                Button("Cancel", action: onCancel)
                    .tint(Palette.headerAccent)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Palette.background)
        // Tints the progress bars: a bare ProgressView takes the system accent,
        // which has nothing to do with the resolved server theme the rest of
        // this screen is painted in.
        .tint(Palette.headerAccent)
        .onAppear { restartAttemptClock() }
        .onChange(of: current) { restartAttemptClock() }
        .presentationDetents([.height(200)])
        // Only Cancel ends this: swiping the sheet away would hide the connect
        // without stopping it, leaving the list inert until the walk timed out.
        .interactiveDismissDisabled()
    }

    /// Restart the clock the bar is measured against, for a newly-started
    /// attempt.
    private func restartAttemptClock() {
        guard current != nil else { return }
        attemptStart = Date()
    }

    /// How much of this attempt's budget has been spent, quantised to whole
    /// seconds so the bar advances in visible steps rather than creeping.
    ///
    /// - Parameter now: the tick being rendered.
    /// - Returns: 0…1, clamped — an attempt that overruns its budget parks the
    ///   bar at full rather than overflowing it.
    private func elapsedFraction(at now: Date) -> Double {
        let ticks = max(0, now.timeIntervalSince(attemptStart).rounded(.down))
        return min(1, ticks / budget)
    }
}

// MARK: - Alerts

/// All four alert modifiers (delete confirmation, generic connection error,
/// cert-changed re-pair prompt, re-pair result) extracted out of
/// `HostsView.body`. SwiftUI's type checker times out when this many `.alert`
/// modifiers chain off the same body, so they live here as a single
/// `ViewModifier`.
private struct HostsAlertsModifier: ViewModifier {
    @Bindable var viewModel: HostsViewModel
    @Binding var deleteTarget: HostEntryLocal?

    func body(content: Content) -> some View {
        content
            .alert("Delete server?", isPresented: .init(
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
            // Title comes from the failure, not the modifier: a server that
            // refused us is titled "Connection refused", not "Connection
            // failed", so the first line already says retrying won't help.
            .alert(viewModel.errorAlert?.title ?? HostsViewModel.failedTitle, isPresented: .init(
                get: { viewModel.errorAlert != nil },
                set: { if !$0 { viewModel.errorAlert = nil } }
            )) {
                Button("OK", role: .cancel) { viewModel.errorAlert = nil }
            } message: {
                if let alert = viewModel.errorAlert {
                    Text(alert.message)
                }
            }
            .alert("Server certificate changed", isPresented: .init(
                get: { viewModel.pinMismatchEntry != nil },
                set: { if !$0 { viewModel.pinMismatchEntry = nil } }
            )) {
                // Mirrors Android's PinMismatchDialog (HostsScreen.kt:511).
                Button("Re-pair") {
                    if let e = viewModel.pinMismatchEntry { viewModel.repairPin(e) }
                }
                Button("Forget", role: .destructive) {
                    if let e = viewModel.pinMismatchEntry { viewModel.forgetHost(e) }
                }
                Button("Cancel", role: .cancel) { viewModel.pinMismatchEntry = nil }
            } message: {
                if let e = viewModel.pinMismatchEntry {
                    Text(pinMismatchMessage(for: e))
                }
            }
            // Mirrors Android's RePairDialog. Offers the connect that the scan
            // implied, so skipping the auto-connect costs the user nothing.
            .alert(
                viewModel.rePairResult.map { "\($0.entry.label) updated" } ?? "Updated",
                isPresented: .init(
                    get: { viewModel.rePairResult != nil },
                    set: { if !$0 { viewModel.rePairResult = nil } }
                )
            ) {
                Button("Connect") {
                    if let r = viewModel.rePairResult {
                        viewModel.rePairResult = nil
                        viewModel.connect(entry: r.entry)
                    }
                }
                Button("Done", role: .cancel) { viewModel.rePairResult = nil }
            } message: {
                if let r = viewModel.rePairResult {
                    Text(rePairMessage(for: r))
                }
            }
    }

    /// Explains what a re-pair scan actually changed — see `RePairResult` for
    /// why saying so matters.
    ///
    /// - Parameter result: the merged entry and how many addresses it gained.
    /// - Returns: the alert body text.
    private func rePairMessage(for result: RePairResult) -> String {
        let label = result.entry.label
        switch result.added {
        case 0:
            return "This code carried no addresses that \"\(label)\" didn't already know, "
                + "so nothing changed."
        case 1:
            return "1 new address was added. \"\(label)\" can now be reached on this network "
                + "as well as the ones it already knew."
        default:
            return "\(result.added) new addresses were added. \"\(label)\" can now be reached "
                + "on this network as well as the ones it already knew."
        }
    }

    private func pinMismatchMessage(for entry: HostEntryLocal) -> String {
        let at = entry.primary?.display ?? entry.label
        return "The server at \"\(entry.label)\" (\(at)) is presenting a different certificate than the one you paired with. "
            + "This could mean the server was reinstalled, or someone is trying to intercept your connection.\n\n"
            + "Re-pair if you trust the new certificate; Forget to remove the server."
    }
}
