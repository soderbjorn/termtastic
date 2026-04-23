/**
 * Terminal colour theme definitions for the Termtastic terminal emulator.
 *
 * Each [TerminalTheme] carries a dark-mode and light-mode foreground/background
 * colour pair. The [recommendedThemes] list is the canonical palette surfaced in
 * every platform's theme picker; it is kept in sync with the HTML colour-picker
 * tool at `tools/neon-green-picker.html`.
 *
 * @see se.soderbjorn.termtastic.client.UiSettings
 * @see se.soderbjorn.termtastic.client.effectiveColors
 */
package se.soderbjorn.termtastic

/**
 * A terminal colour theme with separate foreground and background colours for
 * dark and light appearance modes.
 *
 * @property name   Human-readable theme name shown in the picker UI.
 * @property darkFg Hex foreground colour used in dark mode (e.g. `"#33ff66"`).
 * @property lightFg Hex foreground colour used in light mode.
 * @property darkBg Hex background colour used in dark mode. Defaults to pure
 *   black; designer themes like Solarized override this with a tinted colour.
 * @property lightBg Hex background colour used in light mode. Defaults to pure
 *   white; designer themes override this with their canonical light background.
 */
data class TerminalTheme(
    val name: String,
    val darkFg: String,
    val lightFg: String,
    val darkBg: String = "#000000",
    val lightBg: String = "#ffffff",
    /**
     * Optional hand-tuned colour overrides for semantic tokens that should
     * not be derived from the fg/bg seed.
     *
     * Keys follow the pattern `"group.token.mode"`, e.g.
     * `"syntax.keyword.dark"`, `"sidebar.bg.light"`.  Values are ARGB
     * [Long] values.  The [resolve] function checks this map before falling
     * back to the deterministic derivation.
     *
     * Most themes leave this `null`; only designer palettes (Solarized,
     * Tokyo Night, etc.) specify overrides for their signature syntax colours
     * or other tokens where the derivation would look flat.
     */
    val overrides: Map<String, Long>? = null,
)

/**
 * Curated list of terminal themes available in the theme picker.
 *
 * The first group uses the default pure-black/white backgrounds; the second
 * group ("tinted-background themes") specifies custom background colours for
 * both modes, giving the picker visual variety (cream Solarized Light, deep
 * navy Tokyo Night, etc.).
 *
 * Kept in sync with the "Recommended" tab in `tools/neon-green-picker.html`.
 */
// ── Syntax override maps for designer themes ──────────────────────────

/**
 * Syntax colour overrides for the Tron theme. The green-accent base would
 * produce a monochromatic syntax palette without these hand-tuned values
 * from the design spec.
 */
private val tronOverrides: Map<String, Long> = mapOf(
    "syntax.keyword.dark"  to 0xFF7EE2C1L, "syntax.keyword.light"  to 0xFF00795CL,
    "syntax.string.dark"   to 0xFFE6C380L, "syntax.string.light"   to 0xFFA8771AL,
    "syntax.number.dark"   to 0xFF84D2C7L, "syntax.number.light"   to 0xFF1A7A75L,
    "syntax.comment.dark"  to 0xFF5E7570L, "syntax.comment.light"  to 0xFF8AA29AL,
    "syntax.function.dark" to 0xFF9EE8C4L, "syntax.function.light" to 0xFF1A7A4AL,
    "syntax.type.dark"     to 0xFFE8A5C1L, "syntax.type.light"     to 0xFFB64A78L,
    "syntax.operator.dark" to 0xFFC9D2CDL, "syntax.operator.light" to 0xFF3D4945L,
    "syntax.constant.dark" to 0xFFFF9F6EL, "syntax.constant.light" to 0xFFB85C1CL,
)

/**
 * Syntax colour overrides for the Solarized theme, using the canonical
 * Solarized accent palette (same values in both modes for most tokens).
 */
private val solarizedOverrides: Map<String, Long> = mapOf(
    "syntax.keyword.dark"  to 0xFF859900L, "syntax.keyword.light"  to 0xFF859900L,
    "syntax.string.dark"   to 0xFF2AA198L, "syntax.string.light"   to 0xFF2AA198L,
    "syntax.number.dark"   to 0xFFD33682L, "syntax.number.light"   to 0xFFD33682L,
    "syntax.comment.dark"  to 0xFF586E75L, "syntax.comment.light"  to 0xFF93A1A1L,
    "syntax.function.dark" to 0xFF268BD2L, "syntax.function.light" to 0xFF268BD2L,
    "syntax.type.dark"     to 0xFFB58900L, "syntax.type.light"     to 0xFFB58900L,
    "syntax.operator.dark" to 0xFF93A1A1L, "syntax.operator.light" to 0xFF657B83L,
    "syntax.constant.dark" to 0xFFCB4B16L, "syntax.constant.light" to 0xFFCB4B16L,
)

/**
 * Syntax colour overrides for the Tokyo Night theme, using the official
 * Storm palette values.
 */
private val tokyoNightOverrides: Map<String, Long> = mapOf(
    "syntax.keyword.dark"  to 0xFFBB9AF7L, "syntax.keyword.light"  to 0xFF5A3E8EL,
    "syntax.string.dark"   to 0xFF9ECE6AL, "syntax.string.light"   to 0xFF485E30L,
    "syntax.number.dark"   to 0xFFFF9E64L, "syntax.number.light"   to 0xFFA05A20L,
    "syntax.comment.dark"  to 0xFF565F89L, "syntax.comment.light"  to 0xFF8A91A8L,
    "syntax.function.dark" to 0xFF7AA2F7L, "syntax.function.light" to 0xFF2E5CA8L,
    "syntax.type.dark"     to 0xFF7DCFFFL, "syntax.type.light"     to 0xFF1F6A94L,
    "syntax.operator.dark" to 0xFF89DDFFL, "syntax.operator.light" to 0xFF1F6A94L,
    "syntax.constant.dark" to 0xFFF7768EL, "syntax.constant.light" to 0xFF9A2A44L,
)

