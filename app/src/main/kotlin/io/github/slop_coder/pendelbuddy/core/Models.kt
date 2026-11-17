package io.github.slop_coder.pendelbuddy.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** All timetable times are Swiss local time. */
val SWISS: ZoneId = ZoneId.of("Europe/Zurich")

fun Long.toSwiss(): ZonedDateTime = Instant.ofEpochSecond(this).atZone(SWISS)

@Serializable
data class Station(
    val id: String,
    val name: String,
    val lat: Double? = null,
    val lon: Double? = null,
    /** "train", "bus", "tram", "ship", "cableway"… as the station search reports it. */
    val icon: String? = null,
    /** Your own short name for the tabs at the bottom; null = automatic ([ShortNames]). */
    val short: String? = null,
)

enum class TravelClass { FIRST, SECOND }

@Serializable
data class Settings(
    val home: Station? = null,
    val work: Station? = null,
    /** Stations in between (e.g. a hub where you get off for breakfast); offered as quick picks. */
    val vias: List<Station> = emptyList(),
    val travelClass: TravelClass = TravelClass.SECOND,
    /** Minutes after midnight from which weekday trips go home instead of to work. */
    val switchMinute: Int = 12 * 60,
    /** Most changes a commute trip may have; null = any. */
    val maxChanges: Int? = null,
    /** Extra time to keep when changing at certain stations (a late bus, breakfast). */
    val buffers: List<Buffer> = emptyList(),
    /** Times the morning and evening buttons at the top jump to, minutes after midnight. */
    val morningMinute: Int = 6 * 60,
    val eveningMinute: Int = 16 * 60,
) {
    val complete get() = home != null && work != null && home.id != work.id

    /** Sets the tab name of a station wherever it is saved. */
    fun withShortName(id: String, short: String?): Settings {
        fun Station.renamed() = if (this.id == id) copy(short = short?.trim()?.ifEmpty { null }) else this
        return copy(
            home = home?.renamed(),
            work = work?.renamed(),
            vias = vias.map { it.renamed() },
            buffers = buffers.map { it.copy(station = it.station.renamed()) },
        )
    }
}

/**
 * Keep at least [minutes] between arriving at [station] and leaving it again, on trips in [direction]
 * (null = both directions).
 */
@Serializable
data class Buffer(val station: Station, val minutes: Int, val direction: Direction? = null) {
    fun appliesTo(d: Direction) = direction == null || direction == d
}

/** Expected occupancy from the timetable service: 1 = low, 2 = medium, 3 = high. */
@Serializable
data class Capacity(val first: Int? = null, val second: Int? = null) {
    fun of(c: TravelClass) = if (c == TravelClass.FIRST) first else second

    companion object {
        /** Worst leg counts, since that is where you end up standing. */
        fun worst(all: List<Capacity>) = Capacity(
            all.mapNotNull { it.first }.maxOrNull(),
            all.mapNotNull { it.second }.maxOrNull(),
        )
    }
}

/** A stop the train leaves from on your ride (where you board, or one in between), with the occupancy from there on. */
@Serializable
data class Call(val id: String, val name: String, val departure: Long, val capacity: Capacity = Capacity())

/** A stretch of a ride with one occupancy level in your class (consecutive stops with the same level are joined). */
data class Section(val from: String, val to: String, val level: Int?)

