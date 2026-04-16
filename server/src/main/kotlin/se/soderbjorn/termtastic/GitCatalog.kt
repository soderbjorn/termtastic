package se.soderbjorn.termtastic

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Server-side git operations: list uncommitted changes and produce diffs.
 * All work is done via the `git` CLI through [ProcessBuilder] — no JGit
 * dependency, same git the user has installed.
 *
 * Every method takes an explicit [cwd] so multiple panes can point at
 * different repositories.
 */
object GitCatalog {
    private val log = LoggerFactory.getLogger(GitCatalog::class.java)
    private const val TIMEOUT_SECONDS = 10L
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_FILES = 1000

    /**
     * List uncommitted changes in the working tree (staged, unstaged, and
     * untracked files). Returns `null` if [cwd] is not inside a git repo.
     */
    fun listChanges(cwd: Path): List<GitFileEntry>? {
        val output = runGit(cwd, "status", "--porcelain=v1", "-uall") ?: return null
        val entries = mutableListOf<GitFileEntry>()
        for (line in output.lines()) {
            if (line.length < 4) continue
            val xy = line.substring(0, 2)
            val filePath = line.substring(3).trim()
            if (filePath.isEmpty()) continue

            val status = parseStatus(xy) ?: continue
            val directory = filePath.substringBeforeLast('/', missingDelimiterValue = "")
            entries.add(GitFileEntry(filePath = filePath, status = status, directory = directory))
            if (entries.size >= MAX_FILES) break
        }
        return entries
    }

    /**
     * Produce a parsed diff for [filePath] in the working tree at [cwd].
     * Returns hunks from `git diff` for modified tracked files, or a
     * synthetic all-additions/all-deletions diff for new/deleted files.
     */
    fun readDiff(cwd: Path, filePath: String): List<DiffHunk>? {
        // Path traversal check.
        val base = runCatching { cwd.toRealPath() }.getOrNull() ?: return null
        val resolved = runCatching { base.resolve(filePath).normalize().toRealPath() }.getOrNull()
        if (resolved != null && !resolved.startsWith(base)) return null

        // Determine the file's status to pick the right diff strategy.
        val statusLine = runGit(cwd, "status", "--porcelain=v1", "--", filePath)
            ?.lines()?.firstOrNull { it.length >= 4 }
        val status = if (statusLine != null) parseStatus(statusLine.substring(0, 2)) else null

        return when (status) {
            GitFileStatus.Untracked, GitFileStatus.Added -> {
                val content = readWorkingFile(cwd, filePath) ?: return null
                DiffParser.syntheticAdd(content)
            }
            GitFileStatus.Deleted -> {
                val content = readGitFile(cwd, filePath, "HEAD") ?: return null
                DiffParser.syntheticDelete(content)
            }
            else -> {
                // Modified, Renamed, or unknown — use git diff.
                val diffOutput = runGit(
                    cwd,
                    "diff", "--ignore-space-change", "--ignore-blank-lines",
                    "--no-color", "--", filePath,
                ) ?: return null
                DiffParser.parse(diffOutput)
            }
        }
    }

    /**
     * Read a file's content from the HEAD revision. Returns null on failure.
     */
    fun readGitFile(cwd: Path, filePath: String, revision: String = "HEAD"): String? {
        return runGit(cwd, "show", "$revision:$filePath")
    }

    /**
     * Read a file from the working tree with path traversal protection.
     */
    fun readWorkingFile(cwd: Path, filePath: String): String? {
        val base = runCatching { cwd.toRealPath() }.getOrNull() ?: return null
        val resolved = runCatching { base.resolve(filePath).normalize() }.getOrNull() ?: return null
        val real = runCatching { resolved.toRealPath() }.getOrNull() ?: return null
        if (!real.startsWith(base)) return null
        if (!Files.isRegularFile(real)) return null
        val size = runCatching { Files.size(real) }.getOrNull() ?: return null
        if (size > MAX_FILE_BYTES) return null
        return runCatching { Files.readString(real) }.getOrNull()
    }

    /**
     * Detect language from file extension for syntax highlighting.
     * Delegates to [LanguageDetector] so the file-browser pane sees the same
     * mapping.
     */
    fun detectLanguage(filePath: String): String? = LanguageDetector.detect(filePath)

    private fun parseStatus(xy: String): GitFileStatus? {
        val x = xy[0]
        val y = xy[1]
        return when {
            x == '?' && y == '?' -> GitFileStatus.Untracked
            x == 'A' || y == 'A' -> GitFileStatus.Added
            x == 'D' || y == 'D' -> GitFileStatus.Deleted
            x == 'R' || y == 'R' -> GitFileStatus.Renamed
            x == 'M' || y == 'M' || x == 'U' || y == 'U' -> GitFileStatus.Modified
            x == ' ' && y == ' ' -> null // clean
            else -> GitFileStatus.Modified
        }
    }

    private fun runGit(cwd: Path, vararg args: String): String? {
        return try {
            val cmd = listOf("git") + args.toList()
            val proc = ProcessBuilder(cmd)
                .directory(cwd.toFile())
                .redirectErrorStream(false)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                log.warn("Git command timed out: {}", cmd)
                return null
            }
            if (proc.exitValue() != 0) return null
            output
        } catch (t: Throwable) {
            log.debug("Git command failed in {}: {}", cwd, t.message)
            null
        }
    }
}