/**
 * Syntax colour overrides for the Rose Pine theme, using the official
 * dawn/moon palette values.
 */
private val rosePineOverrides: Map<String, Long> = mapOf(
    "syntax.keyword.dark"  to 0xFFC4A7E7L, "syntax.keyword.light"  to 0xFF6E5599L,
    "syntax.string.dark"   to 0xFFF6C177L, "syntax.string.light"   to 0xFFA87B2AL,
    "syntax.number.dark"   to 0xFFEB6F92L, "syntax.number.light"   to 0xFFA83352L,
    "syntax.comment.dark"  to 0xFF6E6A86L, "syntax.comment.light"  to 0xFF908CAAL,
    "syntax.function.dark" to 0xFF9CCFD8L, "syntax.function.light" to 0xFF2A7B90L,
    "syntax.type.dark"     to 0xFFEBBCBAL, "syntax.type.light"     to 0xFFA85A58L,
    "syntax.operator.dark" to 0xFF908CAAL, "syntax.operator.light" to 0xFF575279L,
    "syntax.constant.dark" to 0xFFEB6F92L, "syntax.constant.light" to 0xFFA83352L,
)

/**
 * Semantic-token overrides for the Verdant theme — a minimalist
 * terminal-IDE palette with a near-black green-tinted background, soft
 * off-white body text, and punchy mint-green accents reserved for active
 * items, focus rings, prompts, and git-add highlights.
 *
 * The seed fg is intentionally low-saturation so primary text reads as
 * near-white; the accent, sidebar-active, focus, cursor, success, and
 * diff-add tokens are overridden to the saturated mint green so the two
 * tiers stay visually distinct.
 */
private val verdantOverrides: Map<String, Long> = mapOf(
    // Surface — light mode is a subtle layered-white look from the design:
    // the window chrome/sidebar is a light warm-gray, and panes (including
    // the terminal) sit on top as near-white panels. The deterministic
    // derivation would darken raised surfaces below bg, which is the wrong
    // direction here.
    "surface.raised.light"     to 0xFFF8F8F5L,
    "surface.sunken.light"     to 0xFFDFE0DCL,
    "surface.overlay.light"    to 0xFFEDEEEAL,

    // Terminal — in light mode, xterm.js fills the whole pane with
    // terminal.bg, so it needs to match the raised surface (near-white)
    // rather than the chrome base, otherwise each terminal pane reads as
    // a dirty gray block against the otherwise-white panes.
    "terminal.bg.light"        to 0xFFF8F8F5L,

    // Bottom bar — a signature of the design: the footer strip stays dark
    // even when the rest of the app is in light mode. Dark mode keeps
    // defaults (= sidebar.bg / text.secondary / border.subtle).
    "bottomBar.bg.light"       to 0xFF0B0F0CL,
    "bottomBar.text.light"     to 0xFFE2E2DEL,
    "bottomBar.textDim.light"  to 0xFF9AA29CL,
    "bottomBar.border.light"   to 0xFF1A1E1BL,

    // Accent — bright mint used for prompts, active indicators, focus
    "accent.primary.dark"      to 0xFF8DE5A8L, "accent.primary.light"      to 0xFF2E7D4AL,
    "accent.primarySoft.dark"  to 0x288DE5A8L, "accent.primarySoft.light"  to 0x242E7D4AL,
    "accent.primaryGlow.dark"  to 0x508DE5A8L, "accent.primaryGlow.light"  to 0x402E7D4AL,

    // Sidebar — green for the currently-selected row
    "sidebar.activeText.dark"  to 0xFF8DE5A8L, "sidebar.activeText.light"  to 0xFF2E7D4AL,
    "sidebar.activeBg.dark"    to 0x228DE5A8L, "sidebar.activeBg.light"    to 0x1A2E7D4AL,

    // Focus ring + terminal cursor
    "border.focus.dark"        to 0xFF8DE5A8L, "border.focus.light"        to 0xFF2E7D4AL,
    "border.focusGlow.dark"    to 0x4C8DE5A8L, "border.focusGlow.light"    to 0x402E7D4AL,
    "terminal.cursor.dark"     to 0xFF8DE5A8L, "terminal.cursor.light"     to 0xFF2E7D4AL,

    // Semantic — tuned muted-but-legible status palette
    "semantic.success.dark"    to 0xFF8DE5A8L, "semantic.success.light"    to 0xFF2E7D4AL,
    "semantic.danger.dark"     to 0xFFE8857AL, "semantic.danger.light"     to 0xFFB84A3AL,
    "semantic.warn.dark"       to 0xFFE6C380L, "semantic.warn.light"       to 0xFFA8771AL,
    "semantic.info.dark"       to 0xFF84D2C7L, "semantic.info.light"       to 0xFF1A7A75L,

    // Diff — subtle tinted backgrounds, saturated gutter + fg
    "diff.addBg.dark"          to 0x228DE5A8L, "diff.addBg.light"          to 0x1A2E7D4AL,
    "diff.addFg.dark"          to 0xFF8DE5A8L, "diff.addFg.light"          to 0xFF2E7D4AL,
    "diff.addGutter.dark"      to 0xFF8DE5A8L, "diff.addGutter.light"      to 0xFF2E7D4AL,
    "diff.removeBg.dark"       to 0x22E8857AL, "diff.removeBg.light"       to 0x1AB84A3AL,
    "diff.removeFg.dark"       to 0xFFE8857AL, "diff.removeFg.light"       to 0xFFB84A3AL,
    "diff.removeGutter.dark"   to 0xFFE8857AL, "diff.removeGutter.light"   to 0xFFB84A3AL,

    // Syntax — calm terminal palette with mint keywords and coral constants
    "syntax.keyword.dark"      to 0xFF8DE5A8L, "syntax.keyword.light"      to 0xFF2E7D4AL,
    "syntax.string.dark"       to 0xFFE6C380L, "syntax.string.light"       to 0xFFA8771AL,
    "syntax.number.dark"       to 0xFFE8A5C1L, "syntax.number.light"       to 0xFFB64A78L,
    "syntax.comment.dark"      to 0xFF5E6D65L, "syntax.comment.light"      to 0xFF8A968EL,
    "syntax.function.dark"     to 0xFF9EE8C4L, "syntax.function.light"     to 0xFF1A7A4AL,
    "syntax.type.dark"         to 0xFF84D2C7L, "syntax.type.light"         to 0xFF1A7A75L,
    "syntax.operator.dark"     to 0xFFC9D2CDL, "syntax.operator.light"     to 0xFF3D4945L,
    "syntax.constant.dark"     to 0xFFE8857AL, "syntax.constant.light"     to 0xFFB84A3AL,
)

