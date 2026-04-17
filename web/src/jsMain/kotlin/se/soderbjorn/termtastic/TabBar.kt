package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.encodeToString
import org.w3c.dom.HTMLElement

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

fun snapActiveIndicator() {
    val tabBar = tabBarEl ?: return
    val indicator = tabBar.querySelector(".tab-active-indicator") as? HTMLElement ?: return
    indicator.classList.add("no-transition")
    positionActiveIndicator()
    @Suppress("UNUSED_VARIABLE")
    val _force = indicator.asDynamic().offsetWidth
    window.requestAnimationFrame { indicator.classList.remove("no-transition") }
}

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
