package io.github.slop_coder.pendelbuddy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class MyTripTest {
    private fun at(h: Int, m: Int, d: Int = 16) = LocalDateTime.of(2026, 11, d, h, m).atZone(SWISS).toEpochSecond()

    private fun leg(line: String, dep: Long, number: String?, ref: String?, delay: Int? = null) = Leg(
        line, fromId = "8599008", fromName = "Talwil", toId = "8599003", toName = "Beispielstadt",
        departure = dep, arrival = dep + 1800, departureDelay = delay, arrivalDelay = delay, trainNumber = number, journeyRef = ref,
    )

    private fun trip(vararg legs: Leg) = Trip(
        fromId = "8599008", toId = "8599003", departure = legs.first().departure, arrival = legs.last().arrival,
        departureDelay = legs.first().departureDelay, arrivalDelay = legs.last().arrivalDelay, legs = legs.toList(),
    )

    private val talwil = Station("8599008", "Talwil")
    private val beispielstadt = Station("8599003", "Beispielstadt")

    @Test
    fun findsTheSameRunAgain() {
        val old = trip(leg("IC 8", at(7, 32), "10809", "ch:1:sjyid:100001:10809-001"))
        // Now 6 min late; an earlier and a later train around it.
        val same = trip(leg("IC 8", at(7, 32), "10809", "ch:1:sjyid:100001:10809-001", delay = 6))
        val fresh = listOf(
            trip(leg("IC 8", at(7, 2), "10807", "ch:1:sjyid:100001:10807-001")),
            same,
            trip(leg("IC 8", at(8, 2), "10811", "ch:1:sjyid:100001:10811-001")),
        )
        assertSame(same, MyTrips.match(old, fresh))
    }

    @Test
    fun fallsBackToTrainNumberThenTime() {
        // Saved from transport.opendata.ch (no journey refs): the train number decides.
        val old = trip(leg("IR 16", at(7, 40), "2110", null))
        val byNumber = trip(leg("IR 16", at(7, 40), "2110", "ch:1:sjyid:100001:2110-001"))
        assertSame(byNumber, MyTrips.match(old, listOf(byNumber)))
        // Neither: same scheduled time and line.
        val bare = trip(leg("S 3", at(7, 45), null, null))
        val again = trip(leg("S 3", at(7, 45), null, null, delay = 1))
        assertSame(again, MyTrips.match(bare, listOf(trip(leg("S 3", at(7, 15), null, null)), again)))
    }

    @Test
    fun noMatchOnAnotherDayOrWhenGone() {
        val old = trip(leg("IC 8", at(7, 32), "10809", "ch:1:sjyid:100001:10809-001"))
        assertNull(MyTrips.match(old, listOf(trip(leg("IC 8", at(7, 32, d = 17), "10809", "ch:1:sjyid:100001:10809-001")))))
        assertNull(MyTrips.match(old, listOf(trip(leg("IC 8", at(8, 2), "10811", "ch:1:sjyid:100001:10811-001")))))
    }

    @Test
    fun refreshOnlyWhenCloseAndOld() {
        val t = trip(leg("IC 8", at(7, 32), "10809", "r", delay = 3))
        val mine = MyTrip(t, talwil, beispielstadt, Direction.TO_WORK, fetchedAt = at(7, 0))
        // More than 10 min before leaving: nothing to update yet.
        assertFalse(mine.dueForRefresh(at(7, 21)))
        assertTrue(mine.dueForRefresh(at(7, 22)))
        // 4 min since the last download: not yet; 5 min: yes.
        assertFalse(mine.copy(fetchedAt = at(7, 22)).dueForRefresh(at(7, 26)))
        assertTrue(mine.copy(fetchedAt = at(7, 22)).dueForRefresh(at(7, 27)))
        // Under way, until the expected arrival (08:02 + 3 min).
        assertTrue(mine.dueForRefresh(at(8, 4)))
        assertTrue(mine.arrived(at(8, 6)))
        assertFalse(mine.dueForRefresh(at(8, 10)))
        // Dropped half an hour after arriving.
        assertFalse(mine.over(at(8, 35)))
        assertTrue(mine.over(at(8, 36)))
        assertEquals(Route(talwil, beispielstadt, Direction.TO_WORK), mine.route)
    }
}