/**
 * Syntax colour overrides for the Cyberpunk theme — neon hot-pink and
 * electric cyan on deep violet-black, with bright green functions and
 * amber numbers. Inspired by Miami-vice / Blade Runner signage.
 */
private val cyberpunkOverrides: Map<String, Long> = mapOf(
    "accent.primary.dark"      to 0xFFFF71CEL, "accent.primary.light"      to 0xFFC01080L,
    "accent.primarySoft.dark"  to 0x28FF71CEL, "accent.primarySoft.light"  to 0x24C01080L,
    "accent.primaryGlow.dark"  to 0x50FF71CEL, "accent.primaryGlow.light"  to 0x40C01080L,
    "sidebar.activeText.dark"  to 0xFFFF71CEL, "sidebar.activeText.light"  to 0xFFC01080L,
    "sidebar.activeBg.dark"    to 0x22FF71CEL, "sidebar.activeBg.light"    to 0x1AC01080L,
    "border.focus.dark"        to 0xFFFF71CEL, "border.focus.light"        to 0xFFC01080L,
    "border.focusGlow.dark"    to 0x4CFF71CEL, "border.focusGlow.light"    to 0x40C01080L,
    "terminal.cursor.dark"     to 0xFFFF71CEL, "terminal.cursor.light"     to 0xFFC01080L,
    "syntax.keyword.dark"      to 0xFFFF71CEL, "syntax.keyword.light"      to 0xFFC01080L,
    "syntax.string.dark"       to 0xFF01CDFEL, "syntax.string.light"       to 0xFF0077B3L,
    "syntax.function.dark"     to 0xFF05FFA1L, "syntax.function.light"     to 0xFF008851L,
    "syntax.number.dark"       to 0xFFFFFB96L, "syntax.number.light"       to 0xFFB88000L,
    "syntax.type.dark"         to 0xFFB967FFL, "syntax.type.light"         to 0xFF6A0DADL,
    "syntax.constant.dark"     to 0xFFFF8DCEL, "syntax.constant.light"     to 0xFFC71585L,
    "syntax.comment.dark"      to 0xFF6B5B8EL, "syntax.comment.light"      to 0xFFAAAACCL,
    "syntax.operator.dark"     to 0xFFE0B8FFL, "syntax.operator.light"     to 0xFF5A1F80L,
)

/**
 * Syntax colour overrides for the Aurora theme — aurora-green, violet and
 * pink ribbons over deep night-sky blue. Tuned so each syntax token hits a
 * different band of the aurora rather than flattening to a single hue.
 */
private val auroraOverrides: Map<String, Long> = mapOf(
    "accent.primary.dark"      to 0xFF6EE7B7L, "accent.primary.light"      to 0xFF047857L,
    "accent.primarySoft.dark"  to 0x286EE7B7L, "accent.primarySoft.light"  to 0x24047857L,
    "accent.primaryGlow.dark"  to 0x506EE7B7L, "accent.primaryGlow.light"  to 0x40047857L,
    "sidebar.activeText.dark"  to 0xFF6EE7B7L, "sidebar.activeText.light"  to 0xFF047857L,
    "sidebar.activeBg.dark"    to 0x226EE7B7L, "sidebar.activeBg.light"    to 0x1A047857L,
    "border.focus.dark"        to 0xFF6EE7B7L, "border.focus.light"        to 0xFF047857L,
    "border.focusGlow.dark"    to 0x4C6EE7B7L, "border.focusGlow.light"    to 0x40047857L,
    "terminal.cursor.dark"     to 0xFF6EE7B7L, "terminal.cursor.light"     to 0xFF047857L,
    "syntax.keyword.dark"      to 0xFFA78BFAL, "syntax.keyword.light"      to 0xFF6D28D9L,
    "syntax.string.dark"       to 0xFF6EE7B7L, "syntax.string.light"       to 0xFF047857L,
    "syntax.function.dark"     to 0xFF60A5FAL, "syntax.function.light"     to 0xFF1D4ED8L,
    "syntax.number.dark"       to 0xFFF9A8D4L, "syntax.number.light"       to 0xFFBE185DL,
    "syntax.type.dark"         to 0xFF7DD3FCL, "syntax.type.light"         to 0xFF0369A1L,
    "syntax.constant.dark"     to 0xFFFDE68AL, "syntax.constant.light"     to 0xFF92400EL,
    "syntax.comment.dark"      to 0xFF4C5E7AL, "syntax.comment.light"      to 0xFF94A3B8L,
    "syntax.operator.dark"     to 0xFFC5E4FFL, "syntax.operator.light"     to 0xFF334155L,
)

