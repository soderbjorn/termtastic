/**
 * Git diff/status pane UI component for the Termtastic web frontend.
 *
 * Renders a split-panel view with a list of uncommitted changed files on the left
 * and a diff viewer on the right. Supports inline, split, and graphical (side-by-side
 * with SVG connector lines) diff modes, text search within diffs, auto-refresh,
 * and a draggable column divider.
 *
 * File lists and diffs are fetched from the server via [WindowCommand.GitList] and
 * [WindowCommand.GitDiff]. Incoming data is routed through [handlePaneContentMessage]
 * in [WindowConnection].
 *
 * @see GitPaneState
 * @see GitPaneView
 * @see buildGitView
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.MouseEvent
import kotlin.js.json

/**
 * Mutable client-side state for a single git pane.
 *
 * Tracks the list of changed file entries, the currently selected file,
 * cached diff HTML, diff mode preferences, and search state. Persists
 * across re-renders via the [gitPaneStates] registry in [WebState].
 *
 * @see GitPaneView
 * @see renderGitList
 */
class GitPaneState {
    var entries: dynamic = null
    var selectedFilePath: String? = null
    var diffHtml: String? = null
    var diffMode: String = "Inline"
    var graphicalDiff: Boolean = false
    var diffFontSize: Int = 12
    var searchQuery: String = ""
    var searchMatchIndex: Int = 0
}

/**
 * Holds references to the live DOM elements of a git pane's panels and search controls.
 *
 * @property listBody the scrollable container for the changed files list
 * @property diffPane the container for the rendered diff content
 * @property searchCounter the element displaying "N/M" search match counter
 * @property searchNavButtons navigation buttons for stepping through search matches
 */
class GitPaneView(
    val listBody: HTMLElement,
    val diffPane: HTMLElement,
    var searchCounter: HTMLElement? = null,
    var searchNavButtons: List<HTMLElement> = emptyList(),
)

/**
 * Updates the "active" CSS class on file list items to highlight the currently selected file.
 *
 * @param view the git pane's live DOM handles
 * @param filePath the path of the currently selected file, or null to deselect all
 */
fun updateGitListActiveFile(view: GitPaneView, filePath: String?) {
    val items = view.listBody.querySelectorAll(".git-list-item")
    for (i in 0 until items.length) {
        val el = items.item(i) as HTMLElement
        val path = el.querySelector(".git-list-item-name")?.getAttribute("title")
        if (path == filePath) el.classList.add("active") else el.classList.remove("active")
    }
}

/**
 * Re-renders the changed files list in the left panel, grouped by parent directory.
 *
 * Each file entry shows a status badge (A/M/D/R/U) and basename. Clicking a file
 * sends [WindowCommand.GitDiff] to fetch its diff. Directory sections are collapsible
 * with smooth CSS transitions.
 *
 * @param paneId the unique pane identifier
 * @param view the git pane's live DOM handles
 * @param state the current client-side git pane state
 */
