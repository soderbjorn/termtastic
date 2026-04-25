/**
 * Theme-related utility functions for the Termtastic web frontend.
 *
 * Provides helpers for resolving the current effective theme colors based on
 * appearance mode (Light/Dark/Auto), detecting the system color scheme preference,
 * and determining whether a background color is light or dark for contrast adjustment.
 *
 * @see buildXtermTheme
 * @see applyAppearanceClass
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.core.*

import kotlinx.browser.window

/** Feature flag: when true, applies a "spiced" variant to dark themes. Currently disabled. */
const val DARK_SPICED = false

/** Feature flag: when true, shows a debug menu on localhost. Currently disabled. */
const val SHOW_DEBUG_MENU = false

/**
 * The default terminal theme, resolved by name from the [recommendedColorSchemes] list.
 */
val defaultTheme: ColorScheme
    get() = recommendedColorSchemes.first { it.name == DEFAULT_THEME_NAME }

/**
 * Checks whether the user's system prefers dark mode via the `prefers-color-scheme` media query.
 *
 * @return true if the system prefers dark mode
 */
fun systemPrefersDark(): Boolean =
    window.matchMedia("(prefers-color-scheme: dark)").matches

/**
 * Determines whether the light variant of a theme should be active based on the
 * current appearance setting.
 *
 * @param appearance the user's selected appearance mode
 * @return true if light mode should be used
 */
fun isLightActive(appearance: Appearance): Boolean = when (appearance) {
    Appearance.Light -> true
    Appearance.Dark -> false
    Appearance.Auto -> !systemPrefersDark()
}

/**
 * Returns the foreground color for the given theme and appearance mode.
 *
 * @param theme the terminal theme
 * @param appearance the user's selected appearance mode
 * @return the hex foreground color string
 */
fun themeForegroundForCurrent(theme: ColorScheme, appearance: Appearance): String =
    if (isLightActive(appearance)) theme.lightFg else theme.darkFg

/**
 * Returns the background color for the given theme and appearance mode.
 *
 * @param theme the terminal theme
 * @param appearance the user's selected appearance mode
 * @return the hex background color string
 */
fun themeBackgroundForCurrent(theme: ColorScheme, appearance: Appearance): String =
    if (isLightActive(appearance)) theme.lightBg else theme.darkBg

/**
 * Determines whether a hex background color is perceptually light using the
 * YIQ luminance formula (weighted sum: R*299 + G*587 + B*114).
 *
 * Used to adjust ANSI color palettes for readability against the terminal background.
 *
 * @param bg the hex color string (e.g. "#1e1e1e")
 * @return true if the background is light (luminance > 140), false otherwise
 */
fun isBackgroundLight(bg: String): Boolean {
    if (bg.length < 7 || !bg.startsWith("#")) return false
    val r = bg.substring(1, 3).toIntOrNull(16) ?: return false
    val g = bg.substring(3, 5).toIntOrNull(16) ?: return false
    val b = bg.substring(5, 7).toIntOrNull(16) ?: return false
    return (r * 299 + g * 587 + b * 114) / 1000 > 140
}

/**
 * Resolves the full semantic palette for the currently active theme and
 * appearance mode, using the global [appVm] state.
 *
 * @return the resolved palette for the current state
 * @see ColorScheme.resolve
 */
fun currentResolvedPalette(): ResolvedPalette {
    val state = appVm.stateFlow.value
    val isDark = !isLightActive(state.appearance)
    return state.theme.resolve(isDark)
}

/**
 * Resolves the semantic palette for a specific theme and appearance mode.
 *
 * Used by the theme chooser to preview palettes before selection.
 *
 * @param theme the theme to resolve
 * @param isDark whether to resolve in dark mode
 * @return the resolved palette
 */
fun resolvedPaletteFor(theme: ColorScheme, isDark: Boolean): ResolvedPalette =
    theme.resolve(isDark)

