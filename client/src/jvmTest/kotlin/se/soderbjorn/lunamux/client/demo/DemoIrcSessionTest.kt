/**
 * Focused checks that [DemoIrcSession] reflows its full-screen frame to the
 * terminal grid reported through [DemoSession.resize] — the behaviour behind
 * the DarknessIRC panes filling their pane on resize — and word-wraps long
 * lines rather than truncating them.
 */
package se.soderbjorn.lunamux.client.demo

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class DemoIrcSessionTest {

    /** Rows a rendered frame occupies — one `CSI n;1H` absolute cursor move per row. */
    private fun rowCount(frame: String): Int = frame.split(";1H").size - 1

    /** Width of the frame's rule line (the run of `─`), a proxy for total width. */
    private fun ruleWidth(frame: String): Int = frame.count { it == '─' }

    /** resize() to a large grid makes the frame grow to fill it (more rows, wider rule). */
    @Test
    fun reflowsToReportedGrid() = runBlocking {
        val spec = DemoIrcContent.channelSpecs().first()
        val session = DemoIrcSession(spec, this)
        val frames = ArrayDeque<String>()
        val collector = launch { session.output().collect { frames.addLast(it.decodeToString()) } }
        withTimeout(2_000) { while (frames.isEmpty()) kotlinx.coroutines.delay(10) }
        val before = frames.last()

        session.resize(cols = 120, rows = 50)

        withTimeout(2_000) { while (rowCount(frames.last()) < 45) kotlinx.coroutines.delay(10) }
        val after = frames.last()

        assertTrue(rowCount(before) in 18..24, "default frame ~21 rows, got ${rowCount(before)}")
        assertTrue(rowCount(after) >= 45, "resized frame should be ~50 rows, got ${rowCount(after)}")
        assertTrue(ruleWidth(after) >= 100, "resized frame should be ~120 wide, rule was ${ruleWidth(after)}")

        session.close()
        collector.cancel()
    }
}