/**
 * Syntax colour overrides for the Nebula theme — cosmic violets, rose-dust
 * pinks and cyan star-points. The accent tiers walk through the visible
 * nebula gases so adjacent tokens never merge.
 */
private val nebulaOverrides: Map<String, Long> = mapOf(
    "accent.primary.dark"      to 0xFFD8B4FEL, "accent.primary.light"      to 0xFF7E22CEL,
    "accent.primarySoft.dark"  to 0x28D8B4FEL, "accent.primarySoft.light"  to 0x247E22CEL,
    "accent.primaryGlow.dark"  to 0x50D8B4FEL, "accent.primaryGlow.light"  to 0x407E22CEL,
    "sidebar.activeText.dark"  to 0xFFD8B4FEL, "sidebar.activeText.light"  to 0xFF7E22CEL,
    "sidebar.activeBg.dark"    to 0x22D8B4FEL, "sidebar.activeBg.light"    to 0x1A7E22CEL,
    "border.focus.dark"        to 0xFFD8B4FEL, "border.focus.light"        to 0xFF7E22CEL,
    "border.focusGlow.dark"    to 0x4CD8B4FEL, "border.focusGlow.light"    to 0x407E22CEL,
    "terminal.cursor.dark"     to 0xFFD8B4FEL, "terminal.cursor.light"     to 0xFF7E22CEL,
    "syntax.keyword.dark"      to 0xFFD8B4FEL, "syntax.keyword.light"      to 0xFF7E22CEL,
    "syntax.string.dark"       to 0xFFFBCFE8L, "syntax.string.light"       to 0xFFBE185DL,
    "syntax.function.dark"     to 0xFF67E8F9L, "syntax.function.light"     to 0xFF0E7490L,
    "syntax.number.dark"       to 0xFFFDE68AL, "syntax.number.light"       to 0xFFA16207L,
    "syntax.type.dark"         to 0xFF93C5FDL, "syntax.type.light"         to 0xFF1D4ED8L,
    "syntax.constant.dark"     to 0xFFFCA5A5L, "syntax.constant.light"     to 0xFFB91C1CL,
    "syntax.comment.dark"      to 0xFF6B5B8EL, "syntax.comment.light"      to 0xFFA094C4L,
    "syntax.operator.dark"     to 0xFFE9D5FFL, "syntax.operator.light"     to 0xFF6D28D9L,
)

/**
 * Semantic-token overrides for the Slack Canvas theme — the white content
 * pane of the Slack-inspired palette. Accents, active-state highlights and
 * syntax colours are pulled from Slack's own brand palette so the content
 * area reads as unmistakably "Slack-like" rather than generic flat-white.
 *
 * The five accent hues — aubergine (keywords), green (strings / success),
 * blue (functions / links / focus), yellow (numbers / warn) and red
 * (constants / danger) — mirror the colour dots Slack uses for channel
 * sections, online indicators, mentions and badges.
 */
private val slackCanvasOverrides: Map<String, Long> = mapOf(
    // Accent — Slack's signature link/focus blue
    "accent.primary.dark"      to 0xFF36C5F0L, "accent.primary.light"      to 0xFF1264A3L,
    "accent.primarySoft.dark"  to 0x2836C5F0L, "accent.primarySoft.light"  to 0x241264A3L,
    "accent.primaryGlow.dark"  to 0x5036C5F0L, "accent.primaryGlow.light"  to 0x401264A3L,

    // Sidebar active row — Slack's blue channel highlight
    "sidebar.activeText.dark"  to 0xFF36C5F0L, "sidebar.activeText.light"  to 0xFF1264A3L,
    "sidebar.activeBg.dark"    to 0x2236C5F0L, "sidebar.activeBg.light"    to 0x1A1264A3L,

    // Focus ring + terminal cursor
    "border.focus.dark"        to 0xFF36C5F0L, "border.focus.light"        to 0xFF1264A3L,
    "border.focusGlow.dark"    to 0x4C36C5F0L, "border.focusGlow.light"    to 0x401264A3L,
    "terminal.cursor.dark"     to 0xFF36C5F0L, "terminal.cursor.light"     to 0xFF1264A3L,

    // Semantic — mapped to Slack's brand palette
    "semantic.success.dark"    to 0xFF2BAC76L, "semantic.success.light"    to 0xFF007A5AL,
    "semantic.warn.dark"       to 0xFFECB22EL, "semantic.warn.light"       to 0xFFB8860AL,
    "semantic.danger.dark"     to 0xFFE01E5AL, "semantic.danger.light"     to 0xFFBA1550L,
    "semantic.info.dark"       to 0xFF36C5F0L, "semantic.info.light"       to 0xFF1264A3L,

    // Diff — green/red tinted bg with saturated gutter + fg
    "diff.addBg.dark"          to 0x222BAC76L, "diff.addBg.light"          to 0x1A007A5AL,
    "diff.addFg.dark"          to 0xFF2BAC76L, "diff.addFg.light"          to 0xFF007A5AL,
    "diff.addGutter.dark"      to 0xFF2BAC76L, "diff.addGutter.light"      to 0xFF007A5AL,
    "diff.removeBg.dark"       to 0x22E01E5AL, "diff.removeBg.light"       to 0x1ABA1550L,
    "diff.removeFg.dark"       to 0xFFE01E5AL, "diff.removeFg.light"       to 0xFFBA1550L,
    "diff.removeGutter.dark"   to 0xFFE01E5AL, "diff.removeGutter.light"   to 0xFFBA1550L,

    // Syntax — five-colour Slack brand palette
    "syntax.keyword.dark"      to 0xFFC39BD6L, "syntax.keyword.light"      to 0xFF4A154BL,
    "syntax.string.dark"       to 0xFF2BAC76L, "syntax.string.light"       to 0xFF007A5AL,
    "syntax.function.dark"     to 0xFF36C5F0L, "syntax.function.light"     to 0xFF1264A3L,
    "syntax.number.dark"       to 0xFFECB22EL, "syntax.number.light"       to 0xFF8A6400L,
    "syntax.type.dark"         to 0xFF7DD3FCL, "syntax.type.light"         to 0xFF0C5C8EL,
    "syntax.constant.dark"     to 0xFFE01E5AL, "syntax.constant.light"     to 0xFFBA1550L,
    "syntax.comment.dark"      to 0xFF6B6D7AL, "syntax.comment.light"      to 0xFF8A8D9AL,
    "syntax.operator.dark"     to 0xFFC0C2CCL, "syntax.operator.light"     to 0xFF3D404EL,
)

