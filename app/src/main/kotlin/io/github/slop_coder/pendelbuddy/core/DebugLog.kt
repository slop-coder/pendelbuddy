package io.github.slop_coder.pendelbuddy.core

import kotlinx.serialization.Serializable

/** One network request that could not be answered from saved data. Never holds the API key. */
@Serializable
data class DebugEntry(
    /** When it was made, epoch milliseconds. */
    val time: Long,
    /** "OJP", "transport.opendata.ch", "Weather", "Station search". */
    val kind: String,
    /** What was asked, e.g. "Talwil → Beispielstadt from Mo 16.11. 07:00". */
    val what: String,
    /** Why, e.g. "Opened app", "Pull to refresh", "Picked day", "Prefetch Mo 16.11. to work". */
    val cause: String,
    /** "12 trips", "HTTP 401", "offline"… */
    val result: String,
    val millis: Long,
) {
    companion object {
        /** Entries kept; older ones are dropped. */
        const val KEEP = 300
    }
}
