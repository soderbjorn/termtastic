/**
 * Top-level singletons for the Termtastic web frontend: the
 * [TermtasticClient], the `/window` [WindowSocket], the
 * [AppBackingViewModel], and the small set of Electron / connection
 * detection flags.
 *
 * The bigger registries (terminals, DOM refs, per-pane VMs, rendering
 * bookkeeping) and the action helpers that operate on them have been
 * moved to companion files in this package:
 *  - [TerminalRegistry] — xterm instances + visibility refit
 *  - [DomRefRegistry] — cached layout DOM elements
 *  - [PaneStateRegistry] — file-browser / git per-pane caches
 *  - [RenderingState] — active tab, modal handlers, animation flags
 *  - [WebStateActions] — command dispatch, theme application, focus, …
 *
 * Splitting was a pure file-level reorganisation: every name remains
 * top-level in `se.soderbjorn.termtastic`, so consumers continue to
 * reference the unqualified `appVm`, `terminals`, `launchCmd(...)`, etc.
 *
 * @see main
 * @see connectWindow
 * @see renderConfig
 */
package se.soderbjorn.termtastic

import se.soderbjorn.termtastic.client.TermtasticClient
import se.soderbjorn.termtastic.client.WindowSocket
import se.soderbjorn.termtastic.client.viewmodel.AppBackingViewModel

// ── Core references (initialized in start()) ────────────────────────
internal lateinit var termtasticClient: TermtasticClient
internal lateinit var windowSocket: WindowSocket
internal lateinit var appVm: AppBackingViewModel

// ── Electron / connection detection ─────────────────────────────────
internal var isElectronClient = false
internal var proto = "ws"
internal var authQueryParam = ""
internal var clientTypeAtStart = "Web"
