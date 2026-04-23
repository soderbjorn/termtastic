/**
 * Inline rename handlers for pane titles and tab labels in the Termtastic web frontend.
 *
 * Provides the UI interaction for renaming: replaces the title/label element with
 * a text input, handles commit (Enter/blur) and cancel (Escape), and sends the
 * appropriate [WindowCommand] to persist the new name on the server.
 *
 * @see startRename
 * @see startTabRename
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

/**
 * Starts an inline rename interaction for a pane title.
 *
 * Replaces the title element with a focused, pre-selected text input. On commit
 * (Enter or blur), sends [WindowCommand.Rename] to the server with the new title.
 * On cancel (Escape), restores the original title element without changes.
 *
 * @param titleEl the DOM element displaying the current pane title
 * @param paneId the unique pane identifier for the rename command
 * @see buildPaneHeader
 */
fun startRename(titleEl: HTMLElement, paneId: String) {
    val current = titleEl.textContent ?: ""
    val parent = titleEl.parentElement ?: return
    val input = document.createElement("input") as HTMLInputElement
    input.type = "text"
    input.className = "terminal-title-input"
    input.value = current
    parent.replaceChild(input, titleEl)
    input.focus()
    input.select()

    var settled = false
    fun cancel() {
        if (settled) return
        settled = true
        if (input.parentElement === parent) parent.replaceChild(titleEl, input)
    }
    fun commit() {
        if (settled) return
        settled = true
        val newTitle = input.value.trim()
        if (newTitle == current) {
            if (input.parentElement === parent) parent.replaceChild(titleEl, input)
            return
        }
        launchCmd(WindowCommand.Rename(paneId = paneId, title = newTitle))
        if (newTitle.isEmpty()) {
            if (input.parentElement === parent) parent.replaceChild(titleEl, input)
            return
        }
    }

    input.addEventListener("blur", { commit() })
    input.addEventListener("keydown", { ev ->
        val key = (ev as KeyboardEvent).key
        when (key) {
            "Enter" -> { ev.preventDefault(); commit() }
            "Escape" -> { ev.preventDefault(); cancel() }
        }
    })
    input.addEventListener("click", { ev -> ev.stopPropagation() })
}

/**
 * Starts an inline rename interaction for a tab label.
 *
 * Similar to [startRename] but operates on tab labels in the tab bar. Closes
 * any open tab menus, adds a "renaming" CSS class during editing, and sends
 * [WindowCommand.RenameTab] to the server on commit.
 *
 * @param labelEl the DOM element displaying the current tab label
 * @param tabId the unique tab identifier for the rename command
 * @see renderConfig
 */
fun startTabRename(labelEl: HTMLElement, tabId: String) {
    val current = labelEl.textContent ?: ""
    val parent = labelEl.parentElement ?: return
    parent.classList.add("renaming")
    val input = document.createElement("input") as HTMLInputElement
    input.type = "text"
    input.className = "tab-label-input"
    input.value = current
    parent.replaceChild(input, labelEl)
    input.focus()
    input.select()

    var settled = false
    fun cancel() {
        if (settled) return
        settled = true
        if (input.parentElement === parent) parent.replaceChild(labelEl, input)
        parent.classList.remove("renaming")
    }
    fun commit() {
        if (settled) return
        settled = true
        val newTitle = input.value.trim()
        if (newTitle.isEmpty() || newTitle == current) {
            if (input.parentElement === parent) parent.replaceChild(labelEl, input)
            parent.classList.remove("renaming")
            return
        }
        parent.classList.remove("renaming")
        launchCmd(WindowCommand.RenameTab(tabId = tabId, title = newTitle))
    }

    input.addEventListener("blur", { commit() })
    input.addEventListener("keydown", { ev ->
        val key = (ev as KeyboardEvent).key
        when (key) {
            "Enter" -> { ev.preventDefault(); commit() }
            "Escape" -> { ev.preventDefault(); cancel() }
        }
    })
    input.addEventListener("click", { ev -> ev.stopPropagation() })
    input.addEventListener("dblclick", { ev -> ev.stopPropagation() })
}
