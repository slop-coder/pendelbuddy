package io.github.slop_coder.pendelbuddy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrimWalksTest {
    private fun walk(dep: Long, arr: Long) = Leg(null, fromId = "", fromName = "", toName = "", departure = dep, arrival = arr)
    private fun ride(dep: Long, arr: Long, pl: String) =
        Leg("S 1", fromId = "", fromName = "", toName = "", departure = dep, arrival = arr, departureDelay = 1, platform = pl, arrivalDelay = 2)

    @Test
    fun outerWalksGoInnerStay() {
        val t = Trip("a", "b", 0, 0, legs = listOf(walk(0, 300), ride(300, 900, "3"), walk(900, 1200), ride(1200, 1800, "7"), walk(1800, 2000)))
            .trimWalks()!!
        assertEquals(300, t.departure)
        assertEquals(1800, t.arrival)
        assertEquals(3, t.legs.size)
        assertEquals("3", t.platform)
        assertEquals(1, t.departureDelay)
        assertEquals(2, t.arrivalDelay)
        assertNull(Trip("a", "b", 0, 0, legs = listOf(walk(0, 300))).trimWalks())
    }
}
