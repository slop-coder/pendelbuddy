package io.github.slop_coder.pendelbuddy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OjpTest {
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 16, h, m).atZone(SWISS).toEpochSecond()

    @Test
    fun stationIds() {
        assertEquals("ch:1:sloid:99002", Ojp.sloid("8599002"))
        assertEquals("ch:1:sloid:1234", Ojp.sloid("8501234"))
        assertEquals("8599002", Ojp.uic("ch:1:sloid:99002:4:7"))
        assertEquals("8501234", Ojp.uic("ch:1:sloid:1234"))
        assertTrue(Ojp.tripRequest(Station("8599008", "Talwil"), Station("8599003", "Beispielstadt"), at(7, 30), 6, 0)
            .contains("<StopPlaceRef>ch:1:sloid:99008</StopPlaceRef>"))
    }

    @Test
    fun parseTrip() {
        val t = Ojp.parseTrips(fixture("ojp-trip.xml"), "8599008", "8599003").single()
        assertEquals(at(7, 33), t.departure)
        assertEquals(at(8, 58), t.arrival)
        assertEquals(2, t.departureDelay)
        assertEquals(5, t.arrivalDelay)
        assertEquals("5", t.platform)
        // Moved from platform 4: the planned one is kept to show the change.
        assertEquals("4", t.legs[0].plannedPlatform)
        assertEquals(1, t.transfers)
        assertEquals(listOf("IC 8", "IR 16"), t.products)
        val ic = t.legs[0]
        assertEquals("8599008", ic.fromId)
        assertEquals("8599002", ic.toId)
        assertEquals("10809", ic.trainNumber)
        assertEquals("Endhausen", ic.direction)
        assertEquals("ch:1:sjyid:100001:10809-001", ic.journeyRef)
        // Talwil → Mittelberg: 1st many, 2nd standing; Mittelberg → Musterdorf: 1st few, 2nd many. The fullest counts.
        assertEquals(Capacity(2, 3), ic.capacity)
        assertEquals(listOf("Talwil", "Mittelberg"), ic.calls.map { it.name })
        assertEquals(
            listOf(Section("Talwil", "Mittelberg", 3), Section("Mittelberg", "Musterdorf", 1)),
            ic.sections(TravelClass.SECOND),
        )
        // Same level twice in a row: one stretch.
        assertEquals(listOf(Section("Talwil", "Musterdorf", null)), ic.copy(calls = ic.calls.map { it.copy(capacity = Capacity()) }).sections(TravelClass.SECOND))
        assertTrue(t.legs[1].walk)
        assertEquals("2110", t.legs[2].trainNumber)
        assertTrue(t.cancelled)
    }

    /** A real OJP answer, trimmed to one trip, with places, lines and train numbers made up. */
    @Test
    fun parseRealTrip() {
        val t = Ojp.parseTrips(fixture("ojp-real-trip.xml"), "8599001", "8599004").single()
        // The walk from the bus stop to the platform is left out: the trip starts with the 17:31 IC 9.
        assertEquals(java.time.LocalTime.of(17, 31), t.departure.toSwiss().toLocalTime())
        assertTrue(!t.legs.first().walk && !t.legs.last().walk)
        // The change at Beispielstadt stays, placed after the IC 9 arrives.
        val change = t.legs[1]
        assertTrue(change.walk)
        assertEquals(t.legs[0].arrival, change.departure)
        assertEquals("33", t.legs[0].arrivalPlatform)
        assertEquals("12", t.legs[2].platform)
        assertEquals("IC 9", t.products.first())
        assertEquals("1909", t.trains.first().trainNumber)
        assertEquals("8599002", t.trains.first().fromId)
        assertEquals("8", t.platform)
        assertEquals(Capacity(1, 2), t.trains.first().capacity)
        assertEquals(1, t.transfers)
        assertEquals(0, t.departureDelay)
    }

    @Test(expected = OjpException::class)
    fun errorCondition() {
        Ojp.parseTrips(
            """<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri"><OJPResponse><siri:ServiceDelivery>
              <OJPTripDelivery><siri:ErrorCondition><siri:OtherError/><siri:Description>TRIP_NOTRIPFOUND</siri:Description>
              </siri:ErrorCondition></OJPTripDelivery></siri:ServiceDelivery></OJPResponse></OJP>""",
            "a", "b",
        )
    }
}
