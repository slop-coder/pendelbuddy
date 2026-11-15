package io.github.slop_coder.pendelbuddy.core

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** How long fetched data may be shown. Seconds unless noted. */
object Freshness {
    /** Delays and platform changes move by the minute. */
    const val DELAYS = 10 * 60L

    /** Occupancy forecasts are made per day; only shown for the day they were fetched for. */
    const val OCCUPANCY = 12 * 3600L

    /** Forecasts older than this are hidden instead of shown as current. */
    const val WEATHER = 3 * 3600L

    /** Older timetable data is dropped; it is refreshed every time the app is online anyway. */
    const val TIMETABLE_DAYS = 35L
}

@Serializable
data class CachedTrip(val trip: Trip, val fetchedAt: Long)

/** Everything known about one from→to pair. Trips keep their real dates; [project] maps them onto another day. */
@Serializable
data class RouteCache(
    val fromId: String,
    val toId: String,
    val trips: List<CachedTrip> = emptyList(),
    /** Date of the last whole-day prefetch. */
    val prefilledOn: String? = null,
)

/** A trip ready to show, with where it came from. */
data class ShownTrip(
    val trip: Trip,
    val fetchedAt: Long,
    /** The day whose timetable this is, when it is not today's own data. */
    val copiedFrom: LocalDate? = null,
)

enum class DayType { WEEKDAY, SATURDAY, SUNDAY;

    companion object {
        fun of(d: LocalDate) = when (d.dayOfWeek) {
            DayOfWeek.SATURDAY -> SATURDAY
            DayOfWeek.SUNDAY -> SUNDAY
            else -> WEEKDAY
        }
    }
}

/** The yearly timetable change: the night after the second Saturday of December. */
fun timetableChange(year: Int): LocalDate =
    LocalDate.of(year, 12, 1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.SATURDAY)).plusDays(1)

/** True if timetable data from [from] is not valid on [on] because a timetable change lies in between. */
fun crossesTimetableChange(from: LocalDate, on: LocalDate): Boolean {
    if (!from.isBefore(on)) return false
    return (from.year..on.year).any { y -> timetableChange(y).let { it.isAfter(from) && !it.isAfter(on) } }
}

/**
 * Adds freshly fetched [fresh] trips. Older trips of the same weekday inside the time span the fetch
 * covered are replaced (they may have been cancelled or moved); trips outside it stay for offline use.
 */
fun RouteCache.merge(fresh: List<Trip>, fetchedAt: Long): RouteCache {
    if (fresh.isEmpty()) return this
    val windows = fresh.groupBy { it.serviceDate }.map { (date, ts) ->
        Triple(date, ts.minOf { it.departure.toSwiss().toLocalTime() }, ts.maxOf { it.departure.toSwiss().toLocalTime() })
    }
    val cutoff = fetchedAt.toSwiss().toLocalDate().minusDays(Freshness.TIMETABLE_DAYS)
    val kept = trips.filter { c ->
        val d = c.trip.serviceDate
        val t = c.trip.departure.toSwiss().toLocalTime()
        d.isAfter(cutoff) && windows.none { (date, from, to) ->
            d.dayOfWeek == date.dayOfWeek && !d.isAfter(date) && t >= from && t <= to
        }
    }
    return copy(trips = (kept + fresh.map { CachedTrip(it, fetchedAt) }).sortedBy { it.trip.departure })
}

/**
 * The trips for [date]: today's own data where there is some, otherwise the timetable of the same
 * weekday (or, failing that, the latest day of the same kind) moved onto [date]. Realtime parts
 * are dropped from copied trips, and data from before a timetable change is never used.
 */
fun RouteCache.project(date: LocalDate): List<ShownTrip> {
    val usable = trips.filter { c ->
        val d = c.trip.serviceDate
        !crossesTimetableChange(d, date) && ChronoUnit.DAYS.between(d, date) <= Freshness.TIMETABLE_DAYS
    }
    val sameWeekday = usable.filter { it.trip.serviceDate.dayOfWeek == date.dayOfWeek }
    val source = sameWeekday.ifEmpty {
        val sameType = usable.filter { DayType.of(it.trip.serviceDate) == DayType.of(date) }
        val latest = sameType.maxOfOrNull { it.trip.serviceDate }
        sameType.filter { it.trip.serviceDate == latest }
    }
    return source
        .groupBy { it.trip.key }
        .map { (_, same) -> same.maxBy { it.trip.serviceDate } }
        .map { c ->
            val d = c.trip.serviceDate
            if (d == date) ShownTrip(c.trip, c.fetchedAt)
            else {
                val start = c.trip.departure.toSwiss()
                val moved = start.toLocalDateTime().plusDays(ChronoUnit.DAYS.between(d, date)).atZone(SWISS)
                ShownTrip(c.trip.shifted(moved.toEpochSecond() - start.toEpochSecond()).timetableOnly(), c.fetchedAt, d)
            }
        }
        .sortedBy { it.trip.departure }
}

/** Hides delays and occupancy that are too old to trust at [now]. */
fun ShownTrip.atTime(now: Long): ShownTrip {
    if (copiedFrom != null) return this
    val age = now - fetchedAt
    var t = trip
    if (age > Freshness.OCCUPANCY) t = t.timetableOnly()
    else if (age > Freshness.DELAYS) t = t.withoutDelays()
    return copy(trip = t)
}

/** How far back the list reaches, so a delayed train (or one that just left) is still shown. */
const val LOOK_BACK = 5 * 60L

/**
 * The trips from [LOOK_BACK] before [from] on, plus earlier ones that are late enough to still be waiting.
 * [from] is now or a picked time; [now] decides which delays are still fresh.
 */
fun List<ShownTrip>.upcoming(from: Long, count: Int, now: Long = from) =
    map { it.atTime(now) }.filter { it.trip.departure >= from - LOOK_BACK || it.trip.expectedDeparture() >= from }.take(count)
