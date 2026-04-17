package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

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

fun startTabRename(labelEl: HTMLElement, tabId: String) {
    val current = labelEl.textContent ?: ""
    val parent = labelEl.parentElement ?: return
    parent.classList.add("renaming")
    val openMenu = parent.querySelector(".tab-menu.open") as? HTMLElement
    openMenu?.classList?.remove("open")
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
