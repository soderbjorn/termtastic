package se.soderbjorn.termtastic

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform