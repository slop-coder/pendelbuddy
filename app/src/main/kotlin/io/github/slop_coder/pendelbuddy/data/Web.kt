package io.github.slop_coder.pendelbuddy.data

import android.util.Log
import io.github.slop_coder.pendelbuddy.BuildConfig
import io.github.slop_coder.pendelbuddy.core.Forecast
import io.github.slop_coder.pendelbuddy.core.Ojp
import io.github.slop_coder.pendelbuddy.core.Station
import io.github.slop_coder.pendelbuddy.core.TransportParser
import io.github.slop_coder.pendelbuddy.core.Trip
import io.github.slop_coder.pendelbuddy.core.WeatherParser
import io.github.slop_coder.pendelbuddy.core.toSwiss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

class HttpException(val code: Int, url: String) : IOException("HTTP $code for $url")

/** Plain HTTP GETs, identified as this app so the API operators can see who is calling. */
object Http {
    private const val USER_AGENT = "PendelBuddy/0.1 (Android; https://github.com/slop-coder/pendelbuddy)"

    suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw HttpException(c.responseCode, url)
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** POSTs XML. The bearer token only goes into the header; it is never part of a message or log line. */
    suspend fun postXml(url: String, body: String, bearer: String): String = withContext(Dispatchers.IO) {
        val c = open(url)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            c.setRequestProperty("Authorization", "Bearer $bearer")
            // OJP answers carry ~1.4 MB of network-wide disruption notices; gzipped that is ~25 kB.
            c.setRequestProperty("Accept-Encoding", "gzip")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) throw HttpException(c.responseCode, url)
            val gzip = c.contentEncoding.equals("gzip", ignoreCase = true)
            if (BuildConfig.DEBUG) Log.d("PendelBuddy", "OJP answer: gzip=$gzip, ${c.contentLength} bytes")
            (if (gzip) GZIPInputStream(c.inputStream) else c.inputStream).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        return c
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** transport.opendata.ch: Swiss timetable, no key needed. */
object TransportClient {
    private const val BASE = "https://transport.opendata.ch/v1"

    suspend fun stations(query: String): List<Station> =
        TransportParser.parseStations(Http.text("$BASE/locations?type=station&query=${enc(query.trim())}"))

    /** Up to [limit] (max 16) connections leaving from [at] (epoch seconds) on. */
    suspend fun connections(fromId: String, toId: String, at: Long, limit: Int = 8): List<Trip> {
        val t = at.toSwiss()
        val time = "%02d:%02d".format(t.hour, t.minute)
        return TransportParser.parseConnections(
            Http.text("$BASE/connections?from=${enc(fromId)}&to=${enc(toId)}&date=${t.toLocalDate()}&time=$time&limit=$limit")
        )
    }
}

/** OJP 2.0 of opentransportdata.swiss: timetable with realtime, needs the user's API key. */
object OjpClient {
    private const val URL = "https://api.opentransportdata.swiss/ojp20"

    /** Debug builds keep the last answer (never the key) in files/ojp-last.xml, to check the parser against it. */
    var debugDump: File? = null

    suspend fun connections(key: String, from: Station, to: Station, at: Long, limit: Int = 8): List<Trip> {
        val body = Http.postXml(URL, Ojp.tripRequest(from, to, at, limit, System.currentTimeMillis() / 1000), key)
        debugDump?.let { f -> withContext(Dispatchers.IO) { f.writeText(body) } }
        return withContext(Dispatchers.Default) { Ojp.parseTrips(body, from.id, to.id) }
    }
}

/** Open-Meteo with the MeteoSwiss ICON-CH2 model (5 days, 2 km). */
object WeatherClient {
    suspend fun forecast(lat: Double, lon: Double, now: Long): Forecast = WeatherParser.parse(
        Http.text(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&hourly=temperature_2m,weather_code&daily=temperature_2m_max,weather_code" +
                "&models=meteoswiss_icon_ch2&timezone=Europe%2FZurich&forecast_days=3"
        ),
        now,
    )
}