fun renderGitList(paneId: String, view: GitPaneView, state: GitPaneState) {
    val savedScroll = view.listBody.scrollTop
    view.listBody.style.setProperty("--git-section-transition", "none")
    view.listBody.innerHTML = ""
    val entries = state.entries
    val arr = entries as? Array<dynamic>
    if (arr == null || arr.isEmpty()) {
        if (arr != null) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "git-list-empty"
            empty.textContent = "No uncommitted changes"
            view.listBody.appendChild(empty)
        }
        view.listBody.scrollTop = savedScroll
        val el2 = view.listBody
        kotlinx.browser.window.requestAnimationFrame { el2.style.removeProperty("--git-section-transition") }
        return
    }

    val groups = LinkedHashMap<String, MutableList<dynamic>>()
    for (entry in arr) {
        val dir = entry.directory as String
        groups.getOrPut(dir) { mutableListOf() }.add(entry)
    }

    for ((parent, items) in groups) {
        val sectionKey = "$paneId|$parent"
        val isCollapsed = sectionKey in collapsedGitSections

        val section = document.createElement("div") as HTMLElement
        section.className = "git-list-section"

        val headline = document.createElement("div") as HTMLElement
        headline.className = "git-list-section-header"
        if (isCollapsed) headline.classList.add("collapsed-tree")

        val chevron = document.createElement("span") as HTMLElement
        chevron.className = "git-list-section-chevron"
        chevron.textContent = "\u25BE"
        headline.appendChild(chevron)

        val titleSpan = document.createElement("span") as HTMLElement
        titleSpan.className = "git-list-section-title"
        titleSpan.textContent = if (parent.isEmpty()) "(root)" else parent
        titleSpan.title = titleSpan.textContent ?: ""
        headline.appendChild(titleSpan)
        section.appendChild(headline)

        val childrenEl = document.createElement("div") as HTMLElement
        childrenEl.className = "git-list-section-children"
        if (isCollapsed) childrenEl.classList.add("collapsed-tree")

        headline.addEventListener("mousedown", { ev -> ev.stopPropagation() })
        headline.addEventListener("click", { _ ->
            if (sectionKey in collapsedGitSections) {
                collapsedGitSections.remove(sectionKey)
                headline.classList.remove("collapsed-tree")
                childrenEl.classList.remove("collapsed-tree")
                childrenEl.style.maxHeight = "${childrenEl.scrollHeight}px"
            } else {
                collapsedGitSections.add(sectionKey)
                headline.classList.add("collapsed-tree")
                childrenEl.classList.add("collapsed-tree")
            }
        })

        for (entry in items) {
            val filePath = entry.filePath as String
            val status = entry.status as String
            val basename = filePath.substringAfterLast('/')
            val statusKey = when (status) {
                "Added" -> "A"; "Modified" -> "M"; "Deleted" -> "D"
                "Renamed" -> "R"; "Untracked" -> "U"; else -> "?"
            }
            val statusSvg = when (status) {
                "Modified" -> """<svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor"><path d="M11.013 1.427a1.75 1.75 0 0 1 2.474 0l1.086 1.086a1.75 1.75 0 0 1 0 2.474l-8.61 8.61c-.21.21-.47.364-.756.445l-3.251.93a.75.75 0 0 1-.927-.928l.929-3.25c.081-.286.235-.547.445-.758l8.61-8.61ZM11.524 2.2l-8.61 8.61a.25.25 0 0 0-.064.108l-.59 2.065 2.066-.59a.25.25 0 0 0 .108-.064l8.61-8.61a.25.25 0 0 0 0-.354l-1.086-1.086a.25.25 0 0 0-.434.079Z"/></svg>"""
                "Added" -> """<svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor"><path d="M8 0a8 8 0 1 1 0 16A8 8 0 0 1 8 0Zm.75 4.75a.75.75 0 0 0-1.5 0v2.5h-2.5a.75.75 0 0 0 0 1.5h2.5v2.5a.75.75 0 0 0 1.5 0v-2.5h2.5a.75.75 0 0 0 0-1.5h-2.5Z"/></svg>"""
                "Deleted" -> """<svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor"><path d="M8 0a8 8 0 1 1 0 16A8 8 0 0 1 8 0ZM4.5 7.25a.75.75 0 0 0 0 1.5h7a.75.75 0 0 0 0-1.5Z"/></svg>"""
                "Renamed" -> """<svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor"><path d="M1 8a.75.75 0 0 1 .75-.75h10.69L9.22 4.03a.75.75 0 0 1 1.06-1.06l4.25 4.25a.75.75 0 0 1 0 1.06l-4.25 4.25a.75.75 0 1 1-1.06-1.06l3.22-3.22H1.75A.75.75 0 0 1 1 8Z"/></svg>"""
                "Untracked" -> """<svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor"><path d="M8 0a8 8 0 1 1 0 16A8 8 0 0 1 8 0Zm.75 4.75a.75.75 0 0 0-1.5 0v2.5h-2.5a.75.75 0 0 0 0 1.5h2.5v2.5a.75.75 0 0 0 1.5 0v-2.5h2.5a.75.75 0 0 0 0-1.5h-2.5Z"/></svg>"""
                else -> """<svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor"><circle cx="8" cy="8" r="6"/></svg>"""
            }
            val item = document.createElement("div") as HTMLElement
            item.className = "git-list-item"
            if (filePath == state.selectedFilePath) item.classList.add("active")

            val badge = document.createElement("span") as HTMLElement
            badge.className = "git-status-badge git-status-$statusKey"
            badge.innerHTML = statusSvg
            val name = document.createElement("span") as HTMLElement
            name.className = "git-list-item-name"
            name.textContent = basename
            name.title = filePath
            item.appendChild(badge)
            item.appendChild(name)

            item.addEventListener("mousedown", { ev -> ev.stopPropagation() })
            item.addEventListener("click", { _ -> launchCmd(WindowCommand.GitDiff(paneId = paneId, filePath = filePath)) })
            childrenEl.appendChild(item)
        }
        section.appendChild(childrenEl)
        if (!isCollapsed) childrenEl.style.maxHeight = "9999px"
        view.listBody.appendChild(section)
    }
    view.listBody.scrollTop = savedScroll
    val el = view.listBody
    kotlinx.browser.window.requestAnimationFrame { el.style.removeProperty("--git-section-transition") }
}

/**
 * Removes all search highlight marks from the diff content, restoring the original text.
 *
 * @param diffContent the diff container element to clear search highlights from
 * @see performDiffSearch
 */
fun clearDiffSearch(diffContent: HTMLElement) {
    val marks = diffContent.querySelectorAll("mark.git-search-hit")
    for (i in 0 until marks.length) {
        val mark = marks.item(i) as HTMLElement
        val parent = mark.parentNode ?: continue
        val text = document.createTextNode(mark.textContent ?: "")
        parent.replaceChild(text, mark)
        (parent as HTMLElement).normalize()
    }
}

/**
 * Performs a case-insensitive text search within the diff content, wrapping matches
 * in `<mark>` elements and scrolling the current match into view.
 *
 * @param diffContent the diff container element to search within
 * @param query the search string (case-insensitive); empty string clears highlights
 * @param state the git pane state holding the current match index
 * @param counterLabel the DOM element to update with "N/M" match count text
 * @param navButtons the search navigation buttons to enable/disable
 * @see navigateDiffSearch
 * @see clearDiffSearch
 */
fun performDiffSearch(
    diffContent: HTMLElement, query: String, state: GitPaneState,
    counterLabel: HTMLElement, navButtons: List<HTMLElement>,
) {
    clearDiffSearch(diffContent)
    if (query.isEmpty()) {
        state.searchMatchIndex = 0; counterLabel.textContent = ""
        navButtons.forEach { it.asDynamic().disabled = true }; return
    }
    val lowerQuery = query.lowercase()
    val matches = mutableListOf<HTMLElement>()
    val walker = document.asDynamic().createTreeWalker(diffContent, 4, null)
    val textNodes = mutableListOf<org.w3c.dom.Text>()
    while (true) { val node = walker.nextNode() ?: break; textNodes.add(node as org.w3c.dom.Text) }
    for (textNode in textNodes) {
        val text = textNode.textContent ?: continue
        val lowerText = text.lowercase()
        val offsets = mutableListOf<Int>()
        var searchFrom = 0
        while (searchFrom < text.length) {
            val idx = lowerText.indexOf(lowerQuery, searchFrom)
            if (idx < 0) break; offsets.add(idx); searchFrom = idx + 1
        }
        if (offsets.isEmpty()) continue
        val parent = textNode.parentNode ?: continue
        val frag = document.createDocumentFragment()
        var pos = 0
        for (off in offsets) {
            if (off > pos) frag.appendChild(document.createTextNode(text.substring(pos, off)))
            val mark = document.createElement("mark") as HTMLElement
            mark.className = "git-search-hit"
            mark.textContent = text.substring(off, off + query.length)
            frag.appendChild(mark); matches.add(mark); pos = off + query.length
        }
        if (pos < text.length) frag.appendChild(document.createTextNode(text.substring(pos)))
        parent.replaceChild(frag, textNode)
    }
    val total = matches.size
    if (total == 0) {
        state.searchMatchIndex = 0; counterLabel.textContent = "0/0"
        navButtons.forEach { it.asDynamic().disabled = true }; return
    }
    state.searchMatchIndex = state.searchMatchIndex.coerceIn(0, total - 1)
    navButtons.forEach { it.asDynamic().disabled = false }
    matches.getOrNull(state.searchMatchIndex)?.classList?.add("git-search-current")
    matches.getOrNull(state.searchMatchIndex)?.scrollIntoView(js("{block:'nearest',behavior:'smooth'}"))
    counterLabel.textContent = "${state.searchMatchIndex + 1}/$total"
}

