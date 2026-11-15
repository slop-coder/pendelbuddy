package io.github.slop_coder.pendelbuddy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

fun fixture(name: String) = ParserTest::class.java.getResource("/fixtures/$name")!!.readText()

class ParserTest {
    @Test
    fun connections() {
        val trips = TransportParser.parseConnections(fixture("connections.json"))
        assertEquals(2, trips.size)
        val ic = trips[0]
        assertEquals("8599002", ic.fromId)
        assertEquals(LocalTime.of(8, 2), ic.departure.toSwiss().toLocalTime())
        assertEquals(3, ic.departureDelay)
        assertEquals(2, ic.arrivalDelay)
        assertEquals(listOf("IC 8"), ic.products)
        assertEquals("7", ic.platform)
        assertEquals(Capacity(1, 3), ic.capacity)
        assertEquals("33", ic.legs[0].arrivalPlatform)
        assertEquals(2, ic.legs[0].arrivalDelay)

        val change = trips[1]
        assertEquals(1, change.transfers)
        assertNull(change.departureDelay)
        assertEquals(3, change.legs.size)
        assertTrue(change.legs[1].walk)
        assertEquals(listOf("IC", "S 29"), change.products)
        // "-1" and null mean unknown; the worst known leg counts.
        assertEquals(Capacity(null, 2), change.capacity)
        assertEquals("S 29|8599007|08:55", change.legs[2].trainKey)
    }

    @Test
    fun stationsSkipAddresses() {
        val s = TransportParser.parseStations(fixture("locations.json"))
        assertEquals(listOf("Zwischenau"), s.map { it.name })
        assertEquals(47.01, s[0].lat!!, 1e-9)
        assertEquals(7.65, s[0].lon!!, 1e-9)
    }

    @Test
    fun weather() {
        val f = WeatherParser.parse(fixture("weather.json"), fetchedAt = 0)
        // The 09:00 hour has no temperature and is skipped.
        assertEquals(2, f.hours.size)
        val at = f.at(java.time.LocalDateTime.of(2026, 11, 16, 7, 30).atZone(SWISS).toEpochSecond())!!
        assertEquals(3.0, at.temp, 1e-9)
        assertEquals(11.3, at.max!!, 1e-9)
        assertEquals(Sky.RAIN, at.sky)
        assertNull(f.at(java.time.LocalDateTime.of(2026, 11, 16, 12, 0).atZone(SWISS).toEpochSecond()))
        assertEquals(Sky.SUNNY, Sky.ofWmo(0))
        assertEquals(Sky.SNOW, Sky.ofWmo(73))
        assertEquals(Sky.THUNDER, Sky.ofWmo(95))
    }
}
