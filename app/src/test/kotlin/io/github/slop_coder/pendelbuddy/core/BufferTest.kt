package io.github.slop_coder.pendelbuddy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Made-up commute: a bus from Talwil to Zwischenau, then a train to Beispielstadt. */
class BufferTest {
    private val talwil = Station("8599008", "Talwil")
    private val zwischenau = Station("8599007", "Zwischenau")
    private val beispielstadt = Station("8599003", "Beispielstadt")
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 16, h, m).atZone(SWISS).toEpochSecond()

    private fun bus(dep: Long, arr: Long, delay: Int? = null) = Trip(
        talwil.id, zwischenau.id, dep, arr, arrivalDelay = delay,
        legs = listOf(Leg("B 7", fromId = talwil.id, fromName = talwil.name, toId = zwischenau.id, toName = zwischenau.name, departure = dep, arrival = arr, arrivalDelay = delay)),
    )
    private fun train(dep: Long, arr: Long) = Trip(
        zwischenau.id, beispielstadt.id, dep, arr,
        legs = listOf(Leg("IC 9", fromId = zwischenau.id, fromName = zwischenau.name, toId = beispielstadt.id, toName = beispielstadt.name, departure = dep, arrival = arr)),
    )

    private val margin = Buffer(zwischenau, 15, Direction.TO_WORK)
    private val settings = Settings(talwil, beispielstadt, listOf(zwischenau), buffers = listOf(margin))

    @Test
    fun joinKeepsMarginAndLatestBus() {
        val buses = listOf(bus(at(6, 40), at(6, 50)), bus(at(6, 50), at(7, 0)), bus(at(7, 0), at(7, 10)), bus(at(7, 10), at(7, 20)))
        val trains = listOf(train(at(7, 5), at(7, 50)), train(at(7, 20), at(8, 5)), train(at(7, 35), at(8, 20)))
        val trips = Commute.joinAtBuffer(buses, trains, margin)
        // 06:40 → 07:05, 06:50 → 07:20; the 07:00 and 07:10 buses both reach only the 07:35, so just the later bus is kept.
        assertEquals(listOf(at(6, 40), at(6, 50), at(7, 10)), trips.map { it.departure })
        assertEquals(at(7, 35), trips[2].trains[1].departure)
        val first = trips[0]
        assertEquals(at(7, 5), first.trains[1].departure)
        assertEquals(1, first.transfers)
        assertTrue(first.legs[1].walk) // the margin, shown as a wait at Zwischenau
        assertFalse(trips.any { Commute.breaksBuffer(it, margin) })
    }

    @Test
    fun lateBusCountsAgainstTheMargin() {
        // The bus is planned to arrive 06:50 but runs 8 minutes late: 06:58 + 15 min misses the 07:05.
        val trips = Commute.joinAtBuffer(listOf(bus(at(6, 40), at(6, 50), delay = 8)), listOf(train(at(7, 5), at(7, 50)), train(at(7, 20), at(8, 5))), margin)
        assertEquals(at(7, 20), trips.single().trains[1].departure)
    }

    @Test
    fun marginOnlyOnTheNormalCommute() {
        assertEquals(margin, Commute.bufferFor(settings, Route(talwil, beispielstadt, Direction.TO_WORK)))
        assertNull(Commute.bufferFor(settings, Route(beispielstadt, talwil, Direction.TO_HOME)))
        // Already at Zwischenau (picked under "I'm at…"): no margin.
        assertNull(Commute.bufferFor(settings, Route(zwischenau, beispielstadt, Direction.TO_WORK)))
    }

    @Test
    fun filters() {
        val tight = Trip(
            talwil.id, beispielstadt.id, at(6, 50), at(7, 50), transfers = 1,
            legs = bus(at(6, 50), at(7, 0)).legs + train(at(7, 5), at(7, 50)).legs,
        )
        assertTrue(Commute.breaksBuffer(tight, margin))
        assertFalse(Commute.allowed(tight, settings, margin))
        assertTrue(Commute.allowed(tight, settings, null))
        assertFalse(Commute.allowed(tight, settings.copy(maxChanges = 0), null))
        assertTrue(Commute.allowed(tight, settings.copy(maxChanges = 1), null))
    }

    @Test
    fun listLooksBackFiveMinutes() {
        val now = at(7, 10)
        val cache = RouteCache("a", "b").merge(
            listOf(
                train(at(7, 0), at(7, 45)),  // left 10 min ago, on time: gone
                Trip("a", "b", at(6, 58), at(7, 40), departureDelay = 15), // 15 min late: still to come
                train(at(7, 6), at(7, 50)),  // left 4 min ago: still shown, maybe it is late
                train(at(7, 20), at(8, 5)),
            ),
            now,
        )
        val shown = cache.project(LocalDate.of(2026, 11, 16)).upcoming(now, 10).map { it.trip.departure }
        assertEquals(listOf(at(6, 58), at(7, 6), at(7, 20)), shown)
    }

    @Test
    fun ojpTransferLimit() {
        val xml = Ojp.tripRequest(talwil, beispielstadt, at(7, 0), 8, 0, maxChanges = 1)
        assertTrue(xml.contains("<TransferLimit>1</TransferLimit>"))
        assertTrue(xml.indexOf("<TransferLimit>") < xml.indexOf("<IncludeIntermediateStops>"))
        assertFalse(Ojp.tripRequest(talwil, beispielstadt, at(7, 0), 8, 0).contains("TransferLimit"))
    }
}
