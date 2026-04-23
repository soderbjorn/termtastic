/**
 * Tab bar component for the Termtastic web frontend.
 *
 * Manages the tab strip at the top of the application, including the sliding
 * active tab indicator, tab switching with CSS transitions, FLIP animations
 * for drag-and-drop tab reordering, and scroll-into-view behavior.
 *
 * @see positionActiveIndicator
 * @see setActiveTab
 * @see runTabFlip
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.encodeToString
import org.w3c.dom.HTMLElement

/**
 * Positions the sliding active tab indicator under the currently active tab button.
 *
 * Reads the active button's position and size and applies a CSS transform and
 * dimensions to the indicator element. Called after tab switches and window resizes.
 */
fun positionActiveIndicator() {
    val tabBar = tabBarEl ?: return
    val indicator = tabBar.querySelector(".tab-active-indicator") as? HTMLElement ?: return
    val active = tabBar.querySelector(".tab-button.active") as? HTMLElement
    if (active == null) { indicator.classList.remove("ready"); return }
    val left = (active.asDynamic().offsetLeft as Number).toDouble()
    val top = (active.asDynamic().offsetTop as Number).toDouble()
    val width = (active.asDynamic().offsetWidth as Number).toDouble()
    val height = (active.asDynamic().offsetHeight as Number).toDouble()
    indicator.style.transform = "translate3d(${left}px, 0, 0)"
    indicator.style.top = "${top}px"
    indicator.style.width = "${width}px"
    indicator.style.height = "${height}px"
    indicator.classList.add("ready")
}

/**
 * Instantly positions the active tab indicator without CSS transitions.
 *
 * Used on first render to avoid an initial animation from the origin. Temporarily
 * adds a "no-transition" class, positions the indicator, forces a reflow, then
 * re-enables transitions on the next animation frame.
 */
fun snapActiveIndicator() {
    val tabBar = tabBarEl ?: return
    val indicator = tabBar.querySelector(".tab-active-indicator") as? HTMLElement ?: return
    indicator.classList.add("no-transition")
    positionActiveIndicator()
    @Suppress("UNUSED_VARIABLE")
    val _force = indicator.asDynamic().offsetWidth
    window.requestAnimationFrame { indicator.classList.remove("no-transition") }
}

/**
 * Pins the active tab indicator to the shifting active button while any
 * `.tab-button.entering` is playing its `tab-enter` animation (320ms
 * max-width 0 → 240px keyframe). Without this, the indicator is positioned
 * once at the start of that animation — when the newly-inserted tab is
 * still zero-width — and ends up lagging behind the active tab as the
 * neighboring buttons reflow. The symptom was the orange ring visibly
 * offset to the right of the active tab after unhiding.
 *
 * Transitions are disabled for the duration so the indicator snaps each
 * frame instead of chasing its target via the 170ms transform transition
 * (which would leave it permanently ~170ms behind the tab's motion).
 * Once there are no more entering tabs, transitions are re-enabled on the
 * next frame so future tab switches animate normally.
 */
fun trackIndicatorThroughTabEnter() {
    val tabBar = tabBarEl ?: return
    val indicator = tabBar.querySelector(".tab-active-indicator") as? HTMLElement ?: return
    if (tabBar.querySelector(".tab-button.entering") == null) return
    indicator.classList.add("no-transition")
    lateinit var step: () -> Unit
    step = {
        positionActiveIndicator()
        if (tabBar.querySelector(".tab-button.entering") != null) {
            window.requestAnimationFrame { step() }
        } else {
            window.requestAnimationFrame { indicator.classList.remove("no-transition") }
        }
    }
    window.requestAnimationFrame { step() }
}

/**
 * Scrolls the tab bar horizontally to ensure the active tab button is visible.
 *
 * Adds a small margin (8px) to avoid the button being flush with the edge.
 */
