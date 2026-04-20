/**
 * Built-in (default) theme configuration presets for Termtastic.
 *
 * A theme configuration is a named snapshot of a main theme plus per-section
 * overrides (sidebar, terminal, diff, file browser, tabs, chrome, windows,
 * and active indicators). The presets defined here ship with the app and
 * cannot be deleted by the user, though they can be reordered alongside
 * user-created configurations.
 *
 * Each preset references themes by name from [recommendedThemes]. An empty
 * string means "use the main theme" (no section override).
 *
 * @see recommendedThemes
 * @see se.soderbjorn.termtastic.client.UiSettings
 */
package se.soderbjorn.termtastic

/**
 * Which appearance mode(s) a built-in theme configuration is designed for.
 *
 * @see ThemeConfigPreset.mode
 */
enum class ConfigMode {
    /** Optimised for dark appearance. */
    Dark,
    /** Optimised for light appearance. */
    Light,
    /** Works well in both dark and light appearance. */
    Both,
}

/**
 * A built-in theme configuration preset that maps a name to a main theme
 * and optional per-section theme overrides.
 *
 * @property name         Human-readable preset name shown in the config list.
 * @property mode         Which appearance mode(s) this preset is designed for.
 * @property theme        Name of the main theme (must exist in [recommendedThemes]).
 * @property sidebar      Section override for the sidebar, or empty for default.
 * @property terminal     Section override for the terminal panes.
 * @property diff         Section override for the diff viewer.
 * @property fileBrowser  Section override for the file browser / markdown viewer.
 * @property tabs         Section override for the tab strip.
 * @property chrome       Section override for the window chrome / titlebar.
 * @property windows      Section override for the pane frames / borders.
 * @property active       Section override for focus rings and active indicators.
 */
data class ThemeConfigPreset(
    val name: String,
    val mode: ConfigMode = ConfigMode.Both,
    val theme: String,
    val sidebar: String = "",
    val terminal: String = "",
    val diff: String = "",
    val fileBrowser: String = "",
    val tabs: String = "",
    val chrome: String = "",
    val windows: String = "",
    val active: String = "",
)

/**
 * The list of built-in theme configuration presets that ship with Termtastic.
 *
 * Each preset is a curated combination of themes assigned to different UI
 * sections, designed to produce a cohesive and visually distinct look. Users
 * can reorder these alongside their custom configurations, but cannot delete
 * them.
 *
 * @see ThemeConfigPreset
 */
