import SwiftUI
import WebKit
import Client

/// Renders a single file from the remote pane. Markdown and text files load
/// into a WKWebView using `wrapFileHtml`; binary files render a placeholder.
/// Mirrors the Android `FileBrowserContentScreen`.
struct FileBrowserContentView: View {
    let paneId: String
    let relPath: String
    var onBack: () -> Void

    @State private var html: String?
    @State private var kind: Client.FileContentKind?
    @State private var errorMessage: String?

    private var fileName: String {
        relPath.components(separatedBy: "/").last ?? relPath
    }

    var body: some View {
        ZStack {
            if kind == Client.FileContentKind.binary {
                Text("Binary file — preview unavailable.")
                    .foregroundStyle(.gray)
                    .padding()
            } else if let currentHtml = html, let currentKind = kind {
                FileWebView(html: wrapFileHtml(currentHtml, kind: currentKind))
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
        .task { await loadContent() }
    }

    private func loadContent() async {
        guard let socket = ConnectionHolder.shared.windowSocket else {
            errorMessage = "Not connected"
            return
        }
        do {
            let reply = try await socket.fileBrowserOpenFile(
                paneId: paneId,
                relPath: relPath,
                timeoutMs: 10_000
            )
            if let content = reply as? Client.WindowEnvelope.FileBrowserContentMsg {
                kind = content.kind
                html = content.html
            } else if let error = reply as? Client.WindowEnvelope.FileBrowserError {
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

private struct FileWebView: UIViewRepresentable {
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

// MARK: - HTML wrapper

/// Wraps server-generated HTML with the markdown preview stylesheet, plus
/// `.hl-\*` rules so pre-tokenised source files match the diff viewer's
/// palette.
private func wrapFileHtml(_ bodyHtml: String, kind: Client.FileContentKind) -> String {
    let body: String
    if kind == Client.FileContentKind.text {
        body = "<pre class=\"file-source\"><code>\(bodyHtml)</code></pre>"
    } else {
        body = bodyHtml
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
        --bg-elevated: #3A3A3C;
        --text-primary: #F5F5F5;
        --text-secondary: #8E8E93;
        --separator: rgba(255, 255, 255, 0.1);
        --accent: #F4B869;
        --hl-keyword: #569cd6;
        --hl-string: #ce9178;
        --hl-comment: #6a9955;
        --hl-number: #b5cea8;
        --hl-tag: #569cd6;
        --hl-attr: #9cdcfe;
        --hl-type: #4ec9b0;
        --hl-annotation: #dcdcaa;
        --hl-punctuation: #808080;
      }
      @media (prefers-color-scheme: light) {
        :root {
          --background: #F5F5F7;
          --surface: #FFFFFF;
          --bg-elevated: #ECECF1;
          --text-primary: #1C1C1E;
          --text-secondary: #6E6E73;
          --separator: rgba(0, 0, 0, 0.12);
          --hl-keyword: #0550ae;
          --hl-string: #0a3069;
          --hl-comment: #6e7781;
          --hl-number: #953800;
          --hl-tag: #6639ba;
          --hl-attr: #0550ae;
          --hl-type: #0e6575;
          --hl-annotation: #9a6700;
          --hl-punctuation: #6e7781;
        }
      }
      * { margin: 0; padding: 0; box-sizing: border-box; }
      html, body {
        background: var(--background);
        color: var(--text-primary);
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        font-size: 15px;
        line-height: 1.6;
        padding: 16px 18px 32px;
        word-wrap: break-word;
      }
      h1, h2, h3, h4, h5, h6 {
        color: var(--text-primary);
        margin-top: 1.5em;
        margin-bottom: 0.5em;
        line-height: 1.25;
      }
      h1 { font-size: 1.8em; border-bottom: 1px solid var(--separator); padding-bottom: 0.3em; }
      h2 { font-size: 1.5em; border-bottom: 1px solid var(--separator); padding-bottom: 0.3em; }
      h3 { font-size: 1.25em; }
      p { margin: 0.75em 0; }
      a { color: var(--accent); text-decoration: none; }
      a:active { text-decoration: underline; }
      code {
        font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        font-size: 0.9em;
        color: var(--text-primary);
        background: var(--bg-elevated);
        border: 1px solid var(--separator);
        padding: 0.15em 0.4em;
        border-radius: 3px;
      }
      pre {
        color: var(--text-primary);
        background: var(--bg-elevated);
        border: 1px solid var(--separator);
        padding: 12px 14px;
        border-radius: 4px;
        overflow-x: auto;
        margin: 0.75em 0;
      }
      pre code { background: transparent; border: none; padding: 0; font-size: 0.9em; }
      pre.file-source {
        white-space: pre;
        font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        font-size: 13px;
        line-height: 1.45;
      }
      blockquote {
        border-left: 3px solid var(--separator);
        margin: 0.75em 0;
        padding: 0 1em;
        color: var(--text-secondary);
      }
      ul, ol { margin: 0.5em 0; padding-left: 1.5em; }
      li { margin: 0.25em 0; }
      hr { border: 0; border-top: 1px solid var(--separator); margin: 1.2em 0; }
      table { border-collapse: collapse; margin: 0.75em 0; }
      th, td { border: 1px solid var(--separator); padding: 6px 10px; }
      th { background: var(--surface); }
      img { max-width: 100%; height: auto; }

      .hl-keyword { color: var(--hl-keyword); }
      .hl-string { color: var(--hl-string); }
      .hl-comment { color: var(--hl-comment); font-style: italic; }
      .hl-number { color: var(--hl-number); }
      .hl-tag { color: var(--hl-tag); }
      .hl-attr { color: var(--hl-attr); }
      .hl-type { color: var(--hl-type); }
      .hl-annotation { color: var(--hl-annotation); }
      .hl-punctuation { color: var(--hl-punctuation); }
    </style>
    </head>
    <body>
    \(body)
    </body>
    </html>
    """
}
