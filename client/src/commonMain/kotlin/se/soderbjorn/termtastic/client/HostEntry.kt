package se.soderbjorn.termtastic.client

import kotlinx.serialization.Serializable

@Serializable
data class HostEntry(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
)
