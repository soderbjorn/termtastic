/**
 * About dialog for the Termtastic web frontend.
 *
 * Displays application information including version, copyright, a link to the
 * GitHub repository, and a list of third-party dependencies. The dialog reuses
 * the same modal overlay pattern as [showConfirmDialog] and [showPaneTypeModal].
 *
 * @see showAboutDialog
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/** Application version displayed in the about dialog. */
private const val APP_VERSION = "0.1.0"

/** Copyright notice displayed in the about dialog. */
private const val COPYRIGHT = "Copyright \u00A9 2026 Robert S\u00F6derbjorn"

/** GitHub repository URL. */
private const val GITHUB_URL = "https://github.com/soderbjorn/termtastic"

/**
 * Third-party dependency entry shown in the about dialog.
 *
 * @property name the library display name
 * @property version the version string
 */
private data class Dependency(val name: String, val version: String)

/** Third-party libraries used by Termtastic. */
private val DEPENDENCIES = listOf(
    Dependency("Kotlin", "2.3.20"),
    Dependency("Ktor", "3.4.1"),
    Dependency("xterm.js", "5.3.0"),
    Dependency("Electron", "32.x"),
    Dependency("kotlinx.serialization", "1.9.0"),
    Dependency("kotlinx.coroutines", "1.10.2"),
    Dependency("SQLDelight", "2.0.2"),
    Dependency("pty4j", "0.13.8"),
    Dependency("JediTerm", "3.49"),
    Dependency("Flexmark", "0.64.8"),
    Dependency("Jsoup", "1.17.2"),
    Dependency("Logback", "1.5.32"),
)

/**
 * Opens a modal "About" dialog showing application info and third-party
 * dependencies.
 *
 * Only one dialog can be visible at a time (guards against duplicates via
 * element ID). The dialog can be dismissed by clicking the close button,
 * clicking the overlay backdrop, or pressing Escape.
 *
 * Called by the about button click handler in [main].
 */
fun showAboutDialog() {
    if (document.getElementById("about-dialog") != null) return

    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "about-dialog"
    overlay.className = "pane-modal-overlay"

    val card = document.createElement("div") as HTMLElement
    card.className = "pane-modal about-dialog"
    card.addEventListener("click", { ev: Event -> ev.stopPropagation() })

    // Close button.
    val closeBtn = document.createElement("button") as HTMLElement
    closeBtn.className = "pane-modal-close"
    (closeBtn.asDynamic()).type = "button"
    closeBtn.innerHTML = "&times;"
    closeBtn.addEventListener("click", { _: Event -> overlay.remove() })
    card.appendChild(closeBtn)

    // App name.
    val title = document.createElement("h2") as HTMLElement
    title.className = "pane-modal-title"
    title.textContent = "Termtastic"
    card.appendChild(title)

    // Version.
    val version = document.createElement("p") as HTMLElement
    version.className = "about-version"
    version.textContent = "Version $APP_VERSION"
    card.appendChild(version)

    // Copyright.
    val copyright = document.createElement("p") as HTMLElement
    copyright.className = "about-copyright"
    copyright.textContent = COPYRIGHT
    card.appendChild(copyright)

    // GitHub link.
    val linkRow = document.createElement("p") as HTMLElement
    linkRow.className = "about-link-row"
    val link = document.createElement("a") as HTMLAnchorElement
    link.className = "about-link"
    link.href = GITHUB_URL
    link.target = "_blank"
    link.rel = "noopener noreferrer"
    link.textContent = GITHUB_URL
    linkRow.appendChild(link)
    card.appendChild(linkRow)

    // Third-party dependencies.
    val depsTitle = document.createElement("h3") as HTMLElement
    depsTitle.className = "about-section-title"
    depsTitle.textContent = "Third-Party Libraries"
    card.appendChild(depsTitle)

    val depsList = document.createElement("ul") as HTMLElement
    depsList.className = "about-deps"
    for (dep in DEPENDENCIES) {
        val li = document.createElement("li") as HTMLElement
        val nameSpan = document.createElement("span") as HTMLElement
        nameSpan.className = "about-dep-name"
        nameSpan.textContent = dep.name
        val versionSpan = document.createElement("span") as HTMLElement
        versionSpan.className = "about-dep-version"
        versionSpan.textContent = dep.version
        li.appendChild(nameSpan)
        li.appendChild(versionSpan)
        depsList.appendChild(li)
    }
    card.appendChild(depsList)

    overlay.appendChild(card)

    // Dismiss on backdrop click.
    overlay.addEventListener("click", { ev: Event ->
        if (ev.target === overlay) overlay.remove()
    })

    // Dismiss on Escape.
    var escHandler: ((Event) -> Unit)? = null
    escHandler = { ev: Event ->
        if ((ev as KeyboardEvent).key == "Escape") {
            overlay.remove()
            escHandler?.let { document.removeEventListener("keydown", it) }
        }
    }
    document.addEventListener("keydown", escHandler)

    document.body?.appendChild(overlay)
}
