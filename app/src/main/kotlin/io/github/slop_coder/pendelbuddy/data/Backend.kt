package io.github.slop_coder.pendelbuddy.data

import android.content.Context
import android.util.Log
import io.github.slop_coder.pendelbuddy.BuildConfig
import io.github.slop_coder.pendelbuddy.core.Change
import io.github.slop_coder.pendelbuddy.core.Changes
import io.github.slop_coder.pendelbuddy.core.Commute
import io.github.slop_coder.pendelbuddy.core.DebugEntry
import io.github.slop_coder.pendelbuddy.core.Fake
import io.github.slop_coder.pendelbuddy.core.LOOK_BACK
import io.github.slop_coder.pendelbuddy.core.MyTrip
import io.github.slop_coder.pendelbuddy.core.Route
import io.github.slop_coder.pendelbuddy.core.Settings
import io.github.slop_coder.pendelbuddy.core.Station
import io.github.slop_coder.pendelbuddy.core.Trip
import io.github.slop_coder.pendelbuddy.core.merge
import io.github.slop_coder.pendelbuddy.core.toSwiss
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where the last timetable came from, or what went wrong with OJP. */
data class Source(val name: String? = null, val ojpProblem: String? = null)

/**
 * Downloads shared by the screen and the trip watcher in the background: one per process, so both see the same
 * request log and your trip, and requests never run side by side.
 */
class Backend private constructor(context: Context) {
    private val app = context.applicationContext
    val store = Store(app)
    val secrets = Secrets(app)

    private val _debugLog = MutableStateFlow<List<DebugEntry>>(emptyList())
    val debugLog: StateFlow<List<DebugEntry>> = _debugLog
    private val logLock = Mutex()
    /** One timetable request at a time (OJP's free tier allows 50 a minute). */
    val fetchLock = Mutex()

    private val _source = MutableStateFlow(Source())
    val source: StateFlow<Source> = _source

    private val _myTrip = MutableStateFlow<MyTrip?>(null)
    val myTrip: StateFlow<MyTrip?> = _myTrip
    private val _myTripLoading = MutableStateFlow(false)
    val myTripLoading: StateFlow<Boolean> = _myTripLoading
    private val myTripLock = Mutex()
    /** Last try of a due refresh, so being offline does not mean a request every few seconds. */
    private var lastAttempt = 0L

    private var loaded = false
    private val loadLock = Mutex()

    init {
        if (BuildConfig.DEBUG) OjpClient.debugDump = File(app.filesDir, "ojp-last.xml")
    }

    /** Reads the request log and your trip once per process. */
    suspend fun load() = loadLock.withLock {
        if (loaded) return@withLock
        _debugLog.value = store.debugLog()
        _myTrip.value = store.myTrip()?.takeIf { !it.over(nowSec()) }
        loaded = true
    }

