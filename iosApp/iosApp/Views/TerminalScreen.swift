import SwiftUI
import UIKit
import SwiftTerm
import Client

/// Full terminal emulator screen using SwiftTerm. Mirrors the Android
/// `TerminalScreen` which uses Termux's `TerminalView` + `TerminalEmulator`.
struct TerminalScreen: View {
    let sessionId: String
    var onBack: () -> Void

    @State private var headerTitle: String = ""
    @State private var paneState: String?
    @State private var coordinator: TerminalCoordinator?
    @State private var swipeInputActive: Bool = false
    @State private var swipeText: String = ""
    @State private var ctrlSticky: Bool = false
    @State private var shiftSticky: Bool = false

    var body: some View {
        VStack(spacing: 0) {
            // Terminal view fills available space
            if let coord = coordinator {
                TerminalViewRepresentable(coordinator: coord)
                    .ignoresSafeArea(.keyboard)

                if swipeInputActive {
                    SwipeInputBar(text: $swipeText) {
                        guard !swipeText.isEmpty else { return }
                        coord.sendBytes(Array((swipeText + "\r").utf8))
                        swipeText = ""
                    }
                }

                ImeHelperToolbar(
                    ctrlSticky: $ctrlSticky,
                    shiftSticky: $shiftSticky,
                    onSend: { bytes in
                        var modified = bytes
                        if ctrlSticky, bytes.count == 1, bytes[0] >= 0x61, bytes[0] <= 0x7a {
                            // Ctrl+letter: a=0x01, b=0x02, ..., z=0x1a
                            modified = [bytes[0] - 0x60]
                            ctrlSticky = false
                        } else if ctrlSticky {
                            ctrlSticky = false
                        }
                        if shiftSticky {
                            shiftSticky = false
                        }
                        coord.sendBytes(modified)
                    }
                )
            } else {
                Palette.background.ignoresSafeArea()
            }
        }
        .background(Palette.background)
        .navigationBarBackButtonHidden(false)
        .toolbar {
            ToolbarItem(placement: .principal) {
                HStack(spacing: 6) {
                    Text(headerTitle.uppercased())
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.headerAccent)
                        .tracking(0.6)
                        .lineLimit(1)
                    if let state = paneState {
                        PaneStateDot(state: state)
                    }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                HStack(spacing: 4) {
                    Button {
                        swipeInputActive.toggle()
                    } label: {
                        Image(systemName: swipeInputActive ? "keyboard.chevron.compact.down.fill" : "keyboard.chevron.compact.down")
                            .foregroundStyle(swipeInputActive ? Palette.headerAccent : .gray)
                    }
                    Button {
                        coordinator?.forceResize()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .foregroundStyle(Palette.headerAccent)
                    }
                }
            }
        }
        .toolbarBackground(.visible, for: .navigationBar)
        .onAppear {
            setupTerminal()
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear {
            coordinator?.teardown()
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    private func setupTerminal() {
        guard let client = ConnectionHolder.shared.client else {
            onBack()
            return
        }

        let ptySocket = client.openPtySocket(sessionId: sessionId)
        let coord = TerminalCoordinator(ptySocket: ptySocket, client: client, sessionId: sessionId)
        self.coordinator = coord
        self.headerTitle = sessionId

        // Observe window config for pane title
        coord.onTitleChanged = { title in
            DispatchQueue.main.async { self.headerTitle = title }
        }

        // Observe per-session state for the dot indicator
        coord.onStateChanged = { state in
            DispatchQueue.main.async { self.paneState = state }
        }
    }
}

// MARK: - Terminal Coordinator

/// Manages the SwiftTerm TerminalView lifecycle, PtySocket I/O, and
/// server-pushed resize handling. This is the iOS equivalent of the
/// Android TerminalScreen's emulator + PtySocket wiring.
final class TerminalCoordinator: NSObject, TerminalViewDelegate {
    let ptySocket: Client.PtySocket
    let client: Client.TermtasticClient
    let sessionId: String

    var terminalView: SwiftTerm.TerminalView?
    var onTitleChanged: ((String) -> Void)?
    var onStateChanged: ((String?) -> Void)?

    private let flowObserver = Client.FlowObserver()
    private var pendingOutput: [[UInt8]] = []
    private var applyingServerSize = false
    private var naturalCols: Int = 80
    private var naturalRows: Int = 24
    private var hasSentInitialSize = false
    private(set) var currentFontSize: CGFloat = 12
    private static let minFontSize: CGFloat = 6
    private static let maxFontSize: CGFloat = 32

    init(ptySocket: Client.PtySocket, client: Client.TermtasticClient, sessionId: String) {
        self.ptySocket = ptySocket
        self.client = client
        self.sessionId = sessionId
        super.init()
        subscribeFlows()
    }

    func configureView(_ view: SwiftTerm.TerminalView) {
        self.terminalView = view
        view.terminalDelegate = self
        view.inputAccessoryView = nil

        // Flush any PTY output that arrived before the view was created
        for chunk in pendingOutput {
            view.feed(byteArray: ArraySlice(chunk))
        }
        pendingOutput.removeAll()

        // Set a reasonable default font size (Android uses 32px ≈ ~12pt at 2x/3x)
        currentFontSize = 12
        view.font = UIFont.monospacedSystemFont(ofSize: currentFontSize, weight: .regular)

        // Apply theme
        Task {
            if let settings = try? await client.fetchUiSettings() {
                DispatchQueue.main.async {
                    self.applyTheme(settings)
                }
            }
        }
    }

    private func subscribeFlows() {
        // PTY output → terminal
        flowObserver.observe(flow: ptySocket.output) { [weak self] chunk in
            guard let self, let data = chunk as? KotlinByteArray else { return }
            let bytes = data.toSwiftData()
            DispatchQueue.main.async {
                if let tv = self.terminalView {
                    tv.feed(byteArray: ArraySlice(bytes))
                } else {
                    self.pendingOutput.append(bytes)
                }
            }
        }

        // Server-pushed PTY size
        flowObserver.observe(flow: ptySocket.ptySize) { [weak self] value in
            guard let self else { return }
            DispatchQueue.main.async {
                self.applyServerSize(value)
            }
        }

        // Window config → pane title
        flowObserver.observe(flow: client.windowState.config) { [weak self] value in
            guard let self, let config = value as? Client.WindowConfig else { return }
            if let title = Self.findLeafTitle(config: config, sessionId: self.sessionId) {
                self.onTitleChanged?(title)
            }
        }

        // Per-session state → dot indicator
        flowObserver.observe(flow: client.windowState.states) { [weak self] value in
            guard let self else { return }
            let state: String?
            if let dict = value as? NSDictionary {
                state = dict[self.sessionId] as? String
            } else if let dict = value as? [String: Any] {
                state = dict[self.sessionId] as? String
            } else {
                state = nil
            }
            DispatchQueue.main.async { self.onStateChanged?(state) }
        }
    }

    private func applyServerSize(_ value: Any?) {
        guard let pair = value as? KotlinPair<KotlinInt, KotlinInt> else { return }
        let cols = Int(truncating: pair.first!)
        let rows = Int(truncating: pair.second!)
        guard cols > 0, rows > 0 else { return }

        applyingServerSize = true
        terminalView?.getTerminal().resize(cols: cols, rows: rows)
        terminalView?.setNeedsDisplay()
        DispatchQueue.main.async { self.applyingServerSize = false }
    }

    /// Called from updateUIView to assert the terminal's actual size once the
    /// view has been laid out. This closes the race where the PTY socket is
    /// opened before SwiftUI has measured the TerminalView.
    func assertSizeIfNeeded(_ view: SwiftTerm.TerminalView) {
        guard !hasSentInitialSize else { return }
        let cols = view.getTerminal().cols
        let rows = view.getTerminal().rows
        guard cols > 0, rows > 0 else { return }
        hasSentInitialSize = true
        naturalCols = cols
        naturalRows = rows
        Task { try? await ptySocket.resize(cols: Int32(cols), rows: Int32(rows)) }
    }

    func forceResize() {
        guard naturalCols > 0, naturalRows > 0 else { return }
        Task { try? await ptySocket.forceResize(cols: Int32(naturalCols), rows: Int32(naturalRows)) }
    }

    @objc func handlePinch(_ gesture: UIPinchGestureRecognizer) {
        guard let view = terminalView else { return }
        if gesture.state == .changed {
            let scale = gesture.scale
            // Apply a deadzone to avoid jitter (matching Android's 5% threshold)
            if scale < 0.95 || scale > 1.05 {
                let newSize = (currentFontSize * scale).clamped(to: Self.minFontSize...Self.maxFontSize)
                let rounded = round(newSize * 2) / 2  // snap to 0.5pt increments
                if rounded != currentFontSize {
                    currentFontSize = rounded
                    view.font = UIFont.monospacedSystemFont(ofSize: currentFontSize, weight: .regular)
                }
                gesture.scale = 1.0
            }
        }
    }

    func sendBytes(_ bytes: [UInt8]) {
        Task { try? await ptySocket.send(bytes: KotlinByteArray.from(bytes)) }
    }

    func teardown() {
        flowObserver.clear()
        ptySocket.closeDetached()
    }

    private func applyTheme(_ settings: Client.UiSettings) {
        // Apply theme colors to the terminal if available
        let colors = settings.theme.effectiveColors(appearance: settings.appearance, systemIsDark: true)
        let fg = colors.first! as String
        let bg = colors.second! as String
        terminalView?.nativeForegroundColor = UIColor(hexString: fg)
        terminalView?.nativeBackgroundColor = UIColor(hexString: bg)
    }

    private static func findLeafTitle(config: Client.WindowConfig, sessionId: String) -> String? {
        func walk(_ node: Client.PaneNode?) -> String? {
            if let leaf = node as? Client.LeafNode {
                return leaf.sessionId == sessionId ? leaf.title : nil
            } else if let split = node as? Client.SplitNode {
                return walk(split.first) ?? walk(split.second)
            }
            return nil
        }
        for tab in config.tabs {
            if let title = walk(tab.root) { return title }
            for fp in tab.floating where fp.leaf.sessionId == sessionId { return fp.leaf.title }
            for po in tab.poppedOut where po.leaf.sessionId == sessionId { return po.leaf.title }
        }
        return nil
    }

    // MARK: - TerminalViewDelegate

    func send(source: SwiftTerm.TerminalView, data: ArraySlice<UInt8>) {
        let bytes = Array(data)
        Task { try? await ptySocket.send(bytes: KotlinByteArray.from(bytes)) }
    }

    func sizeChanged(source: SwiftTerm.TerminalView, newCols: Int, newRows: Int) {
        if !applyingServerSize {
            naturalCols = newCols
            naturalRows = newRows
            Task { try? await ptySocket.resize(cols: Int32(newCols), rows: Int32(newRows)) }
        }
    }

    func setTerminalTitle(source: SwiftTerm.TerminalView, title: String) {
        // Server-managed titles take priority; ignore xterm title sequences
    }

    func scrolled(source: SwiftTerm.TerminalView, position: Double) {}
    func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
    func requestOpenLink(source: SwiftTerm.TerminalView, link: String, params: [String: String]) {
        if let url = URL(string: link) { UIApplication.shared.open(url) }
    }
    func rangeChanged(source: SwiftTerm.TerminalView, startY: Int, endY: Int) {}
    func clipboardCopy(source: SwiftTerm.TerminalView, content: Data) {
        if let str = String(data: content, encoding: .utf8) {
            UIPasteboard.general.string = str
        }
    }
}

// MARK: - UIViewRepresentable wrapper

private struct TerminalViewRepresentable: UIViewRepresentable {
    let coordinator: TerminalCoordinator

    func makeUIView(context: Context) -> SwiftTerm.TerminalView {
        let tv = SwiftTerm.TerminalView(frame: .zero)
        tv.backgroundColor = UIColor(Palette.background)
        coordinator.configureView(tv)

        // Pinch-to-zoom for font size (matches Android behaviour)
        let pinch = UIPinchGestureRecognizer(target: coordinator, action: #selector(TerminalCoordinator.handlePinch(_:)))
        tv.addGestureRecognizer(pinch)

        return tv
    }

    func updateUIView(_ uiView: SwiftTerm.TerminalView, context: Context) {
        // Once the view has a real layout, assert the terminal size to the
        // server. SwiftUI may not trigger sizeChanged until well after the
        // socket is open, so we do it explicitly on each layout pass until
        // a valid size has been sent.
        coordinator.assertSizeIfNeeded(uiView)
    }
}

// MARK: - Pane State Dot

/// 8 pt dot matching the web `.pane-state-dot` CSS:
/// - "working" → blue (#5AC8FA) with a pulse animation
/// - "waiting" → red (#FF6961), static
private struct PaneStateDot: View {
    let state: String

    @State private var pulse = false

    private var color: SwiftUI.Color {
        switch state {
        case "working": return SwiftUI.Color(red: 0x5A/255, green: 0xC8/255, blue: 0xFA/255)
        case "waiting": return SwiftUI.Color(red: 0xFF/255, green: 0x69/255, blue: 0x61/255)
        default: return .clear
        }
    }

    private var shouldPulse: Bool {
        state == "working" || state == "waiting"
    }

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 8, height: 8)
            .opacity(shouldPulse ? (pulse ? 0.4 : 1.0) : 1.0)
            .animation(
                shouldPulse
                    ? .easeInOut(duration: 0.75).repeatForever(autoreverses: true)
                    : .default,
                value: pulse
            )
            .onAppear { if shouldPulse { pulse = true } }
            .onChange(of: state) { newState in
                pulse = (newState == "working" || newState == "waiting")
            }
            .accessibilityLabel("Status: \(state)")
    }
}

// MARK: - Swipe Input Bar

/// Visible text field for gesture-typing (iOS QuickPath / Android swipe).
/// Sits between the terminal and the IME toolbar when active. The standard
/// TextField allows the keyboard to offer swipe-to-write suggestions,
/// unlike SwiftTerm's raw key capture.
private struct SwipeInputBar: View {
    @Binding var text: String
    let onSubmit: () -> Void

    @FocusState private var isFocused: Bool

    var body: some View {
        HStack(spacing: 6) {
            TextField("Type or swipe here\u{2026}", text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(false)
                .font(.system(size: 14, design: .monospaced))
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(Color(red: 0x1C/255, green: 0x1C/255, blue: 0x1E/255), in: RoundedRectangle(cornerRadius: 6))
                .focused($isFocused)
                .onSubmit { onSubmit() }

            Button(action: onSubmit) {
                Text("\u{23CE}")
                    .font(.system(size: 18))
                    .frame(width: 36, height: 36)
                    .background(Color(red: 0xF4/255, green: 0xB8/255, blue: 0x69/255), in: RoundedRectangle(cornerRadius: 6))
                    .foregroundStyle(Color(red: 0x1C/255, green: 0x1C/255, blue: 0x1E/255))
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Color(red: 0x2C/255, green: 0x2C/255, blue: 0x2E/255))
        .onAppear { isFocused = true }
    }
}

// MARK: - IME Helper Toolbar

/// Above-keyboard toolbar for Esc/Ctrl/Shift/Tab/arrows, matching Android's
/// `ImeHelperToolbar` composable.
private struct ImeHelperToolbar: View {
    @Binding var ctrlSticky: Bool
    @Binding var shiftSticky: Bool
    let onSend: ([UInt8]) -> Void

    private let haptic = UIImpactFeedbackGenerator(style: .light)

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                StickyToolbarKey(label: "Ctrl", active: ctrlSticky) { ctrlSticky.toggle() }
                StickyToolbarKey(label: "Shift", active: shiftSticky) { shiftSticky.toggle() }
                ToolbarDivider()
                ToolbarKey(label: "Enter") { haptic.impactOccurred(); onSend([0x0d]) }
                ToolbarKey(label: "Esc") { haptic.impactOccurred(); onSend([0x1b]) }
                ToolbarKey(label: "Tab") { haptic.impactOccurred(); onSend([0x09]) }
                ToolbarKey(label: "\u{2191}", accessLabel: "Up arrow") { haptic.impactOccurred(); onSend(Array("\u{1b}[A".utf8)) }
                ToolbarKey(label: "\u{2193}", accessLabel: "Down arrow") { haptic.impactOccurred(); onSend(Array("\u{1b}[B".utf8)) }
                ToolbarKey(label: "\u{2192}", accessLabel: "Right arrow") { haptic.impactOccurred(); onSend(Array("\u{1b}[C".utf8)) }
                ToolbarKey(label: "\u{2190}", accessLabel: "Left arrow") { haptic.impactOccurred(); onSend(Array("\u{1b}[D".utf8)) }
                ToolbarKey(label: "Home") { haptic.impactOccurred(); onSend(Array("\u{1b}[H".utf8)) }
                ToolbarKey(label: "End") { haptic.impactOccurred(); onSend(Array("\u{1b}[F".utf8)) }
                ToolbarKey(label: "PgUp") { haptic.impactOccurred(); onSend(Array("\u{1b}[5~".utf8)) }
                ToolbarKey(label: "PgDn") { haptic.impactOccurred(); onSend(Array("\u{1b}[6~".utf8)) }
            }
            .padding(.horizontal, 4)
        }
        .frame(height: 44)
        .background(Color(red: 0x1E/255, green: 0x1E/255, blue: 0x1E/255))
    }
}

private struct ToolbarDivider: View {
    var body: some View {
        Rectangle()
            .fill(Color(red: 0x3A/255, green: 0x3A/255, blue: 0x3A/255))
            .frame(width: 1)
            .padding(.vertical, 10)
    }
}

private struct StickyToolbarKey: View {
    let label: String
    let active: Bool
    let action: () -> Void

