package io.github.slop_coder.pendelbuddy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CacheTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(SWISS).toEpochSecond()

    private fun trip(dep: Long, line: String = "IC 8", delay: Int? = null, cap: Int? = null) = Trip(
        fromId = "A", toId = "B", departure = dep, arrival = dep + 3600, departureDelay = delay,
        legs = listOf(Leg(line, fromId = "A", fromName = "Aa", toName = "Bb", departure = dep, arrival = dep + 3600, departureDelay = delay, capacity = Capacity(null, cap))),
    )

    // Monday 9 and Monday 16 November 2026.
    private val mon9 = LocalDate.of(2026, 11, 9)
    private val mon16 = LocalDate.of(2026, 11, 16)

    @Test
    fun copiesSameWeekdayWithoutRealtime() {
        val fetched = at(2026, 11, 9, 7, 0)
        val cache = RouteCache("A", "B").merge(listOf(trip(at(2026, 11, 9, 7, 2), delay = 4, cap = 3)), fetched)
        val shown = cache.project(mon16)
        assertEquals(1, shown.size)
        assertEquals(mon9, shown[0].copiedFrom)
        assertEquals(at(2026, 11, 16, 7, 2), shown[0].trip.departure)
        assertNull(shown[0].trip.departureDelay)
        assertNull(shown[0].trip.capacity.second)
    }

    @Test
    fun freshTodayKeepsRealtimeOnlyWhileFresh() {
        val fetched = at(2026, 11, 16, 7, 0)
        val cache = RouteCache("A", "B").merge(listOf(trip(at(2026, 11, 16, 7, 30), delay = 2, cap = 2)), fetched)
        val today = cache.project(mon16).single()
        assertNull(today.copiedFrom)
        assertEquals(2, today.atTime(fetched + 60).trip.departureDelay)
        // Delays go after 10 minutes, occupancy stays for the day.
        val later = today.atTime(fetched + 20 * 60).trip
        assertNull(later.departureDelay)
        assertEquals(2, later.capacity.second)
        assertNull(today.atTime(fetched + 13 * 3600).trip.capacity.second)
    }

    @Test
    fun newFetchReplacesCoveredWindowOnly() {
        var cache = RouteCache("A", "B").merge(
            listOf(trip(at(2026, 11, 9, 7, 2)), trip(at(2026, 11, 9, 7, 32)), trip(at(2026, 11, 9, 17, 2))),
            at(2026, 11, 9, 7, 0),
        )
        // A week later the 07:32 was dropped from the timetable; the 17:02 was not fetched again.
        cache = cache.merge(listOf(trip(at(2026, 11, 16, 7, 2)), trip(at(2026, 11, 16, 7, 40))), at(2026, 11, 16, 7, 0))
        val times = cache.project(mon16).map { it.trip.departure.toSwiss().toLocalTime().toString() }
        assertEquals(listOf("07:02", "07:40", "17:02"), times)
    }

    @Test
    fun fallsBackToSameKindOfDay() {
        val cache = RouteCache("A", "B").merge(listOf(trip(at(2026, 11, 10, 7, 2))), at(2026, 11, 10, 7, 0)) // Tuesday
        assertEquals(1, cache.project(mon16).size)
        assertTrue(cache.project(LocalDate.of(2026, 11, 21)).isEmpty()) // Saturday
    }

    @Test
    fun timetableChange() {
        assertEquals(LocalDate.of(2026, 12, 13), timetableChange(2026))
        assertEquals(LocalDate.of(2025, 12, 14), timetableChange(2025))
        assertEquals(LocalDate.of(2024, 12, 15), timetableChange(2024))
        assertTrue(crossesTimetableChange(LocalDate.of(2026, 12, 7), LocalDate.of(2026, 12, 14)))
        assertFalse(crossesTimetableChange(LocalDate.of(2026, 12, 13), LocalDate.of(2026, 12, 20)))
        val cache = RouteCache("A", "B").merge(listOf(trip(at(2026, 12, 7, 7, 2))), at(2026, 12, 7, 7, 0))
        assertTrue(cache.project(LocalDate.of(2026, 12, 14)).isEmpty())
    }

    @Test
    fun upcomingSkipsGoneTrains() {
        val now = at(2026, 11, 16, 7, 10)
        val cache = RouteCache("A", "B").merge(
            listOf(trip(at(2026, 11, 16, 7, 2)), trip(at(2026, 11, 16, 7, 5), delay = 6), trip(at(2026, 11, 16, 7, 30))),
            now,
        )
        val next = cache.project(mon16).upcoming(now, 5).map { it.trip.departure.toSwiss().toLocalTime().toString() }
        assertEquals(listOf("07:05", "07:30"), next)
    }
}
