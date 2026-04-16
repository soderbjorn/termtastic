package se.soderbjorn.termtastic

/**
 * Detects the state of AI coding assistants (Claude Code, OpenAI Codex CLI,
 * Gemini CLI) by scanning recent terminal output for known status indicators.
 *
 * Each CLI renders distinctive text in the terminal while it is actively
 * working or waiting for user confirmation. This detector checks the tail of
 * a PTY ring buffer for those patterns and returns a [SessionState] describing
 * which CLI is active and what it is doing.
 *
 * ## Detected CLIs and their patterns
 *
 * ### Claude Code
 *
 * Working (generating or executing a tool):
 * ```
 *   ╭─────────────────────────────────────────╮
 *   │  ...model output...                     │
 *   │                                         │
 *   │                        esc to interrupt  │
 *   ╰─────────────────────────────────────────╯
 * ```
 *
 * Waiting (waiting for a tool result or user action):
 * ```
 *   ╭─────────────────────────────────────────╮
 *   │  ...tool running...                     │
 *   │                                         │
 *   │                           esc to cancel  │
 *   ╰─────────────────────────────────────────╯
 * ```
 * or a confirmation menu:
 * ```
 *   Do you want to proceed?
 *   1. Yes
 *   2. Yes, and don't ask again
 *   3. No, tell Claude what to do differently
 * ```
 * or a plan-mode approval:
 * ```
 *   Would you like to proceed?
 *   1. Yes, auto-accept edits
 *   2. Yes, manually approve edits
 *   3. No, keep planning
 * ```
 *
 * ### OpenAI Codex CLI
 *
 * Working (model is generating or executing):
 * ```
 *   • Working (12s • esc to interrupt)
 * ```
 * or:
 * ```
 *   • Thinking (3s • esc to interrupt)
 * ```
 *
 * Waiting (approval overlay — user must confirm an action):
 * ```
 *   Would you like to run the following command?
 *       npm test
 *   > Yes, proceed
 *     No, continue without running it
 *
 *   Press enter to confirm or esc to cancel
 * ```
 * or:
 * ```
 *   Would you like to make the following edits?
 * ```
 *
 * ### Gemini CLI
 *
 * Working (model is generating or executing a tool):
 * ```
 *   Thinking...
 * ```
 * or:
 * ```
 *   Working...
 * ```
 * or with elapsed timer:
 * ```
 *   (esc to cancel, 5s)
 * ```
 *
 * Waiting (confirmation prompt — user must approve a change):
 * ```
 *   Apply this change?
 *   > Allow once
 *     Allow for this session
 *     No, suggest changes (esc)
 * ```
 * or:
 * ```
 *   Waiting for user confirmation...
 * ```
 */
object StateDetector {

    /** Markers that appear when Claude Code returns to idle (input prompt). */
    private val CLAUDE_IDLE_MARKERS = listOf(
        "\u276f",   // ❯ — the input prompt character
    )

    // Claude Code renders approval menus inside a rounded box, so each row
    // begins with a `│` border before any indentation. Allow the vertical bar
    // (and the surrounding whitespace) in the prefix so the numbered-option
    // anchors still match inside a boxed menu.
    private val CLAUDE_MENU_OPTION_1 = Regex("(?m)^[\\s\u2502]*(?:\u276f\\s*)?1\\.\\s")
    private val CLAUDE_MENU_OPTION_2 = Regex("(?m)^[\\s\u2502]*(?:\u276f\\s*)?2\\.\\s")

