/**
 * The common surface every demo-mode PTY-like session exposes to the demo
 * transports ([DemoPtySocket]) and to [DemoServer]'s session table. Both the
 * scripted shell/Claude sessions ([DemoTerminalSession]) and the interactive
 * IRC TUI sessions ([DemoIrcSession]) implement it, so the server can hold them
 * in one map and the PTY socket can attach to either without caring which is
 * behind the pane.
 *
 * @see DemoTerminalSession
 * @see DemoIrcSession
 * @see DemoServer
 */
package se.soderbjorn.lunamux.client.demo

import kotlinx.coroutines.flow.Flow

/**
 * One simulated terminal-shaped session in demo mode.
 *
 * Implementations replay some accumulated state as a first "snapshot" frame
 * to a newly-attaching client, then stream live output frames; they also
 * accept typed input bytes/text and can be torn down or rewound to their
 * fixture starting point.
 *
 * Called by [DemoPtySocket] (attach + input) and [DemoServer] (create, reap,
 * and reset).
 */
interface DemoSession {
    /** The fixture session id this session answers to. */
    val sessionId: String

    /**
     * The session's output as a cold-start flow: the current accumulated
     * state as one snapshot frame first, then live frames.
     *
     * @return a flow of output byte frames for one attaching client.
     */
    fun output(): Flow<ByteArray>

    /**
     * Feed raw input bytes (keystrokes/paste) into the session.
     *
     * @param bytes UTF-8 input bytes.
     */
    fun input(bytes: ByteArray)

    /**
     * Feed input text into the session (the web client delivers strings).
     *
     * @param text the typed/pasted text (may include control characters).
     */
    fun inputText(text: String)

    /**
     * Notify the session that its attached pane's terminal grid changed to
     * [cols] × [rows] cells. Sessions that render size-aware frames (the IRC
     * TUI) reflow to fill the pane; scripted fixed-frame sessions ignore it.
     * Called by [DemoPtySocket.resize] / [DemoPtySocket.forceResize] whenever
     * the client reports a new PTY size.
     *
     * @param cols terminal width in character cells.
     * @param rows terminal height in character cells.
     */
    suspend fun resize(cols: Int, rows: Int) {}

    /**
     * Rewind the session to its fixture starting point **in place** so
     * already-attached transports keep streaming. Called by
     * [DemoServer.resetToFixtures] before the demo tour plays.
     */
    suspend fun restart()

    /**
     * Permanently shut the session down: stop accepting input and cancel any
     * background coroutines so the session can be garbage-collected. Called by
     * [DemoServer.reapOrphanSessions] when the last pane referencing it closes.
     */
    fun close()
}
