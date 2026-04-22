/**
 * Semantic colour token structure for the Termtastic theme system.
 *
 * A [ResolvedPalette] contains every colour the application needs, organised
 * into role-based groups (surface, text, accent, syntax, etc.).  It is
 * produced by [TerminalTheme.resolve] from a theme's seed fg/bg values and
 * consumed by every platform renderer: web CSS custom properties, Android
 * Compose colours, and iOS SwiftUI colours.
 *
 * All colour values are ARGB [Long] values (`0xAARRGGBB`).
 *
 * @see resolve
 * @see TerminalTheme
 */
package se.soderbjorn.termtastic

/**
 * Complete semantic colour palette derived from a [TerminalTheme].
 *
 * Each nested data class corresponds to one role-based colour group, giving
 * roughly 60 tokens in total.  Platform renderers convert these [Long] ARGB
 * values into their native colour types.
 *
 * @property surface   Background and elevation colours.
 * @property text      Foreground text hierarchy.
 * @property border    Dividers, card edges, and focus rings.
 * @property accent    Brand / interactive colours derived from the theme foreground.
 * @property semantic  Status colours (danger, warn, success, info) shared across themes.
 * @property terminal  Colours fed directly to the xterm.js theme object.
 * @property chrome    macOS / desktop window-frame colours.
 * @property sidebar   Tree-view / pane-list sidebar colours.
 * @property bottomBar Footer strip colours (Claude usage bar).
 * @property diff      Git diff pane line-state colours.
 * @property syntax    Code-pane syntax-highlighting token colours.
 */
data class ResolvedPalette(
    val surface: Surface,
    val text: Text,
    val border: Border,
    val accent: Accent,
    val semantic: Semantic,
    val terminal: Terminal,
    val chrome: Chrome,
    val sidebar: Sidebar,
    val bottomBar: BottomBar,
    val diff: Diff,
    val syntax: Syntax,
) {

    /**
     * Background and elevation colours.
     *
     * @property base    App background / terminal pane / file browser.
     * @property raised  Panels, cards, popovers.
     * @property sunken  Code wells, input fields.
     * @property overlay Hover states, modals.
     */
    data class Surface(
        val base: Long,
        val raised: Long,
        val sunken: Long,
        val overlay: Long,
    )

    /**
     * Foreground text hierarchy.
     *
     * @property primary   Default body text.
     * @property secondary Labels, supporting copy.
     * @property tertiary  Captions, metadata, line numbers.
     * @property disabled  Inactive / greyed-out states.
     * @property inverse   Text rendered on accent-coloured surfaces.
     */
    data class Text(
        val primary: Long,
        val secondary: Long,
        val tertiary: Long,
        val disabled: Long,
        val inverse: Long,
    )

    /**
     * Divider and focus-ring colours.
     *
     * @property subtle    Hairlines, section dividers.
     * @property default   Card edges, input borders.
     * @property strong    Focused cards.
     * @property focus     Keyboard focus ring (inner).
     * @property focusGlow Keyboard focus glow (outer).
     */
    data class Border(
        val subtle: Long,
        val default: Long,
        val strong: Long,
        val focus: Long,
        val focusGlow: Long,
    )

    /**
     * Brand / interactive colours derived from the theme foreground.
     *
     * @property primary     Links, CTAs, terminal prompt.
     * @property primarySoft Chip and tag backgrounds.
     * @property primaryGlow Highlight aura.
     * @property onPrimary   Text on primary-coloured surfaces.
     */
    data class Accent(
        val primary: Long,
        val primarySoft: Long,
        val primaryGlow: Long,
        val onPrimary: Long,
    )

    /**
     * Status colours shared across all themes, shifted by dark/light mode.
     *
     * @property danger  Errors, destructive actions.
     * @property warn    Caution, degraded state.
     * @property success Confirmed, synced.
     * @property info    Neutral informational.
     */
    data class Semantic(
        val danger: Long,
        val warn: Long,
        val success: Long,
        val info: Long,
    )

    /**
     * Colours fed directly to the xterm.js theme object.
     *
     * @property bg            Terminal background.
     * @property fg            Terminal foreground.
     * @property cursor        Cursor block / line colour.
     * @property selection     Selection highlight background.
     * @property selectionText Selection highlight foreground.
     */
    data class Terminal(
        val bg: Long,
        val fg: Long,
        val cursor: Long,
        val selection: Long,
        val selectionText: Long,
    )

    /**
     * macOS / desktop window-frame chrome colours.
     *
     * @property titlebar  Title bar background.
     * @property titleText Title caption colour.
     * @property border    Title bar bottom rule.
     * @property shadow    Window drop shadow.
     * @property closeDot  Traffic-light close button.
     * @property minDot    Traffic-light minimise button.
     * @property maxDot    Traffic-light maximise button.
     */
    data class Chrome(
        val titlebar: Long,
        val titleText: Long,
        val border: Long,
        val shadow: Long,
        val closeDot: Long,
        val minDot: Long,
        val maxDot: Long,
    )

    /**
     * Tree-view / pane-list sidebar colours.
     *
     * @property bg         Sidebar background.
     * @property text       Row label colour.
     * @property textDim    Section caption colour.
     * @property activeBg   Selected / active row background.
     * @property activeText Selected / active row foreground.
     */
    data class Sidebar(
        val bg: Long,
        val text: Long,
        val textDim: Long,
        val activeBg: Long,
        val activeText: Long,
    )

    /**
     * Footer strip colours (the Claude usage bar at the bottom of the window).
     *
     * @property bg      Footer background.
     * @property text    Primary footer text (labels).
     * @property textDim Secondary footer text (captions, metadata).
     * @property border  Top divider between app body and footer.
     */
    data class BottomBar(
        val bg: Long,
        val text: Long,
        val textDim: Long,
        val border: Long,
    )

    /**
     * Git diff pane line-state colours.
     *
     * @property addBg        Added-line background.
     * @property addFg        Added-line text.
     * @property addGutter    Added-line gutter symbol colour.
     * @property removeBg     Removed-line background.
     * @property removeFg     Removed-line text.
     * @property removeGutter Removed-line gutter symbol colour.
     * @property contextFg    Unchanged context-line text.
     */
    data class Diff(
        val addBg: Long,
        val addFg: Long,
        val addGutter: Long,
        val removeBg: Long,
        val removeFg: Long,
        val removeGutter: Long,
        val contextFg: Long,
    )

    /**
     * Code-pane syntax-highlighting token colours.
     *
     * @property keyword  Language keywords (`fun`, `val`, `if`, `else`).
     * @property string   String literals.
     * @property number   Numeric literals.
     * @property comment  Comments.
     * @property function Function calls and declarations.
     * @property type     Type names (`Int`, `String`, `User`).
     * @property operator Operators (`=`, `+`, `→`).
     * @property constant Constants (`null`, `true`, `NIL`).
     */
    data class Syntax(
        val keyword: Long,
        val string: Long,
        val number: Long,
        val comment: Long,
        val function: Long,
        val type: Long,
        val operator: Long,
        val constant: Long,
    )
}
