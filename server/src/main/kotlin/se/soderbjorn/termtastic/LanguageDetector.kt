package se.soderbjorn.termtastic

/**
 * Map a file path to the language identifier the [SyntaxHighlighter] understands,
 * or `null` when no highlighter is available (the highlighter falls back to
 * plain HTML-escaped output in that case). Extracted from
 * `GitCatalog.detectLanguage` so the git-diff pane and the file-browser pane
 * stay in sync.
 */
object LanguageDetector {
    fun detect(filePath: String): String? {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "js", "jsx", "mjs", "cjs" -> "javascript"
            "ts", "tsx", "mts", "cts" -> "typescript"
            "kt", "kts" -> "kotlin"
            "html", "htm" -> "html"
            "css", "scss", "less" -> "css"
            else -> null
        }
    }
}