/**
 * Navigates to the next, previous, first, or last search match within the diff.
 *
 * @param diffContent the diff container element containing highlighted matches
 * @param state the git pane state holding the current match index
 * @param counterLabel the DOM element to update with the new "N/M" position
 * @param delta the navigation direction: 1 for next, -1 for previous,
 *              [Int.MIN_VALUE] for first, [Int.MAX_VALUE] for last
 * @see performDiffSearch
 */
fun navigateDiffSearch(diffContent: HTMLElement, state: GitPaneState, counterLabel: HTMLElement, delta: Int) {
    val marks = diffContent.querySelectorAll("mark.git-search-hit")
    val total = marks.length
    if (total == 0) return
    (marks.item(state.searchMatchIndex) as? HTMLElement)?.classList?.remove("git-search-current")
    state.searchMatchIndex = when (delta) {
        Int.MIN_VALUE -> 0
        Int.MAX_VALUE -> total - 1
        else -> {
            var n = state.searchMatchIndex + delta
            if (n < 0) n = total - 1; if (n >= total) n = 0; n
        }
    }
    val current = marks.item(state.searchMatchIndex) as? HTMLElement
    current?.classList?.add("git-search-current")
    current?.scrollIntoView(js("{block:'nearest',behavior:'smooth'}"))
    counterLabel.textContent = "${state.searchMatchIndex + 1}/$total"
}

/**
 * Renders a unified/inline diff view with old and new line numbers side by side.
 *
 * Addition lines are highlighted green, deletion lines red, and context lines are plain.
 *
 * @param diffPane the container element to render the diff into
 * @param parsed the dynamic diff data from the server containing hunks and lines
 */
fun renderGitDiffInline(diffPane: HTMLElement, parsed: dynamic) {
    diffPane.innerHTML = ""
    val hunks = parsed.hunks as Array<dynamic>
    if (hunks.isEmpty()) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "git-diff-empty"; empty.textContent = "No changes"
        diffPane.appendChild(empty); return
    }
    val container = document.createElement("div") as HTMLElement
    container.className = "git-diff-inline"
    for (hunk in hunks) {
        val hunkDiv = document.createElement("div") as HTMLElement
        hunkDiv.className = "git-hunk"
        val lines = hunk.lines as Array<dynamic>
        for (line in lines) {
            val lineDiv = document.createElement("div") as HTMLElement
            lineDiv.className = "git-diff-line"
            val type = line.type as String
            when (type) { "Addition" -> lineDiv.classList.add("git-line-add"); "Deletion" -> lineDiv.classList.add("git-line-del") }
            val oldNo = document.createElement("span") as HTMLElement
            oldNo.className = "git-line-no"; oldNo.textContent = (line.oldLineNo as? Number)?.toString() ?: ""
            val newNo = document.createElement("span") as HTMLElement
            newNo.className = "git-line-no"; newNo.textContent = (line.newLineNo as? Number)?.toString() ?: ""
            val content = document.createElement("span") as HTMLElement
            content.className = "git-line-content"; content.innerHTML = line.content as String
            lineDiv.appendChild(oldNo); lineDiv.appendChild(newNo); lineDiv.appendChild(content)
            hunkDiv.appendChild(lineDiv)
        }
        container.appendChild(hunkDiv)
    }
    diffPane.appendChild(container)
}

/**
 * Renders a side-by-side (split) diff view with the old file on the left and
 * the new file on the right. Paired additions/deletions appear on the same row;
 * unpaired changes show a placeholder on the opposite side.
 *
 * @param diffPane the container element to render the diff into
 * @param parsed the dynamic diff data from the server containing hunks and lines
 */
