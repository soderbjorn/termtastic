/**
 * Settings-mutation slice of the application backing model. Owns the
 * [SettingsPersister] and the local-change timestamp guard, and contains
 * the JSON parsing / theme-resolution helpers that compute settings-derived
 * fields of [AppBackingViewModel.State].
 *
 * Used internally by [AppBackingViewModel] which delegates its settings
 * methods here; the public surface stays on the coordinator so existing
 * consumers (web, Android Compose, Swift via Kotlin/Native binary) need
 * no call-site changes.
 */
package se.soderbjorn.termtastic.client.viewmodel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.ColorScheme
import se.soderbjorn.darkness.core.ConfigMode
import se.soderbjorn.darkness.core.CustomScheme
import se.soderbjorn.darkness.core.DEFAULT_DARK_THEME_NAME
import se.soderbjorn.darkness.core.DEFAULT_LIGHT_THEME_NAME
import se.soderbjorn.darkness.core.DEFAULT_THEME_NAME
import se.soderbjorn.darkness.core.Theme
import se.soderbjorn.darkness.core.defaultThemes
import se.soderbjorn.darkness.core.recommendedColorSchemes
import kotlin.time.TimeSource

/**
 * Persistence + parsing helper for settings-related fields of
 * [AppBackingViewModel.State]. Holds no flow of its own; the coordinator
 * passes the current state in and receives an updated copy back.
 *
 * @param settingsPersister optional callback to persist UI setting changes
 *   to the server; `null` in unit tests.
 */
