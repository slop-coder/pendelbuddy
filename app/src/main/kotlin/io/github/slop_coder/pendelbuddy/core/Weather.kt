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
data class Hour(val time: Long, val temp: Double, val code: Int)

@Serializable
data class Day(val date: String, val max: Double, val code: Int)

@Serializable
data class Forecast(val fetchedAt: Long, val hours: List<Hour>, val days: List<Day>)

/** What the app shows for a place and time. */
data class WeatherAt(val temp: Double, val max: Double?, val sky: Sky)

object WeatherParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Open-Meteo `/v1/forecast` with `timezone=Europe/Zurich`, hourly temperature_2m,weather_code and daily temperature_2m_max,weather_code. */
    fun parse(body: String, fetchedAt: Long): Forecast {
        val root = json.parseToJsonElement(body).jsonObject
        val hourly = root["hourly"] as? JsonObject
        val daily = root["daily"] as? JsonObject
        val hours = hourly?.let { h ->
            val times = h.list("time")
            val temps = h.list("temperature_2m")
            val codes = h.list("weather_code")
            times.indices.mapNotNull { i ->
                val t = times[i]?.let { LocalDateTime.parse(it).atZone(SWISS).toEpochSecond() } ?: return@mapNotNull null
                Hour(t, temps.getOrNull(i)?.toDoubleOrNull() ?: return@mapNotNull null, codes.getOrNull(i)?.toIntOrNull() ?: 3)
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

/** Temperature at [time] (linear between the hours), the worse sky of the hours around it and the day's maximum. */
fun Forecast.at(time: Long): WeatherAt? {
    val after = hours.indexOfFirst { it.time >= time }
    if (after < 0) return null
    val b = hours[after]
    val a = hours.getOrNull(after - 1)
    if (a == null && b.time - time > 3600) return null
    val temp = if (a == null || b.time == a.time) b.temp
    else a.temp + (b.temp - a.temp) * (time - a.time).toDouble() / (b.time - a.time)
    // Sky is ordered from fair to foul: better to warn of rain that is an hour off than to miss it.
    val sky = listOfNotNull(a, b).maxOf { Sky.ofWmo(it.code) }
    val date = time.toSwiss().toLocalDate().toString()
    return WeatherAt(temp, days.firstOrNull { it.date == date }?.max, sky)
}

fun Forecast.day(date: LocalDate) = days.firstOrNull { it.date == date.toString() }