    private let haptic = UIImpactFeedbackGenerator(style: .light)

    var body: some View {
        Button {
            haptic.impactOccurred()
            action()
        } label: {
            Text(label)
                .font(.footnote)
                .fontWeight(.semibold)
                .foregroundStyle(active ? .white : Color(white: 0.72))
                .padding(.horizontal, 14)
                .frame(maxHeight: .infinity)
                .background(
                    active ? Color(red: 0x0A/255, green: 0x84/255, blue: 0xFF/255) : Color(red: 0x1E/255, green: 0x1E/255, blue: 0x1E/255),
                    in: RoundedRectangle(cornerRadius: 6)
                )
                .overlay(
                    active ? nil : RoundedRectangle(cornerRadius: 6).stroke(Color(white: 0.35), lineWidth: 1)
                )
        }
        .padding(.vertical, 6)
        .accessibilityLabel(label)
        .accessibilityAddTraits(active ? .isSelected : [])
    }
}

private struct ToolbarKey: View {
    let label: String
    var accessLabel: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.footnote)
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .frame(maxHeight: .infinity)
                .background(Color(red: 0x2E/255, green: 0x2E/255, blue: 0x2E/255), in: RoundedRectangle(cornerRadius: 6))
        }
        .padding(.vertical, 6)
        .accessibilityLabel(accessLabel ?? label)
    }
}

// MARK: - Helpers

private extension UIColor {
    convenience init(hexString: String) {
        var hex = hexString.trimmingCharacters(in: .whitespacesAndNewlines)
        if hex.hasPrefix("#") { hex.removeFirst() }
        var rgb: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&rgb)
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255.0,
            green: CGFloat((rgb >> 8) & 0xFF) / 255.0,
            blue: CGFloat(rgb & 0xFF) / 255.0,
            alpha: 1.0
        )
    }
}

// MARK: - Comparable clamping

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

// MARK: - Kotlin/Swift bridge helpers

private extension KotlinByteArray {
    func toSwiftData() -> [UInt8] {
        var result = [UInt8](repeating: 0, count: Int(size))
        for i in 0..<Int(size) {
            result[i] = UInt8(bitPattern: get(index: Int32(i)))
        }
        return result
    }

    static func from(_ bytes: [UInt8]) -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(bytes.count))
        for (i, b) in bytes.enumerated() {
            array.set(index: Int32(i), value: Int8(bitPattern: b))
        }
        return array
    }
}
