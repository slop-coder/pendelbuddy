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
import io.github.slop_coder.pendelbuddy.core.WeatherAt
import io.github.slop_coder.pendelbuddy.core.at
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
)

data class CommuteState(
    val settings: Settings = Settings(),
    val loaded: Boolean = false,
    val route: Route? = null,
    /** Station picked at the bottom ("I'm already here"); null means start where the commute starts. */
    val current: Station? = null,
    val rows: List<TripRow> = emptyList(),
    val quickest: TripRow? = null,
    val weather: WeatherAt? = null,
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
    /** Routes downloaded since the app was last opened; a route is only fetched again on reopening or pull-to-refresh. */
    private val fetchedThisVisit = mutableSetOf<String>()
    private val lastFetch = mutableMapOf<String, Long>()
    private val fetchLock = Mutex()
    private var ticker: Job? = null
    private var searchJob: Job? = null

    init {
        if (BuildConfig.DEBUG) OjpClient.debugDump = File(app.filesDir, "ojp-last.xml")
        viewModelScope.launch {
            store.dropOldFiles()
            ratings = store.ratings()
            forecasts = store.forecasts()
            val keySet = withContext(Dispatchers.IO) { secrets.hasOjpKey() }
            _state.update { it.copy(settings = store.settings(), loaded = true, ojpKeySet = keySet) }
            updateRatingStats()
            refresh()
        }
    }

    /**
     * The app was opened or reopened: download once, then only keep the clock running (trains that left drop
     * off the list, old delays are hidden). No more downloads until reopening or pull-to-refresh.
     */
    fun start() {
        if (_state.value.loaded) {
            fetchedThisVisit.clear()
            viewModelScope.launch { refresh() }
        }
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
            refresh()
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
        viewModelScope.launch { refresh() }
    }

    fun flipDirection() {
        val d = _state.value.route?.direction ?: return
        overrideDirection(d.other)
        _state.update { it.copy(current = null) }
        viewModelScope.launch { refresh() }
    }

    /** Saves (or with null/blank removes) the OJP key and tries it right away. */
    fun setOjpKey(key: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { secrets.setOjpKey(key) }
            val set = withContext(Dispatchers.IO) { secrets.hasOjpKey() }
            _state.update { it.copy(ojpKeySet = set, ojpProblem = null, source = null) }
            refresh(force = true)
        }
    }

    /** Picks the day at the top (today = back to today). */
    fun selectDay(d: LocalDate) {
        val today = nowSec().toSwiss().toLocalDate()
        _state.update { it.copy(day = d.takeIf { it != today }) }
        viewModelScope.launch { refresh() }
    }

    /** Picks the time at the top; null = now. */
    fun selectTime(t: LocalTime?) {
        _state.update { it.copy(time = t) }
        viewModelScope.launch { refresh() }
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
        viewModelScope.launch { refresh(force = true) }
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
        _state.update { it.copy(ratingCount = ratings.size, forecastChecks = Ratings.versusForecast(ratings)) }
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
                TransportClient.stations(q)
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
    private suspend fun refresh(force: Boolean = false) {
        render()
        val route = currentRoute() ?: return
        val now = nowSec()
        val at = queryTime()
        val live = isLive()
        val key = fetchKey(route)
        if (!force && key in fetchedThisVisit) return
        fetchedThisVisit += key
        fetchLock.withLock {
            _state.update { it.copy(loading = true) }
            try {
                val fresh = plan(route, if (live) now - LOOK_BACK else at, limit = 8)
                store.saveRoute(store.route(route.from.id, route.to.id).merge(fresh, now))
                lastFetch[key] = now

                _state.update { it.copy(offline = false) }
                prefillCommute(now)
            } catch (e: Exception) {
                Log.w(TAG, "Timetable download failed", e)
                _state.update { it.copy(offline = true) }
            }
            try {
                updateForecast(route.to, now)
            } catch (e: Exception) {
                Log.w(TAG, "Weather download failed", e)
            }
            _state.update { it.copy(loading = false) }
        }
        render()
    }

    /** Once a day, fetch the whole morning and afternoon commute so it is there when offline. */
    private suspend fun prefillCommute(now: Long) {
        val s = _state.value.settings
        val home = s.home ?: return
        val work = s.work ?: return
        val today = now.toSwiss().toLocalDate()
        val morning = today.atTime(LocalTime.of(5, 30)).atZone(SWISS).toEpochSecond()
        val afternoon = today.atTime(LocalTime.of(s.switchMinute / 60, s.switchMinute % 60)).atZone(SWISS).toEpochSecond()
        val commutes = listOf(Route(home, work, Direction.TO_WORK) to morning, Route(work, home, Direction.TO_HOME) to afternoon)
        for ((route, at) in commutes) {
            val cache = store.route(route.from.id, route.to.id)
            if (cache.prefilledOn == today.toString()) continue
            val trips = plan(route, at, limit = 16)
            store.saveRoute(cache.merge(trips, now).copy(prefilledOn = today.toString()))
        }
    }

    /**
     * Trips for [r] from [at] on. With a safety margin on the way, planned in two parts so that trips with a
     * longer change (which a planner never suggests on its own) are found.
     */
    private suspend fun plan(r: Route, at: Long, limit: Int): List<Trip> {
        val s = _state.value.settings
        val b = Commute.bufferFor(s, r) ?: return connections(r.from, r.to, at, limit, s.maxChanges)
        val first = connections(r.from, b.station, at, limit, null)
        val earliest = first.minOfOrNull { it.expectedArrival() } ?: return emptyList()
        val second = connections(b.station, r.to, earliest + b.minutes * 60L, 16, null)
        return Commute.joinAtBuffer(first, second, b)
    }

    /** OJP 2.0 when a key is saved, else (or when OJP fails) transport.opendata.ch. */
    private suspend fun connections(from: Station, to: Station, at: Long, limit: Int, maxChanges: Int?): List<Trip> {
        val key = withContext(Dispatchers.IO) { secrets.ojpKey() }
        if (key != null) {
            try {
                val trips = OjpClient.connections(key, from, to, at, limit, maxChanges)
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
        return TransportClient.connections(from.id, to.id, at, limit).also {
            _state.update { s -> s.copy(source = "transport.opendata.ch") }
        }
    }

    private suspend fun updateForecast(to: Station, now: Long) {
        val lat = to.lat ?: return
        val lon = to.lon ?: return
        val old = forecasts[to.id]
        if (old != null && now - old.fetchedAt < 3600) return
        forecasts = forecasts + (to.id to WeatherClient.forecast(lat, lon, now))
        store.saveForecasts(forecasts)
    }

    private suspend fun render() {
        val st = _state.value
        val now = nowSec()
        val at = queryTime()
        val route = currentRoute()
        if (route == null) {
            _state.update { it.copy(route = null, rows = emptyList(), quickest = null, weather = null, now = now, at = at) }
            return
        }
        val cls = st.settings.travelClass
        val buffer = Commute.bufferFor(st.settings, route)
        val rows = store.route(route.from.id, route.to.id)
            .project(at.toSwiss().toLocalDate())
            .filter { Commute.allowed(it.trip, st.settings, buffer) }
            .upcoming(at, count = 8, now = now)
            .map { TripRow(it.trip, it.copiedFrom, Ratings.forTrip(ratings, it.trip, cls)) }
        val quickest = Commute.quickest(rows.map { it.trip }, maxOf(at, now))?.let { q -> rows.first { it.trip == q } }
        val weather = forecasts[route.to.id]
            ?.takeIf { now - it.fetchedAt <= Freshness.WEATHER }
            ?.at(quickest?.trip?.expectedArrival() ?: at)
        _state.update {
            it.copy(
                route = route,
                rows = rows,
                quickest = quickest,
                weather = weather,
                liveAt = lastFetch[fetchKey(route)],
                now = now,
                at = at,
            )
        }
    }

    companion object {
        private const val TAG = "PendelBuddy"
    }
}
