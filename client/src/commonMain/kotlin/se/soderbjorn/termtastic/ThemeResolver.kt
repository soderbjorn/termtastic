/**
 * Deterministic palette derivation for the Termtastic semantic theme system.
 *
 * Given a [TerminalTheme]'s seed fg/bg colours and a dark/light mode flag,
 * [resolve] computes the full [ResolvedPalette] (~60 tokens) using colour
 * math: surface shifts, text-hierarchy mixing, border-alpha tinting, and
 * neutral syntax/diff fallbacks.  Designer themes can override any subset
 * of tokens via [TerminalTheme.overrides].
 *
 * The derivation logic mirrors the JavaScript prototype in
 * `Theme System.html`'s `derive(pal, mode)` function exactly.
 *
 * @see ResolvedPalette
 * @see TerminalTheme
 * @see ColorMath
 */
package se.soderbjorn.termtastic

// ── Constants used by the derivation ───────────────────────────────────

/** Pure black as ARGB. */
private const val BLACK = 0xFF000000L

// Minimum WCAG contrast ratios for derived text-hierarchy tokens when
// they are computed (not overridden).  A naive `mixColors(fg, bg, r)`
// dims text toward bg at a fixed ratio, which looks right on neutral
// high-luminance fg (Nord, Gruvbox, GitHub) but produces illegible
// results on saturated low-luminance fg (Vapor pink, Plasma, Tron).
// These floors keep the naive mix when it already reads well and walk
// the mix back toward fg only when contrast against bg would fall below
// the tier's floor.  Themes can still dictate exact values via
// `text.secondary.*` / `text.tertiary.*` / `text.disabled.*` /
// `sidebar.textDim.*` overrides — the floors only govern derivation.
/** Minimum WCAG contrast for derived `text.secondary` against the scheme bg. */
private const val TEXT_SECONDARY_MIN_CONTRAST = 5.0
/** Minimum WCAG contrast for derived `text.tertiary` (and `sidebar.textDim`). */
private const val TEXT_TERTIARY_MIN_CONTRAST = 3.5
/** Minimum WCAG contrast for derived `text.disabled`. */
private const val TEXT_DISABLED_MIN_CONTRAST = 2.5

// ── Override key helpers ───────────────────────────────────────────────

/**
 * Looks up an override value from [TerminalTheme.overrides] for the given
 * token [key] and [isDark] mode.
 *
 * Override keys follow the pattern `"group.token.dark"` or
 * `"group.token.light"`, e.g. `"syntax.keyword.dark"`.
 *
 * @param overrides the theme's override map, or null
 * @param key       the dot-separated token name, e.g. `"syntax.keyword"`
 * @param isDark    whether dark mode is active
 * @return the override ARGB value, or null if not overridden
 */
private fun overrideFor(overrides: Map<String, Long>?, key: String, isDark: Boolean): Long? {
    if (overrides == null) return null
    val suffix = if (isDark) "dark" else "light"
    return overrides["$key.$suffix"]
}

// ── Main derivation function ───────────────────────────────────────────

/**
 * Derives a complete [ResolvedPalette] from this theme's seed colours.
 *
 * Most tokens are computed deterministically from the fg/bg pair via mixing
 * and alpha tinting.  If [overrides] contains an entry for a token (keyed as
 * `"group.token.dark"` / `"group.token.light"`), the override value is used
 * instead of the computed one.
 *
 * Called by every platform renderer to obtain the full semantic palette for
 * the currently active theme and appearance mode.
 *
 * @param isDark `true` for dark mode, `false` for light mode
 * @return the fully resolved semantic palette
 * @see TerminalTheme.resolve
 */
