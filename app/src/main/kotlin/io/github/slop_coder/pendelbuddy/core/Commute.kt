package io.github.slop_coder.pendelbuddy.core

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
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

    /** Departures fetched ahead for every working day: to work 05:30–08:00, home 15:30–18:30. */
    val MORNING_WINDOW = LocalTime.of(5, 30)..LocalTime.of(8, 0)
    val EVENING_WINDOW = LocalTime.of(15, 30)..LocalTime.of(18, 30)

    /** One commute window to fetch: the route, its day and the departure span (epoch seconds). */
    data class Window(val route: Route, val day: LocalDate, val from: Long, val until: Long)

    /**
     * The commute windows of the working days among [days], in date order. Today's start no earlier than
     * [LOOK_BACK] before [now]; windows that are over are left out.
     */
    fun commuteWindows(s: Settings, days: List<LocalDate>, now: Long): List<Window> {
        val home = s.home ?: return emptyList()
        val work = s.work ?: return emptyList()
        return days.sorted().filter { DayType.of(it) == DayType.WEEKDAY }.flatMap { day ->
            listOf(
                Route(home, work, Direction.TO_WORK) to MORNING_WINDOW,
                Route(work, home, Direction.TO_HOME) to EVENING_WINDOW,
            ).mapNotNull { (route, span) ->
                val from = maxOf(day.atTime(span.start).atZone(SWISS).toEpochSecond(), now - LOOK_BACK)
                val until = day.atTime(span.endInclusive).atZone(SWISS).toEpochSecond()
                if (until < from) null else Window(route, day, from, until)
            }
        }
    }

    /**
     * Earlier departures (up to [count], latest first, within an hour) of [t]'s first ride: the same line from
     * the same stop, found among the other trips known for that day. For leaving earlier, e.g. to buy breakfast.
     */
    fun earlierDepartures(t: Trip, known: List<Trip>, count: Int = 2): List<Long> {
        val first = t.trains.firstOrNull() ?: return emptyList()
        return known.mapNotNull { other -> other.trains.firstOrNull()?.takeIf { it.line == first.line && it.fromId == first.fromId } }
            .map { it.departure }
            .filter { it < first.departure && it >= first.departure - 3600 }
            .distinct()
            .sortedDescending()
            .take(count)
    }

    /** The dates behind the Mo–Su tabs: each the next such day from [today] on (on a Wednesday, Mo is next week's). */
    fun weekDays(today: LocalDate): List<LocalDate> =
        DayOfWeek.entries.map { dow -> today.plusDays(((dow.value - today.dayOfWeek.value + 7) % 7).toLong()) }

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

    /**
     * The safety margin for this route: only on the normal commute (starting at home or work, not at a station
     * picked under "I'm at…") and only at a station strictly between start and end.
     */
    fun bufferFor(s: Settings, r: Route): Buffer? {
        if (r.from.id != origin(s, r.direction)?.id) return null
        return s.buffers.firstOrNull { it.appliesTo(r.direction) && it.station.id != r.from.id && it.station.id != r.to.id }
    }

    /**
     * Trips planned in two parts around a margin: each [first] (start → buffer station) is joined to the
     * earliest [second] (buffer station → end) leaving at least [Buffer.minutes] after it arrives. Of several
     * first parts reaching the same second part, only the latest is kept: no point waiting longer.
     */
    fun joinAtBuffer(first: List<Trip>, second: List<Trip>, b: Buffer): List<Trip> {
        val joined = first.filter { !it.cancelled }.mapNotNull { a ->
            val next = second.filter { !it.cancelled && it.departure >= a.expectedArrival() + b.minutes * 60L }
                .minByOrNull { it.arrival } ?: return@mapNotNull null
            a to next
        }
        return joined.groupBy { it.second }.map { (b2, pairs) -> pairs.maxBy { it.first.departure }.first to b2 }
            .map { (a, b2) ->
                val wait = Leg(
                    line = null,
                    fromId = b.station.id,
                    fromName = b.station.name,
                    toId = b.station.id,
                    toName = b.station.name,
                    departure = a.arrival,
                    arrival = b2.departure,
                )
                Trip(
                    fromId = a.fromId,
                    toId = b2.toId,
                    departure = a.departure,
                    arrival = b2.arrival,
                    departureDelay = a.departureDelay,
                    arrivalDelay = b2.arrivalDelay,
                    platform = a.platform,
                    transfers = a.transfers + b2.transfers + 1,
                    legs = a.legs + wait + b2.legs,
                )
            }
            .sortedBy { it.departure }
    }

    /** True if the trip changes at the buffer station with less than its margin (e.g. a trip cached before the margin was set). */
    fun breaksBuffer(t: Trip, b: Buffer): Boolean {
        val rides = t.trains
        for (i in 0 until rides.size - 1) {
            val inbound = rides[i]
            if (inbound.toId != b.station.id) continue
            if (rides[i + 1].departure - inbound.expectedArrival() < b.minutes * 60L) return true
        }
        return false
    }

    /** The trips allowed by the settings: no more than the maximum changes, and the margin kept. */
    fun allowed(t: Trip, s: Settings, buffer: Buffer?) =
        (s.maxChanges == null || t.transfers <= s.maxChanges) && (buffer == null || !breaksBuffer(t, buffer))
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
    /**
     * The stop the rated stretch starts at (the train had last left it); null = the whole ride (older ratings, or
     * no stops known). The forecast fields are then the forecast from that stop.
     */
    val callId: String? = null,
    val callName: String? = null,
    /** The platform sector of the wagon you sat in (from the train map), e.g. "C". */
    val sector: String? = null,
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

    /**
     * Your average for this train in this class on the same kind of day, or null if you never rated it. With
     * [callId]: only ratings of the stretch from that stop (and whole-ride ratings); without: all of them.
     */
    fun average(all: List<Rating>, leg: Leg, cls: TravelClass, callId: String? = null): Average? {
        val key = leg.trainKey ?: return null
        val type = DayType.of(leg.departure.toSwiss().toLocalDate())
        val mine = latest(all).filter {
            it.trainKey == key && it.travelClass == cls && DayType.of(LocalDate.parse(it.date)) == type &&
                (callId == null || it.callId == null || it.callId == callId)
        }
        if (mine.isEmpty()) return null
        return Average(mine.map { it.percent }.average().let { Math.round(it / STEP).toInt() * STEP }, mine.size)
    }

    /** Your averages stretch by stretch: for each stop of the ride rated from, in the order of the ride. */
    fun byStop(all: List<Rating>, leg: Leg, cls: TravelClass): List<Pair<Call, Average>> {
        val key = leg.trainKey ?: return emptyList()
        val type = DayType.of(leg.departure.toSwiss().toLocalDate())
        val mine = latest(all).filter { it.trainKey == key && it.travelClass == cls && it.callId != null && DayType.of(LocalDate.parse(it.date)) == type }
        return leg.calls.mapNotNull { c ->
            val rs = mine.filter { it.callId == c.id }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            c to Average(rs.map { it.percent }.average().let { Math.round(it / STEP).toInt() * STEP }, rs.size)
        }
    }

    /** The fullest train leg of the trip by your ratings. */
    fun forTrip(all: List<Rating>, trip: Trip, cls: TravelClass) =
        trip.trains.mapNotNull { average(all, it, cls) }.maxByOrNull { it.percent }

    /** All ratings are kept; a stretch of a train rated twice on one day counts once, with its latest rating. */
    fun latest(all: List<Rating>) =
        all.groupBy { listOf(it.trainKey, it.date, it.travelClass, it.callId) }.map { (_, rs) -> rs.maxBy { it.ratedAt } }

    /** The latest rating of this train on its day from stop [callId], to start the rating bar from. */
    fun current(all: List<Rating>, leg: Leg, cls: TravelClass, callId: String? = null): Rating? {
        val key = leg.trainKey ?: return null
        val date = leg.departure.toSwiss().toLocalDate().toString()
        return all.filter { it.trainKey == key && it.date == date && it.travelClass == cls && it.callId == callId }.maxByOrNull { it.ratedAt }
    }

    /** Ratings grouped by the forecast level they were made against (null = no forecast), for the class rated. */
    fun versusForecast(all: List<Rating>): List<ForecastCheck> =
        latest(all).groupBy { it.forecast() }
            .map { (level, rs) -> ForecastCheck(level, rs.size, rs.map { it.percent }.average().toInt()) }
            .sortedBy { it.level ?: 99 }

    private fun csv(s: String?) = s?.let { if (it.any { c -> c == ',' || c == '"' || c == '\n' }) "\"${it.replace("\"", "\"\"")}\"" else it }.orEmpty()

    /** Every rating, one row each, for analysis in a spreadsheet. Times are Swiss local time. */
    fun toCsv(all: List<Rating>): String = buildString {
        appendLine("rated_at,date,departure,line,train_number,from,to,delay_min,class,rating_percent,forecast_level,forecast_first,forecast_second,rated_from,sector")
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
                    csv(r.callName),
                    csv(r.sector),
                ).joinToString(",")
            )
        }
    }
}

data class Average(val percent: Int, val count: Int)
