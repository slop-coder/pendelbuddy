package io.github.slop_coder.pendelbuddy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ChangesTest {
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 16, h, m).atZone(SWISS).toEpochSecond()

    /** Talwil 07:32 → Musterdorf 08:02 (IC 8), change 6 min, Musterdorf 08:10 → Beispielstadt 08:40 (S 3). */
    private fun ic(depDelay: Int? = null, arrDelay: Int? = null, platform: String? = "5", planned: String? = "5", cancelled: Boolean = false, boardSkipped: Boolean = false, alightSkipped: Boolean = false) = Leg(
        "IC 8", fromId = "8599008", fromName = "Talwil", toId = "8599002", toName = "Musterdorf",
        departure = at(7, 32), arrival = at(8, 2), departureDelay = depDelay, arrivalDelay = arrDelay,
        platform = platform, plannedPlatform = planned, cancelled = cancelled || boardSkipped || alightSkipped,
        boardSkipped = boardSkipped, alightSkipped = alightSkipped,
        calls = listOf(Call("8599008", "Talwil", at(7, 32)), Call("8599007", "Zwischenau", at(7, 45))),
    )

    private val change = Leg(null, fromId = "8599002", fromName = "Musterdorf", toName = "Musterdorf", departure = at(8, 2), arrival = at(8, 8))

    private fun s3(depDelay: Int? = null, arrDelay: Int? = null) = Leg(
        "S 3", fromId = "8599002", fromName = "Musterdorf", toId = "8599003", toName = "Beispielstadt",
        departure = at(8, 10), arrival = at(8, 40), departureDelay = depDelay, arrivalDelay = arrDelay,
    )

    private fun mine(vararg legs: Leg, found: Boolean = true) = MyTrip(
        Trip(
            fromId = "8599008", toId = "8599003", departure = legs.first().departure, arrival = legs.last().arrival,
            legs = legs.toList(),
        ),
        Station("8599008", "Talwil"), Station("8599003", "Beispielstadt"), Direction.TO_WORK, fetchedAt = at(7, 0), found = found,
    )

    private fun keys(m: MyTrip, now: Long = at(7, 20)) = Changes.of(m, now).map { it.key }

    @Test
    fun onTimeIsQuiet() {
        assertEquals(emptyList<String>(), keys(mine(ic(depDelay = 0), change, s3(depDelay = 0))))
        // Late, but under 10 minutes and the connection still works: no alert.
        assertEquals(emptyList<String>(), keys(mine(ic(depDelay = 4, arrDelay = 2), change, s3(arrDelay = 9))))
    }

    @Test
    fun tenMinutesLate() {
        val c = Changes.of(mine(ic(depDelay = 12, arrDelay = 0), change, s3()), at(7, 20))
        assertEquals(listOf("late-dep:1"), c.map { it.key })
        // Alternatives from where you get on, at the planned time.
        assertEquals("8599008", c.single().station?.id)
        assertEquals(at(7, 32), c.single().at)
        // Arriving 10+ min late; 20 min is a new step, notified again.
        assertEquals(listOf("late-arr:1"), keys(mine(ic(), change, s3(arrDelay = 10))))
        assertEquals(listOf("late-arr:2"), keys(mine(ic(), change, s3(arrDelay = 21))))
    }

    @Test
    fun newPlatformOnlyWhereYouGetOn() {
        assertEquals(listOf("platform:0:7"), keys(mine(ic(platform = "7", planned = "5"), change, s3())))
        // Once the train has left it does not matter any more.
        assertEquals(emptyList<String>(), keys(mine(ic(platform = "7", planned = "5"), change, s3()), now = at(7, 40)))
        // No planned platform known (e.g. transport.opendata.ch): nothing to compare.
        assertEquals(emptyList<String>(), keys(mine(ic(platform = "7", planned = null), change, s3())))
    }

    @Test
    fun cancelledOrNotStopping() {
        assertEquals(listOf("cancelled:0"), keys(mine(ic(cancelled = true), change, s3())))
        assertEquals(listOf("skip-board:0"), keys(mine(ic(boardSkipped = true), change, s3())))
        // Does not reach Musterdorf, where you change. Under way: alternatives from the next stop.
        val c = Changes.of(mine(ic(alightSkipped = true), change, s3()), at(7, 40)).single()
        assertEquals("skip-alight:0", c.key)
        assertEquals("Zwischenau", c.station?.name)
        assertEquals(at(7, 45), c.at)
    }

    @Test
    fun fakeChangesAreAnnounced() {
        val real = mine(ic(), change, s3())
        fun keysWith(f: Fake) = keys(real.copy(trip = f.applyTo(real.trip), found = !f.gone))
        assertEquals(listOf("late-dep:1", "connection:1"), keysWith(Fake(delay = 10)))
        assertEquals(listOf("cancelled:0"), keysWith(Fake(cancelFirst = true)))
        assertEquals(listOf("platform:0:6"), keysWith(Fake(newPlatform = true)))
        assertEquals(listOf("skip-alight:0"), keysWith(Fake(skipExit = true)))
        assertEquals(listOf("gone"), keysWith(Fake(gone = true)))
        // Applied to the real trip each time: switching the delay off again leaves no trace.
        assertEquals(real.trip, Fake().applyTo(real.trip))
        assertEquals(10, Fake(delay = 10).applyTo(real.trip).departureDelay)
    }

    @Test
    fun problemInWords() {
        assertEquals(null, ic().problem)
        assertEquals("IC 8 is cancelled", ic(cancelled = true).problem)
        assertEquals("IC 8 does not stop at Talwil", ic(boardSkipped = true).problem)
        assertEquals("IC 8 does not stop at Musterdorf", ic(alightSkipped = true).problem)
    }

    @Test
    fun skippedStopsInBetweenDoNotCount() {
        // The answer marks a stop in between as not served; only boarding and exit stops are kept as flags.
        assertEquals(emptyList<String>(), keys(mine(ic(), change, s3())))
    }

    @Test
    fun lostConnection() {
        // IC 8 arrives 08:07, the walk takes 6 min, the S 3 leaves 08:10.
        val c = Changes.of(mine(ic(arrDelay = 5), change, s3()), at(7, 20))
        assertEquals(listOf("connection:1"), c.map { it.key })
        assertEquals("Musterdorf", c.single().station?.name)
        assertEquals(at(8, 7), c.single().at)
        // The S 3 waits (late too): still reachable.
        assertEquals(emptyList<String>(), keys(mine(ic(arrDelay = 5), change, s3(depDelay = 3))))
    }

    @Test
    fun goneFromTheTimetable() {
        val c = Changes.of(mine(ic(), change, s3(), found = false), at(7, 20))
        assertEquals(listOf("gone"), c.map { it.key })
        assertTrue(c.single().text.contains("no longer"))
        assertEquals("Talwil", c.single().station?.name)
    }
}