fun TerminalTheme.resolve(isDark: Boolean): ResolvedPalette {
    val ovr = overrides
    val bg = hexToArgb(if (isDark) darkBg else lightBg)
    val fg = hexToArgb(if (isDark) darkFg else lightFg)

    // ── Surface ────────────────────────────────────────────────────
    val surfaceBase = overrideFor(ovr, "surface.base", isDark) ?: bg
    val surfaceRaised = overrideFor(ovr, "surface.raised", isDark)
        ?: if (isDark) mixColors(bg, fg, 0.06) else mixColors(bg, BLACK, 0.04)
    val surfaceSunken = overrideFor(ovr, "surface.sunken", isDark)
        ?: if (isDark) mixColors(bg, BLACK, 0.35) else mixColors(bg, BLACK, 0.02)
    val surfaceOverlay = overrideFor(ovr, "surface.overlay", isDark)
        ?: if (isDark) mixColors(bg, fg, 0.10) else mixColors(bg, BLACK, 0.06)

    // ── Text hierarchy ─────────────────────────────────────────────
    val textPrimary = overrideFor(ovr, "text.primary", isDark) ?: fg
    val textSecondary = overrideFor(ovr, "text.secondary", isDark)
        ?: mixWithContrastFloor(fg, bg, 0.30, TEXT_SECONDARY_MIN_CONTRAST)
    val textTertiary = overrideFor(ovr, "text.tertiary", isDark)
        ?: mixWithContrastFloor(fg, bg, 0.55, TEXT_TERTIARY_MIN_CONTRAST)
    val textDisabled = overrideFor(ovr, "text.disabled", isDark)
        ?: mixWithContrastFloor(fg, bg, 0.72, TEXT_DISABLED_MIN_CONTRAST)
    val textInverse = overrideFor(ovr, "text.inverse", isDark) ?: bg

    // ── Borders ────────────────────────────────────────────────────
    val borderSubtle = overrideFor(ovr, "border.subtle", isDark)
        ?: withAlpha(fg, if (isDark) 0.07 else 0.09)
    val borderDefault = overrideFor(ovr, "border.default", isDark)
        ?: withAlpha(fg, if (isDark) 0.12 else 0.14)
    val borderStrong = overrideFor(ovr, "border.strong", isDark)
        ?: withAlpha(fg, if (isDark) 0.22 else 0.26)
    val borderFocus = overrideFor(ovr, "border.focus", isDark) ?: fg
    val borderFocusGlow = overrideFor(ovr, "border.focusGlow", isDark)
        ?: withAlpha(fg, 0.30)

    // ── Accent ─────────────────────────────────────────────────────
    val accentPrimary = overrideFor(ovr, "accent.primary", isDark) ?: fg
    val accentPrimarySoft = overrideFor(ovr, "accent.primarySoft", isDark)
        ?: withAlpha(fg, 0.14)
    val accentPrimaryGlow = overrideFor(ovr, "accent.primaryGlow", isDark)
        ?: withAlpha(fg, 0.35)
    val accentOnPrimary = overrideFor(ovr, "accent.onPrimary", isDark) ?: bg

    // ── Semantic (fixed per mode) ──────────────────────────────────
    val semanticDanger = overrideFor(ovr, "semantic.danger", isDark)
        ?: if (isDark) 0xFFE28A84L else 0xFFB33A36L
    val semanticWarn = overrideFor(ovr, "semantic.warn", isDark)
        ?: if (isDark) 0xFFE6C380L else 0xFFA8771AL
    val semanticSuccess = overrideFor(ovr, "semantic.success", isDark)
        ?: if (isDark) 0xFF8FD39AL else 0xFF2F7D49L
    val semanticInfo = overrideFor(ovr, "semantic.info", isDark)
        ?: if (isDark) 0xFF84D2C7L else 0xFF1A7A75L

    // ── Terminal ───────────────────────────────────────────────────
    val terminalBg = overrideFor(ovr, "terminal.bg", isDark) ?: bg
    val terminalFg = overrideFor(ovr, "terminal.fg", isDark) ?: fg
    val terminalCursor = overrideFor(ovr, "terminal.cursor", isDark) ?: fg
    val terminalSelection = overrideFor(ovr, "terminal.selection", isDark)
        ?: withAlpha(fg, 0.22)
    val terminalSelectionText = overrideFor(ovr, "terminal.selectionText", isDark) ?: bg

    // ── Chrome ─────────────────────────────────────────────────────
    val chromeTitlebar = overrideFor(ovr, "chrome.titlebar", isDark)
        ?: if (isDark) mixColors(bg, fg, 0.12) else mixColors(bg, BLACK, 0.06)
    val chromeTitleText = overrideFor(ovr, "chrome.titleText", isDark)
        ?: mixColors(fg, bg, 0.45)
    val chromeBorder = overrideFor(ovr, "chrome.border", isDark) ?: borderSubtle
    val chromeShadow = overrideFor(ovr, "chrome.shadow", isDark)
        ?: if (isDark) withAlpha(BLACK, 0.55) else withAlpha(0xFF141E19L, 0.15)
    val chromeCloseDot = overrideFor(ovr, "chrome.closeDot", isDark) ?: 0xFFFF5F57L
    val chromeMinDot = overrideFor(ovr, "chrome.minDot", isDark) ?: 0xFFFEBC2EL
    val chromeMaxDot = overrideFor(ovr, "chrome.maxDot", isDark) ?: 0xFF28C840L

    // ── Sidebar ────────────────────────────────────────────────────
    val sidebarBg = overrideFor(ovr, "sidebar.bg", isDark)
        ?: if (isDark) mixColors(bg, BLACK, 0.20) else mixColors(bg, BLACK, 0.03)
    val sidebarText = overrideFor(ovr, "sidebar.text", isDark)
        ?: mixColors(fg, bg, 0.15)
    val sidebarTextDim = overrideFor(ovr, "sidebar.textDim", isDark)
        ?: mixWithContrastFloor(fg, bg, 0.55, TEXT_TERTIARY_MIN_CONTRAST)
    val sidebarActiveBg = overrideFor(ovr, "sidebar.activeBg", isDark)
        ?: withAlpha(fg, if (isDark) 0.10 else 0.08)
    val sidebarActiveText = overrideFor(ovr, "sidebar.activeText", isDark) ?: fg

    // ── Diff ───────────────────────────────────────────────────────
    val diffAddBg = overrideFor(ovr, "diff.addBg", isDark)
        ?: withAlpha(semanticSuccess, if (isDark) 0.12 else 0.10)
    val diffAddFg = overrideFor(ovr, "diff.addFg", isDark)
        ?: if (isDark) mixColors(semanticSuccess, fg, 0.35) else mixColors(semanticSuccess, BLACK, 0.25)
    val diffAddGutter = overrideFor(ovr, "diff.addGutter", isDark) ?: semanticSuccess
    val diffRemoveBg = overrideFor(ovr, "diff.removeBg", isDark)
        ?: withAlpha(semanticDanger, if (isDark) 0.12 else 0.10)
    val diffRemoveFg = overrideFor(ovr, "diff.removeFg", isDark)
        ?: if (isDark) mixColors(semanticDanger, fg, 0.35) else mixColors(semanticDanger, BLACK, 0.25)
    val diffRemoveGutter = overrideFor(ovr, "diff.removeGutter", isDark) ?: semanticDanger
    val diffContextFg = overrideFor(ovr, "diff.contextFg", isDark) ?: textSecondary

    // ── Syntax ─────────────────────────────────────────────────────
    val syntaxKeyword = overrideFor(ovr, "syntax.keyword", isDark) ?: fg
    val syntaxString = overrideFor(ovr, "syntax.string", isDark) ?: semanticWarn
    val syntaxNumber = overrideFor(ovr, "syntax.number", isDark) ?: semanticInfo
    val syntaxComment = overrideFor(ovr, "syntax.comment", isDark) ?: textTertiary
    val syntaxFunction = overrideFor(ovr, "syntax.function", isDark)
        ?: mixColors(fg, semanticSuccess, 0.4)
    val syntaxType = overrideFor(ovr, "syntax.type", isDark) ?: semanticInfo
    val syntaxOperator = overrideFor(ovr, "syntax.operator", isDark) ?: textSecondary
    val syntaxConstant = overrideFor(ovr, "syntax.constant", isDark) ?: semanticDanger

    return ResolvedPalette(
        surface = ResolvedPalette.Surface(
            base = surfaceBase,
            raised = surfaceRaised,
            sunken = surfaceSunken,
            overlay = surfaceOverlay,
        ),
        text = ResolvedPalette.Text(
            primary = textPrimary,
            secondary = textSecondary,
            tertiary = textTertiary,
            disabled = textDisabled,
            inverse = textInverse,
        ),
        border = ResolvedPalette.Border(
            subtle = borderSubtle,
            default = borderDefault,
            strong = borderStrong,
            focus = borderFocus,
            focusGlow = borderFocusGlow,
        ),
        accent = ResolvedPalette.Accent(
            primary = accentPrimary,
            primarySoft = accentPrimarySoft,
            primaryGlow = accentPrimaryGlow,
            onPrimary = accentOnPrimary,
        ),
        semantic = ResolvedPalette.Semantic(
            danger = semanticDanger,
            warn = semanticWarn,
            success = semanticSuccess,
            info = semanticInfo,
        ),
        terminal = ResolvedPalette.Terminal(
            bg = terminalBg,
            fg = terminalFg,
            cursor = terminalCursor,
            selection = terminalSelection,
            selectionText = terminalSelectionText,
        ),
        chrome = ResolvedPalette.Chrome(
            titlebar = chromeTitlebar,
            titleText = chromeTitleText,
            border = chromeBorder,
            shadow = chromeShadow,
            closeDot = chromeCloseDot,
            minDot = chromeMinDot,
            maxDot = chromeMaxDot,
        ),
        sidebar = ResolvedPalette.Sidebar(
            bg = sidebarBg,
            text = sidebarText,
            textDim = sidebarTextDim,
            activeBg = sidebarActiveBg,
            activeText = sidebarActiveText,
        ),
        diff = ResolvedPalette.Diff(
            addBg = diffAddBg,
            addFg = diffAddFg,
            addGutter = diffAddGutter,
            removeBg = diffRemoveBg,
            removeFg = diffRemoveFg,
            removeGutter = diffRemoveGutter,
            contextFg = diffContextFg,
        ),
        syntax = ResolvedPalette.Syntax(
            keyword = syntaxKeyword,
            string = syntaxString,
            number = syntaxNumber,
            comment = syntaxComment,
            function = syntaxFunction,
            type = syntaxType,
            operator = syntaxOperator,
            constant = syntaxConstant,
        ),
    )
}

/**
 * Convenience overload that resolves the dark/light flag from the user's
 * [Appearance] preference and the host platform's system setting.
 *
 * @param appearance   the user's selected appearance mode
 * @param systemIsDark whether the host OS is currently in dark mode
 * @return the fully resolved semantic palette
 */
fun TerminalTheme.resolve(appearance: Appearance, systemIsDark: Boolean): ResolvedPalette {
    val isDark = when (appearance) {
        Appearance.Dark -> true
        Appearance.Light -> false
        Appearance.Auto -> systemIsDark
    }
    return resolve(isDark)
}
