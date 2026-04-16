package se.soderbjorn.termtastic.android.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import se.soderbjorn.termtastic.FileContentKind
import se.soderbjorn.termtastic.WindowEnvelope
import se.soderbjorn.termtastic.android.net.ConnectionHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserContentScreen(
    paneId: String,
    relPath: String,
    onBack: () -> Unit,
) {
    val windowSocket = ConnectionHolder.windowSocket()
    if (windowSocket == null) {
        onBack()
        return
    }

    var html by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf<FileContentKind?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isDark = isSystemInDarkTheme()

    LaunchedEffect(paneId, relPath) {
        val reply = runCatching { windowSocket.fileBrowserOpenFile(paneId, relPath) }.getOrNull()
        when (reply) {
            is WindowEnvelope.FileBrowserContentMsg -> {
                kind = reply.kind
                html = reply.html
                errorMessage = null
            }
            is WindowEnvelope.FileBrowserError -> errorMessage = reply.message
            else -> errorMessage = "No response"
        }
    }

    BackHandler { onBack() }

    val fileName = relPath.substringAfterLast('/')

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            val currentHtml = html
            val currentKind = kind
            when {
                currentKind == FileContentKind.Binary -> Text(
                    "Binary file — preview unavailable.",
                    color = Color.Gray,
                    modifier = Modifier.padding(16.dp),
                )
                currentHtml != null && currentKind != null -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = false
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        }
                    },
                    update = { webView ->
                        webView.loadDataWithBaseURL(
                            null,
                            wrapFileHtml(currentHtml, currentKind, isDark),
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    },
                )
                errorMessage != null -> Text(
                    text = errorMessage!!,
                    color = Color.Gray,
                    modifier = Modifier.padding(16.dp),
                )
                else -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/**
 * Wrap the server-rendered HTML body in a minimal stylesheet. For Markdown
 * files the body is flexmark output (prose). For Text files the body holds
 * pre-tokenised `<span class="hl-…">` spans from [SyntaxHighlighter] — those
 * need the same hl-\* palette the diff viewer already ships on the web.
 */
private fun wrapFileHtml(bodyHtml: String, kind: FileContentKind, isDark: Boolean): String {
    val vars = if (isDark) """
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
    """.trimIndent() else """
    --background: #F5F5F7;
    --surface: #FFFFFF;
    --bg-elevated: #ECECF1;
    --text-primary: #1C1C1E;
    --text-secondary: #6E6E73;
    --separator: rgba(0, 0, 0, 0.12);
    --accent: #F4B869;
    --hl-keyword: #0550ae;
    --hl-string: #0a3069;
    --hl-comment: #6e7781;
    --hl-number: #953800;
    --hl-tag: #6639ba;
    --hl-attr: #0550ae;
    --hl-type: #0e6575;
    --hl-annotation: #9a6700;
    --hl-punctuation: #6e7781;
    """.trimIndent()

    val body = when (kind) {
        FileContentKind.Text -> "<pre class=\"file-source\"><code>$bodyHtml</code></pre>"
        else -> bodyHtml
    }

    return """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<style>
  :root {
    $vars
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
$body
</body>
</html>
""".trimIndent()
}
