package io.github.slop_coder.pendelbuddy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WeatherTest {
    private val day = LocalDate.of(2026, 11, 16)
    private fun h(hour: Int, temp: Double, code: Int = 3, mm: Double = 0.0) =
        Hour(LocalDateTime.of(day, java.time.LocalTime.of(hour, 0)).atZone(SWISS).toEpochSecond(), temp, code, mm)

    @Test
    fun intensity() {
        assertEquals(Intensity.LIGHT, h(7, 5.0, 61).intensity())
        assertEquals(Intensity.MODERATE, h(7, 5.0, 81).intensity())
        assertEquals(Intensity.HEAVY, h(7, 5.0, 65).intensity())
        // Cloudy code but measurable rain: judged by the mm per hour.
        assertEquals(Intensity.MODERATE, h(7, 5.0, 3, 2.0).intensity())
        assertNull(h(7, 5.0, 3, 0.05).intensity())
    }

    @Test
    fun dayView() {
        val hours = (0..23).map { hr ->
            when (hr) {
                14 -> h(hr, 9.0, 61, 0.4)
                15 -> h(hr, 8.0, 63, 2.5)
                16 -> h(hr, 7.0, 61, 0.3)
                20 -> h(hr, 2.0, 71, 0.2)
                else -> h(hr, if (hr < 5) -1.0 else 4.0 + hr / 4.0)
            }
        }
        val f = Forecast(fetchedAt = 0, hours = hours, days = listOf(Day(day.toString(), 10.5, 63)))
        val w = f.dayWeather(day)!!
        assertEquals(19, w.hours.size) // 05–23
        assertEquals(2.0, w.min, 1e-9) // night hours before 05 do not count
        assertEquals(10.5, w.max, 1e-9)
        assertEquals(Sky.RAIN, w.sky)
        assertEquals(
            listOf(Precipitation(false, 14, 17, Intensity.MODERATE), Precipitation(true, 20, 21, Intensity.LIGHT)),
            w.precipitation,
        )
        assertNull(f.dayWeather(day.plusDays(1)))
    }
}
