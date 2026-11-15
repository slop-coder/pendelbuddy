package io.github.slop_coder.pendelbuddy.core

import org.junit.Assert.assertEquals
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
    fun quickestArrival() {
        val a = Trip("1", "3", departure = 1000, arrival = 5000)
        val b = Trip("1", "3", departure = 1300, arrival = 4000)
        val c = Trip("1", "3", departure = 1100, arrival = 3000, departureDelay = 1)
        assertEquals(b, Commute.quickest(listOf(a, b, c), leaveAt = 1200))
        assertEquals(c, Commute.quickest(listOf(a, b, c), leaveAt = 1000))
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
        assertEquals("rated_at,date,departure,line,train_number,from,to,delay_min,class,rating_percent,forecast_level,forecast_first,forecast_second", csv[0])
        assertEquals(6, csv.count { it.isNotEmpty() }) // every rating, re-ratings included
        assertEquals("1970-01-01T01:00:02,2026-11-10,,IC 8,,Talwil,,,2,100,3,,3", csv[2])
    }
}