internal class SettingsViewModel(
    private val settingsPersister: SettingsPersister?,
) {
    /**
     * Monotonic timestamp of the most recent local settings mutation.
     * Used to suppress server-pushed UiSettings echoes that arrive shortly
     * after the client itself POSTed.
     */
    var lastLocalSettingsChange: TimeSource.Monotonic.ValueTimeMark =
        TimeSource.Monotonic.markNow()
        private set

    /** Stamp the local-change time and persist a single setting. */
    suspend fun persistSetting(key: String, value: String) {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        settingsPersister?.putSetting(key, value)
    }

    /** Stamp the local-change time and persist a batch of settings. */
    suspend fun persistSettings(settings: Map<String, String>) {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        settingsPersister?.putSettings(settings)
    }

    /** Stamp the local-change time and fire-and-forget a settings batch. */
    fun fireAndForgetPersistSettings(settings: Map<String, String>) {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        settingsPersister?.fireAndForgetPutSettings(settings)
    }

    /** Stamp the local-change time and persist a structured JSON blob. */
    suspend fun persistJsonSettings(obj: JsonObject) {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        settingsPersister?.putJsonSettings(obj)
    }

    /**
     * Apply a server-pushed UI settings JSON object to the given current
     * [cur] state and return the updated copy. Unknown keys / enum values
     * fall through to the existing values so a bad server blob never crashes.
     */
    fun applyServerUiSettings(
        cur: AppBackingViewModel.State,
        settings: JsonObject,
    ): AppBackingViewModel.State {
        val customSchemes = parseCustomSchemes(settings["customSchemes"]) ?: cur.customSchemes
        val customThemes = parseCustomThemes(settings["themeConfigs"]) ?: cur.customThemes

        fun themeByName(name: String?): ColorScheme? {
            if (name.isNullOrEmpty()) return null
            customSchemes[name]?.let { return it.toColorScheme() }
            return recommendedColorSchemes.firstOrNull { it.name == name }
        }

        val themeName = settings["theme"]?.jsonPrimitive?.contentOrNull
        val theme = themeByName(themeName) ?: cur.theme

        val appearanceName = settings["appearance"]?.jsonPrimitive?.contentOrNull
        val appearance = appearanceName
            ?.let { runCatching { Appearance.valueOf(it) }.getOrNull() }
            ?: cur.appearance

        val fontSize = settings["paneFontSize"]?.jsonPrimitive?.intOrNull ?: cur.paneFontSize
        val fontFamily = settings["paneFontFamily"]?.jsonPrimitive?.contentOrNull
            ?.ifEmpty { null } ?: cur.paneFontFamily
        val sidebarW = settings["sidebarWidth"]?.jsonPrimitive?.intOrNull ?: cur.sidebarWidth
        val sidebarCol = settings["sidebarCollapsed"]?.jsonPrimitive?.booleanOrNull ?: cur.sidebarCollapsed
        val headerCol = settings["headerCollapsed"]?.jsonPrimitive?.booleanOrNull ?: cur.headerCollapsed
        val usageBarCol = settings["usageBarCollapsed"]?.jsonPrimitive?.booleanOrNull ?: cur.usageBarCollapsed
        val desktopNotif = settings["desktopNotifications"]?.jsonPrimitive?.booleanOrNull ?: cur.desktopNotifications
        val electronCustom = settings["electronCustomTitleBar"]?.jsonPrimitive?.booleanOrNull ?: cur.electronCustomTitleBar

        val storedLight = settings["theme.light"]?.jsonPrimitive?.contentOrNull
        val storedDark = settings["theme.dark"]?.jsonPrimitive?.contentOrNull
        val lightName = storedLight?.takeIf { it.isNotEmpty() }
            ?: themeName?.takeIf { it.isNotEmpty() } ?: cur.lightThemeName
        val darkName = storedDark?.takeIf { it.isNotEmpty() }
            ?: themeName?.takeIf { it.isNotEmpty() } ?: cur.darkThemeName

        val firstLoadMigration = storedLight == null && storedDark == null
        val hasLegacySectionKey = listOf(
            "theme.sidebar", "theme.terminal", "theme.diff", "theme.fileBrowser",
            "theme.tabs", "theme.chrome", "theme.windows", "theme.active",
            "theme.bottomBar",
        ).any { (settings[it]?.jsonPrimitive?.contentOrNull).orEmpty().isNotEmpty() }
        if (firstLoadMigration || hasLegacySectionKey) {
            fireAndForgetPersistSettings(buildMap {
                put("theme.light", lightName)
                put("theme.dark", darkName)
                put("theme.sidebar", "")
                put("theme.terminal", "")
                put("theme.diff", "")
                put("theme.fileBrowser", "")
                put("theme.tabs", "")
                put("theme.chrome", "")
                put("theme.windows", "")
                put("theme.active", "")
                put("theme.bottomBar", "")
            })
        }

        val favorites = parseStringList(settings["favorites.themes"]) ?: cur.favoriteThemes
        val favoriteSchemes = parseStringList(settings["favorites.schemes"]) ?: cur.favoriteSchemes

        return cur.copy(
            theme = theme,
            appearance = appearance,
            paneFontSize = fontSize,
            paneFontFamily = fontFamily,
            sidebarWidth = sidebarW,
            sidebarCollapsed = sidebarCol,
            headerCollapsed = headerCol,
            usageBarCollapsed = usageBarCol,
            desktopNotifications = desktopNotif,
            electronCustomTitleBar = electronCustom,
            uiSettingsHydrated = true,
            lightThemeName = lightName,
            darkThemeName = darkName,
            favoriteThemes = favorites,
            favoriteSchemes = favoriteSchemes,
            customSchemes = customSchemes,
            customThemes = customThemes,
        )
    }

    /**
     * Parse a `customSchemes` JsonElement into a typed map. Returns `null`
     * when the element is absent or malformed.
     */
    private fun parseCustomSchemes(el: JsonElement?): Map<String, CustomScheme>? {
        val obj = (el as? JsonObject) ?: return null
        val out = linkedMapOf<String, CustomScheme>()
        for ((name, value) in obj) {
            val o = value as? JsonObject ?: continue
            val darkFg = o["darkFg"]?.jsonPrimitive?.contentOrNull ?: continue
            val lightFg = o["lightFg"]?.jsonPrimitive?.contentOrNull ?: continue
            val darkBg = o["darkBg"]?.jsonPrimitive?.contentOrNull ?: continue
            val lightBg = o["lightBg"]?.jsonPrimitive?.contentOrNull ?: continue
            val ovr = (o["overrides"] as? JsonObject)?.let { om ->
                om.mapNotNull { (k, v) ->
                    val p = v as? JsonPrimitive ?: return@mapNotNull null
                    val l = p.longOrNull ?: p.contentOrNull?.toLongOrNull()
                    l?.let { k to it }
                }.toMap()
            } ?: emptyMap()
            out[name] = CustomScheme(name, darkFg, lightFg, darkBg, lightBg, ovr)
        }
        return out
    }

    /**
     * Parse the `themeConfigs` JsonElement into typed user-defined [Theme]s.
     * Legacy entries without a `mode` field default to [ConfigMode.Both];
     * legacy default-marker sentinels (`__default`) are ignored — defaults
     * live in [defaultThemes].
     */
    private fun parseCustomThemes(el: JsonElement?): Map<String, Theme>? {
        val obj = (el as? JsonObject) ?: return null
        val out = linkedMapOf<String, Theme>()
        for ((name, value) in obj) {
            val o = value as? JsonObject ?: continue
            if (o["__default"]?.jsonPrimitive?.booleanOrNull == true) continue
            val colorScheme = o["colorScheme"]?.jsonPrimitive?.contentOrNull ?: continue
            val modeStr = o["mode"]?.jsonPrimitive?.contentOrNull
            val mode = modeStr?.let { runCatching { ConfigMode.valueOf(it) }.getOrNull() } ?: ConfigMode.Both
            out[name] = Theme(
                name = name,
                mode = mode,
                colorScheme = colorScheme,
                sidebar = o["theme.sidebar"]?.jsonPrimitive?.contentOrNull ?: "",
                terminal = o["theme.terminal"]?.jsonPrimitive?.contentOrNull ?: "",
                diff = o["theme.diff"]?.jsonPrimitive?.contentOrNull ?: "",
                fileBrowser = o["theme.fileBrowser"]?.jsonPrimitive?.contentOrNull ?: "",
                tabs = o["theme.tabs"]?.jsonPrimitive?.contentOrNull ?: "",
                chrome = o["theme.chrome"]?.jsonPrimitive?.contentOrNull ?: "",
                windows = o["theme.windows"]?.jsonPrimitive?.contentOrNull ?: "",
                active = o["theme.active"]?.jsonPrimitive?.contentOrNull ?: "",
                bottomBar = o["theme.bottomBar"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
        return out
    }

    /** Parse a JsonArray-of-strings element into a Kotlin list. */
    private fun parseStringList(el: JsonElement?): List<String>? {
        val arr = (el as? JsonArray) ?: return null
        return arr.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    /**
     * Look up a theme bundle by name. Custom themes take precedence
     * over [defaultThemes].
     */
    fun lookupTheme(cur: AppBackingViewModel.State, name: String): Theme? {
        if (name.isEmpty()) return null
        cur.customThemes[name]?.let { return it }
        return defaultThemes.firstOrNull { it.name == name }
    }

    /**
     * Look up a colour scheme by name. Custom schemes take precedence
     * over [recommendedColorSchemes].
     */
    fun lookupScheme(cur: AppBackingViewModel.State, name: String): ColorScheme? {
        if (name.isEmpty()) return null
        cur.customSchemes[name]?.let { return it.toColorScheme() }
        return recommendedColorSchemes.firstOrNull { it.name == name }
    }

    /**
     * Apply the active theme (chosen by appearance + light/dark slot
     * selection) to [cur] and return the updated copy with the main
     * [ColorScheme] and per-section override fields populated.
     */
    fun refreshActiveTheme(
        cur: AppBackingViewModel.State,
        systemIsDark: Boolean,
    ): AppBackingViewModel.State {
        val effectiveDark = when (cur.appearance) {
            Appearance.Dark -> true
            Appearance.Light -> false
            Appearance.Auto -> systemIsDark
        }
        val selectedName = if (effectiveDark) cur.darkThemeName else cur.lightThemeName
        val preset = lookupTheme(cur, selectedName)
            ?: Theme(name = selectedName, colorScheme = selectedName)
        val main = lookupScheme(cur, preset.colorScheme)
            ?: lookupScheme(cur, DEFAULT_THEME_NAME)
            ?: recommendedColorSchemes.first()
        fun sec(s: String): ColorScheme? = lookupScheme(cur, s)
        return cur.copy(
            theme = main,
            sidebarTheme = sec(preset.sidebar),
            terminalTheme = sec(preset.terminal),
            diffTheme = sec(preset.diff),
            fileBrowserTheme = sec(preset.fileBrowser),
            tabsTheme = sec(preset.tabs),
            chromeTheme = sec(preset.chrome),
            windowsTheme = sec(preset.windows),
            activeTheme = sec(preset.active),
            bottomBarTheme = sec(preset.bottomBar),
        )
    }

    /** Build the JSON blob for the current customSchemes map. */
    fun buildCustomSchemesJson(schemes: Map<String, CustomScheme>): JsonObject =
        buildJsonObject {
            put("customSchemes", JsonObject(schemes.mapValues { (_, s) ->
                buildJsonObject {
                    put("darkFg", JsonPrimitive(s.darkFg))
                    put("lightFg", JsonPrimitive(s.lightFg))
                    put("darkBg", JsonPrimitive(s.darkBg))
                    put("lightBg", JsonPrimitive(s.lightBg))
                    if (s.overrides.isNotEmpty()) {
                        put("overrides", JsonObject(s.overrides.mapValues { JsonPrimitive(it.value) }))
                    }
                }
            }))
        }

    /** Build the JSON blob for the current customThemes map. */
    fun buildCustomThemesJson(themes: Map<String, Theme>): JsonObject =
        buildJsonObject {
            put("themeConfigs", JsonObject(themes.mapValues { (_, t) ->
                buildJsonObject {
                    put("mode", JsonPrimitive(t.mode.name))
                    put("colorScheme", JsonPrimitive(t.colorScheme))
                    put("theme.sidebar", JsonPrimitive(t.sidebar))
                    put("theme.terminal", JsonPrimitive(t.terminal))
                    put("theme.diff", JsonPrimitive(t.diff))
                    put("theme.fileBrowser", JsonPrimitive(t.fileBrowser))
                    put("theme.tabs", JsonPrimitive(t.tabs))
                    put("theme.chrome", JsonPrimitive(t.chrome))
                    put("theme.windows", JsonPrimitive(t.windows))
                    put("theme.active", JsonPrimitive(t.active))
                    put("theme.bottomBar", JsonPrimitive(t.bottomBar))
                }
            }))
        }

    /** Default light theme name fallback used when slots get orphaned. */
    val defaultLightThemeName: String get() = DEFAULT_LIGHT_THEME_NAME

    /** Default dark theme name fallback used when slots get orphaned. */
    val defaultDarkThemeName: String get() = DEFAULT_DARK_THEME_NAME
}
