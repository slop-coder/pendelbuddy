package io.github.slop_coder.pendelbuddy.core

import java.time.format.DateTimeFormatter

/**
 * Something about your trip that changes your plans. [key] stays the same while the problem does, so each one is
 * announced once; [station] and [at] are where and when to look for other trips (null: nothing to replan).
 */
data class Change(val key: String, val text: String, val station: Station? = null, val at: Long? = null)

/**
 * The material changes of your trip: 10+ minutes late, a new platform where you get on, a cancelled train, a train
 * not stopping where you get on or off, a connection you can no longer make, or the trip gone from the timetable.
 * Stops the train skips in between do not count.
 */
object Changes {
    const val DELAY_MINUTES = 10

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private fun hm(t: Long) = t.toSwiss().format(HM)

    fun of(m: MyTrip, now: Long): List<Change> {
        val t = m.trip
        val trains = t.trains
        if (trains.isEmpty()) return emptyList()
        val out = mutableListOf<Change>()
        if (!m.found) out += replan("gone", "Your trip is no longer in the timetable", current(trains, now), now)
        trains.forEachIndexed { i, leg ->
            val name = "${leg.line} ${hm(leg.departure)}"
            when {
                leg.boardSkipped -> out += replan("skip-board:$i", "$name does not stop at ${leg.fromName}", leg, now)
                leg.alightSkipped -> out += replan("skip-alight:$i", "$name does not stop at ${leg.toName}", leg, now)
                leg.cancelled -> out += replan("cancelled:$i", "$name is cancelled", leg, now)
            }
            val planned = leg.plannedPlatform
            if (planned != null && leg.platform != null && leg.platform != planned && now < leg.expectedDeparture()) {
                out += Change("platform:$i:${leg.platform}", "$name leaves ${leg.fromName} from platform ${leg.platform}, not $planned")
            }
        }
        val first = trains.first()
        val late = first.departureDelay ?: 0
        if (late >= DELAY_MINUTES && now < first.expectedDeparture()) {
            out += replan("late-dep:${late / DELAY_MINUTES}", "${first.line} leaves ${first.fromName} $late min late, at ${hm(first.expectedDeparture())}", first, now)
        }
        val last = trains.last()
        val lateArrival = last.arrivalDelay ?: 0
        if (lateArrival >= DELAY_MINUTES) {
            out += replan("late-arr:${lateArrival / DELAY_MINUTES}", "You arrive at ${last.toName} $lateArrival min late, at ${hm(last.expectedArrival())}", current(trains, now), now)
        }
        out += lostConnections(t)
        return out.distinctBy { it.key }
    }

    /** Changes where the next train leaves before you can get there (arrival plus the walk in between). */
    private fun lostConnections(t: Trip): List<Change> {
        val out = mutableListOf<Change>()
        var prev: Leg? = null
        var walk = 0L
        var n = 0
        for (leg in t.legs) {
            if (leg.walk) {
                walk += leg.arrival - leg.departure
                continue
            }
            val a = prev
            if (a != null && !leg.cancelled && leg.expectedDeparture() < a.expectedArrival() + walk) {
                out += Change(
                    "connection:$n",
                    "Connection at ${a.toName} lost: ${a.line} arrives ${hm(a.expectedArrival())}, ${leg.line} leaves ${hm(leg.expectedDeparture())}",
                    Station(a.toId, a.toName),
                    a.expectedArrival(),
                )
            }
            prev = leg
            walk = 0
            n++
        }
        return out
    }

    /** The train you are on, or the next one you take. */
    private fun current(trains: List<Leg>, now: Long) = trains.lastOrNull { now >= it.expectedDeparture() } ?: trains.first()

    /** A change with other trips to look at instead of [leg]: from where it leaves, or once on board, from its next stop. */
    private fun replan(key: String, text: String, leg: Leg, now: Long): Change {
        if (now < leg.expectedDeparture()) return Change(key, text, Station(leg.fromId, leg.fromName), maxOf(now, leg.departure))
        val next = leg.calls.firstOrNull { it.departure > now }
        return if (next != null) Change(key, text, Station(next.id, next.name), next.departure)
        else Change(key, text, Station(leg.toId, leg.toName), leg.expectedArrival())
    }
}