fun renderGitDiffSplit(diffPane: HTMLElement, parsed: dynamic) {
    diffPane.innerHTML = ""
    val hunks = parsed.hunks as Array<dynamic>
    if (hunks.isEmpty()) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "git-diff-empty"; empty.textContent = "No changes"
        diffPane.appendChild(empty); return
    }
    val container = document.createElement("div") as HTMLElement
    container.className = "git-diff-split"

    data class SplitRow(
        val oldNo: String, val oldContent: String, val oldClass: String,
        val newNo: String, val newContent: String, val newClass: String,
    )
    val rows = mutableListOf<SplitRow>()

    for (hunk in hunks) {
        val lines = hunk.lines as Array<dynamic>
        var i = 0
        while (i < lines.size) {
            val line = lines[i]; val type = line.type as String
            if (type == "Context") {
                val no = (line.oldLineNo as? Number)?.toString() ?: ""
                val nno = (line.newLineNo as? Number)?.toString() ?: ""
                rows.add(SplitRow(no, line.content as String, "", nno, line.content as String, ""))
                i++
            } else {
                val dels = mutableListOf<dynamic>(); val adds = mutableListOf<dynamic>()
                while (i < lines.size && (lines[i].type as String) == "Deletion") { dels.add(lines[i]); i++ }
                while (i < lines.size && (lines[i].type as String) == "Addition") { adds.add(lines[i]); i++ }
                val maxLen = maxOf(dels.size, adds.size)
                for (j in 0 until maxLen) {
                    val del = dels.getOrNull(j); val add = adds.getOrNull(j)
                    val isModified = del != null && add != null
                    rows.add(SplitRow(
                        oldNo = if (del != null) (del.oldLineNo as? Number)?.toString() ?: "" else "",
                        oldContent = if (del != null) del.content as String else "",
                        oldClass = when { del == null -> "git-split-placeholder"; isModified -> "git-split-mod"; else -> "git-line-del" },
                        newNo = if (add != null) (add.newLineNo as? Number)?.toString() ?: "" else "",
                        newContent = if (add != null) add.content as String else "",
                        newClass = when { add == null -> "git-split-placeholder"; isModified -> "git-split-mod"; else -> "git-line-add" },
                    ))
                }
            }
        }
    }

    for (row in rows) {
        val rowDiv = document.createElement("div") as HTMLElement
        rowDiv.className = "git-split-row"
        val leftHalf = document.createElement("div") as HTMLElement
        leftHalf.className = "git-split-half ${row.oldClass}"
        val leftNo = document.createElement("span") as HTMLElement
        leftNo.className = "git-line-no"; leftNo.textContent = row.oldNo
        val leftContent = document.createElement("span") as HTMLElement
        leftContent.className = "git-line-content"; leftContent.innerHTML = row.oldContent
        leftHalf.appendChild(leftNo); leftHalf.appendChild(leftContent)
        val gutter = document.createElement("div") as HTMLElement
        gutter.className = "git-split-gutter"
        val rightHalf = document.createElement("div") as HTMLElement
        rightHalf.className = "git-split-half ${row.newClass}"
        val rightNo = document.createElement("span") as HTMLElement
        rightNo.className = "git-line-no"; rightNo.textContent = row.newNo
        val rightContent = document.createElement("span") as HTMLElement
        rightContent.className = "git-line-content"; rightContent.innerHTML = row.newContent
        rightHalf.appendChild(rightNo); rightHalf.appendChild(rightContent)
        rowDiv.appendChild(leftHalf); rowDiv.appendChild(gutter); rowDiv.appendChild(rightHalf)
        container.appendChild(rowDiv)
    }
    diffPane.appendChild(container)
}

/**
 * Renders a graphical side-by-side diff with SVG connector curves linking
 * corresponding change regions between the old and new file panels.
 *
 * Both panels show full file content with changed lines highlighted. The connector
 * SVG is updated on scroll/resize using a piecewise-linear scroll mapping so that
 * corresponding regions stay aligned. Scrolling is synchronized from the right panel
 * to the left.
 *
 * @param diffPane the container element to render the diff into
 * @param parsed the dynamic diff data containing hunks, oldContent, and newContent
 * @param state the git pane state (used for font size configuration)
 * @see renderGitDiffSplit
 */