/**
 * Syntax colour overrides for the Cotton Candy theme — playful pink on a
 * pink-blush light bg, with mint strings and cyan functions for a
 * confectionery feel without washing out on the pastel background.
 */
private val cottonCandyOverrides: Map<String, Long> = mapOf(
    "accent.primary.dark"      to 0xFFFF9CCCL, "accent.primary.light"      to 0xFFC1297AL,
    "accent.primarySoft.dark"  to 0x28FF9CCCL, "accent.primarySoft.light"  to 0x24C1297AL,
    "accent.primaryGlow.dark"  to 0x50FF9CCCL, "accent.primaryGlow.light"  to 0x40C1297AL,
    "sidebar.activeText.dark"  to 0xFFFF9CCCL, "sidebar.activeText.light"  to 0xFFC1297AL,
    "sidebar.activeBg.dark"    to 0x22FF9CCCL, "sidebar.activeBg.light"    to 0x1AC1297AL,
    "border.focus.dark"        to 0xFFFF9CCCL, "border.focus.light"        to 0xFFC1297AL,
    "border.focusGlow.dark"    to 0x4CFF9CCCL, "border.focusGlow.light"    to 0x40C1297AL,
    "terminal.cursor.dark"     to 0xFFFF9CCCL, "terminal.cursor.light"     to 0xFFC1297AL,
    "syntax.keyword.dark"      to 0xFFFF9CCCL, "syntax.keyword.light"      to 0xFFC1297AL,
    "syntax.string.dark"       to 0xFF6EE7B7L, "syntax.string.light"       to 0xFF059669L,
    "syntax.function.dark"     to 0xFF67E8F9L, "syntax.function.light"     to 0xFF0891B2L,
    "syntax.number.dark"       to 0xFFFDE68AL, "syntax.number.light"       to 0xFFB45309L,
    "syntax.type.dark"         to 0xFFD8B4FEL, "syntax.type.light"         to 0xFF7E22CEL,
    "syntax.constant.dark"     to 0xFFFCA5A5L, "syntax.constant.light"     to 0xFFDC2626L,
    "syntax.comment.dark"      to 0xFF9B7A8FL, "syntax.comment.light"      to 0xFFC794B2L,
    "syntax.operator.dark"     to 0xFFE8BFD5L, "syntax.operator.light"     to 0xFF8A1F5BL,
)

