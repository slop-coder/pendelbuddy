package io.github.slop_coder.pendelbuddy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PrefetchTest {
    private val home = Station("8599008", "Talwil")
    private val work = Station("8599003", "Beispielstadt")
    private val s = Settings(home, work)
    private fun at(d: LocalDate, h: Int, m: Int) = LocalDateTime.of(d, java.time.LocalTime.of(h, m)).atZone(SWISS).toEpochSecond()

    @Test
    fun windowsForWorkingDaysOnly() {
        val wednesday = LocalDate.of(2026, 11, 18)
        val now = at(wednesday, 7, 0)
        val w = Commute.commuteWindows(s, Commute.weekDays(wednesday), now)
        // We 18 (morning from 06:55, evening) … Fr 20, Mo 23, Tu 24; no Saturday or Sunday.
        assertEquals(
            listOf(18, 18, 19, 19, 20, 20, 23, 23, 24, 24),
            w.map { it.day.dayOfMonth },
        )
        assertEquals(now - LOOK_BACK, w[0].from)
        assertEquals(Direction.TO_WORK, w[0].route.direction)
        assertEquals(home, w[0].route.from)
        assertEquals(at(wednesday, 15, 30), w[1].from)
        assertEquals(at(wednesday, 18, 30), w[1].until)
        assertEquals(work, w[1].route.from)
    }

    @Test
    fun finishedWindowsAreSkipped() {
        val wednesday = LocalDate.of(2026, 11, 18)
        val w = Commute.commuteWindows(s, listOf(wednesday), at(wednesday, 12, 0))
        assertEquals(listOf(Direction.TO_HOME), w.map { it.route.direction })
        assertTrue(Commute.commuteWindows(s, listOf(wednesday), at(wednesday, 19, 0)).isEmpty())
        assertTrue(Commute.commuteWindows(Settings(home), listOf(wednesday), 0).isEmpty())
    }

    @Test
    fun fetchedOncePerDay() {
        val today = LocalDate.of(2026, 11, 18)
        val friday = LocalDate.of(2026, 11, 20)
        var c = RouteCache("a", "b").markPrefilled(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 16))
        c = c.markPrefilled(friday, today)
        assertTrue(c.prefilledToday(friday, today))
        assertFalse(c.prefilledToday(friday, today.plusDays(1)))
        // Days that have passed are forgotten.
        assertEquals(setOf("2026-11-20"), c.prefilled.keys)
    }
}
