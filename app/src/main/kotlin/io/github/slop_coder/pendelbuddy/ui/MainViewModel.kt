package io.github.slop_coder.pendelbuddy.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.slop_coder.pendelbuddy.BuildConfig
import io.github.slop_coder.pendelbuddy.core.Average
import io.github.slop_coder.pendelbuddy.core.Commute
import io.github.slop_coder.pendelbuddy.core.Direction
import io.github.slop_coder.pendelbuddy.core.Forecast
import io.github.slop_coder.pendelbuddy.core.Freshness
import io.github.slop_coder.pendelbuddy.core.LOOK_BACK
import io.github.slop_coder.pendelbuddy.core.Leg
import io.github.slop_coder.pendelbuddy.core.ForecastCheck
import io.github.slop_coder.pendelbuddy.core.Rating
import io.github.slop_coder.pendelbuddy.core.Ratings
import io.github.slop_coder.pendelbuddy.core.Route
import io.github.slop_coder.pendelbuddy.core.SWISS
import io.github.slop_coder.pendelbuddy.core.Settings
import io.github.slop_coder.pendelbuddy.core.Station
import io.github.slop_coder.pendelbuddy.core.Trip
import io.github.slop_coder.pendelbuddy.core.DayWeather
import io.github.slop_coder.pendelbuddy.core.DebugEntry
import io.github.slop_coder.pendelbuddy.core.ShortNames
import io.github.slop_coder.pendelbuddy.core.dayWeather
import io.github.slop_coder.pendelbuddy.core.merge
import io.github.slop_coder.pendelbuddy.core.project
import io.github.slop_coder.pendelbuddy.core.toSwiss
import io.github.slop_coder.pendelbuddy.core.upcoming
import io.github.slop_coder.pendelbuddy.data.HttpException
import io.github.slop_coder.pendelbuddy.data.OjpClient
import io.github.slop_coder.pendelbuddy.data.Secrets
import io.github.slop_coder.pendelbuddy.data.Store
import io.github.slop_coder.pendelbuddy.data.TransportClient
import io.github.slop_coder.pendelbuddy.data.WeatherClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalTime

data class TripRow(
    val trip: Trip,
    /** Set when this is another day's timetable shown offline. */
    val copiedFrom: LocalDate?,
    /** Your own fullness ratings for this train. */
    val mine: Average?,
    /** Earlier departures of the first ride's line from the same stop that day (latest first), for extra time. */
    val earlier: List<Long> = emptyList(),
)