fun scrollActiveTabIntoView() {
    val tabBar = tabBarEl ?: return
    val active = tabBar.querySelector(".tab-button.active") as? HTMLElement ?: return
    val barRect = tabBar.asDynamic().getBoundingClientRect()
    val btnRect = active.asDynamic().getBoundingClientRect()
    val barLeft = (barRect.left as Double); val barRight = (barRect.right as Double)
    val btnLeft = (btnRect.left as Double); val btnRight = (btnRect.right as Double)
    if (btnLeft < barLeft) tabBar.scrollLeft -= (barLeft - btnLeft) + 8
    else if (btnRight > barRight) tabBar.scrollLeft += (btnRight - barRight) + 8
}

/**
 * Switches the active tab, updating CSS classes on tab buttons and pane sections,
 * scrolling the tab into view, repositioning the indicator, and refitting terminals.
 *
 * Sends [WindowCommand.SetActiveTab] to the server to persist the selection.
 * Uses a transition listener on the new tab pane to defer terminal fitting until
 * the fade-in animation completes.
 *
 * @param tabId the ID of the tab to activate
 */
fun setActiveTab(tabId: String) {
    if (activeTabId == tabId) return
    activeTabId = tabId
    launchCmd(WindowCommand.SetActiveTab(tabId = tabId))
    val tabBar = tabBarEl ?: return
    val wrap = terminalWrapEl ?: return
    val buttons = tabBar.querySelectorAll(".tab-button")
    for (i in 0 until buttons.length) {
        val btn = buttons.item(i) as HTMLElement
        val id = btn.getAttribute("data-tab")
        if (id == tabId) btn.classList.add("active") else btn.classList.remove("active")
    }
    val panes = wrap.querySelectorAll(".tab-pane")
    for (i in 0 until panes.length) {
        val pane = panes.item(i) as HTMLElement
        if (pane.id == tabId) pane.classList.add("active") else pane.classList.remove("active")
    }
    scrollActiveTabIntoView()
    positionActiveIndicator()
    val newPane = wrap.querySelector("#$tabId.tab-pane.active") as? HTMLElement
    var refit = false
    fun doRefit() {
        if (refit) return; refit = true
        fitVisible(); focusFirstPaneInActiveTab()
    }
    if (newPane != null) {
        lateinit var listener: (org.w3c.dom.events.Event) -> Unit
        listener = { ev ->
            if (ev.target === newPane && (ev.asDynamic().propertyName as? String) == "opacity") {
                newPane.removeEventListener("transitionend", listener); doRefit()
            }
        }
        newPane.addEventListener("transitionend", listener)
    }
    window.setTimeout({ doRefit() }, 320)
}

/**
 * Performs a FLIP (First, Last, Invert, Play) animation on tab buttons after
 * a drag-and-drop tab reorder.
 *
 * Compares each tab button's current position to its position in the pre-reorder
 * snapshot, applies an inverse translateX transform, then removes it in the next
 * animation frame to trigger a smooth CSS transition.
 *
 * @param snapshot a map of tab IDs to their previous left-edge positions (in pixels)
 */
fun runTabFlip(snapshot: Map<String, Double>) {
    val tabBar = tabBarEl ?: return
    val buttons = tabBar.querySelectorAll(".tab-button")
    val moved = mutableListOf<HTMLElement>()
    for (i in 0 until buttons.length) {
        val btn = buttons.item(i) as HTMLElement
        val id = btn.getAttribute("data-tab") ?: continue
        val oldLeft = snapshot[id] ?: continue
        val newLeft = (btn.asDynamic().getBoundingClientRect().left as Number).toDouble()
        val dx = oldLeft - newLeft
        if (kotlin.math.abs(dx) < 0.5) continue
        btn.style.transform = "translateX(${dx}px)"
        moved.add(btn)
    }
    if (moved.isEmpty()) return
    @Suppress("UNUSED_VARIABLE")
    val _force = tabBar.asDynamic().offsetWidth
    window.requestAnimationFrame {
        for (btn in moved) { btn.classList.add("flip-animating"); btn.style.transform = "" }
        window.setTimeout({ for (btn in moved) btn.classList.remove("flip-animating") }, 320)
    }
}