val recommendedThemes: List<TerminalTheme> = listOf(
    TerminalTheme("Matrix",        "#33ff66", "#0a7d2c"),
    TerminalTheme("Mint terminal", "#33ff99", "#0b8a5b"),
    TerminalTheme("Cyber teal",    "#00e5ff", "#006d80"),
    TerminalTheme("Tron",          "#00ff9f", "#00795c", overrides = tronOverrides),
    TerminalTheme("Vapor pink",    "#ff77ff", "#a3008c"),
    TerminalTheme("Amber Glow",    "#F4B869", "#A87620"),
    TerminalTheme("Amber CRT",     "#ffb000", "#8a4b00"),
    TerminalTheme("Ember",         "#ff6d3d", "#a23b00"),
    TerminalTheme("Plasma",        "#ff3df8", "#9b008f"),
    TerminalTheme("Cobalt",        "#5b9dff", "#0a3d91"),
    TerminalTheme("Aqua glow",     "#00ffe1", "#007466"),
    TerminalTheme("Sunset",        "#ffaa33", "#9a4a00"),
    TerminalTheme("Hot rose",      "#ff5fa2", "#a00050"),
    TerminalTheme("Cyber lime",    "#ccff00", "#5a7800"),
    TerminalTheme("Royal violet",  "#b388ff", "#4527a0"),
    TerminalTheme("Synthwave",     "#ff7edb", "#b33d8f"),
    TerminalTheme("Forest",        "#7fce6f", "#2d6a1f"),
    TerminalTheme("Ocean",         "#4fc3f7", "#0277bd"),
    TerminalTheme("Lava",          "#ff5722", "#b71c1c"),
    TerminalTheme("Ice",           "#5dd8ff", "#006e93"),
    TerminalTheme("Coral",         "#ff8a65", "#c1421c"),
    TerminalTheme("Lavender",      "#b39ddb", "#5e35b1"),
    TerminalTheme("Pastel Pink",   "#ffb6d9", "#c2185b"),
    TerminalTheme("Lime Burst",    "#d4ff00", "#6b7c00"),
    TerminalTheme("Magenta",       "#ff00ff", "#8b008b"),
    TerminalTheme("Sunflower",     "#ffd700", "#b8860b"),
    TerminalTheme("Cherry",        "#ff4d6d", "#9d0028"),
    TerminalTheme("Sky",           "#87ceeb", "#1e6091"),
    TerminalTheme("Mint Cream",    "#98ff98", "#2e8b57"),
    TerminalTheme("Peach",         "#ffcc99", "#cc6600"),
    TerminalTheme("Indigo",        "#6f70ff", "#1a237e"),
    TerminalTheme("Sand",          "#d2b48c", "#8b6914"),
    TerminalTheme("Crimson",       "#ff5252", "#8b0000"),
    TerminalTheme("Sea Foam",      "#71eeb8", "#00695c"),
    TerminalTheme("Apricot",       "#ffb347", "#c66900"),
    TerminalTheme("Ultraviolet",   "#9d4edd", "#4a148c"),
    TerminalTheme("Periwinkle",    "#aab8ff", "#3949ab"),
    TerminalTheme("Teal Storm",    "#20d6c7", "#00695c"),
    TerminalTheme("Olive",         "#c5d637", "#5d6e0a"),
    TerminalTheme("Cyan Pop",      "#00ffff", "#007a7a"),
    TerminalTheme("Honey",         "#f4c430", "#a47000"),
    TerminalTheme("Sage",          "#b2c8a4", "#5d7c4f"),

    // Tinted-background themes — designer palettes with their canonical
    // background colors instead of pure black/white. These give the picker
    // real variation (cream Solarized Light, deep navy Tokyo Night, etc.).
    TerminalTheme("Verdant",        darkFg = "#e2e2de", lightFg = "#1a1d1a",
                                    darkBg = "#0b0f0c", lightBg = "#e9eae6",
                                    overrides = verdantOverrides),
    TerminalTheme("Solarized",      darkFg = "#93a1a1", lightFg = "#657b83",
                                    darkBg = "#002b36", lightBg = "#fdf6e3",
                                    overrides = solarizedOverrides),
    TerminalTheme("Gruvbox",        darkFg = "#ebdbb2", lightFg = "#3c3836",
                                    darkBg = "#282828", lightBg = "#fbf1c7"),
    TerminalTheme("Nord",           darkFg = "#d8dee9", lightFg = "#2e3440",
                                    darkBg = "#2e3440", lightBg = "#eceff4"),
    TerminalTheme("Dracula",        darkFg = "#f8f8f2", lightFg = "#282a36",
                                    darkBg = "#282a36", lightBg = "#f8f8f2"),
    TerminalTheme("Monokai",        darkFg = "#f8f8f2", lightFg = "#272822",
                                    darkBg = "#272822", lightBg = "#fafafa"),
    TerminalTheme("Tokyo Night",    darkFg = "#a9b1d6", lightFg = "#343b58",
                                    darkBg = "#1a1b26", lightBg = "#d5d6db",
                                    overrides = tokyoNightOverrides),
    TerminalTheme("One Dark",       darkFg = "#abb2bf", lightFg = "#383a42",
                                    darkBg = "#282c34", lightBg = "#fafafa"),
    TerminalTheme("GitHub",         darkFg = "#c9d1d9", lightFg = "#24292f",
                                    darkBg = "#0d1117", lightBg = "#ffffff"),
    TerminalTheme("Slack Canvas",   darkFg = "#d0d3de", lightFg = "#1d1c1d",
                                    darkBg = "#1a1d29", lightBg = "#ffffff",
                                    overrides = slackCanvasOverrides),
    TerminalTheme("Catppuccin",     darkFg = "#cdd6f4", lightFg = "#4c4f69",
                                    darkBg = "#1e1e2e", lightBg = "#eff1f5"),
    TerminalTheme("Rose Pine",      darkFg = "#e0def4", lightFg = "#575279",
                                    darkBg = "#191724", lightBg = "#faf4ed",
                                    overrides = rosePineOverrides),
    TerminalTheme("Ayu",            darkFg = "#b3b1ad", lightFg = "#5c6773",
                                    darkBg = "#0a0e14", lightBg = "#fafafa"),
    TerminalTheme("Ayu Mirage",     darkFg = "#cbccc6", lightFg = "#5c6773",
                                    darkBg = "#1f2430", lightBg = "#fafafa"),
    TerminalTheme("Night Owl",      darkFg = "#d6deeb", lightFg = "#403f53",
                                    darkBg = "#011627", lightBg = "#fbfbfb"),
    TerminalTheme("Material",       darkFg = "#eeffff", lightFg = "#37474f",
                                    darkBg = "#263238", lightBg = "#fafafa"),
    TerminalTheme("Cobalt2",        darkFg = "#ffffff", lightFg = "#193549",
                                    darkBg = "#193549", lightBg = "#e8eef2"),
    TerminalTheme("Ubuntu",         darkFg = "#eeeeec", lightFg = "#300a24",
                                    darkBg = "#300a24", lightBg = "#f5e6f0"),
    TerminalTheme("Sepia",          darkFg = "#e8d9b6", lightFg = "#5b4636",
                                    darkBg = "#3a2e25", lightBg = "#f4ecd8"),
    TerminalTheme("Pencil",         darkFg = "#f1f1f1", lightFg = "#424242",
                                    darkBg = "#212121", lightBg = "#f1f1f1"),
    TerminalTheme("Hopscotch",      darkFg = "#b9b5b8", lightFg = "#322931",
                                    darkBg = "#322931", lightBg = "#ffffff"),
    TerminalTheme("Spacegray",      darkFg = "#c0c5ce",  lightFg = "#2c2e34",
                                    darkBg = "#2c2e34", lightBg = "#f5f5f5"),
    TerminalTheme("Paper White",    darkFg = "#222222", lightFg = "#222222",
                                    darkBg = "#f5f5dc", lightBg = "#fffff8"),
    TerminalTheme("Mono Black",     darkFg = "#ffffff", lightFg = "#000000",
                                    darkBg = "#000000", lightBg = "#ffffff"),

    // ── Vibrant dark-optimised palettes ───────────────────────────────
    // Each picks a saturated signature colour and pairs it with a deep
    // tinted bg so the whole UI glows in that hue.
    TerminalTheme("Cyberpunk",      darkFg = "#ff71ce", lightFg = "#8a0f55",
                                    darkBg = "#0a0014", lightBg = "#fdf0f8",
                                    overrides = cyberpunkOverrides),
    TerminalTheme("Aurora",         darkFg = "#a7f3d0", lightFg = "#065f46",
                                    darkBg = "#0a1626", lightBg = "#eef7f3",
                                    overrides = auroraOverrides),
    TerminalTheme("Volcanic",       darkFg = "#ffb380", lightFg = "#7f1d1d",
                                    darkBg = "#1a0a05", lightBg = "#fff4ed"),
    TerminalTheme("Deep Sea",       darkFg = "#7dd3fc", lightFg = "#075985",
                                    darkBg = "#001428", lightBg = "#edf7ff"),
    TerminalTheme("Dragon's Hoard", darkFg = "#fbbf24", lightFg = "#78350f",
                                    darkBg = "#14100a", lightBg = "#fdf7e6"),
    TerminalTheme("Nebula",         darkFg = "#d8b4fe", lightFg = "#581c87",
                                    darkBg = "#0f0820", lightBg = "#f5edff",
                                    overrides = nebulaOverrides),
    TerminalTheme("Toxic",          darkFg = "#d4ff47", lightFg = "#3f5d00",
                                    darkBg = "#080c00", lightBg = "#f9ffec"),
    TerminalTheme("Blood Moon",     darkFg = "#ff8b8b", lightFg = "#7f1d1d",
                                    darkBg = "#1a0505", lightBg = "#fff0f0"),
    TerminalTheme("Arcade Night",   darkFg = "#ffcf40", lightFg = "#5a3a00",
                                    darkBg = "#0a0020", lightBg = "#fffbea"),
    TerminalTheme("Firefly",        darkFg = "#bef264", lightFg = "#3f6212",
                                    darkBg = "#0a1a0a", lightBg = "#f5fae8"),

    // ── Vibrant light-optimised palettes ──────────────────────────────
    // Tinted light bg (not pure white) so the whole UI reads as a fresh
    // colour wash; fg is a deep saturated sibling of the bg hue.
    TerminalTheme("Cotton Candy",   darkFg = "#ff9ccc", lightFg = "#8a0f55",
                                    darkBg = "#1a0a14", lightBg = "#fff0f8",
                                    overrides = cottonCandyOverrides),
    TerminalTheme("Peach Sorbet",   darkFg = "#ffb380", lightFg = "#7c2d12",
                                    darkBg = "#1a0d05", lightBg = "#fff4e8"),
    TerminalTheme("Mint Chip",      darkFg = "#86efac", lightFg = "#065f46",
                                    darkBg = "#0a1a12", lightBg = "#e8f7ef"),
    TerminalTheme("Lavender Dream", darkFg = "#d8b4fe", lightFg = "#581c87",
                                    darkBg = "#140a1f", lightBg = "#f5edff"),
    TerminalTheme("Citrus Zest",    darkFg = "#fbbf24", lightFg = "#7a4a00",
                                    darkBg = "#1a1405", lightBg = "#fff8d1"),
    TerminalTheme("Sky Breeze",     darkFg = "#93c5fd", lightFg = "#0c2b5c",
                                    darkBg = "#050f1f", lightBg = "#e8f4ff"),
    TerminalTheme("Rose Quartz",    darkFg = "#fca5c7", lightFg = "#831843",
                                    darkBg = "#1a050d", lightBg = "#fff0f3"),
    TerminalTheme("Coral Reef",     darkFg = "#ff9e7a", lightFg = "#7c1d2e",
                                    darkBg = "#1f0805", lightBg = "#fff5f0"),
    TerminalTheme("Marigold",       darkFg = "#fbbf24", lightFg = "#713f12",
                                    darkBg = "#1a1000", lightBg = "#fff8e1"),
    TerminalTheme("Tidepool",       darkFg = "#5eead4", lightFg = "#115e59",
                                    darkBg = "#0a1f1d", lightBg = "#e8f7f4"),

    // ── Panel themes ──────────────────────────────────────────────────
    // Designed to be assigned to sidebar / chrome / windows / bottomBar
    // sections of light-mode configs so those panels stay deeply
    // saturated even when the content area is pastel. They keep dark
    // backgrounds in BOTH appearance modes (just slightly darker in dark
    // mode), pairing each with appropriate light foreground text.
    TerminalTheme("Midnight Panel",   darkFg = "#c8d3e8", lightFg = "#ffffff",
                                      darkBg = "#0d1326", lightBg = "#1a2847"),
    TerminalTheme("Forest Panel",     darkFg = "#c0e0c8", lightFg = "#f0f5e8",
                                      darkBg = "#0a1f14", lightBg = "#1f3a2e"),
    TerminalTheme("Royal Plum Panel", darkFg = "#e0c8e8", lightFg = "#f5e8ff",
                                      darkBg = "#1a0d20", lightBg = "#3d1f4d"),
    TerminalTheme("Burgundy Panel",   darkFg = "#f0c8d0", lightFg = "#fff0f5",
                                      darkBg = "#200a14", lightBg = "#4a1c2e"),
    TerminalTheme("Espresso Panel",   darkFg = "#e8d4b8", lightFg = "#f5e8d4",
                                      darkBg = "#1a0d05", lightBg = "#3d2418"),
    TerminalTheme("Deep Teal Panel",  darkFg = "#b8e0e0", lightFg = "#e8fafa",
                                      darkBg = "#051f1f", lightBg = "#0d3a3a"),
    TerminalTheme("Slate Panel",      darkFg = "#c8d2e0", lightFg = "#f7fafc",
                                      darkBg = "#0d1320", lightBg = "#2d3748"),
    TerminalTheme("Indigo Panel",     darkFg = "#cad0f0", lightFg = "#e0e7ff",
                                      darkBg = "#0d0a24", lightBg = "#1e1b4b"),
    TerminalTheme("Terracotta Panel", darkFg = "#f0d4b8", lightFg = "#ffe8d4",
                                      darkBg = "#200a05", lightBg = "#4a2418"),
    TerminalTheme("Olive Panel",      darkFg = "#e0e8c0", lightFg = "#f0f5d4",
                                      darkBg = "#14140a", lightBg = "#3a3d1f"),
    TerminalTheme("Mocha Panel",      darkFg = "#e0d0b8", lightFg = "#f5e8d4",
                                      darkBg = "#1a0f0a", lightBg = "#3d2818"),
    TerminalTheme("Charcoal Panel",   darkFg = "#d8d8d8", lightFg = "#f5f5f5",
                                      darkBg = "#1a1a1a", lightBg = "#2d2d2d"),
    TerminalTheme("Slack Navy Panel",  darkFg = "#e8eaf0", lightFg = "#ffffff",
                                       darkBg = "#0f1220", lightBg = "#1a1d29"),
    TerminalTheme("Slack Slate Panel", darkFg = "#d8dcea", lightFg = "#f0f2f8",
                                       darkBg = "#242a47", lightBg = "#3d4266"),

    // ── Bar themes ────────────────────────────────────────────────────
    // Vivid saturated colours intended for the tab strip (or any other
    // narrow accent surface) so it pops next to a pastel content pane
    // and a deep panel sidebar. Both modes use a saturated background
    // with white text.
    TerminalTheme("Tangerine Bar",    darkFg = "#fff5e8", lightFg = "#ffffff",
                                      darkBg = "#c2410c", lightBg = "#ea580c"),
    TerminalTheme("Hot Magenta Bar",  darkFg = "#fff0f5", lightFg = "#ffffff",
                                      darkBg = "#be185d", lightBg = "#db2777"),
    TerminalTheme("Cyan Bar",         darkFg = "#e8faff", lightFg = "#ffffff",
                                      darkBg = "#0e7490", lightBg = "#0891b2"),
    TerminalTheme("Lime Bar",         darkFg = "#f5fae8", lightFg = "#ffffff",
                                      darkBg = "#4d7c0f", lightBg = "#65a30d"),
)

