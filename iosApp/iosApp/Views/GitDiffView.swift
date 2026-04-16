import SwiftUI
import WebKit
import Client

/// Renders a git diff for a single file in a WKWebView with syntax-highlighted
/// additions and deletions. Mirrors the pattern of `MarkdownContentView`.
struct GitDiffView: View {
    let paneId: String
    let filePath: String
    var onBack: () -> Void

    @State private var diffHtml: String?
    @State private var errorMessage: String?

    private var fileName: String {
        filePath.components(separatedBy: "/").last ?? filePath
    }

    var body: some View {
        ZStack {
            if let html = diffHtml {
                DiffWebView(html: html)
            } else if let error = errorMessage {
                Text(error)
                    .foregroundStyle(.gray)
                    .padding()
            } else {
                ProgressView()
                    .tint(Palette.textSecondary)
            }
        }
        .navigationTitle(fileName)
        .navigationBarTitleDisplayMode(.inline)
        .task { await loadDiff() }
    }

    private func loadDiff() async {
        guard let socket = ConnectionHolder.shared.windowSocket else {
            errorMessage = "Not connected"
            return
        }
        do {
            let reply = try await socket.gitDiff(paneId: paneId, filePath: filePath, timeoutMs: 10_000)
            if let result = reply as? Client.WindowEnvelope.GitDiffResult {
                diffHtml = buildDiffHtml(result)
            } else if let error = reply as? Client.WindowEnvelope.GitError {
                errorMessage = error.message
            } else {
                errorMessage = "No response"
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - WKWebView wrapper

private struct DiffWebView: UIViewRepresentable {
    let html: String

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.isOpaque = false
        webView.backgroundColor = UIColor(Palette.background)
        webView.scrollView.backgroundColor = UIColor(Palette.background)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        webView.loadHTMLString(html, baseURL: nil)
    }
}

// MARK: - Diff HTML builder

private func buildDiffHtml(_ result: Client.WindowEnvelope.GitDiffResult) -> String {
    var body = ""
    for hunk in result.hunks {
        for line in hunk.lines {
            let oldNo = line.oldLineNo.map { String(describing: $0) } ?? ""
            let newNo = line.newLineNo.map { String(describing: $0) } ?? ""
            let cls: String
            let prefix: String
            switch line.type {
            case .addition:
                cls = "add"
                prefix = "+"
            case .deletion:
                cls = "del"
                prefix = "-"
            default:
                cls = "ctx"
                prefix = " "
            }
            body += "<div class=\"line \(cls)\">"
            body += "<span class=\"ln\">\(oldNo)</span>"
            body += "<span class=\"ln\">\(newNo)</span>"
            body += "<span class=\"prefix\">\(prefix)</span>"
            body += "<span class=\"code\">\(line.content)</span>"
            body += "</div>\n"
        }
    }

    return """
    <!DOCTYPE html>
    <html>
    <head>
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <style>
      :root {
        color-scheme: light dark;
        --background: #1C1C1E;
        --surface: #2C2C2E;
        --text-primary: #F5F5F5;
        --text-secondary: #8E8E93;
        --add-bg: rgba(40, 167, 69, 0.18);
        --del-bg: rgba(220, 53, 69, 0.18);
        --ln-color: #555;
        --separator: rgba(255, 255, 255, 0.1);
      }
      @media (prefers-color-scheme: light) {
        :root {
          --background: #F5F5F7;
          --surface: #FFFFFF;
          --text-primary: #1C1C1E;
          --text-secondary: #6E6E73;
          --add-bg: rgba(40, 167, 69, 0.14);
          --del-bg: rgba(220, 53, 69, 0.14);
          --ln-color: #999;
          --separator: rgba(0, 0, 0, 0.12);
        }
      }
      * { margin: 0; padding: 0; box-sizing: border-box; }
      html, body {
        background: var(--background);
        color: var(--text-primary);
        font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        font-size: 12px;
        line-height: 1.5;
      }
      .line {
        display: flex;
        white-space: pre;
        padding: 0 8px;
        min-height: 20px;
        border-bottom: 1px solid var(--separator);
      }
      .line.add { background: var(--add-bg); }
      .line.del { background: var(--del-bg); }
      .ln {
        display: inline-block;
        width: 40px;
        text-align: right;
        padding-right: 8px;
        color: var(--ln-color);
        user-select: none;
        flex-shrink: 0;
      }
      .prefix {
        display: inline-block;
        width: 16px;
        flex-shrink: 0;
        color: var(--text-secondary);
      }
      .code {
        flex: 1;
        overflow-x: auto;
      }

      /* Syntax highlighting classes (server pre-highlights) */
      .hl-keyword  { color: #C678DD; }
      .hl-string   { color: #98C379; }
      .hl-comment  { color: #7F848E; font-style: italic; }
      .hl-number   { color: #D19A66; }
      .hl-type     { color: #E5C07B; }
      .hl-function { color: #61AFEF; }
      .hl-operator { color: #ABB2BF; }
      .hl-punctuation { color: #ABB2BF; }

      @media (prefers-color-scheme: light) {
        .hl-keyword  { color: #A626A4; }
        .hl-string   { color: #50A14F; }
        .hl-comment  { color: #A0A1A7; font-style: italic; }
        .hl-number   { color: #986801; }
        .hl-type     { color: #C18401; }
        .hl-function { color: #4078F2; }
        .hl-operator { color: #383A42; }
        .hl-punctuation { color: #383A42; }
      }
    </style>
    </head>
    <body>
    \(body)
    </body>
    </html>
    """
}
