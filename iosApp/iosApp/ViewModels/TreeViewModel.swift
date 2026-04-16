import Foundation
import Observation
import Client

// MARK: - Tree row model

enum LeafKind {
    case terminal
    case fileBrowser
    case git
    case empty
}

enum TreeRow: Identifiable {
    case tabHeader(tabId: String, title: String, aggregateState: String?)
    case leaf(paneId: String, sessionId: String, title: String, kind: LeafKind, floating: Bool)

    var id: String {
        switch self {
        case .tabHeader(let tabId, _, _): return "tab-\(tabId)"
        case .leaf(let paneId, _, _, _, _): return "leaf-\(paneId)"
        }
    }
}

// MARK: - ViewModel

/// Observes the KMP `WindowSocket`'s config and states flows via
/// `FlowObserver` and flattens the window tree into a list of `TreeRow`s
/// for the SwiftUI `TreeView`.
@Observable
final class TreeViewModel {
    var rows: [TreeRow] = []
    var states: [String: String] = [:]

    private let flowObserver = Client.FlowObserver()
    private var latestConfig: Client.WindowConfig?

    func subscribe() {
        guard let client = ConnectionHolder.shared.client else { return }

        flowObserver.observe(flow: client.windowState.config) { [weak self] value in
            DispatchQueue.main.async {
                guard let self else { return }
                self.latestConfig = value as? Client.WindowConfig
                self.rebuild()
            }
        }

        flowObserver.observe(flow: client.windowState.states) { [weak self] value in
            DispatchQueue.main.async {
                guard let self else { return }
                if let map = value as? [String: String?] {
                    var clean: [String: String] = [:]
                    for (k, v) in map {
                        if let v { clean[k] = v }
                    }
                    self.states = clean
                }
                self.rebuild()
            }
        }
    }

    /// All leaf panes available as split targets.
    var splitTargets: [(paneId: String, title: String)] {
        guard let config = latestConfig else { return [] }
        var targets: [(String, String)] = []
        func walk(_ node: Client.PaneNode) {
            if let leaf = node as? Client.LeafNode {
                targets.append((leaf.id, leaf.title))
            } else if let split = node as? Client.SplitNode {
                walk(split.first)
                walk(split.second)
            }
        }
        for tab in config.tabs {
            if let root = tab.root { walk(root) }
            for fp in tab.floating { targets.append((fp.leaf.id, fp.leaf.title)) }
            for po in tab.poppedOut { targets.append((po.leaf.id, po.leaf.title)) }
        }
        return targets
    }

    func addTab(name: String) {
        guard let client = ConnectionHolder.shared.client,
              let socket = ConnectionHolder.shared.windowSocket else { return }
        Task {
            try? await Client.PaneActionsKt.addTab(
                client: client, socket: socket, name: name, timeoutMs: 5_000
            )
        }
    }

    func addSiblingPane(anchorPaneId: String, kindWire: String) {
        guard let socket = ConnectionHolder.shared.windowSocket else { return }
        Task {
            if kindWire == "fileBrowser" {
                try? await Client.PaneActionsKt.addSiblingFileBrowser(
                    socket: socket, anchorPaneId: anchorPaneId
                )
            } else if kindWire == "git" {
                try? await Client.PaneActionsKt.addSiblingGit(
                    socket: socket, anchorPaneId: anchorPaneId
                )
            } else {
                try? await Client.PaneActionsKt.addSiblingTerminal(
                    socket: socket, anchorPaneId: anchorPaneId
                )
            }
        }
    }

    func disconnect() {
        flowObserver.clear()
        Task { @MainActor in
            ConnectionHolder.shared.disconnect()
        }
    }

    deinit {
        flowObserver.clear()
    }

    // MARK: - Flatten logic (mirrors Android's flatten())

    private func rebuild() {
        guard let config = latestConfig else {
            rows = []
            return
        }
        var result: [TreeRow] = []
        for tab in config.tabs {
            var leaves: [(paneId: String, sessionId: String, title: String, kind: LeafKind, floating: Bool)] = []

            if let root = tab.root {
                collectLeaves(node: root, floating: false, out: &leaves)
            }
            for fp in tab.floating {
                addLeaf(leaf: fp.leaf, floating: true, out: &leaves)
            }
            for po in tab.poppedOut {
                addLeaf(leaf: po.leaf, floating: false, out: &leaves)
            }

            // Aggregate state: "waiting" wins over "working"
            var tabState: String? = nil
            for leaf in leaves {
                switch states[leaf.sessionId] {
                case "waiting":
                    tabState = "waiting"
                case "working":
                    if tabState != "waiting" { tabState = "working" }
                default:
                    break
                }
                if tabState == "waiting" { break }
            }

            result.append(.tabHeader(tabId: tab.id, title: tab.title, aggregateState: tabState))
            for leaf in leaves {
                result.append(.leaf(
                    paneId: leaf.paneId,
                    sessionId: leaf.sessionId,
                    title: leaf.title,
                    kind: leaf.kind,
                    floating: leaf.floating
                ))
            }
        }
        rows = result
    }

    private func collectLeaves(
        node: Client.PaneNode,
        floating: Bool,
        out: inout [(paneId: String, sessionId: String, title: String, kind: LeafKind, floating: Bool)]
    ) {
        if let leaf = node as? Client.LeafNode {
            addLeaf(leaf: leaf, floating: floating, out: &out)
        } else if let split = node as? Client.SplitNode {
            collectLeaves(node: split.first, floating: floating, out: &out)
            collectLeaves(node: split.second, floating: floating, out: &out)
        }
    }

    private func addLeaf(
        leaf: Client.LeafNode,
        floating: Bool,
        out: inout [(paneId: String, sessionId: String, title: String, kind: LeafKind, floating: Bool)]
    ) {
        let kind: LeafKind
        if leaf.content is Client.GitContent {
            kind = .git
        } else if leaf.content is Client.FileBrowserContent {
            kind = .fileBrowser
        } else if leaf.content is Client.TerminalContent || leaf.content == nil {
            kind = .terminal
        } else {
            kind = .empty
        }
        out.append((paneId: leaf.id, sessionId: leaf.sessionId, title: leaf.title, kind: kind, floating: floating))
    }
}