/**
 * Resolves the palette for a specific app section, using the section-specific
 * theme override if set, or falling back to the global theme.
 *
 * @param section one of `"sidebar"`, `"terminal"`, `"diff"`, `"fileBrowser"`, `"tabs"`, `"chrome"`, `"windows"`
 * @return the resolved palette for that section
 */
fun sectionPalette(section: String): ResolvedPalette {
    val state = appVm.stateFlow.value
    val isDark = !isLightActive(state.appearance)
    val sectionTheme = when (section) {
        "sidebar" -> state.sidebarTheme
        "terminal" -> state.terminalTheme
        "diff" -> state.diffTheme
        "fileBrowser" -> state.fileBrowserTheme
        "tabs" -> state.tabsTheme
        "chrome" -> state.chromeTheme
        "windows" -> state.windowsTheme
        "active" -> state.activeTheme
        "bottomBar" -> state.bottomBarTheme
        else -> null
    }
    return (sectionTheme ?: state.theme).resolve(isDark)
}

/**
 * Converts a [ResolvedPalette] to a map of CSS custom property names to
 * CSS colour values.
 *
 * Property names follow the `--t-group-token` convention (e.g.
 * `--t-surface-base`, `--t-text-primary`).  Colours with alpha use
 * `rgba()` format; fully opaque colours use `#rrggbb`.
 *
 * @return map of CSS property name to CSS colour string
 * @see argbToCss
 */
/**
 * Converts a [ResolvedPalette] to a map of the legacy CSS alias variables
 * used by existing stylesheet rules (e.g. `--surface`, `--text-primary`).
 *
 * These aliases are defined on `:root` in styles.css as `var(--t-*, fallback)`.
 * Because CSS resolves `var()` at the element where the alias is defined,
 * setting `--t-*` on a child element does NOT update the inherited alias.
 * This map must be set alongside [toCssVarMap] on any section container
 * that needs a per-section theme override.
 *
 * @return map of legacy CSS alias name to CSS colour string
 * @see toCssVarMap
 */
fun ResolvedPalette.toCssAliasMap(): Map<String, String> = buildMap {
    put("--termtastic-orange", argbToCss(accent.primary))
    put("--termtastic-orange-accent", argbToCss(accent.primary))
    put("--background", argbToCss(sidebar.bg))
    put("--surface", argbToCss(surface.raised))
    put("--bg-elevated", argbToCss(surface.overlay))
    put("--text-primary", argbToCss(text.primary))
    put("--text-secondary", argbToCss(text.secondary))
    put("--separator", argbToCss(border.subtle))
    put("--terminal-bg", argbToCss(terminal.bg))
    put("--toolbar-shadow", "0 2px 8px ${argbToCss(chrome.shadow)}")
}

/**
 * Converts a [ResolvedPalette] to a map of CSS custom property names to
 * CSS colour values.
 *
 * Property names follow the `--t-group-token` convention (e.g.
 * `--t-surface-base`, `--t-text-primary`).  Colours with alpha use
 * `rgba()` format; fully opaque colours use `#rrggbb`.
 *
 * @return map of CSS property name to CSS colour string
 * @see argbToCss
 */