    /** Runs a network request and notes it in the request log, with how long it took and what came back. */
    suspend fun <T> logged(kind: String, what: String, cause: String, request: suspend () -> T, result: (T) -> String): T {
        val start = System.currentTimeMillis()
        fun entry(r: String) = DebugEntry(start, kind, what, cause, r, System.currentTimeMillis() - start)
        try {
            return request().also { addLog(entry(result(it))) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            addLog(entry("HTTP ${e.code}"))
            throw e
        } catch (e: Exception) {
            addLog(entry(e.javaClass.simpleName + (e.message?.let { ": ${it.take(80)}" } ?: "")))
            throw e
        }
    }

    private suspend fun addLog(e: DebugEntry) = logLock.withLock {
        _debugLog.update { (listOf(e) + it).take(DebugEntry.KEEP) }
        store.saveDebugLog(_debugLog.value)
    }

    suspend fun clearDebugLog() = logLock.withLock {
        _debugLog.value = emptyList()
        store.saveDebugLog(emptyList())
    }

    fun clearSourceProblem() = _source.update { Source() }

    /**
     * Trips for [r] from [at] on. With a safety margin on the way, planned in two parts so that trips with a
     * longer change (which a planner never suggests on its own) are found. Call it holding [fetchLock].
     */
    suspend fun plan(s: Settings, r: Route, at: Long, limit: Int, cause: String): List<Trip> {
        val b = Commute.bufferFor(s, r) ?: return connections(r.from, r.to, at, limit, s.maxChanges, cause)
        val first = connections(r.from, b.station, at, limit, null, "$cause (margin, part 1)")
        // Kept on their own: every bus to the margin station, for the "Also at" times in the trip screen.
        store.saveRoute(store.route(r.from.id, b.station.id).merge(first, nowSec()))
        val earliest = first.minOfOrNull { it.expectedArrival() } ?: return emptyList()
        val second = connections(b.station, r.to, earliest + b.minutes * 60L, 16, null, "$cause (margin, part 2)")
        return Commute.joinAtBuffer(first, second, b)
    }

    /** OJP 2.0 when a key is saved, else (or when OJP fails) transport.opendata.ch. */
    private suspend fun connections(from: Station, to: Station, at: Long, limit: Int, maxChanges: Int?, cause: String): List<Trip> {
        val key = withContext(Dispatchers.IO) { secrets.ojpKey() }
        val what = "${from.name} → ${to.name}, ${at.toSwiss().format(WHEN)}"
        if (key != null) {
            try {
                val trips = logged("OJP", what, cause, { OjpClient.connections(key, from, to, at, limit, maxChanges) }) { "${it.size} trips" }
                _source.value = Source("OJP 2.0")
                return trips
            } catch (e: HttpException) {
                // Only the status code is shown or logged, never the request headers.
                Log.w(TAG, "OJP failed: HTTP ${e.code}")
                _source.update {
                    it.copy(ojpProblem = if (e.code == 401 || e.code == 403) "OJP key rejected (HTTP ${e.code})" else "OJP error (HTTP ${e.code})")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw e // offline: transport.opendata.ch will not work either
            } catch (e: Exception) {
                Log.w(TAG, "OJP answer not understood", e)
                _source.update { it.copy(ojpProblem = "OJP answer not understood: ${e.message?.take(120)}") }
            }
        }
        return logged("transport.opendata.ch", what, cause, { TransportClient.connections(from.id, to.id, at, limit) }) { "${it.size} trips" }.also {
            _source.update { s -> s.copy(name = "transport.opendata.ch") }
        }
    }

    /** "I take this": [mine] becomes your trip, watched until you arrive. */
    suspend fun takeTrip(mine: MyTrip) {
        _myTrip.value = mine
        store.saveMyTrip(mine)
        TripWatch.schedule(app, mine)
    }

    suspend fun dropTrip() {
        _myTrip.value = null
        store.saveMyTrip(null)
        TripWatch.cancel(app)
    }

    /**
     * Asks for your trip again: the same route from shortly before it leaves, and the same trains picked out of
     * the answer. [onlyIfDue]: only when the last download is 5 minutes old and the trip is close or under way.
     * Changes not announced before get a notification. Returns false if the download failed.
     */
    suspend fun refreshMyTrip(cause: String, onlyIfDue: Boolean = false): Boolean = myTripLock.withLock {
        val now = nowSec()
        val mine = _myTrip.value ?: return@withLock true
        if (mine.over(now)) {
            dropTrip()
            return@withLock true
        }
        if (mine.arrived(now) || (onlyIfDue && (!mine.dueForRefresh(now) || now - lastAttempt < RETRY))) return@withLock true
        lastAttempt = now
        _myTripLoading.value = true
        try {
            val s = store.settings()
            val fresh = fetchLock.withLock { plan(s, mine.route, mine.trip.departure - LOOK_BACK, limit = 4, "$cause, my trip") }
            store.saveRoute(store.route(mine.from.id, mine.to.id).merge(fresh, now))
            val found = mine.findIn(fresh)
            // Not in the answer: keep showing the last known version, marked as missing.
            // Dropped or replaced while downloading: keep what you chose.
            val latest = _myTrip.value
            if (latest?.trip?.key != mine.trip.key) return@withLock true
            val fake = latest.fake
            val real = found ?: latest.real ?: latest.trip
            publish(
                latest.copy(
                    trip = fake?.applyTo(real) ?: real,
                    real = real.takeIf { fake != null },
                    fetchedAt = now,
                    found = found != null && fake?.gone != true,
                ),
                now,
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "My trip download failed", e)
            false
        } finally {
            _myTripLoading.value = false
        }
    }

    /** Saves [updated] as your trip and notifies the changes not announced before. */
    private suspend fun publish(updated: MyTrip, now: Long) {
        val news = Changes.of(updated, now).filter { it.notify && it.key !in updated.alerted }
        val saved = updated.copy(alerted = updated.alerted + news.map { it.key })
        _myTrip.value = saved
        store.saveMyTrip(saved)
        if (news.isNotEmpty()) Alerts.changed(app, saved, news)
        Alerts.updateWatching(app, saved)
    }

    /**
     * Testing: puts made-up changes on your trip right away (and on every download after), and announces them like
     * real ones. Clearing forgets what was announced, so the same test can be repeated, and downloads the trip again.
     */
    suspend fun setFake(fake: Fake?) {
        myTripLock.withLock {
            val mine = _myTrip.value ?: return
            val f = fake?.takeIf { it.any }
            val real = mine.real ?: mine.trip
            if (f == null) {
                _myTrip.value = mine.copy(fake = null, real = null, trip = real, alerted = emptySet(), found = true)
                store.saveMyTrip(_myTrip.value)
            } else {
                publish(mine.copy(fake = f, real = real, trip = f.applyTo(real), found = !f.gone), nowSec())
            }
        }
        if (fake?.any != true) refreshMyTrip("Test changes cleared")
    }

    /** Your trip's current changes, for the banner and the My trip tab. */
    fun changes(now: Long = nowSec()): List<Change> = _myTrip.value?.let { Changes.of(it, now) }.orEmpty()

    companion object {
        private const val TAG = "PendelBuddy"
        private const val RETRY = 60L
        private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)

        @Volatile private var instance: Backend? = null

        fun get(context: Context): Backend =
            instance ?: synchronized(this) { instance ?: Backend(context).also { instance = it } }

        fun nowSec() = System.currentTimeMillis() / 1000
    }
}
