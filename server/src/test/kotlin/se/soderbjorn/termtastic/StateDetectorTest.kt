package se.soderbjorn.termtastic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StateDetectorTest {

    @Test
    fun `plan-mode approval with selection cursor is detected as waiting`() {
        val text = """
            ╭────────────────────────────────────────╮
            │  Ready to code?                        │
            ╰────────────────────────────────────────╯

            Would you like to proceed?

            ❯ 1. Yes, auto-accept edits
              2. Yes, manually approve edits
              3. No, tell Claude what to change
        """.trimIndent()

        assertEquals(
            SessionState(cli = "claude", state = "waiting"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `plan-mode approval rendered inside box border is detected as waiting`() {
        val text = """
            ╭────────────────────────────────────────╮
            │  Would you like to proceed?            │
            │                                        │
            │  ❯ 1. Yes, auto-accept edits           │
            │    2. Yes, manually approve edits      │
            │    3. No, keep planning                │
            ╰────────────────────────────────────────╯
        """.trimIndent()

        assertEquals(
            SessionState(cli = "claude", state = "waiting"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `tool approval prompt is detected as waiting`() {
        val text = """
            Do you want to proceed?

            ❯ 1. Yes
              2. Yes, and don't ask again
              3. No, tell Claude what to do differently
        """.trimIndent()

        assertEquals(
            SessionState(cli = "claude", state = "waiting"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `proceed phrase without numbered options is not detected`() {
        val text = """
            earlier in the chat the user said "would you like to proceed?"
            and now we are back at the prompt
            ❯
        """.trimIndent()

        assertNull(StateDetector.detectState(text))
    }

    @Test
    fun `esc to interrupt without idle marker is working`() {
        val text = "Generating...                esc to interrupt"
        assertEquals(
            SessionState(cli = "claude", state = "working"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `esc to interrupt followed by idle prompt is idle`() {
        val text = """
            old output                   esc to interrupt
            done.
            ❯
        """.trimIndent()
        assertNull(StateDetector.detectState(text))
    }

    @Test
    fun `esc to cancel without idle marker is claude waiting`() {
        val text = "Running tool...              esc to cancel"
        assertEquals(
            SessionState(cli = "claude", state = "waiting"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `gemini esc to cancel with comma is gemini working`() {
        val text = "(esc to cancel, 5s)"
        assertEquals(
            SessionState(cli = "gemini", state = "working"),
            StateDetector.detectState(text),
        )
    }
}
