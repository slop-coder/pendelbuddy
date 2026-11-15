package io.github.slop_coder.pendelbuddy.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class Direction { TO_WORK, TO_HOME;

    val other get() = if (this == TO_WORK) TO_HOME else TO_WORK
}

data class Route(val from: Station, val to: Station, val direction: Direction)

object Commute {
    /** Weekdays: to work until the switch time, home after it. Weekends behave the same until day trips exist. */
    fun direction(s: Settings, now: ZonedDateTime): Direction =
        if (now.hour * 60 + now.minute < s.switchMinute) Direction.TO_WORK else Direction.TO_HOME

    fun destination(s: Settings, d: Direction) = if (d == Direction.TO_WORK) s.work else s.home
    fun origin(s: Settings, d: Direction) = if (d == Direction.TO_WORK) s.home else s.work

    /**
     * The route to show. [current] is the station picked at the bottom ("I'm already here");
     * picking the destination itself means you have arrived, so it plans the way back.
     */
    fun route(s: Settings, direction: Direction, current: Station?): Route? {
        val to = destination(s, direction) ?: return null
        if (current != null && current.id == to.id) {
            val back = origin(s, direction) ?: return null
            return Route(current, back, direction.other)
        }
        val from = current ?: origin(s, direction) ?: return null
        if (from.id == to.id) return null
        return Route(from, to, direction)
    }

    /**
     * Stations offered as quick picks at the bottom, in the order you pass them: home, the stations in between
     * (nearest to home first), work; reversed on the way home.
     */
    fun quickPicks(s: Settings, direction: Direction = Direction.TO_WORK): List<Station> {
        val outward = (listOfNotNull(s.home) + viasInOrder(s) + listOfNotNull(s.work)).distinctBy { it.id }
        return if (direction == Direction.TO_HOME) outward.reversed() else outward
    }

    /** The stations in between sorted by distance from home; ones without coordinates keep their place at the end. */
    fun viasInOrder(s: Settings): List<Station> {
        val home = s.home
        if (home?.lat == null || home.lon == null) return s.vias
        return s.vias.sortedBy { v -> if (v.lat == null || v.lon == null) Double.MAX_VALUE else distanceKm(home.lat, home.lon, v.lat, v.lon) }
    }

    /** Great-circle distance. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6371 * 2 * asin(sqrt(a))
    }

    /** Earliest arrival when leaving no sooner than [leaveAt]. */
    fun quickest(trips: List<Trip>, leaveAt: Long) =
        trips.filter { !it.cancelled && it.expectedDeparture() >= leaveAt }.minByOrNull { it.expectedArrival() }
}

/**
 * A fullness rating of one train leg, 10 (almost empty) to 110 (no seats, many standing). Every rating is kept,
 * with the forecast shown at that moment, so ratings can be compared with the forecast later.
 */
@Serializable
data class Rating(
    val trainKey: String,
    /** Operating day of the train, yyyy-mm-dd. */
    val date: String,
    val percent: Int,
    val travelClass: TravelClass,
    val line: String,
    val fromName: String,
    val toName: String = "",
    val trainNumber: String? = null,
    /** Scheduled departure at [fromName], epoch seconds. */
    val departure: Long = 0,
    val departureDelay: Int? = null,
    /** Forecast level (1 low, 2 medium, 3 high) per class when the rating was made, null if there was none. */
    val forecastFirst: Int? = null,
    val forecastSecond: Int? = null,
    /** When the rating was made, epoch seconds. */
    val ratedAt: Long = 0,
) {
    fun forecast() = if (travelClass == TravelClass.FIRST) forecastFirst else forecastSecond
}

/** Your ratings for one forecast level: how many, and their mean. */
data class ForecastCheck(val level: Int?, val count: Int, val mean: Int)

object Ratings {
    val SCALE = 10..110
    const val STEP = 10

    fun label(percent: Int) = when {
        percent <= 10 -> "Almost empty"
        percent <= 30 -> "Plenty of seats"
        percent <= 50 -> "Half full"
        percent <= 70 -> "Few free seats"
        percent <= 90 -> "Hardly a seat"
        percent <= 100 -> "No seats left"
        else -> "No seats, many standing"
    }

    /** Your average for this train in this class on the same kind of day, or null if you never rated it. */
    fun average(all: List<Rating>, leg: Leg, cls: TravelClass): Average? {
        val key = leg.trainKey ?: return null
        val type = DayType.of(leg.departure.toSwiss().toLocalDate())
        val mine = latest(all).filter {
            it.trainKey == key && it.travelClass == cls && DayType.of(LocalDate.parse(it.date)) == type
        }
        if (mine.isEmpty()) return null
        return Average(mine.map { it.percent }.average().let { Math.round(it / STEP).toInt() * STEP }, mine.size)
    }

    /** The fullest train leg of the trip by your ratings. */
    fun forTrip(all: List<Rating>, trip: Trip, cls: TravelClass) =
        trip.trains.mapNotNull { average(all, it, cls) }.maxByOrNull { it.percent }

    /** All ratings are kept; a train rated twice on one day counts once, with its latest rating. */
    fun latest(all: List<Rating>) =
        all.groupBy { Triple(it.trainKey, it.date, it.travelClass) }.map { (_, rs) -> rs.maxBy { it.ratedAt } }

    /** The latest rating of this train on its day, to start the rating bar from. */
    fun current(all: List<Rating>, leg: Leg, cls: TravelClass): Rating? {
        val key = leg.trainKey ?: return null
        val date = leg.departure.toSwiss().toLocalDate().toString()
        return all.filter { it.trainKey == key && it.date == date && it.travelClass == cls }.maxByOrNull { it.ratedAt }
    }

    /** Ratings grouped by the forecast level they were made against (null = no forecast), for the class rated. */
    fun versusForecast(all: List<Rating>): List<ForecastCheck> =
        latest(all).groupBy { it.forecast() }
            .map { (level, rs) -> ForecastCheck(level, rs.size, rs.map { it.percent }.average().toInt()) }
            .sortedBy { it.level ?: 99 }

    private fun csv(s: String?) = s?.let { if (it.any { c -> c == ',' || c == '"' || c == '\n' }) "\"${it.replace("\"", "\"\"")}\"" else it }.orEmpty()

    /** Every rating, one row each, for analysis in a spreadsheet. Times are Swiss local time. */
    fun toCsv(all: List<Rating>): String = buildString {
        appendLine("rated_at,date,departure,line,train_number,from,to,delay_min,class,rating_percent,forecast_level,forecast_first,forecast_second")
        for (r in all.sortedBy { it.ratedAt }) {
            appendLine(
                listOf(
                    if (r.ratedAt > 0) r.ratedAt.toSwiss().toLocalDateTime().toString() else "",
                    r.date,
                    if (r.departure > 0) r.departure.toSwiss().toLocalTime().toString() else "",
                    csv(r.line), csv(r.trainNumber), csv(r.fromName), csv(r.toName),
                    r.departureDelay?.toString().orEmpty(),
                    if (r.travelClass == TravelClass.FIRST) "1" else "2",
                    r.percent.toString(),
                    r.forecast()?.toString().orEmpty(),
                    r.forecastFirst?.toString().orEmpty(),
                    r.forecastSecond?.toString().orEmpty(),
                ).joinToString(",")
            )
        }
    }
}

data class Average(val percent: Int, val count: Int)
