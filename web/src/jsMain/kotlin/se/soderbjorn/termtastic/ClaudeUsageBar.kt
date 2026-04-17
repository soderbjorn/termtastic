package se.soderbjorn.termtastic

import org.w3c.dom.HTMLElement

private val MONTH_MAP = mapOf(
    "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3,
    "may" to 4, "jun" to 5, "jul" to 6, "aug" to 7,
    "sep" to 8, "oct" to 9, "nov" to 10, "dec" to 11,
)

private fun pctClass(pct: Int): String = when {
    pct >= 90 -> "usage-critical"
    pct >= 70 -> "usage-warn"
    else -> "usage-ok"
}

private fun formatFetchedAt(isoString: String): String {
    if (isoString.isBlank()) return ""
    return try {
        val d = kotlin.js.Date(isoString)
        val year = d.getFullYear()
        val month = (d.getMonth() + 1).toString().padStart(2, '0')
        val day = d.getDate().toString().padStart(2, '0')
        val hour = d.getHours().toString().padStart(2, '0')
        val min = d.getMinutes().toString().padStart(2, '0')
        "(updated $year-$month-$day $hour:$min)"
    } catch (_: Exception) { "" }
}

private fun formatResetTime(raw: String): String {
    if (raw.isBlank()) return ""
    val timeMatch = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)""", RegexOption.IGNORE_CASE).find(raw)
        ?: return ""
    var h = timeMatch.groupValues[1].toInt()
    val m = timeMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
    val isPm = timeMatch.groupValues[3].lowercase() == "pm"
    if (isPm && h < 12) h += 12
    if (!isPm && h == 12) h = 0
    val hh = h.toString().padStart(2, '0')
    val mm = m.toString().padStart(2, '0')

    val dateMatch = Regex("""([A-Za-z]{3})\s+(\d{1,2})""").find(raw)
    val now = kotlin.js.Date()
    val year: Int
    val month: Int
    val day: Int
    if (dateMatch != null) {
        month = MONTH_MAP[dateMatch.groupValues[1].lowercase()] ?: now.getMonth()
        day = dateMatch.groupValues[2].toInt()
        year = if (month < now.getMonth() ||
            (month == now.getMonth() && day < now.getDate()))
            now.getFullYear() + 1 else now.getFullYear()
    } else {
        year = now.getFullYear()
        month = now.getMonth()
        day = now.getDate()
    }
    val yyyy = year.toString()
    val mo = (month + 1).toString().padStart(2, '0')
    val dd = day.toString().padStart(2, '0')
    return "(resets $yyyy-$mo-$dd $hh:$mm)"
}

private fun usageItem(label: String, pct: Int, resetTime: String = ""): String {
    val reset = formatResetTime(resetTime)
    val resetHtml = if (reset.isNotBlank()) """ <span class="usage-reset">$reset</span>""" else ""
    return """<span class="usage-item">$label <span class="usage-pct ${pctClass(pct)}">${pct}%</span>$resetHtml</span>"""
}

fun updateClaudeUsageBadge(usage: dynamic) {
    val bar = usageBar ?: return
    if (usage == null || usage == undefined) {
        bar.style.display = "none"
        return
    }
    val sessionPct = (usage.sessionPercent as? Int) ?: 0
    val sessionReset = (usage.sessionResetTime as? String) ?: ""
    val weeklyAllPct = (usage.weeklyAllPercent as? Int) ?: 0
    val weeklyAllReset = (usage.weeklyAllResetTime as? String) ?: ""
    val weeklySonnetPct = (usage.weeklySonnetPercent as? Int) ?: 0
    val fetchedAt = (usage.fetchedAt as? String) ?: ""

    val ts = formatFetchedAt(fetchedAt)

    var html = """<span class="usage-item" style="font-weight:600;">Claude Code usage:</span>"""
    html += usageItem("Session", sessionPct, sessionReset)
    html += usageItem("Week", weeklyAllPct, weeklyAllReset)
    html += usageItem("Sonnet", weeklySonnetPct)
    if (ts.isNotBlank()) {
        html += """<span class="usage-item">$ts</span>"""
    }
    html += """<button class="usage-refresh-btn" title="Refresh usage">&#x21bb;</button>"""

    bar.innerHTML = html
    bar.querySelector(".usage-refresh-btn")?.addEventListener("click", { launchCmd(WindowCommand.RefreshUsage) })
    bar.className = "claude-usage-bar ${pctClass(sessionPct)}"
    bar.style.display = ""
}
