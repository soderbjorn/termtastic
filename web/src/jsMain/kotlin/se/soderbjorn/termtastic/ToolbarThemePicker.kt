/**
 * Toolbar theme button for the Termtastic web frontend.
 *
 * Renders a header icon button (palette) that, on click, opens the Theme
 * Manager sidebar. The button's tooltip updates reactively whenever the
 * ViewModel state changes (appearance switch, new favourite, slot
 * reassignment, clone/delete) so hovering reveals the currently-active
 * theme name.
 *
 * @see showThemeManager
 * @see AppBackingViewModel
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.core.*

import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Wire up the toolbar theme button: click opens the Theme Manager, tooltip
 * stays in sync with the currently-active theme via a reactive state flow
 * collector.
 *
 * Must be called once during app boot, after the DOM is ready.
 */
internal fun initToolbarThemeButton() {
    val button = document.getElementById("theme-button") as? HTMLElement ?: return

    fun refreshButton() {
        val state = appVm.stateFlow.value
        val activeDark = !isLightActive(state.appearance)
        val activeName = if (activeDark) state.darkThemeName else state.lightThemeName
        button.title = "Active ${if (activeDark) "dark" else "light"} theme: $activeName"
    }

    button.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        // Toggle: if the theme manager is already open, close it with the
        // usual slide-out animation instead of re-opening.
        if (themeManagerPanel != null) {
            closeThemeManager()
        } else {
            showThemeManager(initialTab = "themes")
        }
    })

    refreshButton()
    GlobalScope.launch {
        var prevLight = appVm.stateFlow.value.lightThemeName
        var prevDark = appVm.stateFlow.value.darkThemeName
        var prevAppearance = appVm.stateFlow.value.appearance
        appVm.stateFlow.collect { state ->
            if (state.lightThemeName != prevLight || state.darkThemeName != prevDark ||
                state.appearance != prevAppearance
            ) {
                prevLight = state.lightThemeName
                prevDark = state.darkThemeName
                prevAppearance = state.appearance
                refreshButton()
            }
        }
    }
}
