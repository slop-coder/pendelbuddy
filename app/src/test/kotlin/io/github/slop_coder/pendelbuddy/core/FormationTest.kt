package io.github.slop_coder.pendelbuddy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.slop_coder.pendelbuddy.ui.ranges

class FormationTest {
    @Test
    fun shortString() {
        val w = Formations.parseShort("@A,F,[(1:1#VH;BZ;NF@B,1:2#VH;KW;NF,-2:5,%W2:4#BHP;NF@C,FA):8#KW;NF@D,LK]")
        assertEquals(listOf("1", "1", "2", "W2", "FA", "LK"), w.map { it.type })
        // Wagon 1 starts in A and reaches into B; wagon 2 stands in B.
        assertEquals(listOf("A", "B"), w[0].sectors)
        assertEquals(listOf("B"), w[1].sectors)
        assertEquals(listOf("VH", "BZ", "NF"), w[0].services)
        assertTrue(w[2].closed)
        assertTrue(w[3].restaurant && w[3].notServed && w[3].secondClass)
        assertEquals(8, w[4].number)
        assertTrue(w[4].secondClass)
        assertNull(w[5].number)
        assertFalse(w[5].passengers)
    }

    /** A real answer with places, train number and times made up. */
    @Test
    fun parseAnswer() {
        val stops = Formations.parse(fixture("formation.json"))
        assertEquals(listOf("Talwil", "Mittelberg", "Musterdorf", "Endhausen"), stops.map { it.stopName })
        val talwil = stops[0]
        assertEquals("8599008", talwil.stopId)
        assertEquals("8", talwil.track)
        assertEquals(16, talwil.wagons.size)
        assertEquals(listOf(1, 2, 3), talwil.wagons.take(3).map { it.number })
        assertEquals(listOf("A", "B"), talwil.wagons[0].sectors)
        // Two units: 1st class at the front of each.
        assertEquals(listOf("A", "B", "C", "E", "F"), talwil.sectorsOf(TravelClass.FIRST))
        assertTrue(talwil.wagons.count { it.restaurant } == 2)
        // Along the platform, A is on the left; at Musterdorf the train stands the other way round.
        assertEquals(1, talwil.alongPlatform.first().number)
        val musterdorf = stops[2]
        assertEquals("H", musterdorf.wagons.first().sectors.first())
        assertEquals(18, musterdorf.alongPlatform.first().number)
    }

    @Test
    fun sectorRanges() {
        assertEquals("A–C, E–F", ranges(listOf("A", "B", "C", "E", "F")))
        assertEquals("D", ranges(listOf("D")))
        assertEquals("A, C", ranges(listOf("A", "C")))
    }
}