fun ResolvedPalette.toCssVarMap(): Map<String, String> = buildMap {
    // Surface
    put("--t-surface-base", argbToCss(surface.base))
    put("--t-surface-raised", argbToCss(surface.raised))
    put("--t-surface-sunken", argbToCss(surface.sunken))
    put("--t-surface-overlay", argbToCss(surface.overlay))
    // Text
    put("--t-text-primary", argbToCss(text.primary))
    put("--t-text-secondary", argbToCss(text.secondary))
    put("--t-text-tertiary", argbToCss(text.tertiary))
    put("--t-text-disabled", argbToCss(text.disabled))
    put("--t-text-inverse", argbToCss(text.inverse))
    // Border
    put("--t-border-subtle", argbToCss(border.subtle))
    put("--t-border-default", argbToCss(border.default))
    put("--t-border-strong", argbToCss(border.strong))
    put("--t-border-focus", argbToCss(border.focus))
    put("--t-border-focusGlow", argbToCss(border.focusGlow))
    // Accent
    put("--t-accent-primary", argbToCss(accent.primary))
    put("--t-accent-primarySoft", argbToCss(accent.primarySoft))
    put("--t-accent-primaryGlow", argbToCss(accent.primaryGlow))
    put("--t-accent-onPrimary", argbToCss(accent.onPrimary))
    // Semantic
    put("--t-semantic-danger", argbToCss(semantic.danger))
    put("--t-semantic-warn", argbToCss(semantic.warn))
    put("--t-semantic-success", argbToCss(semantic.success))
    put("--t-semantic-info", argbToCss(semantic.info))
    // Terminal
    put("--t-terminal-bg", argbToCss(terminal.bg))
    put("--t-terminal-fg", argbToCss(terminal.fg))
    put("--t-terminal-cursor", argbToCss(terminal.cursor))
    put("--t-terminal-selection", argbToCss(terminal.selection))
    put("--t-terminal-selectionText", argbToCss(terminal.selectionText))
    // Chrome
    put("--t-chrome-titlebar", argbToCss(chrome.titlebar))
    put("--t-chrome-titleText", argbToCss(chrome.titleText))
    put("--t-chrome-border", argbToCss(chrome.border))
    put("--t-chrome-shadow", argbToCss(chrome.shadow))
    put("--t-chrome-closeDot", argbToCss(chrome.closeDot))
    put("--t-chrome-minDot", argbToCss(chrome.minDot))
    put("--t-chrome-maxDot", argbToCss(chrome.maxDot))
    // Sidebar
    put("--t-sidebar-bg", argbToCss(sidebar.bg))
    put("--t-sidebar-text", argbToCss(sidebar.text))
    put("--t-sidebar-textDim", argbToCss(sidebar.textDim))
    put("--t-sidebar-activeBg", argbToCss(sidebar.activeBg))
    put("--t-sidebar-activeText", argbToCss(sidebar.activeText))
    // Bottom bar (Claude usage footer)
    put("--t-bottomBar-bg", argbToCss(bottomBar.bg))
    put("--t-bottomBar-text", argbToCss(bottomBar.text))
    put("--t-bottomBar-textDim", argbToCss(bottomBar.textDim))
    put("--t-bottomBar-border", argbToCss(bottomBar.border))
    // Diff
    put("--t-diff-addBg", argbToCss(diff.addBg))
    put("--t-diff-addFg", argbToCss(diff.addFg))
    put("--t-diff-addGutter", argbToCss(diff.addGutter))
    put("--t-diff-removeBg", argbToCss(diff.removeBg))
    put("--t-diff-removeFg", argbToCss(diff.removeFg))
    put("--t-diff-removeGutter", argbToCss(diff.removeGutter))
    put("--t-diff-contextFg", argbToCss(diff.contextFg))
    // Syntax
    put("--t-syntax-keyword", argbToCss(syntax.keyword))
    put("--t-syntax-string", argbToCss(syntax.string))
    put("--t-syntax-number", argbToCss(syntax.number))
    put("--t-syntax-comment", argbToCss(syntax.comment))
    put("--t-syntax-function", argbToCss(syntax.function))
    put("--t-syntax-type", argbToCss(syntax.type))
    put("--t-syntax-operator", argbToCss(syntax.operator))
    put("--t-syntax-constant", argbToCss(syntax.constant))
}

/**
 * CSS custom property names used by the "active indicators" section.
 *
 * These properties are always set on `:root` so they are available in
 * every section container regardless of per-section scoping.
 */
private val activeAccentProps = listOf(
    "--t-active-accent",
    "--t-active-accentSoft",
    "--t-active-accentGlow",
    "--t-active-sidebarActiveBg",
    "--t-active-sidebarActiveText",
)

