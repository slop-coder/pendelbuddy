package io.github.slop_coder.pendelbuddy.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.LocalDateTime

enum class Sky { SUNNY, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SNOW, THUNDER;

    companion object {
        /** WMO weather interpretation codes as used by Open-Meteo. */
        fun ofWmo(code: Int) = when (code) {
            0 -> SUNNY
            1, 2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..67, in 80..82 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> THUNDER
            else -> CLOUDY
        }
    }
}

@Serializable
data class Hour(val time: Long, val temp: Double, val code: Int, val precipitation: Double = 0.0) {
    /**
     * How hard it rains or snows, by the amount (so bar height and color agree): light below 2.5 mm/h, moderate
     * up to 7.6, heavy above (the usual meteorological limits). Without an amount, the weather code decides;
     * drizzle is always light.
     */
    fun intensity(): Intensity? {
        val wet = Sky.ofWmo(code).let { it == Sky.RAIN || it == Sky.SNOW || it == Sky.THUNDER }
        if (precipitation >= 0.1) {
            return when {
                precipitation < 2.5 -> Intensity.LIGHT
                precipitation < 7.6 -> Intensity.MODERATE
                else -> Intensity.HEAVY
            }
        }
        if (!wet) return null
        return when (code) {
            in 51..57, 61, 66, 71, 77, 80, 85 -> Intensity.LIGHT
            63, 73, 81 -> Intensity.MODERATE
            65, 67, 75, 82, 86, in 95..99 -> Intensity.HEAVY
            else -> Intensity.LIGHT
        }
    }
}

enum class Intensity { LIGHT, MODERATE, HEAVY }

/** A stretch of rain or snow within a day, e.g. "Rain 14–17 h, moderate". */
data class Precipitation(val snow: Boolean, val fromHour: Int, val toHour: Int, val intensity: Intensity)

/** The weather of one day at one place, as the card shows it. */
data class DayWeather(
    val date: LocalDate,
    /** The hours from [FIRST_HOUR] to [LAST_HOUR]. */
    val hours: List<Hour>,
    val min: Double,
    val max: Double,
    val sky: Sky,
    val precipitation: List<Precipitation>,
    val fetchedAt: Long,
) {
    companion object {
        const val FIRST_HOUR = 5
        const val LAST_HOUR = 23
    }
}

@Serializable
data class Day(val date: String, val max: Double, val code: Int)

@Serializable
data class Forecast(val fetchedAt: Long, val hours: List<Hour>, val days: List<Day>)

object WeatherParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Open-Meteo `/v1/forecast` with `timezone=Europe/Zurich`, hourly temperature_2m,weather_code,precipitation and daily temperature_2m_max,weather_code. */
    fun parse(body: String, fetchedAt: Long): Forecast {
        val root = json.parseToJsonElement(body).jsonObject
        val hourly = root["hourly"] as? JsonObject
        val daily = root["daily"] as? JsonObject
        val hours = hourly?.let { h ->
            val times = h.list("time")
            val temps = h.list("temperature_2m")
            val codes = h.list("weather_code")
            val precips = h.list("precipitation")
            times.indices.mapNotNull { i ->
                val t = times[i]?.let { LocalDateTime.parse(it).atZone(SWISS).toEpochSecond() } ?: return@mapNotNull null
                Hour(
                    t,
                    temps.getOrNull(i)?.toDoubleOrNull() ?: return@mapNotNull null,
                    codes.getOrNull(i)?.toIntOrNull() ?: 3,
                    precips.getOrNull(i)?.toDoubleOrNull() ?: 0.0,
                )
            }
        }.orEmpty()
        val days = daily?.let { d ->
            val dates = d.list("time")
            val maxes = d.list("temperature_2m_max")
            val codes = d.list("weather_code")
            dates.indices.mapNotNull { i ->
                Day(dates[i] ?: return@mapNotNull null, maxes.getOrNull(i)?.toDoubleOrNull() ?: return@mapNotNull null, codes.getOrNull(i)?.toIntOrNull() ?: 3)
            }
        }.orEmpty()
        return Forecast(fetchedAt, hours, days)
    }

    private fun JsonObject.list(k: String) =
        (this[k] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }.orEmpty()
}

fun Forecast.day(date: LocalDate) = days.firstOrNull { it.date == date.toString() }

/** The card's view of [date]: hours 05–23, min and max, the day's sky and its rain or snow; null if not covered. */
fun Forecast.dayWeather(date: LocalDate): DayWeather? {
    val hours = hours.filter {
        val t = it.time.toSwiss()
        t.toLocalDate() == date && t.hour in DayWeather.FIRST_HOUR..DayWeather.LAST_HOUR
    }
    if (hours.size < 6) return null
    val sky = day(date)?.code?.let(Sky::ofWmo) ?: hours.maxOf { Sky.ofWmo(it.code) }
    return DayWeather(
        date = date,
        hours = hours,
        min = hours.minOf { it.temp },
        max = day(date)?.max ?: hours.maxOf { it.temp },
        sky = sky,
        precipitation = precipitationSpans(hours),
        fetchedAt = fetchedAt,
    )
}

/** Consecutive wet hours joined into stretches, each with its strongest intensity. */
fun precipitationSpans(hours: List<Hour>): List<Precipitation> {
    val out = mutableListOf<Precipitation>()
    for (h in hours) {
        val i = h.intensity() ?: continue
        val hour = h.time.toSwiss().hour
        val snow = Sky.ofWmo(h.code) == Sky.SNOW
        val last = out.lastOrNull()
        if (last != null && last.toHour == hour && last.snow == snow) {
            out[out.size - 1] = last.copy(toHour = hour + 1, intensity = maxOf(last.intensity, i))
        } else {
            out += Precipitation(snow, hour, hour + 1, i)
        }
    }
    return out
}