val defaultThemeConfigs: List<ThemeConfigPreset> = listOf(
    // ── Dark mode configurations ──────────────────────────────────────

    ThemeConfigPreset(
        name = "Neon Circuit",
        mode = ConfigMode.Dark,
        theme = "Tron",
        sidebar = "Vapor pink",
        terminal = "Cyber teal",
        tabs = "Tron",
        active = "Plasma",
    ),
    ThemeConfigPreset(
        name = "Sunset Drive",
        mode = ConfigMode.Dark,
        theme = "Amber Glow",
        sidebar = "Ember",
        terminal = "Coral",
        tabs = "Sunset",
        active = "Sunset",
    ),
    ThemeConfigPreset(
        name = "Purple Haze",
        mode = ConfigMode.Dark,
        theme = "Dracula",
        sidebar = "Royal violet",
        terminal = "Ultraviolet",
        tabs = "Dracula",
        active = "Lavender",
    ),
    ThemeConfigPreset(
        name = "Cyber Noir",
        mode = ConfigMode.Dark,
        theme = "Night Owl",
        sidebar = "Tron",
        terminal = "Matrix",
        windows = "Ayu Mirage",
        tabs = "Night Owl",
        diff = "Ayu",
        active = "Cyber teal",
    ),
    ThemeConfigPreset(
        name = "Ember Dusk",
        mode = ConfigMode.Dark,
        theme = "One Dark",
        sidebar = "Monokai",
        terminal = "Lava",
        windows = "One Dark",
        tabs = "Hopscotch",
        diff = "Dracula",
        active = "Crimson",
    ),
    ThemeConfigPreset(
        name = "Candy Pop",
        mode = ConfigMode.Dark,
        theme = "Catppuccin",
        sidebar = "Dracula",
        terminal = "Vapor pink",
        windows = "Catppuccin",
        tabs = "Hopscotch",
        diff = "Rose Pine",
        active = "Magenta",
    ),
    ThemeConfigPreset(
        name = "Ubuntu Terminal",
        mode = ConfigMode.Dark,
        theme = "Ubuntu",
        sidebar = "Dracula",
        terminal = "Cherry",
        windows = "Hopscotch",
        tabs = "Ubuntu",
        diff = "One Dark",
        active = "Hot rose",
    ),
    ThemeConfigPreset(
        name = "Hacker",
        mode = ConfigMode.Dark,
        theme = "Ayu",
        sidebar = "Night Owl",
        terminal = "Matrix",
        windows = "Ayu",
        tabs = "Spacegray",
        diff = "Monokai",
        fileBrowser = "Ayu Mirage",
        active = "Cyber lime",
    ),
    ThemeConfigPreset(
        name = "Retro Terminal",
        mode = ConfigMode.Dark,
        theme = "Pencil",
        sidebar = "Ayu",
        terminal = "Amber CRT",
        windows = "Pencil",
        tabs = "Spacegray",
        diff = "Monokai",
        fileBrowser = "Pencil",
        active = "Cyber lime",
    ),

    ThemeConfigPreset(
        name = "Magma",
        mode = ConfigMode.Dark,
        theme = "Monokai",
        sidebar = "Hopscotch",
        terminal = "Crimson",
        windows = "Monokai",
        tabs = "Ubuntu",
        diff = "Dracula",
        active = "Lava",
    ),
    ThemeConfigPreset(
        name = "Midnight Oil",
        mode = ConfigMode.Dark,
        theme = "Ayu Mirage",
        sidebar = "Cobalt2",
        terminal = "Indigo",
        windows = "Ayu Mirage",
        tabs = "Night Owl",
        diff = "Tokyo Night",
        active = "Cobalt",
    ),
    ThemeConfigPreset(
        name = "Toxic",
        mode = ConfigMode.Dark,
        theme = "Matrix",
        sidebar = "Ayu",
        terminal = "Cyber lime",
        windows = "Spacegray",
        tabs = "Pencil",
        diff = "Monokai",
        active = "Aqua glow",
    ),
    ThemeConfigPreset(
        name = "Outrun",
        mode = ConfigMode.Dark,
        theme = "Synthwave",
        sidebar = "Dracula",
        terminal = "Hot rose",
        windows = "Catppuccin",
        tabs = "Hopscotch",
        diff = "Rose Pine",
        active = "Vapor pink",
    ),
    ThemeConfigPreset(
        name = "Deep Space",
        mode = ConfigMode.Dark,
        theme = "Night Owl",
        sidebar = "Tokyo Night",
        terminal = "Cobalt",
        windows = "Night Owl",
        tabs = "Ayu Mirage",
        diff = "Nord",
        active = "Periwinkle",
    ),
    ThemeConfigPreset(
        name = "Molten Gold",
        mode = ConfigMode.Dark,
        theme = "Sepia",
        sidebar = "Monokai",
        terminal = "Sunflower",
        windows = "Sepia",
        tabs = "Gruvbox",
        diff = "One Dark",
        active = "Honey",
    ),
    ThemeConfigPreset(
        name = "Blood Moon",
        mode = ConfigMode.Dark,
        theme = "Dracula",
        sidebar = "Ubuntu",
        terminal = "Cherry",
        windows = "Hopscotch",
        tabs = "Dracula",
        diff = "One Dark",
        active = "Crimson",
    ),

    // ── Light mode configurations ─────────────────────────────────────

    ThemeConfigPreset(
        name = "Paper & Ink",
        mode = ConfigMode.Light,
        theme = "Paper White",
        sidebar = "GitHub",
        terminal = "Pencil",
        windows = "Paper White",
        tabs = "Spacegray",
        diff = "GitHub",
        active = "Mono Black",
    ),
    ThemeConfigPreset(
        name = "Solarized Warm",
        mode = ConfigMode.Light,
        theme = "Solarized",
        sidebar = "Sepia",
        terminal = "Solarized",
        windows = "Gruvbox",
        tabs = "Sepia",
        diff = "Solarized",
        active = "Honey",
    ),
    ThemeConfigPreset(
        name = "Morning Fog",
        mode = ConfigMode.Light,
        theme = "Nord",
        sidebar = "Spacegray",
        terminal = "GitHub",
        windows = "Nord",
        tabs = "Material",
        diff = "Nord",
        active = "Sky",
    ),
    ThemeConfigPreset(
        name = "Lavender Fields",
        mode = ConfigMode.Light,
        theme = "Catppuccin",
        sidebar = "Rose Pine",
        terminal = "Lavender",
        windows = "Catppuccin",
        tabs = "Hopscotch",
        diff = "Rose Pine",
        active = "Royal violet",
    ),
    ThemeConfigPreset(
        name = "Cream & Coffee",
        mode = ConfigMode.Light,
        theme = "Gruvbox",
        sidebar = "Sepia",
        terminal = "Sand",
        windows = "Gruvbox",
        tabs = "Monokai",
        diff = "Gruvbox",
        fileBrowser = "Sepia",
        active = "Amber Glow",
    ),
    ThemeConfigPreset(
        name = "Ocean Breeze",
        mode = ConfigMode.Light,
        theme = "GitHub",
        sidebar = "Cobalt2",
        terminal = "Sky",
        windows = "GitHub",
        tabs = "Nord",
        diff = "Material",
        active = "Ocean",
    ),
    ThemeConfigPreset(
        name = "Spring Garden",
        mode = ConfigMode.Light,
        theme = "One Dark",
        sidebar = "Gruvbox",
        terminal = "Mint Cream",
        windows = "One Dark",
        tabs = "Material",
        diff = "GitHub",
        fileBrowser = "One Dark",
        active = "Sea Foam",
    ),
    ThemeConfigPreset(
        name = "Peach Blossom",
        mode = ConfigMode.Light,
        theme = "Rose Pine",
        sidebar = "Catppuccin",
        terminal = "Peach",
        windows = "Rose Pine",
        tabs = "Hopscotch",
        diff = "Catppuccin",
        active = "Coral",
    ),
    ThemeConfigPreset(
        name = "Clean Slate",
        mode = ConfigMode.Light,
        theme = "Mono Black",
        sidebar = "GitHub",
        terminal = "Spacegray",
        windows = "Pencil",
        tabs = "Mono Black",
        diff = "GitHub",
        active = "Cobalt",
    ),
    ThemeConfigPreset(
        name = "Ubuntu Rose",
        mode = ConfigMode.Light,
        theme = "Ubuntu",
        sidebar = "Hopscotch",
        terminal = "Pastel Pink",
        windows = "Ubuntu",
        tabs = "Rose Pine",
        diff = "Catppuccin",
        active = "Cherry",
    ),

    ThemeConfigPreset(
        name = "Ivory Tower",
        mode = ConfigMode.Light,
        theme = "GitHub",
        sidebar = "One Dark",
        terminal = "Paper White",
        windows = "GitHub",
        tabs = "Spacegray",
        diff = "Nord",
        active = "Indigo",
    ),
    ThemeConfigPreset(
        name = "Mint Tea",
        mode = ConfigMode.Light,
        theme = "Material",
        sidebar = "Nord",
        terminal = "Mint Cream",
        windows = "Material",
        tabs = "GitHub",
        diff = "One Dark",
        active = "Sea Foam",
    ),
    ThemeConfigPreset(
        name = "Sunset Terrace",
        mode = ConfigMode.Light,
        theme = "Gruvbox",
        sidebar = "Monokai",
        terminal = "Peach",
        windows = "Gruvbox",
        tabs = "Sepia",
        diff = "One Dark",
        fileBrowser = "Gruvbox",
        active = "Coral",
    ),
    ThemeConfigPreset(
        name = "Nordic Frost",
        mode = ConfigMode.Light,
        theme = "Nord",
        sidebar = "Cobalt2",
        terminal = "Ice",
        windows = "Nord",
        tabs = "GitHub",
        diff = "Material",
        active = "Periwinkle",
    ),
    ThemeConfigPreset(
        name = "Daybreak",
        mode = ConfigMode.Light,
        theme = "One Dark",
        sidebar = "Catppuccin",
        terminal = "Sky",
        windows = "One Dark",
        tabs = "Nord",
        diff = "GitHub",
        active = "Ocean",
    ),
    ThemeConfigPreset(
        name = "Honey Bee",
        mode = ConfigMode.Light,
        theme = "Sepia",
        sidebar = "Gruvbox",
        terminal = "Sunflower",
        windows = "Sepia",
        tabs = "Monokai",
        diff = "Gruvbox",
        active = "Amber Glow",
    ),

    // ── Both modes ────────────────────────────────────────────────────

    ThemeConfigPreset(
        name = "Deep Ocean",
        theme = "Nord",
        sidebar = "Cobalt",
        terminal = "Ocean",
        tabs = "Nord",
        active = "Ice",
    ),
    ThemeConfigPreset(
        name = "Forest Floor",
        theme = "Gruvbox",
        sidebar = "Forest",
        terminal = "Sage",
        tabs = "Gruvbox",
        active = "Olive",
    ),
    ThemeConfigPreset(
        name = "Tokyo Midnight",
        theme = "Tokyo Night",
        sidebar = "Dracula",
        terminal = "Catppuccin",
        tabs = "Tokyo Night",
        active = "Rose Pine",
    ),
    ThemeConfigPreset(
        name = "Solarized Classic",
        theme = "Solarized",
    ),
    ThemeConfigPreset(
        name = "Warm Hearth",
        theme = "Monokai",
        sidebar = "Sepia",
        terminal = "Amber CRT",
        tabs = "Monokai",
        active = "Honey",
    ),
    ThemeConfigPreset(
        name = "Rose Garden",
        theme = "Rose Pine",
        sidebar = "Catppuccin",
        terminal = "Pastel Pink",
        tabs = "Rose Pine",
        active = "Hopscotch",
    ),
    ThemeConfigPreset(
        name = "Monochrome",
        theme = "Mono Black",
        sidebar = "Pencil",
        terminal = "Spacegray",
        tabs = "Mono Black",
        active = "GitHub",
    ),
    ThemeConfigPreset(
        name = "Arctic Aurora",
        theme = "Nord",
        sidebar = "Material",
        terminal = "Ice",
        windows = "Nord",
        tabs = "Cobalt2",
        diff = "GitHub",
        fileBrowser = "Nord",
        active = "Aqua glow",
    ),
    ThemeConfigPreset(
        name = "Sakura",
        theme = "Rose Pine",
        sidebar = "Hopscotch",
        terminal = "Pastel Pink",
        windows = "Catppuccin",
        tabs = "Rose Pine",
        diff = "Catppuccin",
        fileBrowser = "Rose Pine",
        active = "Hot rose",
    ),
    ThemeConfigPreset(
        name = "Copper Patina",
        theme = "Material",
        sidebar = "Gruvbox",
        terminal = "Sea Foam",
        windows = "Material",
        tabs = "Sepia",
        diff = "Ayu Mirage",
        active = "Honey",
    ),
    ThemeConfigPreset(
        name = "Midnight Violet",
        theme = "Tokyo Night",
        sidebar = "Catppuccin",
        terminal = "Indigo",
        windows = "Tokyo Night",
        tabs = "Dracula",
        diff = "Rose Pine",
        active = "Royal violet",
    ),
    ThemeConfigPreset(
        name = "Sandstorm",
        theme = "Sepia",
        sidebar = "Gruvbox",
        terminal = "Sand",
        windows = "Sepia",
        tabs = "Monokai",
        diff = "Gruvbox",
        fileBrowser = "Sepia",
        active = "Apricot",
    ),
    ThemeConfigPreset(
        name = "Electric Blue",
        theme = "Cobalt2",
        sidebar = "Nord",
        terminal = "Cobalt",
        windows = "Cobalt2",
        tabs = "Night Owl",
        diff = "Material",
        active = "Periwinkle",
    ),
    ThemeConfigPreset(
        name = "Jungle",
        theme = "Ayu",
        sidebar = "Material",
        terminal = "Forest",
        windows = "Ayu",
        tabs = "Nord",
        diff = "Gruvbox",
        fileBrowser = "Ayu Mirage",
        active = "Teal Storm",
    ),
    ThemeConfigPreset(
        name = "Campfire",
        theme = "Gruvbox",
        sidebar = "Sepia",
        terminal = "Amber CRT",
        windows = "Monokai",
        tabs = "Gruvbox",
        diff = "One Dark",
        fileBrowser = "Gruvbox",
        active = "Sunset",
    ),
    ThemeConfigPreset(
        name = "Frost",
        theme = "GitHub",
        sidebar = "Nord",
        terminal = "Sky",
        windows = "GitHub",
        tabs = "Spacegray",
        diff = "Nord",
        active = "Periwinkle",
    ),
    ThemeConfigPreset(
        name = "Twilight",
        theme = "Ayu Mirage",
        sidebar = "Tokyo Night",
        terminal = "Lavender",
        windows = "Ayu Mirage",
        tabs = "Catppuccin",
        diff = "Dracula",
        active = "Synthwave",
    ),
    ThemeConfigPreset(
        name = "Autumn Leaves",
        theme = "Monokai",
        sidebar = "Gruvbox",
        terminal = "Amber Glow",
        windows = "Sepia",
        tabs = "Monokai",
        diff = "Gruvbox",
        fileBrowser = "Monokai",
        active = "Olive",
    ),
    ThemeConfigPreset(
        name = "Coral Reef",
        theme = "Night Owl",
        sidebar = "Material",
        terminal = "Sea Foam",
        windows = "Night Owl",
        tabs = "Cobalt2",
        diff = "Ayu Mirage",
        fileBrowser = "Night Owl",
        active = "Aqua glow",
    ),
    ThemeConfigPreset(
        name = "Starlight",
        theme = "One Dark",
        sidebar = "Catppuccin",
        terminal = "Nord",
        windows = "Spacegray",
        tabs = "One Dark",
        diff = "Tokyo Night",
        active = "Periwinkle",
    ),
    ThemeConfigPreset(
        name = "Driftwood",
        theme = "Sepia",
        sidebar = "Material",
        terminal = "Peach",
        windows = "Sepia",
        tabs = "Gruvbox",
        diff = "Monokai",
        fileBrowser = "Sepia",
        active = "Sand",
    ),
    ThemeConfigPreset(
        name = "Nebula",
        theme = "Catppuccin",
        sidebar = "Tokyo Night",
        terminal = "Royal violet",
        windows = "Catppuccin",
        tabs = "Dracula",
        diff = "Rose Pine",
        active = "Synthwave",
    ),
    ThemeConfigPreset(
        name = "Evergreen",
        theme = "Material",
        sidebar = "Nord",
        terminal = "Forest",
        windows = "Material",
        tabs = "Gruvbox",
        diff = "Ayu Mirage",
        fileBrowser = "Material",
        active = "Sea Foam",
    ),
)

/**
 * Set of default theme configuration names for quick membership checks.
 *
 * Used to prevent users from creating custom configurations with names
 * that collide with built-in presets, and to identify default entries
 * in the merged configuration list.
 *
 * @see defaultThemeConfigs
 */
val defaultThemeConfigNames: Set<String> = defaultThemeConfigs.map { it.name }.toSet()
