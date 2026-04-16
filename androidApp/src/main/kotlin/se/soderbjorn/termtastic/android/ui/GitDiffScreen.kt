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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import se.soderbjorn.termtastic.DiffHunk
import se.soderbjorn.termtastic.DiffLine
import se.soderbjorn.termtastic.DiffLineType
import se.soderbjorn.termtastic.WindowEnvelope
import se.soderbjorn.termtastic.android.net.ConnectionHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitDiffScreen(
    paneId: String,
    filePath: String,
    onBack: () -> Unit,
) {
    val windowSocket = ConnectionHolder.windowSocket()
    if (windowSocket == null) {
        onBack()
        return
    }

    var hunks by remember { mutableStateOf<List<DiffHunk>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isDark = isSystemInDarkTheme()

    LaunchedEffect(paneId, filePath) {
        val reply = runCatching { windowSocket.gitDiff(paneId, filePath) }.getOrNull()
        when (reply) {
            is WindowEnvelope.GitDiffResult -> {
                hunks = reply.hunks
                errorMessage = null
            }
            is WindowEnvelope.GitError -> {
                errorMessage = reply.message
            }
            else -> {
                errorMessage = "No response"
            }
        }
    }

    BackHandler { onBack() }

    val fileName = filePath.substringAfterLast('/')

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
            val currentHunks = hunks
            when {
                currentHunks != null -> {
                    val diffHtml = remember(currentHunks, isDark) {
                        buildDiffHtml(currentHunks, isDark)
                    }
                    AndroidView(
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
                                diffHtml,
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        },
                    )
                }
                errorMessage != null -> Text(
                    text = errorMessage!!,
                    color = Color.Gray,
                    modifier = Modifier.padding(16.dp),
                )
                else -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/**
 * Build a complete HTML page rendering the diff hunks in inline (unified)
 * format, styled for mobile with line numbers, coloured backgrounds for
 * additions/deletions, and syntax-highlight CSS classes.
 */
private fun buildDiffHtml(hunks: List<DiffHunk>, isDark: Boolean): String {
    val vars = if (isDark) """
    --background: #1C1C1E;
    --surface: #2C2C2E;
    --text-primary: #F5F5F5;
    --text-secondary: #8E8E93;
    --line-no: #6E6E73;
    --add-bg: rgba(50, 215, 75, 0.12);
    --add-border: rgba(50, 215, 75, 0.25);
    --del-bg: rgba(255, 69, 58, 0.12);
    --del-border: rgba(255, 69, 58, 0.25);
    --ctx-bg: transparent;
    --separator: rgba(255, 255, 255, 0.1);
    """.trimIndent() else """
    --background: #F5F5F7;
    --surface: #FFFFFF;
    --text-primary: #1C1C1E;
    --text-secondary: #6E6E73;
    --line-no: #8E8E93;
    --add-bg: rgba(40, 167, 69, 0.10);
    --add-border: rgba(40, 167, 69, 0.20);
    --del-bg: rgba(220, 53, 69, 0.10);
    --del-border: rgba(220, 53, 69, 0.20);
    --ctx-bg: transparent;
    --separator: rgba(0, 0, 0, 0.12);
    """.trimIndent()

    val body = buildString {
        for (hunk in hunks) {
            append("<table>")
            for (line in hunk.lines) {
                val cls = when (line.type) {
                    DiffLineType.Addition -> "add"
                    DiffLineType.Deletion -> "del"
                    DiffLineType.Context  -> "ctx"
                }
                val oldNo = line.oldLineNo?.toString() ?: ""
                val newNo = line.newLineNo?.toString() ?: ""
                val prefix = when (line.type) {
                    DiffLineType.Addition -> "+"
                    DiffLineType.Deletion -> "-"
                    DiffLineType.Context  -> " "
                }
                val escaped = line.content
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                append("<tr class=\"$cls\">")
                append("<td class=\"ln\">$oldNo</td>")
                append("<td class=\"ln\">$newNo</td>")
                append("<td class=\"pfx\">$prefix</td>")
                append("<td class=\"code\">$escaped</td>")
                append("</tr>")
            }
            append("</table>")
        }
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
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 13px;
    line-height: 1.5;
    padding: 0;
  }
  table {
    width: 100%;
    border-collapse: collapse;
    table-layout: fixed;
  }
  tr.add { background: var(--add-bg); }
  tr.del { background: var(--del-bg); }
  tr.ctx { background: var(--ctx-bg); }
  td { vertical-align: top; white-space: pre-wrap; word-break: break-all; }
  td.ln {
    width: 36px;
    min-width: 36px;
    max-width: 36px;
    text-align: right;
    padding: 0 4px;
    color: var(--line-no);
    font-size: 11px;
    user-select: none;
    -webkit-user-select: none;
    border-right: 1px solid var(--separator);
  }
  td.pfx {
    width: 16px;
    min-width: 16px;
    max-width: 16px;
    text-align: center;
    color: var(--text-secondary);
    user-select: none;
    -webkit-user-select: none;
  }
  td.code {
    padding: 0 8px;
  }
  /* Syntax highlighting classes matching server output */
  .hl-keyword  { color: #FF79C6; }
  .hl-string   { color: #F1FA8C; }
  .hl-comment  { color: #6272A4; font-style: italic; }
  .hl-number   { color: #BD93F9; }
  .hl-type     { color: #8BE9FD; }
  .hl-function { color: #50FA7B; }
  .hl-operator { color: #FF79C6; }
  .hl-punctuation { color: var(--text-secondary); }
</style>
</head>
<body>
$body
</body>
</html>
""".trimIndent()
}