fun renderGitDiffGraphical(diffPane: HTMLElement, parsed: dynamic, state: GitPaneState) {
    diffPane.innerHTML = ""
    val oldContent = parsed.oldContent as? String
    val newContent = parsed.newContent as? String
    val hunks = parsed.hunks as Array<dynamic>
    if (oldContent == null && newContent == null) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "git-diff-empty"; empty.textContent = "No content available for graphical diff"
        diffPane.appendChild(empty); return
    }
    val oldLines = (oldContent ?: "").split("\n")
    val newLines = (newContent ?: "").split("\n")
    val fontSize = state.diffFontSize
    val lineHeight = (fontSize * 1.54).toInt()
    val lh = lineHeight.toDouble()

    data class ChangeRegion(val oldStart: Int, val oldEnd: Int, val newStart: Int, val newEnd: Int, val type: String)
    val oldModified = mutableSetOf<Int>(); val newModified = mutableSetOf<Int>()
    val oldChanged = mutableSetOf<Int>(); val newChanged = mutableSetOf<Int>()
    val regions = mutableListOf<ChangeRegion>()

    for (hunk in hunks) {
        val lines = hunk.lines as Array<dynamic>
        var lastOldLineNo = (hunk.oldStart as Number).toInt() - 1
        var lastNewLineNo = (hunk.newStart as Number).toInt() - 1
        var i = 0
        while (i < lines.size) {
            val type = lines[i].type as String
            if (type == "Context") {
                lastOldLineNo = (lines[i].oldLineNo as? Number)?.toInt() ?: lastOldLineNo
                lastNewLineNo = (lines[i].newLineNo as? Number)?.toInt() ?: lastNewLineNo
                i++; continue
            }
            val dels = mutableListOf<dynamic>(); val adds = mutableListOf<dynamic>()
            while (i < lines.size && (lines[i].type as String) == "Deletion") { dels.add(lines[i]); i++ }
            while (i < lines.size && (lines[i].type as String) == "Addition") { adds.add(lines[i]); i++ }
            val paired = minOf(dels.size, adds.size)
            for (j in 0 until paired) {
                val oln = (dels[j].oldLineNo as Number).toInt(); val nln = (adds[j].newLineNo as Number).toInt()
                oldModified.add(oln); oldChanged.add(oln); newModified.add(nln); newChanged.add(nln)
            }
            for (j in paired until dels.size) oldChanged.add((dels[j].oldLineNo as Number).toInt())
            for (j in paired until adds.size) newChanged.add((adds[j].newLineNo as Number).toInt())
            val regionType = when { dels.isNotEmpty() && adds.isNotEmpty() -> "mod"; dels.isNotEmpty() -> "del"; else -> "add" }
            val oldStart = if (dels.isNotEmpty()) (dels.first().oldLineNo as Number).toInt() else lastOldLineNo + 1
            val oldEnd = if (dels.isNotEmpty()) (dels.last().oldLineNo as Number).toInt() else oldStart - 1
            val newStart = if (adds.isNotEmpty()) (adds.first().newLineNo as Number).toInt() else lastNewLineNo + 1
            val newEnd = if (adds.isNotEmpty()) (adds.last().newLineNo as Number).toInt() else newStart - 1
            regions.add(ChangeRegion(oldStart, oldEnd, newStart, newEnd, regionType))
            if (dels.isNotEmpty()) lastOldLineNo = (dels.last().oldLineNo as Number).toInt()
            if (adds.isNotEmpty()) lastNewLineNo = (adds.last().newLineNo as Number).toInt()
        }
    }

    val container = document.createElement("div") as HTMLElement
    container.className = "git-diff-graphical"

    fun buildPanel(linesList: List<String>, modifiedSet: Set<Int>, changedSet: Set<Int>, highlightMod: String, highlightChanged: String): Pair<HTMLElement, HTMLElement> {
        val pane = document.createElement("div") as HTMLElement
        val inner = document.createElement("div") as HTMLElement
        inner.className = "git-graphical-inner"
        for ((idx, line) in linesList.withIndex()) {
            val lineNo = idx + 1
            val div = document.createElement("div") as HTMLElement
            div.className = "git-graphical-line" + when {
                modifiedSet.contains(lineNo) -> " $highlightMod"
                changedSet.contains(lineNo) -> " $highlightChanged"
                else -> ""
            }
            div.style.height = "${lineHeight}px"; div.style.lineHeight = "${lineHeight}px"
            val noSpan = document.createElement("span") as HTMLElement
            noSpan.className = "git-line-no"; noSpan.textContent = "$lineNo"
            val cSpan = document.createElement("span") as HTMLElement
            cSpan.className = "git-line-content"; cSpan.textContent = line.ifEmpty { " " }
            div.appendChild(noSpan); div.appendChild(cSpan)
            inner.appendChild(div)
        }
        pane.appendChild(inner)
        return Pair(pane, inner)
    }

    val (leftPane, leftInner) = buildPanel(oldLines, oldModified, oldChanged, "highlight-mod", "highlight-del")
    leftPane.className = "git-graphical-left"
    val (rightPane, rightInner) = buildPanel(newLines, newModified, newChanged, "highlight-mod", "highlight-add")
    rightPane.className = "git-graphical-right"

    val diff = kotlin.math.abs(oldLines.size - newLines.size) * lineHeight
    if (diff > 0) {
        val pad = document.createElement("div") as HTMLElement
        pad.style.height = "${diff}px"
        if (oldLines.size > newLines.size) rightInner.appendChild(pad) else leftInner.appendChild(pad)
    }

    val svgNs = "http://www.w3.org/2000/svg"
    val svgContainer = document.createElement("div") as HTMLElement
    svgContainer.className = "git-graphical-connector"
    val svg = document.createElementNS(svgNs, "svg")
    svg.setAttribute("class", "git-graphical-svg")
    svgContainer.appendChild(svg)

    container.appendChild(leftPane); container.appendChild(svgContainer); container.appendChild(rightPane)
    diffPane.appendChild(container)

    // Piecewise linear scroll mapping
    data class Anchor(val rightY: Double, val leftY: Double)
    val anchors = mutableListOf<Anchor>()
    anchors.add(Anchor(0.0, 0.0))
    for (r in regions) {
        val oc = if (r.oldEnd >= r.oldStart) r.oldEnd - r.oldStart + 1 else 0
        val nc = if (r.newEnd >= r.newStart) r.newEnd - r.newStart + 1 else 0
        if (nc == 0) continue
        anchors.add(Anchor((r.newStart - 1) * lh, (r.oldStart - 1) * lh))
        anchors.add(Anchor((r.newStart - 1 + nc) * lh, (r.oldStart - 1 + oc) * lh))
    }
    anchors.add(Anchor(newLines.size * lh, oldLines.size * lh))

    fun mapRightToLeft(rY: Double): Double {
        for (i in 1 until anchors.size) {
            if (rY <= anchors[i].rightY) {
                val prev = anchors[i - 1]; val next = anchors[i]
                val span = next.rightY - prev.rightY
                if (span <= 0.0) return next.leftY
                val t = ((rY - prev.rightY) / span).coerceIn(0.0, 1.0)
                return prev.leftY + t * (next.leftY - prev.leftY)
            }
        }
        return anchors.last().leftY
    }

    var rafPending = false
    fun updateConnectors() {
        val connW = svgContainer.clientWidth.toDouble()
        val viewH = svgContainer.clientHeight.toDouble()
        if (connW <= 0 || viewH <= 0) return
        svg.setAttribute("width", "$connW"); svg.setAttribute("height", "$viewH")
        svg.setAttribute("viewBox", "0 0 $connW $viewH")
        while (svg.firstChild != null) svg.removeChild(svg.firstChild!!)
        val leftScroll = leftPane.scrollTop.toDouble(); val rightScroll = rightPane.scrollTop.toDouble()
        val centerY = viewH / 2.0

        data class RegionDraw(val leftTop: Double, val leftBot: Double, val rightTop: Double, val rightBot: Double, val type: String, val distFromCenter: Double)
        val candidates = mutableListOf<RegionDraw>()
        for (region in regions) {
            val lt = (region.oldStart - 1) * lh - leftScroll
            val lb = if (region.oldEnd >= region.oldStart) region.oldEnd * lh - leftScroll else lt + lh * 0.5
            val rt = (region.newStart - 1) * lh - rightScroll
            val rb = if (region.newEnd >= region.newStart) region.newEnd * lh - rightScroll else rt + lh * 0.5
            if (lb < 0 && rb < 0) continue; if (lt > viewH && rt > viewH) continue
            val midL = (lt + lb) / 2.0; val midR = (rt + rb) / 2.0
            val dist = minOf(kotlin.math.abs(midL - centerY), kotlin.math.abs(midR - centerY))
            candidates.add(RegionDraw(lt, lb, rt, rb, region.type, dist))
        }
        candidates.sortBy { it.distFromCenter }
        val maxDraw = 5
        for ((idx, rd) in candidates.take(maxDraw).withIndex()) {
            val fade = if (candidates.size <= 1) 1.0 else (1.0 - idx.toDouble() / maxDraw).coerceIn(0.3, 1.0)
            val fill = when (rd.type) {
                "del" -> "rgba(180, 60, 55, ${0.35 * fade})"; "add" -> "rgba(55, 140, 65, ${0.30 * fade})"
                else -> "rgba(170, 130, 50, ${0.30 * fade})"
            }
            val stroke = when (rd.type) {
                "del" -> "rgba(180, 60, 55, ${0.55 * fade})"; "add" -> "rgba(55, 140, 65, ${0.50 * fade})"
                else -> "rgba(170, 130, 50, ${0.50 * fade})"
            }
            val cx1 = connW * 0.35; val cx2 = connW * 0.65
            val path = document.createElementNS(svgNs, "path")
            val d = buildString {
                append("M 0 ${rd.leftTop} "); append("C $cx1 ${rd.leftTop} $cx2 ${rd.rightTop} $connW ${rd.rightTop} ")
                append("L $connW ${rd.rightBot} "); append("C $cx2 ${rd.rightBot} $cx1 ${rd.leftBot} 0 ${rd.leftBot} "); append("Z")
            }
            path.setAttribute("d", d); path.setAttribute("fill", fill)
            path.setAttribute("stroke", stroke); path.setAttribute("stroke-width", "1")
            svg.appendChild(path)
        }
    }

    window.requestAnimationFrame { updateConnectors() }
    fun scheduleUpdate() {
        if (!rafPending) { rafPending = true; window.requestAnimationFrame { rafPending = false; updateConnectors() } }
    }

    var syncing = false
    fun syncLeftFromRight() {
        if (syncing) return; syncing = true
        val rightTop = rightPane.scrollTop.toDouble()
        val halfView = rightPane.clientHeight / 2.0
        val leftCenter = mapRightToLeft(rightTop + halfView)
        val leftTarget = leftCenter - halfView
        val leftMax = (leftPane.scrollHeight - leftPane.clientHeight).toDouble()
        leftPane.scrollTop = leftTarget.coerceIn(0.0, if (leftMax > 0) leftMax else 0.0)
        syncing = false
    }
    rightPane.addEventListener("scroll", { _ -> syncLeftFromRight(); scheduleUpdate() })
    leftPane.addEventListener("scroll", { _ -> scheduleUpdate() })
    leftPane.asDynamic().addEventListener("wheel", { ev: dynamic ->
        ev.preventDefault()
        rightPane.scrollTop = rightPane.scrollTop + (ev.deltaY as Number).toDouble()
        leftPane.scrollLeft = leftPane.scrollLeft + (ev.deltaX as Number).toDouble()
    }, json("passive" to false))
    svgContainer.asDynamic().addEventListener("wheel", { ev: dynamic ->
        ev.preventDefault()
        rightPane.scrollTop = rightPane.scrollTop + (ev.deltaY as Number).toDouble()
    }, json("passive" to false))
    window.requestAnimationFrame { syncLeftFromRight() }
    window.addEventListener("resize", { _ -> syncLeftFromRight(); scheduleUpdate() })
}

