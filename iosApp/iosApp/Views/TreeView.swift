import SwiftUI

/// Which pane kind to split — local mirror to avoid KMP enum in @State.
private enum SplitPaneKind: String {
    case terminal, fileBrowser, git
}

/// Displays the window layout tree — tabs and their panes with state dots.
/// Mirrors the Android `TreeScreen` composable.
struct TreeView: View {
    @Bindable var viewModel: TreeViewModel
    var onOpenTerminal: (String) -> Void
    var onOpenFileBrowser: (String) -> Void
    var onOpenGit: (String) -> Void
    var onDisconnect: () -> Void

    // Creation flow state
    @State private var showCreateSheet = false
    @State private var showTabNameAlert = false
    @State private var tabName = ""
    @State private var paneSplitKind: SplitPaneKind = .terminal
    @State private var showPanePicker = false

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()

            if viewModel.rows.isEmpty {
                Text("Connecting\u{2026}")
                    .foregroundStyle(Palette.textSecondary)
            } else {
                List(viewModel.rows) { row in
                    switch row {
                    case .tabHeader(_, let title, let aggState):
                        TabHeaderRow(title: title, aggregateState: aggState)
                            .listRowBackground(Palette.background)
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets(top: 0, leading: 8, bottom: 0, trailing: 8))

                    case .leaf(_, let sessionId, let title, let kind, let floating):
                        Button {
                            switch kind {
                            case .terminal: onOpenTerminal(sessionId)
                            case .fileBrowser: onOpenFileBrowser(row.paneId)
                            case .git: onOpenGit(row.paneId)
                            case .empty: break // undecided pane — no action
                            }
                        } label: {
                            LeafRow(
                                title: title,
                                kind: kind,
                                state: viewModel.states[sessionId],
                                floating: floating
                            )
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(Palette.background)
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 0, leading: 4, bottom: 0, trailing: 4))
                    }
                }
                .listStyle(.plain)
                .environment(\.defaultMinListRowHeight, 44)
                .scrollContentBackground(.hidden)
            }
        }
        .navigationTitle("Termtastic")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Palette.background, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    viewModel.disconnect()
                    onDisconnect()
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.backward")
                        Text("Hosts")
                    }
                    .foregroundStyle(Palette.headerAccent)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showCreateSheet = true } label: {
                    Image(systemName: "plus")
                        .foregroundStyle(Palette.textPrimary)
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .onAppear { viewModel.subscribe() }
        .confirmationDialog("Create new", isPresented: $showCreateSheet) {
            Button("Tab") {
                tabName = ""
                showTabNameAlert = true
            }
            Button("Terminal") {
                paneSplitKind = .terminal
                showPanePicker = true
            }
            Button("File Browser") {
                paneSplitKind = .fileBrowser
                showPanePicker = true
            }
            Button("Git") {
                paneSplitKind = .git
                showPanePicker = true
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("New tab", isPresented: $showTabNameAlert) {
            TextField("Tab name", text: $tabName)
            Button("Create") {
                guard !tabName.trimmingCharacters(in: .whitespaces).isEmpty else { return }
                viewModel.addTab(name: tabName)
            }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(isPresented: $showPanePicker) {
            PanePickerSheet(
                kindLabel: paneSplitKind == .terminal ? "Terminal" : paneSplitKind == .fileBrowser ? "File Browser" : "Git",
                targets: viewModel.splitTargets,
                onPick: { paneId in
                    showPanePicker = false
                    viewModel.addSiblingPane(anchorPaneId: paneId, kindWire: paneSplitKind.rawValue)
                },
                onCancel: { showPanePicker = false }
            )
        }
    }
}

// MARK: - Pane Picker Sheet

private struct PanePickerSheet: View {
    let kindLabel: String
    let targets: [(paneId: String, title: String)]
    let onPick: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            Group {
                if targets.isEmpty {
                    Text("No panes available to split.")
                        .foregroundStyle(Palette.textSecondary)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(targets, id: \.paneId) { target in
                        Button {
                            onPick(target.paneId)
                        } label: {
                            Text(target.title)
                                .foregroundStyle(Palette.textPrimary)
                        }
                    }
                }
            }
            .navigationTitle("Add \(kindLabel)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

// Helper to extract paneId from TreeRow
private extension TreeRow {
    var paneId: String {
        switch self {
        case .leaf(let paneId, _, _, _, _): return paneId
        default: return ""
        }
    }
}

// MARK: - Tab Header Row

private struct TabHeaderRow: View {
    let title: String
    let aggregateState: String?

    var body: some View {
        HStack {
            Text(title.uppercased())
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundStyle(Palette.textPrimary)
                .tracking(0.5)
            StateDot(state: aggregateState, size: 6)
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 6)
    }
}

// MARK: - Leaf Row

private struct LeafRow: View {
    let title: String
    let kind: LeafKind
    let state: String?
    let floating: Bool

    var body: some View {
        HStack(spacing: 6) {
            PaneIcon(kind: kind, floating: floating)
            StateDot(state: state, size: 6)
            Text(title)
                .font(.body)
                .foregroundStyle(Palette.textSecondary)
                .lineLimit(1)
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption2)
                .foregroundStyle(Palette.textSecondary.opacity(0.5))
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
        .accessibilityHint(
            kind == .terminal ? "Opens terminal session" :
            kind == .fileBrowser ? "Opens file browser" :
            kind == .git ? "Opens git changes" :
            "Undecided pane"
        )
    }
}

// MARK: - State Dot

private struct StateDot: View {
    let state: String?
    let size: CGFloat

    @State private var pulse = false

    var body: some View {
        if let color = dotColor {
            Circle()
                .fill(color)
                .frame(width: size, height: size)
                .opacity(pulse ? 0.4 : 1.0)
                .animation(
                    .easeInOut(duration: 0.75).repeatForever(autoreverses: true),
                    value: pulse
                )
                .onAppear { pulse = true }
                .accessibilityLabel("Status: \(state ?? "unknown")")
        }
    }

    private var dotColor: Color? {
        switch state {
        case "working": return Palette.dotWorking
        case "waiting": return Palette.dotWaiting
        default: return nil
        }
    }
}

// MARK: - Pane Icon (matches web SVGs: terminal, fileBrowser, empty, floating)

private struct PaneIcon: View {
    let kind: LeafKind
    let floating: Bool

    var body: some View {
        Canvas { context, size in
            let w = size.width
            let px = w / 16.0
            let alpha = floating ? 0.9 : 0.6
            let tint = Palette.textSecondary.opacity(alpha)
            let stroke = StrokeStyle(lineWidth: 1.3 * px, lineCap: .round, lineJoin: .round)

            switch kind {
            case .git:
                // Git branch icon
                let s = StrokeStyle(lineWidth: 1.3 * px, lineCap: .round, lineJoin: .round)
                // Main vertical line
                var trunk = Path()
                trunk.move(to: CGPoint(x: 8*px, y: 2*px))
                trunk.addLine(to: CGPoint(x: 8*px, y: 14*px))
                context.stroke(trunk, with: .color(tint), style: s)
                // Branch line
                var branch = Path()
                branch.move(to: CGPoint(x: 8*px, y: 6*px))
                branch.addLine(to: CGPoint(x: 12*px, y: 4*px))
                context.stroke(branch, with: .color(tint), style: s)
                // Dots
                let dotSize = 2.5 * px
                context.fill(Circle().path(in: CGRect(x: 8*px - dotSize, y: 2*px - dotSize, width: dotSize*2, height: dotSize*2)), with: .color(tint))
                context.fill(Circle().path(in: CGRect(x: 12*px - dotSize, y: 4*px - dotSize, width: dotSize*2, height: dotSize*2)), with: .color(tint))
                context.fill(Circle().path(in: CGRect(x: 8*px - dotSize, y: 14*px - dotSize, width: dotSize*2, height: dotSize*2)), with: .color(tint))

            case .fileBrowser:
                // Document with folded corner and two text lines
                var doc = Path()
                doc.move(to: CGPoint(x: 3*px, y: 1.75*px))
                doc.addLine(to: CGPoint(x: 9.5*px, y: 1.75*px))
                doc.addLine(to: CGPoint(x: 13*px, y: 5.25*px))
                doc.addLine(to: CGPoint(x: 13*px, y: 14.25*px))
                doc.addCurve(to: CGPoint(x: 12.5*px, y: 14.75*px),
                             control1: CGPoint(x: 13*px, y: 14.53*px),
                             control2: CGPoint(x: 12.78*px, y: 14.75*px))
                doc.addLine(to: CGPoint(x: 3*px, y: 14.75*px))
                doc.addCurve(to: CGPoint(x: 2.5*px, y: 14.25*px),
                             control1: CGPoint(x: 2.72*px, y: 14.75*px),
                             control2: CGPoint(x: 2.5*px, y: 14.53*px))
                doc.addLine(to: CGPoint(x: 2.5*px, y: 2.25*px))
                doc.addCurve(to: CGPoint(x: 3*px, y: 1.75*px),
                             control1: CGPoint(x: 2.5*px, y: 1.97*px),
                             control2: CGPoint(x: 2.72*px, y: 1.75*px))
                doc.closeSubpath()
                context.stroke(doc, with: .color(tint), style: stroke)
                // Fold
                var fold = Path()
                fold.move(to: CGPoint(x: 9.25*px, y: 1.75*px))
                fold.addLine(to: CGPoint(x: 9.25*px, y: 5.25*px))
                fold.addLine(to: CGPoint(x: 13*px, y: 5.25*px))
                context.stroke(fold, with: .color(tint), style: stroke)
                // Text lines
                var line1 = Path()
                line1.move(to: CGPoint(x: 5*px, y: 8.5*px))
                line1.addLine(to: CGPoint(x: 10.5*px, y: 8.5*px))
                context.stroke(line1, with: .color(tint),
                               style: StrokeStyle(lineWidth: 1.2*px, lineCap: .round))
                var line2 = Path()
                line2.move(to: CGPoint(x: 5*px, y: 11*px))
                line2.addLine(to: CGPoint(x: 10.5*px, y: 11*px))
                context.stroke(line2, with: .color(tint),
                               style: StrokeStyle(lineWidth: 1.2*px, lineCap: .round))

            case .empty:
                // Dashed rounded rect placeholder
                let rect = CGRect(x: 2*px, y: 2*px, width: 12*px, height: 12*px)
                context.stroke(RoundedRectangle(cornerRadius: 1.5*px).path(in: rect),
                               with: .color(tint),
                               style: StrokeStyle(lineWidth: 1.3*px, dash: [2.5*px, 2*px]))

            case .terminal:
                if floating {
                    let rect = CGRect(x: 2*px, y: 1*px, width: 12*px, height: 10*px)
                    context.stroke(RoundedRectangle(cornerRadius: 1.5*px).path(in: rect),
                                   with: .color(tint), style: stroke)
                    var line = Path()
                    line.move(to: CGPoint(x: 4*px, y: 8*px))
                    line.addLine(to: CGPoint(x: 10*px, y: 8*px))
                    context.stroke(line, with: .color(tint),
                                   style: StrokeStyle(lineWidth: 1.2*px, lineCap: .round))
                    let taskbar = CGRect(x: 4*px, y: 13*px, width: 8*px, height: 1.2*px)
                    context.fill(RoundedRectangle(cornerRadius: 0.6*px).path(in: taskbar),
                                 with: .color(tint.opacity(0.5)))
                } else {
                    let rect = CGRect(x: 1*px, y: 2*px, width: 14*px, height: 12*px)
                    context.stroke(RoundedRectangle(cornerRadius: 1.5*px).path(in: rect),
                                   with: .color(tint), style: stroke)
                    var chevron = Path()
                    chevron.move(to: CGPoint(x: 4*px, y: 7*px))
                    chevron.addLine(to: CGPoint(x: 6*px, y: 5*px))
                    chevron.addLine(to: CGPoint(x: 4*px, y: 3*px))
                    context.stroke(chevron, with: .color(tint), style: stroke)
                    var line = Path()
                    line.move(to: CGPoint(x: 7*px, y: 7*px))
                    line.addLine(to: CGPoint(x: 11*px, y: 7*px))
                    context.stroke(line, with: .color(tint),
                                   style: StrokeStyle(lineWidth: 1.2*px, lineCap: .round))
                }
            }
        }
        .frame(width: 16, height: 16)
        .accessibilityLabel(
            kind == .fileBrowser ? "File browser pane" :
            kind == .git ? "Git pane" :
            kind == .empty ? "Undecided pane" :
            floating ? "Floating pane" : "Pane"
        )
    }
}
