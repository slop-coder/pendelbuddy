package io.github.slop_coder.pendelbuddy.core

import kotlinx.serialization.Serializable

/**
 * The trip you said you take ("I take this"), with the route it was planned on so it can be asked for again.
 * [found] is false when the latest answer no longer had this trip (cancelled or moved).
 */
@Serializable
data class MyTrip(
    val trip: Trip,
    val from: Station,
    val to: Station,
    val direction: Direction,
    /** When [trip] was last downloaded, epoch seconds. */
    val fetchedAt: Long,
    val found: Boolean = true,
    /** [Change.key]s already announced, so each change is notified once. */
    val alerted: Set<String> = emptySet(),
    /** Made-up changes for testing the alerts (Settings → Testing); null = real data only. */
    val fake: Fake? = null,
    /** With [fake] on: the trip as downloaded, without the made-up changes. */
    val real: Trip? = null,
) {
    val route get() = Route(from, to, direction)

    /** Arrived: no more refreshing. */
    fun arrived(now: Long) = now > trip.expectedArrival()

    /** Long enough after arriving (time to rate the trains) to drop it on its own. */
    fun over(now: Long) = now > trip.expectedArrival() + KEEP_AFTER_ARRIVAL

    /** Refreshed every [REFRESH] while the app is open, from [WATCH_BEFORE] before leaving until arriving. */
    fun dueForRefresh(now: Long) =
        !arrived(now) && now >= trip.departure - WATCH_BEFORE && now - fetchedAt >= REFRESH

    /** The same trip in [fresh], or null if it is not there. */
    fun findIn(fresh: List<Trip>): Trip? = MyTrips.match(trip, fresh)

    companion object {
        const val REFRESH = 5 * 60L
        /** Watching (the 5-minute refresh, and in the background) starts at most this long before departure. */
        const val WATCH_BEFORE = 10 * 60L
        const val KEEP_AFTER_ARRIVAL = 30 * 60L
    }
}

object MyTrips {
    /**
     * The same trip among [fresh]: the same runs of the same trains (OJP journey refs), else the same train
     * numbers on the same day, else the same scheduled time and lines.
     */
    fun match(old: Trip, fresh: List<Trip>): Trip? {
        val sameDay = fresh.filter { it.serviceDate == old.serviceDate }
        val refs = old.trains.map { it.journeyRef }
        if (refs.isNotEmpty() && refs.all { it != null }) {
            sameDay.firstOrNull { t -> t.trains.map { it.journeyRef } == refs }?.let { return it }
        }
        val numbers = old.trains.map { it.trainNumber }
        if (numbers.isNotEmpty() && numbers.all { it != null }) {
            sameDay.firstOrNull { t -> t.trains.map { it.trainNumber } == numbers }?.let { return it }
        }
        return sameDay.firstOrNull { it.key == old.key }
    }
}

/** Made-up changes to your trip, for trying out the alerts: put on top of every download until cleared. */
@Serializable
data class Fake(
    /** Minutes late, leaving and arriving, for the first train. */
    val delay: Int = 0,
    val cancelFirst: Boolean = false,
    /** The first train leaves from another platform than planned. */
    val newPlatform: Boolean = false,
    /** The first train does not stop where you get off. */
    val skipExit: Boolean = false,
    /** The trip is no longer in the timetable. */
    val gone: Boolean = false,
) {
    val any get() = this != Fake()

    fun applyTo(t: Trip): Trip {
        val first = t.legs.indexOfFirst { !it.walk }.takeIf { it >= 0 } ?: return t
        val legs = t.legs.toMutableList()
        var l = legs[first]
        if (delay > 0) l = l.copy(departureDelay = delay, arrivalDelay = delay)
        if (newPlatform) {
            val planned = l.plannedPlatform ?: l.platform ?: "1"
            l = l.copy(plannedPlatform = planned, platform = planned.toIntOrNull()?.plus(1)?.toString() ?: "${planned}A")
        }
        if (cancelFirst) l = l.copy(cancelled = true)
        if (skipExit) l = l.copy(cancelled = true, alightSkipped = true)
        legs[first] = l
        val trains = legs.filter { !it.walk }
        return t.copy(legs = legs, departureDelay = trains.first().departureDelay, arrivalDelay = trains.last().arrivalDelay)
    }
}
