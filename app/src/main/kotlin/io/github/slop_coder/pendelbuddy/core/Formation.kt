package io.github.slop_coder.pendelbuddy.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * One vehicle of a train at a stop, from the formation service's short string. [type] is its code: "1", "2", "12"
 * (both classes), "FA" (family coach), "WR"/"W1"/"W2" (restaurant, with 1st or 2nd class seats), "CC", "WL", "LK"
 * (locomotive), "D" (baggage), "K" (no class).
 */
@Serializable
data class Wagon(
    val type: String,
    /** The number on the wagon (seat reservations); null if it has none. */
    val number: Int? = null,
    /** Platform sectors it stands in at this stop, e.g. ["A", "B"]. */
    val sectors: List<String> = emptyList(),
    /** Closed ("-"), or a restaurant that is open but not served ("%"). */
    val closed: Boolean = false,
    val notServed: Boolean = false,
    /** "BZ" business zone, "FZ" family zone, "VH" bikes, "BHP" wheelchairs, "NF" low floor, "KW" prams… */
    val services: List<String> = emptyList(),
) {
    val firstClass get() = type == "1" || type == "12" || type == "W1"
    val secondClass get() = type == "2" || type == "12" || type == "W2" || type == "FA" || type == "CC"
    val restaurant get() = type == "WR" || type == "W1" || type == "W2"
    val passengers get() = firstClass || secondClass || restaurant || type == "WL"

    fun has(c: TravelClass) = if (c == TravelClass.FIRST) firstClass else secondClass
}

/** The train as it stands at one stop: its track and its wagons in train order. */
@Serializable
data class TrainFormation(val stopId: String, val stopName: String, val track: String? = null, val wagons: List<Wagon>) {
    /** Wagons as seen along the platform, sector A on the left. */
    val alongPlatform: List<Wagon>
        get() {
            val first = wagons.firstNotNullOfOrNull { it.sectors.firstOrNull() }
            val last = wagons.lastOrNull { it.sectors.isNotEmpty() }?.sectors?.last()
            return if (first != null && last != null && first > last) wagons.reversed() else wagons
        }

    /** The sectors where wagons of class [c] stand, e.g. "A–C, E–F". */
    fun sectorsOf(c: TravelClass): List<String> = wagons.filter { it.has(c) && !it.closed }.flatMap { it.sectors }.distinct().sorted()
}

/** A downloaded formation: every stop of the run (empty: the service has none for this train). */
@Serializable
data class CachedFormation(val fetchedAt: Long, val stops: List<TrainFormation>)

object Formations {
    private val json = Json { ignoreUnknownKeys = true }

    /** Every stop of a formations_full (or stop_based) answer, in the order of the run. */
    fun parse(body: String): List<TrainFormation> {
        val root = json.parseToJsonElement(body).jsonObject
        val stops = root["formationsAtScheduledStops"]?.jsonArray ?: return emptyList()
        return stops.mapNotNull { e ->
            val o = e.jsonObject
            val stop = o.obj("scheduledStop") ?: return@mapNotNull null
            val point = stop.obj("stopPoint") ?: return@mapNotNull null
            val short = o.obj("formationShort")?.str("formationShortString") ?: return@mapNotNull null
            TrainFormation(
                stopId = point.str("uic") ?: return@mapNotNull null,
                stopName = point.str("name").orEmpty(),
                track = stop.str("track")?.takeIf { it != "N/A" },
                wagons = parseShort(short),
            )
        }
    }

    /**
     * The short string, e.g. `@A,F,[(1:1#VH;BZ;NF@B,1:2#VH;KW;NF,%W2:4#BHP;NF@C,…]`: vehicles separated by commas,
     * "@X" where sector X starts (alone, or after a vehicle reaching into it), "F" fill-in places on the platform,
     * "[" "]" around the train, "(" ")" no way through to the next vehicle, a status prefix ("-" closed, "%" not
     * served, ">" / "=" groups), the type, ":" the wagon number and "#" its services.
     */
    fun parseShort(s: String): List<Wagon> {
        val out = mutableListOf<Wagon>()
        var sector: String? = null
        for (raw in s.split(',')) {
            val parts = raw.trim().split('@')
            val body = parts[0].filter { it !in "[]()" }
            val later = parts.drop(1).filter { it.isNotEmpty() }
            if (body.isEmpty() || body == "F" || body == "X") {
                // Only a sector mark, a fill-in place, or a parked vehicle that is not part of the train.
                later.lastOrNull()?.let { sector = it }
                continue
            }
            val status = body.takeWhile { it in "-%>=" }
            val rest = body.drop(status.length)
            val type = rest.substringBefore('#').substringBefore(':')
            val number = rest.substringBefore('#').substringAfter(':', "").toIntOrNull()
            val services = rest.substringAfter('#', "").split(';').filter { it.isNotEmpty() }
            val sectors = listOfNotNull(sector) + later
            out += Wagon(type, number, sectors.distinct(), closed = '-' in status, notServed = '%' in status, services = services)
            later.lastOrNull()?.let { sector = it }
        }
        return out
    }

    private fun JsonObject.obj(k: String) = (this[k] as? JsonObject)
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