/**
 * Returns CSS custom properties derived from the "active indicators"
 * section theme, or an empty map if no override is set.
 *
 * The returned vars are set on `:root` by [applyAppearanceClass] so
 * the active-indicator CSS rules can reference them with a fallback
 * chain back to the per-section accent colour.
 *
 * @return map of CSS property name to CSS colour string, or empty
 * @see applyAppearanceClass
 */
fun activeAccentCssVars(): Map<String, String> {
    val state = appVm.stateFlow.value
    if (state.activeTheme == null) return emptyMap()
    val p = sectionPalette("active")
    return mapOf(
        "--t-active-accent" to argbToCss(p.accent.primary),
        "--t-active-accentSoft" to argbToCss(p.accent.primarySoft),
        "--t-active-accentGlow" to argbToCss(p.accent.primaryGlow),
        "--t-active-sidebarActiveBg" to argbToCss(p.sidebar.activeBg),
        "--t-active-sidebarActiveText" to argbToCss(p.sidebar.activeText),
    )
}

/**
 * Clears the `--t-active-*` CSS properties from an element.
 *
 * Called when the active-indicators section theme is cleared so the
 * CSS fallback chain reverts to each section's own accent colour.
 *
 * @param el the element to clear (typically `:root`)
 */
fun clearActiveAccentVars(el: org.w3c.dom.HTMLElement) {
    for (prop in activeAccentProps) el.style.removeProperty(prop)
}

/**
 * Resolve a scheme name against the user's custom schemes first, then the
 * built-in `recommendedColorSchemes` list. Returns `null` when the name is empty
 * or cannot be resolved (e.g. the underlying custom scheme was deleted).
 *
 * @param name scheme name to resolve, or `null`
 * @return the [ColorScheme], or `null` if not found
 */
internal fun resolvePaneScheme(name: String?): ColorScheme? {
    if (name.isNullOrEmpty()) return null
    val state = appVm.stateFlow.value
    return state.customSchemes[name]?.toColorScheme()
        ?: recommendedColorSchemes.firstOrNull { it.name == name }
}

/**
 * Build an xterm.js theme object from an explicit [palette] instead of the
 * globally-resolved one. Mirrors [buildXtermTheme] but takes the palette
 * as an argument so per-pane overrides can feed the pane's own scheme
 * colours into xterm without mutating global state.
 *
 * @param palette already-resolved palette to read `terminal.*`, `accent.*`
 *   from
 * @return a dynamic object suitable for `term.options.theme`
 * @see buildXtermTheme
 */
internal fun buildXtermThemeFromPalette(palette: ResolvedPalette): dynamic {
    val bg = argbToHex(palette.terminal.bg)
    val fg = argbToHex(palette.terminal.fg)
    val bgLight = isColorLight(palette.terminal.bg)
    val base = kotlin.js.json(
        "background" to bg,
        "foreground" to fg,
        "cursor" to argbToHex(palette.terminal.cursor),
        "cursorAccent" to argbToHex(palette.accent.onPrimary),
        "selectionBackground" to argbToCss(palette.terminal.selection),
    )
    if (bgLight) {
        base["white"] = "#3b3b3b"; base["brightWhite"] = "#5a5a5a"
        base["yellow"] = "#866a00"; base["brightYellow"] = "#9d7e00"
        base["green"] = "#116329"; base["brightGreen"] = "#1a7f37"
        base["cyan"] = "#0b6e6e"; base["brightCyan"] = "#0f8585"
        base["blue"] = "#0550ae"; base["brightBlue"] = "#0969da"
        base["magenta"] = "#6e3996"; base["brightMagenta"] = "#8250df"
        base["red"] = "#b3261e"; base["brightRed"] = "#cf222e"
    } else {
        base["black"] = "#5a5a5a"; base["brightBlack"] = "#8a8a8a"
    }
    return base
}

