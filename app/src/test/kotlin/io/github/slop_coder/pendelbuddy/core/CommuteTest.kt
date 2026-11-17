package io.github.slop_coder.pendelbuddy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class CommuteTest {
    private val home = Station("1", "Talwil")
    private val via = Station("2", "Zwischenau")
    private val work = Station("3", "Beispielstadt")
    private val s = Settings(home, work, listOf(via), switchMinute = 13 * 60)

    @Test
    fun directionBySwitchTime() {
        assertEquals(Direction.TO_WORK, Commute.direction(s, LocalDateTime.of(2026, 11, 16, 12, 59).atZone(SWISS)))
        assertEquals(Direction.TO_HOME, Commute.direction(s, LocalDateTime.of(2026, 11, 16, 13, 0).atZone(SWISS)))
    }

    @Test
    fun currentStation() {
        assertEquals(Route(home, work, Direction.TO_WORK), Commute.route(s, Direction.TO_WORK, null))
        assertEquals(Route(via, work, Direction.TO_WORK), Commute.route(s, Direction.TO_WORK, via))
        // At the destination: plan the way back.
        assertEquals(Route(work, home, Direction.TO_HOME), Commute.route(s, Direction.TO_WORK, work))
        assertNull(Commute.route(Settings(home), Direction.TO_WORK, null))
        assertEquals(listOf(home, via, work), Commute.quickPicks(s))
        assertEquals(listOf(work, via, home), Commute.quickPicks(s, Direction.TO_HOME))
    }

    @Test
    fun viasInRouteOrder() {
        // A made-up line running east, the stations in between entered out of order.
        val home = Station("8599001", "Musterdorf, Post", 47.00, 7.00)
        val near = Station("8599002", "Musterdorf", 47.00, 7.02)
        val far = Station("8599007", "Zwischenau", 47.00, 7.50)
        val work = Station("8599003", "Beispielstadt", 47.00, 8.00)
        val s = Settings(home, work, listOf(far, near))
        assertEquals(listOf(home, near, far, work), Commute.quickPicks(s, Direction.TO_WORK))
        assertEquals(listOf(work, far, near, home), Commute.quickPicks(s, Direction.TO_HOME))
        // Without coordinates the entered order is kept.
        assertEquals(listOf(via), Commute.viasInOrder(this.s))
    }

    @Test
    fun weekDayTabs() {
        val wednesday = java.time.LocalDate.of(2026, 11, 18)
        val days = Commute.weekDays(wednesday)
        assertEquals(java.time.LocalDate.of(2026, 11, 23), days[0]) // Mo: next week
        assertEquals(java.time.LocalDate.of(2026, 11, 24), days[1]) // Tu: next week
        assertEquals(wednesday, days[2])
        assertEquals(java.time.LocalDate.of(2026, 11, 22), days[6]) // Su: this week
    }

    @Test
    fun ratings() {
        val dep = LocalDateTime.of(2026, 11, 16, 7, 2).atZone(SWISS).toEpochSecond()
        val leg = Leg("IC 8", fromId = "1", fromName = "Talwil", toName = "Zwischenau", departure = dep, arrival = dep + 1200)
        var t = 0L
        fun r(date: String, p: Int, cls: TravelClass = TravelClass.SECOND, forecast: Int? = null) =
            Rating(leg.trainKey!!, date, p, cls, "IC 8", "Talwil", forecastSecond = forecast, ratedAt = ++t)
        val all = listOf(
            r("2026-11-09", 70, forecast = 2),
            r("2026-11-10", 100, forecast = 3),
            r("2026-11-10", 90, forecast = 3), // re-rated the same day: kept, but the latest counts
            r("2026-11-14", 10), // Saturday: other kind of day
            r("2026-11-11", 10, TravelClass.FIRST),
        )
        assertEquals(Average(80, 2), Ratings.average(all, leg, TravelClass.SECOND))
        assertEquals(Average(10, 1), Ratings.average(all, leg, TravelClass.FIRST))
        assertEquals(90, Ratings.current(all, leg.copy(departure = LocalDateTime.of(2026, 11, 10, 7, 2).atZone(SWISS).toEpochSecond()), TravelClass.SECOND)?.percent)
        assertEquals("No seats, many standing", Ratings.label(110))

        val checks = Ratings.versusForecast(all)
        assertEquals(listOf(ForecastCheck(2, 1, 70), ForecastCheck(3, 1, 90), ForecastCheck(null, 2, 10)), checks)

        val csv = Ratings.toCsv(all).lines()
        assertEquals("rated_at,date,departure,line,train_number,from,to,delay_min,class,rating_percent,forecast_level,forecast_first,forecast_second,rated_from,wagon,sector", csv[0])
        assertEquals(6, csv.count { it.isNotEmpty() }) // every rating, re-ratings included
        assertEquals("1970-01-01T01:00:02,2026-11-10,,IC 8,,Talwil,,,2,100,3,,3,,,", csv[2])
    }

    @Test
    fun ratingsPerStop() {
        val dep = LocalDateTime.of(2026, 11, 16, 7, 2).atZone(SWISS).toEpochSecond()
        val talwil = Call("8599008", "Talwil", dep, Capacity(1, 1))
        val zwischenau = Call("8599007", "Zwischenau", dep + 600, Capacity(1, 3))
        val leg = Leg(
            "IC 8", fromId = "8599008", fromName = "Talwil", toName = "Musterdorf", departure = dep, arrival = dep + 1800,
            departureDelay = 2, calls = listOf(talwil, zwischenau),
        )
        // The stop the train last left, allowing for its 2 min delay.
        assertEquals(talwil, leg.callAt(dep - 300))
        assertEquals(talwil, leg.callAt(dep + 700))
        assertEquals(zwischenau, leg.callAt(dep + 720))
        // Stretches for the badge: 10 min low, then 20 min high.
        assertEquals(listOf(1 to 600L, 3 to 1200L), leg.levelSpans(TravelClass.SECOND))
        assertEquals(listOf(1 to 1800L), leg.levelSpans(TravelClass.FIRST))

        var t = 0L
        fun r(p: Int, c: Call?) = Rating(leg.trainKey!!, "2026-11-16", p, TravelClass.SECOND, "IC 8", "Talwil", ratedAt = ++t, callId = c?.id, callName = c?.name)
        // Both stretches rated the same day: both count; a whole-ride rating counts for every stretch.
        val all = listOf(r(20, talwil), r(100, zwischenau), r(60, null))
        assertEquals(Average(60, 3), Ratings.average(all, leg, TravelClass.SECOND))
        assertEquals(Average(40, 2), Ratings.average(all, leg, TravelClass.SECOND, talwil.id))
        assertEquals(listOf(talwil to Average(20, 1), zwischenau to Average(100, 1)), Ratings.byStop(all, leg, TravelClass.SECOND))
        assertEquals(100, Ratings.current(all, leg, TravelClass.SECOND, zwischenau.id)?.percent)
        assertTrue(Ratings.toCsv(all).lines()[2].endsWith(",Zwischenau,,"))
    }
}