data class CommuteState(
    val settings: Settings = Settings(),
    val loaded: Boolean = false,
    val route: Route? = null,
    /** Station picked at the bottom ("I'm already here"); null means start where the commute starts. */
    val current: Station? = null,
    val rows: List<TripRow> = emptyList(),
    /** The picked day's weather at the start and at the destination (home and work), with their tab names. */
    val weather: List<Pair<String, DayWeather>> = emptyList(),
    /** Every rating, newest first. */
    val ratings: List<Rating> = emptyList(),
    /** Requests that went to the network, newest first. */
    val debugLog: List<DebugEntry> = emptyList(),
    /** Last successful timetable download for the shown route, epoch seconds. */
    val liveAt: Long? = null,
    val offline: Boolean = false,
    val loading: Boolean = false,
    val now: Long = System.currentTimeMillis() / 1000,
    /** An OJP key is saved (the key itself never leaves [Secrets]). */
    val ojpKeySet: Boolean = false,
    /** Where the last timetable came from, or what went wrong with OJP. */
    val source: String? = null,
    val ojpProblem: String? = null,
    /** Days whose commute is being fetched in the background right now. */
    val updating: Set<LocalDate> = emptySet(),
    /** The day and time picked at the top; null = today / now. */
    val day: LocalDate? = null,
    val time: LocalTime? = null,
    /** The moment trips are shown for (now, or the picked day and time), epoch seconds. */
    val at: Long = System.currentTimeMillis() / 1000,
    /** Number of ratings saved, and how they compare with the forecast. */
    val ratingCount: Int = 0,
    val forecastChecks: List<ForecastCheck> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val secrets = Secrets(app)

    private val _state = MutableStateFlow(CommuteState())
    val state: StateFlow<CommuteState> = _state
    private val _stationResults = MutableStateFlow<List<Station>>(emptyList())
    val stationResults: StateFlow<List<Station>> = _stationResults

    /** Direction chosen by hand, and the automatic direction at that moment: it ends when that changes (e.g. next morning). */
    private var directionOverride: Pair<Direction, Direction>? = null
    private var ratings: List<Rating> = emptyList()
    private var forecasts: Map<String, Forecast> = emptyMap()
    private var debugLog: List<DebugEntry> = emptyList()
    private val logLock = Mutex()
    /** Routes downloaded since the app was last opened; a route is only fetched again on reopening or pull-to-refresh. */
    private val fetchedThisVisit = mutableSetOf<String>()
    private val lastFetch = mutableMapOf<String, Long>()
    private val fetchLock = Mutex()
    /** Background prefetches run one after another, so they never compete with what you tapped. */
    private val prefetchLock = Mutex()
    private var ticker: Job? = null
    private var searchJob: Job? = null

    init {
        if (BuildConfig.DEBUG) OjpClient.debugDump = File(app.filesDir, "ojp-last.xml")
        viewModelScope.launch {
            store.dropOldFiles()
            ratings = store.ratings()
            forecasts = store.forecasts()
            debugLog = store.debugLog()
            _state.update { it.copy(debugLog = debugLog) }
            val keySet = withContext(Dispatchers.IO) { secrets.hasOjpKey() }
            _state.update { it.copy(settings = store.settings(), loaded = true, ojpKeySet = keySet) }
            updateRatingStats()
            openedApp()
        }
    }

    /** On opening: the trips shown, today's and tomorrow's commute, and once a day the rest of the working week. */
    private fun openedApp() {
        fetchedThisVisit.clear()
        viewModelScope.launch {
            // What is on screen first, then the weather and the commute in the background.
            refresh(cause = "Opened app")
            updateWeather(force = true, cause = "Opened app")
            val today = nowSec().toSwiss().toLocalDate()
            prefetch(Commute.weekDays(today), force = setOf(today, today.plusDays(1)))
        }
    }

    /**
     * The app was opened or reopened: download once, then only keep the clock running (trains that left drop
     * off the list, old delays are hidden). No more downloads until reopening or pull-to-refresh.
     */
    fun start() {
        if (_state.value.loaded) openedApp()
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                if (_state.value.loaded) render()
            }
        }
    }

    fun stop() {
        ticker?.cancel()
    }

    fun saveSettings(s: Settings) {
        val current = _state.value.current?.takeIf { c -> Commute.quickPicks(s).any { it.id == c.id } }
        _state.update { it.copy(settings = s, current = current) }
        viewModelScope.launch {
            store.saveSettings(s)
            fetchedThisVisit.clear()
            refresh(cause = "Settings changed")
            // New home or work: their weather (only what is not there yet today).
            updateWeather(force = false, cause = "Settings changed")
            prefetch(Commute.weekDays(nowSec().toSwiss().toLocalDate()), force = emptySet())
        }
    }

    /** Picks the station you are at; picking the destination plans the trip back. */
    fun setCurrent(station: Station?) {
        val st = _state.value
        val route = st.route
        if (station != null && route != null && station.id == route.to.id) {
            // Arrived: from now on the commute goes the other way, starting here.
            overrideDirection(route.direction.other)
            _state.update { it.copy(current = null) }
        } else {
            _state.update { it.copy(current = station) }
        }
        viewModelScope.launch { refresh(cause = "Picked station") }
    }

    fun flipDirection() {
        val d = _state.value.route?.direction ?: return
        overrideDirection(d.other)
        _state.update { it.copy(current = null) }
        viewModelScope.launch { refresh(cause = "Flipped direction") }
    }

    /** Saves (or with null/blank removes) the OJP key and tries it right away. */
    fun setOjpKey(key: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { secrets.setOjpKey(key) }
            val set = withContext(Dispatchers.IO) { secrets.hasOjpKey() }
            _state.update { it.copy(ojpKeySet = set, ojpProblem = null, source = null) }
            refresh(force = true, cause = "Key saved")
        }
    }

    /** Picks the day at the top (today = back to today). */
    fun selectDay(d: LocalDate) {
        val today = nowSec().toSwiss().toLocalDate()
        _state.update { it.copy(day = d.takeIf { it != today }) }
        viewModelScope.launch {
            refresh(cause = "Picked day")
            updateWeather(force = false, cause = "Picked day")
            // The picked day's commute, unless already fetched today; right away, the page updates when it arrives.
            prefetch(listOf(d), force = emptySet(), background = false)
        }
    }

    /** Picks the time at the top; null = now. */
    fun selectTime(t: LocalTime?) {
        _state.update { it.copy(time = t) }
        viewModelScope.launch { refresh(cause = "Picked time") }
    }

    /** One download per route and selection: now, or a picked day at a picked time (or at "the current time"). */
    private fun fetchKey(r: Route): String {
        val st = _state.value
        val sel = if (isLive()) "now" else "${st.day ?: "today"}-${st.time ?: "clock"}"
        return "${r.from.id}-${r.to.id}-$sel"
    }

    /** Showing the trips from right now (not a picked day or time). */
    private fun isLive() = _state.value.day == null && _state.value.time == null

    /** The picked moment: now, or the picked day at the picked time (or at the current time of day). */
    private fun queryTime(): Long {
        val now = nowSec()
        val st = _state.value
        val today = now.toSwiss().toLocalDate()
        // A picked day that has passed (the app stayed open overnight) goes back to today.
        if (st.day != null && st.day.isBefore(today)) _state.update { it.copy(day = null) }
        if (isLive()) return now
        val date = _state.value.day ?: today
        val time = st.time ?: now.toSwiss().toLocalTime().withSecond(0).withNano(0)
        return date.atTime(time).atZone(SWISS).toEpochSecond()
    }

    fun pullToRefresh() {
        viewModelScope.launch {
            refresh(force = true, cause = "Pull to refresh")
            updateWeather(force = true, cause = "Pull to refresh")
        }
    }

    /** Saves a rating of [leg] together with the forecast it showed; earlier ratings are kept for analysis. */
    fun rate(leg: Leg, percent: Int) {
        val r = Rating(
            trainKey = leg.trainKey ?: return,
            date = leg.departure.toSwiss().toLocalDate().toString(),
            percent = percent,
            travelClass = _state.value.settings.travelClass,
            line = leg.line ?: "",
            fromName = leg.fromName,
            toName = leg.toName,
            trainNumber = leg.trainNumber,
            departure = leg.departure,
            departureDelay = leg.departureDelay,
            forecastFirst = leg.capacity.first,
            forecastSecond = leg.capacity.second,
            ratedAt = nowSec(),
        )
        ratings = ratings + r
        _state.update { it.copy(ratings = ratings.sortedByDescending { r -> r.ratedAt }) }
        viewModelScope.launch {
            store.saveRatings(ratings)
            updateRatingStats()
            render()
        }
    }

    fun ratingFor(leg: Leg): Rating? = Ratings.current(ratings, leg, _state.value.settings.travelClass)

    fun averageFor(leg: Leg): Average? = Ratings.average(ratings, leg, _state.value.settings.travelClass)

    /** Writes every rating as CSV to a file the user picked. */
    fun exportRatings(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)!!.use {
                        it.write(Ratings.toCsv(ratings).toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            if (!ok) Log.w(TAG, "Rating export failed")
        }
    }

    private fun updateRatingStats() {
        _state.update {
            it.copy(
                ratingCount = ratings.size,
                forecastChecks = Ratings.versusForecast(ratings),
                ratings = ratings.sortedByDescending { r -> r.ratedAt },
            )
        }
    }

    /** Runs a network request and notes it in the debug log, with how long it took and what came back. */
    private suspend fun <T> logged(kind: String, what: String, cause: String, request: suspend () -> T, result: (T) -> String): T {
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
        debugLog = (listOf(e) + debugLog).take(DebugEntry.KEEP)
        _state.update { it.copy(debugLog = debugLog) }
        store.saveDebugLog(debugLog)
    }

    fun clearDebugLog() {
        viewModelScope.launch {
            logLock.withLock {
                debugLog = emptyList()
                _state.update { it.copy(debugLog = debugLog) }
                store.saveDebugLog(debugLog)
            }
        }
    }

    fun searchStations(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            _stationResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _stationResults.value = try {
                logged("Station search", "\"$q\"", "Station search", { TransportClient.stations(q) }) { "${it.size} stations" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Station search failed", e)
                // Offline: at least find the stations you already saved.
                Commute.quickPicks(_state.value.settings).filter { it.name.contains(q, ignoreCase = true) }
            }
        }
    }

    private fun nowSec() = System.currentTimeMillis() / 1000

    private fun autoDirection() = Commute.direction(_state.value.settings, queryTime().toSwiss())

    private fun overrideDirection(d: Direction) {
        directionOverride = d to autoDirection()
    }

    private fun currentRoute(): Route? {
        val auto = autoDirection()
        val manual = directionOverride
        if (manual != null && manual.second != auto) {
            // A new half of the day: back to the normal commute.
            directionOverride = null
            _state.update { it.copy(current = null) }
        }
        val st = _state.value
        return Commute.route(st.settings, directionOverride?.first ?: auto, st.current)
    }

    /** Shows what the cache has, then downloads the route unless it was already fetched since the app was opened. */
    private suspend fun refresh(force: Boolean = false, cause: String) {
        render()
        val route = currentRoute() ?: return
        val now = nowSec()
        val at = queryTime()
        val live = isLive()
        val key = fetchKey(route)
        if (!force && key in fetchedThisVisit) return
        fetchedThisVisit += key
        if (!force && !live && coveredByPrefetch(route, at, now)) return
        fetchLock.withLock {
            _state.update { it.copy(loading = true) }
            try {
                val fresh = plan(route, if (live) now - LOOK_BACK else at, limit = 8, cause)
                store.saveRoute(store.route(route.from.id, route.to.id).merge(fresh, now))
                lastFetch[key] = now

                _state.update { it.copy(offline = false) }
            } catch (e: Exception) {
                Log.w(TAG, "Timetable download failed", e)
                _state.update { it.copy(offline = true) }
            }
            _state.update { it.copy(loading = false) }
        }
        render()
    }

    /** True if a commute window fetched today already holds the trips for [r] at [at]: no request needed. */
    private suspend fun coveredByPrefetch(r: Route, at: Long, now: Long): Boolean {
        val day = at.toSwiss().toLocalDate()
        val windows = Commute.commuteWindows(_state.value.settings, listOf(day), now)
        return windows.any { w -> w.route.from.id == r.from.id && w.route.to.id == r.to.id && at >= w.from && at <= w.until } &&
            store.route(r.from.id, r.to.id).prefilledToday(day, now.toSwiss().toLocalDate())
    }

    /**
     * Fetches the commute windows (to work 05:30–08:00, home 15:30–18:30) of the working days among [days]:
     * days in [force] always, the others once a day. The page is updated after each window. In the [background]
     * the windows queue up with pauses between requests; asked for from the screen, they go right away.
     */
    private fun prefetch(days: List<LocalDate>, force: Set<LocalDate>, background: Boolean = true) {
        viewModelScope.launch {
            if (background) prefetchLock.withLock { prefetchNow(days, force, PREFETCH_PAUSE, "Prefetch") }
            else prefetchNow(days, force, 0, "Picked day, prefetch")
        }
    }

    private suspend fun prefetchNow(days: List<LocalDate>, force: Set<LocalDate>, pause: Long, cause: String) {
        val now = nowSec()
        val today = now.toSwiss().toLocalDate()
        for (w in Commute.commuteWindows(_state.value.settings, days, now)) {
            val r = w.route
            if (w.day !in force && store.route(r.from.id, r.to.id).prefilledToday(w.day, today)) continue
            _state.update { it.copy(updating = it.updating + w.day) }
            try {
                val why = "$cause ${day(w.day)} " + if (w.route.direction == Direction.TO_WORK) "to work" else "home"
                val trips = fetchWindow(w, pause, why)
                store.saveRoute(store.route(r.from.id, r.to.id).merge(trips, now).markPrefilled(w.day, today))
                render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or the API refuses: try again on the next opening.
                Log.w(TAG, "Prefetch failed", e)
                break
            } finally {
                _state.update { it.copy(updating = it.updating - w.day) }
            }
            delay(pause)
        }
    }

    /** All departures of one window, page by page (up to 4 pages of 16). */
    private suspend fun fetchWindow(w: Commute.Window, pause: Long, cause: String): List<Trip> {
        val all = mutableListOf<Trip>()
        var at = w.from
        repeat(4) {
            val page = fetchLock.withLock { plan(w.route, at, limit = 16, cause) }
            all += page
            val last = page.maxOfOrNull { it.departure } ?: return all
            if (last >= w.until || last < at) return all
            at = last + 60
            delay(pause)
        }
        return all
    }

    /**
     * Trips for [r] from [at] on. With a safety margin on the way, planned in two parts so that trips with a
     * longer change (which a planner never suggests on its own) are found.
     */
    private suspend fun plan(r: Route, at: Long, limit: Int, cause: String): List<Trip> {
        val s = _state.value.settings
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
        val what = "${from.name} → ${to.name}, ${day(at.toSwiss().toLocalDate())} ${hm(at)}"
        if (key != null) {
            try {
                val trips = logged("OJP", what, cause, { OjpClient.connections(key, from, to, at, limit, maxChanges) }) { "${it.size} trips" }
                _state.update { it.copy(source = "OJP 2.0", ojpProblem = null) }
                return trips
            } catch (e: HttpException) {
                // Only the status code is shown or logged, never the request headers.
                Log.w(TAG, "OJP failed: HTTP ${e.code}")
                _state.update {
                    it.copy(ojpProblem = if (e.code == 401 || e.code == 403) "OJP key rejected (HTTP ${e.code})" else "OJP error (HTTP ${e.code})")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw e // offline: transport.opendata.ch will not work either
            } catch (e: Exception) {
                Log.w(TAG, "OJP answer not understood", e)
                _state.update { it.copy(ojpProblem = "OJP answer not understood: ${e.message?.take(120)}") }
            }
        }
        return logged("transport.opendata.ch", what, cause, { TransportClient.connections(from.id, to.id, at, limit) }) { "${it.size} trips" }.also {
            _state.update { s -> s.copy(source = "transport.opendata.ch") }
        }
    }

    /**
     * Weather for home and work (one request each covers 5 days), on the same schedule as the trips: on opening
     * and pull-to-refresh ([force]), otherwise once a day.
     */
    private suspend fun updateWeather(force: Boolean, cause: String) {
        val s = _state.value.settings
        val now = nowSec()
        val today = now.toSwiss().toLocalDate()
        for (place in listOfNotNull(s.home, s.work)) {
            val lat = place.lat ?: continue
            val lon = place.lon ?: continue
            val old = forecasts[place.id]
            if (!force && old != null && old.fetchedAt.toSwiss().toLocalDate() == today) continue
            try {
                val f = logged("Weather", place.name, cause, { WeatherClient.forecast(lat, lon, now) }) { "${it.hours.size} hours" }
                forecasts = forecasts + (place.id to f)
                store.saveForecasts(forecasts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Weather download failed", e)
                break
            }
        }
        render()
    }

    private suspend fun render() {
        val st = _state.value
        val now = nowSec()
        val at = queryTime()
        val route = currentRoute()
        if (route == null) {
            _state.update { it.copy(route = null, rows = emptyList(), weather = emptyList(), now = now, at = at) }
            return
        }
        val cls = st.settings.travelClass
        val buffer = Commute.bufferFor(st.settings, route)
        val day = store.route(route.from.id, route.to.id)
            .project(at.toSwiss().toLocalDate())
            .filter { Commute.allowed(it.trip, st.settings, buffer) }
        // Earlier departures of the first ride: from the trips, and with a margin from all rides to the margin station.
        val known = day.map { it.trip } + (buffer?.let { b ->
            store.route(route.from.id, b.station.id).project(at.toSwiss().toLocalDate()).map { it.trip }
        } ?: emptyList())
        val rows = day.upcoming(at, count = 8, now = now)
            .map { TripRow(it.trip, it.copiedFrom, Ratings.forTrip(ratings, it.trip, cls), Commute.earlierDepartures(it.trip, known)) }
        // Where you start and where you go: home and work, in the order of the trip.
        val places = listOfNotNull(Commute.origin(st.settings, route.direction), Commute.destination(st.settings, route.direction))
        val weather = places.mapNotNull { p ->
            forecasts[p.id]?.takeIf { now - it.fetchedAt <= Freshness.WEATHER_MAX }
                ?.dayWeather(at.toSwiss().toLocalDate())?.let { ShortNames.of(p) to it }
        }
        _state.update {
            it.copy(
                route = route,
                rows = rows,
                weather = weather,
                liveAt = lastFetch[fetchKey(route)],
                now = now,
                at = at,
            )
        }
    }

    companion object {
        private const val TAG = "PendelBuddy"
        /** Between background requests: OJP's free tier allows 50 requests a minute. */
        private const val PREFETCH_PAUSE = 1_500L
    }
}
