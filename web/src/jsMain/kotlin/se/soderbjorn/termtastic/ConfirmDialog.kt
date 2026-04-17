package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

fun showConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Close",
    onConfirm: () -> Unit,
) {
    if (document.getElementById("confirm-dialog") != null) return
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "confirm-dialog"
    overlay.className = "pane-modal-overlay"

    val card = document.createElement("div") as HTMLElement
    card.className = "pane-modal confirm-modal"
    card.addEventListener("click", { ev: Event -> ev.stopPropagation() })

    val titleEl = document.createElement("h2") as HTMLElement
    titleEl.className = "pane-modal-title"
    titleEl.textContent = title
    card.appendChild(titleEl)

    val msg = document.createElement("p") as HTMLElement
    msg.className = "confirm-message"
    msg.innerHTML = message
    card.appendChild(msg)

    val btnRow = document.createElement("div") as HTMLElement
    btnRow.className = "confirm-buttons"

    val cancelBtn = document.createElement("button") as HTMLElement
    cancelBtn.className = "confirm-btn confirm-cancel"
    (cancelBtn.asDynamic()).type = "button"
    cancelBtn.textContent = "Cancel"
    cancelBtn.addEventListener("click", { _: Event -> overlay.remove() })
    btnRow.appendChild(cancelBtn)

    val confirmBtn = document.createElement("button") as HTMLElement
    confirmBtn.className = "confirm-btn confirm-ok"
    (confirmBtn.asDynamic()).type = "button"
    confirmBtn.textContent = confirmLabel
    confirmBtn.addEventListener("click", { _: Event ->
        overlay.remove()
        onConfirm()
    })
    btnRow.appendChild(confirmBtn)

    card.appendChild(btnRow)
    overlay.appendChild(card)
    overlay.addEventListener("click", { ev: Event ->
        if (ev.target === overlay) overlay.remove()
    })
    var escHandler: ((Event) -> Unit)? = null
    escHandler = { ev: Event ->
        if ((ev as KeyboardEvent).key == "Escape") {
            overlay.remove()
            escHandler?.let { document.removeEventListener("keydown", it) }
        }
    }
    document.addEventListener("keydown", escHandler)
    document.body?.appendChild(overlay)
    confirmBtn.focus()
}