    /**
     * Scan the given [text] (typically the tail of a PTY ring buffer, decoded
     * as UTF-8) for known CLI state indicators.
     *
     * The caller is expected to pass ANSI-stripped text so that escape
     * sequences don't break substring matching.
     *
     * @return a [SessionState] if a known CLI is detected, or `null` if the
     *         terminal appears idle (no recognisable AI assistant UI).
     */
    fun detectState(text: String): SessionState? {
        val lower = text.lowercase()

        // ── Claude Code ─────────────────────────────────────────────
        // Claude uses "esc to interrupt" while generating and "esc to cancel"
        // while a tool is running. These are the most reliable indicators
        // because they appear in the bottom-right of the Claude Code TUI
        // and are unlikely to collide with normal shell output.
        //
        // Note: Codex CLI also uses "esc to interrupt", but the state
        // meaning is identical (working), so attributing it to Claude is
        // harmless — the important thing is the state, not the CLI name.
        //
        // Position-aware matching: the raw PTY buffer may contain stale
        // "esc to interrupt" text from a previous working phase. If an
        // idle indicator (e.g. the ❯ input prompt) appears AFTER the last
        // working indicator, Claude has finished and is idle.

        val interruptIdx = lower.lastIndexOf("esc to interrupt")
        val cancelIdx = lower.lastIndexOf("esc to cancel")
        val activeIdx = maxOf(interruptIdx, cancelIdx)

        if (activeIdx >= 0) {
            // Check if an idle marker appears after the last active indicator.
            val idleAfter = CLAUDE_IDLE_MARKERS.any { marker ->
                text.lastIndexOf(marker) > activeIdx
            }
            if (!idleAfter) {
                if (cancelIdx > interruptIdx) {
                    // Gemini CLI uses "esc to cancel," (with trailing comma and
                    // elapsed time). If the comma-form is present, this is Gemini
                    // working, not Claude waiting. Check Gemini first.
                    if ("esc to cancel," in lower) {
                        return SessionState(cli = "gemini", state = "working")
                    }
                    return SessionState(cli = "claude", state = "waiting")
                }
                return SessionState(cli = "claude", state = "working")
            }
            // Idle marker found after active indicator — Claude is idle,
            // fall through to check other CLIs or return null.
        }

        // Claude Code confirmation menus don't render "esc to cancel" in
        // the input box, so the branch above misses them. Match the
        // prompt text directly. "Do you want to proceed?" is the normal
        // tool-approval prompt; "Would you like to proceed?" is the
        // plan-mode approval prompt.
        //
        // We cannot gate on ❯ being absent: Claude uses ❯ both for the
        // idle input box and as the selection cursor inside approval
        // menus, so the menu's own ❯ would hide the wait. Instead gate
        // structurally — require a numbered option list ("1. " and
        // "2. " on their own lines) to appear after the phrase. Every
        // Claude approval menu has this shape, and the ^-anchor rules
        // out chat history that merely quotes the phrase.
        val proceedIdx = listOf("do you want to proceed?", "would you like to proceed?")
            .map { lower.lastIndexOf(it) }
            .filter { it >= 0 }
            .maxOrNull()
        if (proceedIdx != null) {
            val tail = lower.substring(proceedIdx)
            if (CLAUDE_MENU_OPTION_1.containsMatchIn(tail) &&
                CLAUDE_MENU_OPTION_2.containsMatchIn(tail)
            ) {
                return SessionState(cli = "claude", state = "waiting")
            }
        }

        // ── OpenAI Codex CLI ────────────────────────────────────────
        // Codex approval overlays show distinctive prompt text when waiting
        // for the user to confirm an action.
        if ("would you like to run the following command" in lower ||
            "would you like to make the following edits" in lower ||
            "would you like to grant these permissions" in lower ||
            "press enter to confirm or esc to cancel" in lower
        ) {
            return SessionState(cli = "codex", state = "waiting")
        }

        // ── Gemini CLI ──────────────────────────────────────────────
        // Gemini shows "Thinking..." or "Working..." as a status label
        // while the model is generating.
        if ("thinking..." in lower || "working..." in lower) {
            return SessionState(cli = "gemini", state = "working")
        }
        // Gemini confirmation prompts.
        if ("apply this change?" in lower ||
            "waiting for user confirmation" in lower ||
            "allow once" in lower
        ) {
            return SessionState(cli = "gemini", state = "waiting")
        }

        return null
    }
}

/**
 * The detected state of an AI coding assistant in a terminal session.
 *
 * @property cli   Which CLI was detected: `"claude"`, `"codex"`, or `"gemini"`.
 * @property state What the CLI is doing: `"working"` (actively generating or
 *                 executing) or `"waiting"` (blocked on user confirmation).
 */
data class SessionState(val cli: String, val state: String)
