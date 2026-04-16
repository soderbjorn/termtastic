package se.soderbjorn.termtastic.pty

import se.soderbjorn.termtastic.persistence.AppPaths
import java.io.File

/**
 * Makes interactive shells emit OSC 7 cwd reports so [Osc7Scanner] can pick
 * them up. Most distros that ship VTE already do this; macOS bash/zsh do not.
 *
 * Strategy:
 *  - **zsh**: redirect ZDOTDIR to a generated bootstrap dir whose `.zshrc`
 *    sources the user's real `~/.zshrc` (or `${'$'}TERMTASTIC_REAL_ZDOTDIR/.zshrc`)
 *    and then registers a `chpwd_functions` hook.
 *  - **bash**: append an OSC 7 emitter snippet to PROMPT_COMMAND.
 *  - **fish**: nothing — already emits OSC 7 by default.
 *  - **other**: nothing; the polling fallback in [ProcessCwdReader] still works.
 */
internal object ShellInitFiles {

    private val zshBootstrapDir: File by lazy {
        val dir = File(AppPaths.dataDir(), "shell-init/zsh")
        dir.mkdirs()
        File(dir, ".zshrc").writeText(zshRcContents())
        dir
    }

    fun configureEnv(shellPath: String, env: MutableMap<String, String>) {
        when (File(shellPath).name) {
            "zsh" -> configureZsh(env)
            "bash" -> configureBash(env)
            // fish: ships OSC 7 already.
        }
    }

    private fun configureZsh(env: MutableMap<String, String>) {
        // Stash whatever ZDOTDIR the user already had so the bootstrap can
        // delegate. If unset, the bootstrap falls back to ${'$'}HOME.
        env["ZDOTDIR"]?.let { env["TERMTASTIC_REAL_ZDOTDIR"] = it }
        env["ZDOTDIR"] = zshBootstrapDir.absolutePath
    }

    private fun configureBash(env: MutableMap<String, String>) {
        val snippet =
            """printf '\033]7;file://%s%s\007' "${'$'}{HOSTNAME:-localhost}" "${'$'}PWD""""
        val existing = env["PROMPT_COMMAND"]
        env["PROMPT_COMMAND"] = if (existing.isNullOrBlank()) snippet else "$existing; $snippet"
    }

    private fun zshRcContents(): String = """
        # termtastic zsh bootstrap — sources the real .zshrc, then installs an
        # OSC 7 emitter so the host server can track this pane's cwd.
        #
        # We restore ZDOTDIR (and clear TERMTASTIC_REAL_ZDOTDIR) BEFORE sourcing
        # the real .zshrc. Otherwise, anything in the user's startup chain that
        # re-execs or spawns a new interactive zsh (p10k instant prompt, zinit,
        # tmux auto-attach, `exec zsh`, …) would see the bootstrap ZDOTDIR in
        # its env and re-enter this file recursively, eventually tripping zsh's
        # "job table full or recursion limit exceeded".
        if [[ -n "${'$'}TERMTASTIC_REAL_ZDOTDIR" ]]; then
          local __termtastic_real_zdotdir="${'$'}TERMTASTIC_REAL_ZDOTDIR"
          export ZDOTDIR="${'$'}__termtastic_real_zdotdir"
          unset TERMTASTIC_REAL_ZDOTDIR
          [[ -r "${'$'}__termtastic_real_zdotdir/.zshrc" ]] && source "${'$'}__termtastic_real_zdotdir/.zshrc"
          unset __termtastic_real_zdotdir
        else
          unset ZDOTDIR
          [[ -r "${'$'}HOME/.zshrc" ]] && source "${'$'}HOME/.zshrc"
        fi

        __termtastic_emit_osc7() {
          # zsh's parameter expansion handles the only character that commonly
          # appears in cwds and needs encoding (space). Everything else is left
          # alone — the server URL-decodes leniently.
          local enc=${'$'}{PWD// /%20}
          printf '\e]7;file://%s%s\e\\' "${'$'}{HOST:-localhost}" "${'$'}enc"
        }
        typeset -ga chpwd_functions
        chpwd_functions+=(__termtastic_emit_osc7)
        # Emit once for the initial cwd so the server learns it before the
        # first 'cd' happens.
        __termtastic_emit_osc7
    """.trimIndent() + "\n"
}