/**
 * Apply (or clear) a per-pane colour-scheme override by scoping CSS
 * variables on the pane's [.floating-pane] wrapper, [.terminal-cell] root,
 * and content container. Child elements inherit the overridden `--t-*` vars
 * via the CSS cascade, so the outer pane frame (border + background), the
 * pane chrome (header, title), and the content area all pick up the chosen
 * palette.
 *
 * The `.floating-pane` wrapper is the **parent** of `.terminal-cell`, and
 * CSS custom properties inherit downward only — setting vars on the cell
 * does NOT reach the wrapper's own `border`/`background` rules. The wrapper
 * must be targeted explicitly; otherwise the outer 1px pane frame keeps
 * reading the global theme even when a per-pane override is active. In
 * popout mode the cell has no `.floating-pane` parent (see [PopoutMode]),
 * so the wrapper lookup is optional.
 *
 * When a live terminal is hosted in the pane, its xterm.js theme is also
 * rebuilt from the override palette — xterm reads `term.options.theme`
 * directly, not CSS variables, so it needs its own update.
 *
 * @param cell   the pane's `.terminal-cell` element (must already be
 *   attached to the DOM for the wrapper lookup to succeed)
 * @param name   scheme name to apply, or `null` to clear the override
 * @see applyAppearanceClass
 * @see buildXtermThemeFromPalette
 */
internal fun applyPaneSchemeOverride(cell: org.w3c.dom.HTMLElement, name: String?) {
    val resolved = resolvePaneScheme(name)
    val state = appVm.stateFlow.value
    val isDark = !isLightActive(state.appearance)

    // Clear any previously-applied inline vars. The set of keys is computed
    // against the current global palette — the key space is stable across
    // all themes, so this reliably removes anything a prior call wrote.
    val templateKeys = currentResolvedPalette().let { it.toCssVarMap().keys + it.toCssAliasMap().keys }

    // `.floating-pane` wrapper: the ancestor that paints the outer pane
    // border and background. Absent in popout mode.
    val wrapper = (cell.parentElement as? org.w3c.dom.HTMLElement)
        ?.takeIf { it.classList.contains("floating-pane") }

    // Also target the content container inside the pane. [applyAppearanceClass]
    // sets inline vars directly on these children (see section loop: `.terminal`,
    // `.md-view`, `.git-view`) to block the global windows override from
    // bleeding through — but those inline vars on the child would ALSO block
    // the per-pane cascade from .terminal-cell, so the content area wouldn't
    // recolor. We override them on the child too.
    val contentKind = cell.getAttribute("data-content-kind")
    val contentSelector = when (contentKind) {
        "terminal" -> ".terminal"
        "fileBrowser" -> ".md-view"
        "git" -> ".git-view"
        else -> null
    }
    val contentEl = contentSelector?.let { cell.querySelector(it) as? org.w3c.dom.HTMLElement }

    for (prop in templateKeys) {
        wrapper?.style?.removeProperty(prop)
        cell.style.removeProperty(prop)
        contentEl?.style?.removeProperty(prop)
    }

    if (resolved != null) {
        val palette = resolved.resolve(isDark)
        val cssVars = palette.toCssVarMap() + palette.toCssAliasMap()
        for ((prop, value) in cssVars) {
            wrapper?.style?.setProperty(prop, value)
            cell.style.setProperty(prop, value)
            contentEl?.style?.setProperty(prop, value)
        }
        // xterm theme — only relevant for terminal panes; [terminals] is a
        // map of live xterm instances keyed by paneId.
        val paneId = cell.getAttribute("data-pane")
        if (paneId != null) {
            terminals[paneId]?.let { entry ->
                entry.term.options.theme = buildXtermThemeFromPalette(palette)
            }
        }
    } else {
        // Reverting: if this pane hosts a terminal, snap xterm back to the
        // global theme so the selection/cursor/ANSI tints recover. The
        // content-container inline vars get reasserted by the next
        // [applyAppearanceClass] pass (which [applyAll] schedules after a
        // server config push), so we don't try to restore them here.
        val paneId = cell.getAttribute("data-pane")
        if (paneId != null) {
            terminals[paneId]?.let { entry ->
                entry.term.options.theme = buildXtermTheme()
            }
        }
    }
}