@Serializable
data class Leg(
    /** "IC 8", "S 3", "B 10"; null for walking. */
    val line: String?,
    val direction: String? = null,
    val fromId: String,
    val fromName: String,
    val toId: String = "",
    val toName: String,
    /** Scheduled times, epoch seconds. */
    val departure: Long,
    val arrival: Long,
    val departureDelay: Int? = null,
    val platform: String? = null,
    /** The platform in the timetable, when the answer says so; differs from [platform] after a change. */
    val plannedPlatform: String? = null,
    val arrivalDelay: Int? = null,
    val arrivalPlatform: String? = null,
    val plannedArrivalPlatform: String? = null,
    val capacity: Capacity = Capacity(),
    /** Train number without leading zeros, e.g. "10809"; kept with ratings. */
    val trainNumber: String? = null,
    /** The whole run is cancelled, or it skips your boarding or exit stop (see [boardSkipped], [alightSkipped]). */
    val cancelled: Boolean = false,
    val boardSkipped: Boolean = false,
    val alightSkipped: Boolean = false,
    /** Boarding stop and stops in between, each with the occupancy when leaving it; [capacity] is their worst. */
    val calls: List<Call> = emptyList(),
    /** OJP's id of this run of the train and its operating day (yyyy-mm-dd), to find it again in a later answer. */
    val journeyRef: String? = null,
    val operatingDay: String? = null,
    /** Transport company number from OJP ("11" = SBB), to ask the formation service the right company. */
    val operator: String? = null,
) {
    val walk get() = line == null

    /** The company code the formation service knows this train by; SBB when unknown. */
    val evu get() = EVU[operator] ?: "SBBP"

    /** Why you cannot ride this train as planned, or null. */
    val problem get() = when {
        !cancelled -> null
        boardSkipped -> "$line does not stop at $fromName"
        alightSkipped -> "$line does not stop at $toName"
        else -> "$line is cancelled"
    }

    /** The ride split where the occupancy in [cls] changes, e.g. "A → B: low", "B → C: high". */
    fun sections(cls: TravelClass): List<Section> {
        val out = mutableListOf<Section>()
        calls.forEachIndexed { i, c ->
            val to = calls.getOrNull(i + 1)?.name ?: toName
            val level = c.capacity.of(cls)
            val last = out.lastOrNull()
            if (last != null && last.level == level) out[out.size - 1] = last.copy(to = to)
            else out += Section(c.name, to, level)
        }
        return out
    }

    /**
     * The occupancy in [cls] stretch by stretch with how long each lasts (seconds), the same level in a row joined;
     * for the badge, whose left end is where you get on. Without stops in between: the whole ride at its worst.
     */
    fun levelSpans(cls: TravelClass): List<Pair<Int?, Long>> {
        if (calls.isEmpty()) return listOf(capacity.of(cls) to (arrival - departure).coerceAtLeast(1))
        val out = mutableListOf<Pair<Int?, Long>>()
        calls.forEachIndexed { i, c ->
            val end = calls.getOrNull(i + 1)?.departure ?: arrival
            val level = c.capacity.of(cls)
            val span = (end - c.departure).coerceAtLeast(1)
            val last = out.lastOrNull()
            if (last != null && last.first == level) out[out.size - 1] = level to last.second + span else out += level to span
        }
        return out
    }

    /** The stop the train last left at [now] (allowing for its delay): where a rating made now starts. */
    fun callAt(now: Long): Call? {
        val late = 60L * (departureDelay ?: 0)
        return calls.lastOrNull { it.departure + late <= now } ?: calls.firstOrNull()
    }

    fun expectedDeparture() = departure + 60L * (departureDelay ?: 0)
    fun expectedArrival() = arrival + 60L * (arrivalDelay ?: 0)

    /** Identifies "the same train" across days: line, boarding station and scheduled time of day. */
    val trainKey get() = line?.let { "$it|$fromId|${departure.toSwiss().toLocalTime()}" }
}

/** OJP operator numbers → formation service company codes (only those the service covers). */
private val EVU = mapOf("11" to "SBBP", "33" to "BLSP", "82" to "SOB", "65" to "THURBO")

@Serializable
data class Trip(
    val fromId: String,
    val toId: String,
    val departure: Long,
    val arrival: Long,
    /** Minutes of delay from the realtime prognosis, if there was one. */
    val departureDelay: Int? = null,
    val arrivalDelay: Int? = null,
    val platform: String? = null,
    val transfers: Int = 0,
    val legs: List<Leg> = emptyList(),
) {
    val capacity get() = Capacity.worst(legs.map { it.capacity })
    val trains get() = legs.filter { !it.walk }
    val products get() = trains.mapNotNull { it.line }
    val cancelled get() = legs.any { it.cancelled }
    val serviceDate: LocalDate get() = departure.toSwiss().toLocalDate()

    /** Same scheduled train(s) at the same time; used to merge fresh results into the cache. */
    val key get() = "${departure.toSwiss().toLocalTime()}|${products.joinToString(",")}"

    fun expectedDeparture() = departure + 60L * (departureDelay ?: 0)
    fun expectedArrival() = arrival + 60L * (arrivalDelay ?: 0)

    /** Copy with all times moved by [seconds]. */
    fun shifted(seconds: Long) = copy(
        departure = departure + seconds,
        arrival = arrival + seconds,
        legs = legs.map { it.copy(departure = it.departure + seconds, arrival = it.arrival + seconds) },
    )

    /** Copy without the realtime and forecast parts, for showing old data. */
    fun timetableOnly() = copy(
        departureDelay = null,
        arrivalDelay = null,
        legs = legs.map { l ->
            l.copy(departureDelay = null, capacity = Capacity(), calls = l.calls.map { it.copy(capacity = Capacity()) })
        },
    )

    fun withoutDelays() = copy(departureDelay = null, arrivalDelay = null, legs = legs.map { it.copy(departureDelay = null, arrivalDelay = null) })

    /**
     * Without walks before the first and after the last train: the trip runs from the first departure to the
     * last arrival, whatever stop was asked for. Walks between trains (changes) stay.
     */
    fun trimWalks(): Trip? {
        val first = legs.indexOfFirst { !it.walk }.takeIf { it >= 0 } ?: return null
        val last = legs.indexOfLast { !it.walk }
        val rides = legs.subList(first, last + 1)
        val a = rides.first()
        val b = rides.last()
        return copy(
            departure = a.departure,
            arrival = b.arrival,
            departureDelay = a.departureDelay,
            arrivalDelay = b.arrivalDelay,
            platform = a.platform,
            legs = rides,
        )
    }
}
