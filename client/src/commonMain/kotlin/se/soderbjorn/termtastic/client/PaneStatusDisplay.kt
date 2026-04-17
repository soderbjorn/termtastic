package se.soderbjorn.termtastic.client

enum class PaneStatusDisplay { Dots, Glow, Both, None }

fun parsePaneStatusDisplay(s: String?): PaneStatusDisplay =
    when (s) {
        "Dots" -> PaneStatusDisplay.Dots
        "Both" -> PaneStatusDisplay.Both
        "None" -> PaneStatusDisplay.None
        else -> PaneStatusDisplay.Glow
    }
