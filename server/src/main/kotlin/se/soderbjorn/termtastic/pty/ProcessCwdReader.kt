package se.soderbjorn.termtastic.pty

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Polling fallback for shells that don't (or can't) emit OSC 7 cwd reports.
 * Reads the running shell's own working directory directly from the OS.
 *
 * - **Linux**: `/proc/<pid>/cwd` is a symlink to the directory.
 * - **macOS**: `lsof -a -p <pid> -d cwd -Fn` — small fork, but vastly simpler
 *   than the JNA dance for `proc_pidinfo` and the cost is fine for a
 *   ~3 s polling cadence.
 * - **Windows**: not supported (returns null). ConPTY-hosted shells generally
 *   handle OSC 7 anyway.
 */
internal object ProcessCwdReader {

    private val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    private val isMac = os.contains("mac") || os.contains("darwin")
    private val isLinux = !isMac && os.contains("linux")

    fun read(pid: Long): String? = try {
        when {
            isLinux -> Files.readSymbolicLink(Path.of("/proc/$pid/cwd")).toString()
            isMac -> readMacViaLsof(pid)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private fun readMacViaLsof(pid: Long): String? {
        val proc = ProcessBuilder("lsof", "-a", "-p", pid.toString(), "-d", "cwd", "-Fn")
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(2, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) return null
        // Output: one record per line; lines starting with 'n' carry the path.
        var cwd: String? = null
        for (line in output.lineSequence()) {
            if (line.startsWith("n")) cwd = line.substring(1)
        }
        return cwd?.takeIf { it.isNotEmpty() }
    }
}