/**
 * Constructs the complete DOM subtree for a git pane, including the changed files
 * list, diff viewer, column divider, and header toolbar controls (diff mode, search,
 * auto-refresh, refresh).
 *
 * Called by [buildLeafCell] when a pane's content kind is "git". Restores persisted
 * state (selected file, diff mode, graphical toggle, column width) from the leaf's
 * content payload and triggers initial file list and diff requests.
 *
 * @param paneId the unique pane identifier
 * @param leaf the dynamic leaf node from the server config containing content state
 * @param headerEl optional pane header element to inject toolbar buttons into
 * @return the root HTMLElement for the git pane view
 * @see buildLeafCell
 * @see GitPaneState
 */
fun buildGitView(paneId: String, leaf: dynamic, headerEl: HTMLElement? = null): HTMLElement {
    val content = leaf.content
    val initialWidth = (content?.leftColumnWidthPx as? Number)?.toInt() ?: 280
    val initialSelected = content?.selectedFilePath as? String
    val initialAutoRefresh = content?.autoRefresh as? Boolean ?: false
    val initialDiffMode = (content?.diffMode as? String) ?: "Inline"
    val initialGraphical = content?.graphicalDiff as? Boolean ?: false
    val state = gitPaneStates.getOrPut(paneId) { GitPaneState() }
    if (initialSelected != null) state.selectedFilePath = initialSelected
    state.diffMode = initialDiffMode
    state.graphicalDiff = initialGraphical
    state.diffFontSize = (appVm.stateFlow.value.paneFontSize ?: 14)

    val outer = document.createElement("div") as HTMLElement
    outer.className = "git-view"
    val left = document.createElement("div") as HTMLElement
    left.className = "git-list"
    left.style.width = "${initialWidth.coerceIn(0, 640)}px"
    left.style.asDynamic().flexShrink = "0"

    val header = document.createElement("div") as HTMLElement
    header.className = "git-list-header"
    val titleEl = document.createElement("span") as HTMLElement
    titleEl.className = "git-list-title"; titleEl.textContent = "Git Changes"
    header.appendChild(titleEl)
    left.appendChild(header)

    val listBody = document.createElement("div") as HTMLElement
    listBody.className = "git-list-body"
    left.appendChild(listBody)

    val divider = document.createElement("div") as HTMLElement
    divider.className = "git-divider"
    val right = document.createElement("div") as HTMLElement
    right.className = "git-diff-pane"
    val diffContent = document.createElement("div") as HTMLElement
    diffContent.className = "git-diff-content"

    // Header flyout controls
    val iconDiffMode = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09a1.65 1.65 0 0 0-1.08-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09a1.65 1.65 0 0 0 1.51-1.08 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1.08 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1.08z"/></svg>"""
    val iconSearch = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><line x1="16" y1="16" x2="21" y2="21"/></svg>"""
    val iconAutoRefresh = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><polyline points="12,7 12,12 15,14"/></svg>"""

    fun makeFlyoutBtn(titleText: String, svg: String, extraClass: String = ""): Pair<HTMLElement, HTMLElement> {
        val wrap = document.createElement("div") as HTMLElement
        wrap.className = "pane-flyout-wrap"
        wrap.addEventListener("mousedown", { ev -> ev.stopPropagation() })
        val btn = document.createElement("button") as HTMLElement
        btn.className = "pane-action-btn" + (if (extraClass.isNotEmpty()) " $extraClass" else "")
        btn.setAttribute("type", "button"); btn.setAttribute("title", titleText); btn.innerHTML = svg
        btn.addEventListener("click", { ev ->
            ev.stopPropagation()
            val open = document.querySelectorAll(".pane-flyout-wrap.open")
            for (i in 0 until open.length) { val other = open.item(i) as HTMLElement; if (other !== wrap) other.classList.remove("open") }
            wrap.classList.toggle("open")
        })
        wrap.appendChild(btn)
        val flyout = document.createElement("div") as HTMLElement
        flyout.className = "pane-flyout"
        flyout.addEventListener("click", { ev -> ev.stopPropagation() })
        wrap.appendChild(flyout)
        return Pair(wrap, flyout)
    }

    var updateGraphicalVisibility: () -> Unit = {}

    val (diffModeWrap, diffModeFlyout) = makeFlyoutBtn("Diff mode", iconDiffMode)
    val modeRow = document.createElement("div") as HTMLElement
    modeRow.className = "pane-flyout-row"
    fun updateModeButtons() {
        val btns = modeRow.querySelectorAll(".git-mode-btn")
        for (i in 0 until btns.length) {
            val btn = btns.item(i) as HTMLElement
            val mode = btn.getAttribute("data-mode") ?: ""
            if (mode == state.diffMode) btn.classList.add("active") else btn.classList.remove("active")
        }
    }
    for (mode in listOf("Inline", "Split")) {
        val btn = document.createElement("button") as HTMLElement
        btn.className = "git-mode-btn"; (btn.asDynamic()).type = "button"
        btn.textContent = mode; btn.setAttribute("data-mode", mode)
        btn.addEventListener("click", { _ ->
            if (state.diffMode != mode) {
                state.diffMode = mode; state.diffHtml = null
                launchCmd(WindowCommand.SetGitDiffMode(paneId = paneId, mode = if (mode == "Split") GitDiffMode.Split else GitDiffMode.Inline))
                updateModeButtons(); updateGraphicalVisibility()
                val sel = state.selectedFilePath
                if (sel != null) launchCmd(WindowCommand.GitDiff(paneId = paneId, filePath = sel))
            }
        })
        modeRow.appendChild(btn)
    }
    diffModeFlyout.appendChild(modeRow)

    val graphicalLabel = document.createElement("label") as HTMLElement
    graphicalLabel.className = "git-graphical-toggle"
    graphicalLabel.style.display = if (state.diffMode == "Split") "inline-flex" else "none"
    val graphicalCb = document.createElement("input") as HTMLInputElement
    graphicalCb.type = "checkbox"; graphicalCb.checked = state.graphicalDiff
    graphicalCb.addEventListener("change", { _ ->
        state.graphicalDiff = graphicalCb.checked; state.diffHtml = null
        launchCmd(WindowCommand.SetGitGraphicalDiff(paneId = paneId, enabled = graphicalCb.checked))
        val sel = state.selectedFilePath
        if (sel != null) launchCmd(WindowCommand.GitDiff(paneId = paneId, filePath = sel))
    })
    graphicalLabel.appendChild(graphicalCb)
    val graphicalText = document.createElement("span") as HTMLElement
    graphicalText.textContent = "Graphical"
    graphicalLabel.appendChild(graphicalText)
    diffModeFlyout.appendChild(graphicalLabel)
    updateModeButtons()
    updateGraphicalVisibility = { graphicalLabel.style.display = if (state.diffMode == "Split") "inline-flex" else "none" }

    // Search flyout
    val (searchWrap, searchFlyout) = makeFlyoutBtn("Search diff", iconSearch)
    val searchInput = document.createElement("input") as HTMLInputElement
    searchInput.type = "text"; searchInput.placeholder = "Search\u2026"
    searchInput.className = "git-search-input"; searchInput.value = state.searchQuery
    searchFlyout.appendChild(searchInput)

    val searchNavRow = document.createElement("div") as HTMLElement
    searchNavRow.className = "pane-flyout-row"
    val searchCounter = document.createElement("span") as HTMLElement
    searchCounter.className = "git-search-counter"
    searchNavRow.appendChild(searchCounter)

    fun makeNavBtn(text: String, titleText: String): HTMLElement {
        val b = document.createElement("button") as HTMLElement
        (b.asDynamic()).type = "button"; b.className = "git-font-btn git-search-nav"
        b.textContent = text; b.title = titleText; b.asDynamic().disabled = true; return b
    }
    val firstBtn = makeNavBtn("\u23EE", "First match")
    val prevBtn = makeNavBtn("\u25C0", "Previous match")
    val nextBtn = makeNavBtn("\u25B6", "Next match")
    val lastBtn = makeNavBtn("\u23ED", "Last match")
    val searchNavButtons = listOf(firstBtn, prevBtn, nextBtn, lastBtn)

    var searchTimer: Int? = null
    fun runSearch() {
        state.searchQuery = searchInput.value; state.searchMatchIndex = 0
        performDiffSearch(diffContent, state.searchQuery, state, searchCounter, searchNavButtons)
    }
    searchInput.addEventListener("input", { _ ->
        searchTimer?.let { kotlinx.browser.window.clearTimeout(it) }
        searchTimer = kotlinx.browser.window.setTimeout({ runSearch() }, 200)
    })
    firstBtn.addEventListener("click", { _ -> navigateDiffSearch(diffContent, state, searchCounter, Int.MIN_VALUE) })
    prevBtn.addEventListener("click", { _ -> navigateDiffSearch(diffContent, state, searchCounter, -1) })
    nextBtn.addEventListener("click", { _ -> navigateDiffSearch(diffContent, state, searchCounter, 1) })
    lastBtn.addEventListener("click", { _ -> navigateDiffSearch(diffContent, state, searchCounter, Int.MAX_VALUE) })
    for (b in searchNavButtons) searchNavRow.appendChild(b)
    searchFlyout.appendChild(searchNavRow)

    // Auto-refresh toggle
    val autoRefreshBtn = document.createElement("button") as HTMLElement
    autoRefreshBtn.className = "pane-action-btn" + if (initialAutoRefresh) " active" else ""
    autoRefreshBtn.setAttribute("type", "button"); autoRefreshBtn.setAttribute("title", "Auto-refresh")
    autoRefreshBtn.innerHTML = iconAutoRefresh
    autoRefreshBtn.addEventListener("mousedown", { ev -> ev.stopPropagation() })
    autoRefreshBtn.addEventListener("click", { ev ->
        ev.stopPropagation()
        val nowOn = !autoRefreshBtn.classList.contains("active")
        autoRefreshBtn.classList.toggle("active", nowOn)
        launchCmd(WindowCommand.SetGitAutoRefresh(paneId = paneId, enabled = nowOn))
    })

    // Refresh button
    val refreshBtn = document.createElement("button") as HTMLElement
    refreshBtn.className = "pane-action-btn"
    refreshBtn.setAttribute("type", "button"); refreshBtn.setAttribute("title", "Refresh")
    refreshBtn.innerHTML = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 8a6 6 0 0 1 10.5-3.9"/><polyline points="13,2 13,5 10,5"/><path d="M14 8a6 6 0 0 1 -10.5 3.9"/><polyline points="3,14 3,11 6,11"/></svg>"""
    refreshBtn.addEventListener("mousedown", { ev -> ev.stopPropagation() })
    refreshBtn.addEventListener("click", { ev ->
        ev.stopPropagation()
        launchCmd(WindowCommand.GitList(paneId = paneId))
        val sel = state.selectedFilePath
        if (sel != null) { state.diffHtml = null; launchCmd(WindowCommand.GitDiff(paneId = paneId, filePath = sel)) }
    })

    if (headerEl != null) {
        val actions = headerEl.querySelector(".pane-actions") as? HTMLElement
        if (actions != null) {
            actions.parentNode?.insertBefore(diffModeWrap, actions)
            actions.parentNode?.insertBefore(searchWrap, actions)
            actions.parentNode?.insertBefore(autoRefreshBtn, actions)
            actions.parentNode?.insertBefore(refreshBtn, actions)
            val spacer = document.createElement("div") as HTMLElement
            spacer.className = "pane-header-spacer"
            actions.parentNode?.insertBefore(spacer, actions)
        }
    }

    fun applyFontSize() {
        diffContent.style.fontSize = "${state.diffFontSize}px"
        diffContent.style.lineHeight = "${(state.diffFontSize * 1.54).toInt()}px"
    }

    val emptyState = document.createElement("div") as HTMLElement
    emptyState.className = "git-diff-empty"
    emptyState.innerHTML = """
        <div class="git-diff-empty-icon">&#128221;</div>
        <div class="git-diff-empty-title">No file selected</div>
        <div class="git-diff-empty-subtitle">Choose a file from the list to view its diff</div>
    """.trimIndent()
    diffContent.appendChild(emptyState)
    right.appendChild(diffContent)
    applyFontSize()

    outer.appendChild(left); outer.appendChild(divider); outer.appendChild(right)

    val view = GitPaneView(listBody, diffContent, searchCounter, searchNavButtons)
    gitPaneViews[paneId] = view
    renderGitList(paneId, view, state)
    if (state.diffHtml != null) diffContent.innerHTML = state.diffHtml!!
    launchCmd(WindowCommand.GitList(paneId = paneId))
    if (state.selectedFilePath != null && state.diffHtml == null) {
        launchCmd(WindowCommand.GitDiff(paneId = paneId, filePath = state.selectedFilePath!!))
    }
    if (initialAutoRefresh) launchCmd(WindowCommand.SetGitAutoRefresh(paneId = paneId, enabled = true))

    divider.addEventListener("mousedown", { ev ->
        val mev = ev as MouseEvent; mev.preventDefault()
        divider.classList.add("dragging")
        val startX = mev.clientX; val startWidth = left.offsetWidth
        val previousBodyCursor = document.body?.style?.cursor ?: ""
        document.body?.style?.cursor = "col-resize"
        var latestPx = startWidth
        val moveListener: (org.w3c.dom.events.Event) -> Unit = { evMove ->
            val m = evMove as MouseEvent
            val w = (startWidth + (m.clientX - startX)).coerceIn(0, 640)
            latestPx = w; left.style.width = "${w}px"
        }
        lateinit var upListener: (org.w3c.dom.events.Event) -> Unit
        upListener = { _ ->
            document.removeEventListener("mousemove", moveListener)
            document.removeEventListener("mouseup", upListener)
            divider.classList.remove("dragging")
            document.body?.style?.cursor = previousBodyCursor
            launchCmd(WindowCommand.SetGitLeftWidth(paneId = paneId, px = latestPx))
        }
        document.addEventListener("mousemove", moveListener)
        document.addEventListener("mouseup", upListener)
    })

    return outer
}