/** Name of the theme applied when the server has no stored preference. */
const val DEFAULT_THEME_NAME = "Tron"

/**
 * Name of the default theme bundle used for the light slot on fresh
 * installs. Resolves to a light-optimised preset in [defaultThemeConfigs].
 */
const val DEFAULT_LIGHT_THEME_NAME = "Paper & Ink"

/**
 * Name of the default theme bundle used for the dark slot on fresh
 * installs. Resolves to a dark-optimised preset in [defaultThemeConfigs].
 */
const val DEFAULT_DARK_THEME_NAME = "Neon Circuit"

/**
 * User preference for light/dark appearance. [Auto] defers to the host
 * platform's system setting.
 */
enum class Appearance { Auto, Dark, Light }

/**
 * A user-defined custom colour scheme. Unlike [recommendedThemes] which are
 * hard-coded at compile time, these are persisted server-side as part of the
 * UI settings blob and may be freely edited by the user.
 *
 * A [CustomScheme] materialises into a [TerminalTheme] at apply time via
 * [toTerminalTheme]. The user may edit every semantic override token
 * independently for dark and light appearance; default [recommendedThemes]
 * remain read-only and must be cloned into a [CustomScheme] before editing.
 *
 * @property name      Human-readable scheme name; unique across custom schemes.
 * @property darkFg    Hex foreground colour used in dark mode.
 * @property lightFg   Hex foreground colour used in light mode.
 * @property darkBg    Hex background colour used in dark mode.
 * @property lightBg   Hex background colour used in light mode.
 * @property overrides Hand-tuned ARGB override values keyed by
 *   `"group.token.mode"` (e.g. `"syntax.keyword.dark"`). Mirrors
 *   [TerminalTheme.overrides]; empty map by default.
 */
data class CustomScheme(
    val name: String,
    val darkFg: String,
    val lightFg: String,
    val darkBg: String,
    val lightBg: String,
    val overrides: Map<String, Long> = emptyMap(),
) {
    /**
     * Materialise this custom scheme into a [TerminalTheme] suitable for
     * passing to [resolve] / the theme pipeline. Called whenever a custom
     * scheme is referenced from a theme slot (main or per-section).
     *
     * @return a [TerminalTheme] with the same name and colours.
     */
    fun toTerminalTheme(): TerminalTheme = TerminalTheme(
        name = name,
        darkFg = darkFg,
        lightFg = lightFg,
        darkBg = darkBg,
        lightBg = lightBg,
        overrides = overrides.ifEmpty { null },
    )
}
