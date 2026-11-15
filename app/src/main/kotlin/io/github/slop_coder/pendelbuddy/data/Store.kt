package io.github.slop_coder.pendelbuddy.data

import android.content.Context
import android.util.Log
import io.github.slop_coder.pendelbuddy.core.DebugEntry
import io.github.slop_coder.pendelbuddy.core.Forecast
import io.github.slop_coder.pendelbuddy.core.Rating
import io.github.slop_coder.pendelbuddy.core.RouteCache
import io.github.slop_coder.pendelbuddy.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** JSON files in the app's files/ dir (not cache/, which Android may clear while offline data is still needed). */
class Store(context: Context) {
    private val dir = context.filesDir
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    suspend fun settings() = read("settings.json", Settings.serializer()) ?: Settings()
    suspend fun saveSettings(s: Settings) = write("settings.json", Settings.serializer(), s)

    suspend fun ratings() = read("ratings.json", ListSerializer(Rating.serializer())).orEmpty()
    suspend fun saveRatings(r: List<Rating>) = write("ratings.json", ListSerializer(Rating.serializer()), r)

    suspend fun route(fromId: String, toId: String) =
        read(routeFile(fromId, toId), RouteCache.serializer()) ?: RouteCache(fromId, toId)
    suspend fun saveRoute(r: RouteCache) = write(routeFile(r.fromId, r.toId), RouteCache.serializer(), r)

    /** Forecasts by station id. */
    suspend fun forecasts() = read("forecasts.json", forecastMap).orEmpty()
    suspend fun saveForecasts(f: Map<String, Forecast>) = write("forecasts.json", forecastMap, f)

    /** Requests that went to the network, newest first. */
    suspend fun debugLog() = read("debug-log.json", ListSerializer(DebugEntry.serializer())).orEmpty()
    suspend fun saveDebugLog(l: List<DebugEntry>) = write("debug-log.json", ListSerializer(DebugEntry.serializer()), l)

    /** Files of features that were removed (the occupancy forecast download, now part of OJP). */
    suspend fun dropOldFiles() = withContext(Dispatchers.IO) { File(dir, "occupancy.json").delete() }

    private val forecastMap = MapSerializer(String.serializer(), Forecast.serializer())

    private fun routeFile(fromId: String, toId: String) = "routes/${fromId.safe()}-${toId.safe()}.json"
    private fun String.safe() = replace(Regex("[^A-Za-z0-9]"), "_")

    private suspend fun <T> read(name: String, s: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        val f = File(dir, name)
        if (!f.exists()) return@withContext null
        try {
            json.decodeFromString(s, f.readText())
        } catch (e: Exception) {
            // A broken file must not lock the user out; start fresh for that file.
            Log.w("Store", "Could not read $name", e)
            null
        }
    }

    private suspend fun <T> write(name: String, s: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.encodeToString(s, value))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }
}
