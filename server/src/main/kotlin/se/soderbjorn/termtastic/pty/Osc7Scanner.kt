package se.soderbjorn.termtastic.pty

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * Streaming parser that watches a PTY byte stream for OSC 7 cwd reports
 * (`ESC ] 7 ; file://<host>/<urlencoded-path> ST` where ST is `BEL` or `ESC \`)
 * and invokes [onCwd] each time it sees one for the local host.
 *
 * Designed to be fed in arbitrary chunks: the state machine carries any partial
 * sequence across [feed] calls so a sequence that straddles a buffer boundary
 * still parses correctly.
 *
 * Bytes are NOT removed from the stream — terminal emulators render OSC
 * sequences invisibly anyway, and stripping them would break other consumers
 * (e.g. iTerm2 integration scrolling, font escapes) sharing the same OSC range.
 */
internal class Osc7Scanner(private val onCwd: (String) -> Unit) {

    private enum class State { IDLE, ESC, OSC, OSC_ESC }

    private var state = State.IDLE
    private val buf = StringBuilder()

    fun feed(chunk: ByteArray, len: Int = chunk.size) {
        var i = 0
        while (i < len) {
            val b = chunk[i].toInt() and 0xFF
            when (state) {
                State.IDLE -> if (b == 0x1B) state = State.ESC
                State.ESC -> when (b) {
                    0x5D /* ] */ -> { state = State.OSC; buf.setLength(0) }
                    0x1B -> Unit // stay in ESC
                    else -> state = State.IDLE
                }
                State.OSC -> when (b) {
                    0x07 /* BEL */ -> { finishOsc(); state = State.IDLE }
                    0x1B -> state = State.OSC_ESC
                    else -> {
                        if (buf.length < MAX_BUF) {
                            buf.append(b.toChar())
                        } else {
                            // Runaway sequence — abort and resync.
                            buf.setLength(0)
                            state = State.IDLE
                        }
                    }
                }
                State.OSC_ESC -> when (b) {
                    0x5C /* \ */ -> { finishOsc(); state = State.IDLE }
                    0x1B -> { buf.setLength(0); state = State.ESC }
                    else -> { buf.setLength(0); state = State.IDLE }
                }
            }
            i++
        }
    }

    private fun finishOsc() {
        val payload = buf.toString()
        buf.setLength(0)
        if (!payload.startsWith("7;")) return
        val (host, path) = parseFileUrl(payload.substring(2)) ?: return
        if (host.isNotEmpty() && host != "localhost" && host != localHostname) return
        if (path.isNotEmpty()) onCwd(path)
    }

    private fun parseFileUrl(url: String): Pair<String, String>? {
        if (!url.startsWith("file://")) return null
        val rest = url.substring(7)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val host = rest.substring(0, slash)
        val path = percentDecode(rest.substring(slash))
        return host to path
    }

    private fun percentDecode(s: String): String {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    out.write(v)
                    i += 3
                    continue
                }
            }
            // Non-ASCII shouldn't appear in a valid file URL, but be lenient.
            out.write(c.code and 0xFF)
            i++
        }
        return out.toString(Charsets.UTF_8)
    }

    companion object {
        private const val MAX_BUF = 4096

        private val localHostname: String by lazy {
            runCatching { InetAddress.getLocalHost().hostName }.getOrNull().orEmpty()
        }
    }
}
