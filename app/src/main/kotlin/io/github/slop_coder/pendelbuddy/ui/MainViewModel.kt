package io.github.slop_coder.pendelbuddy.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.slop_coder.pendelbuddy.core.Average
import io.github.slop_coder.pendelbuddy.core.Call
import io.github.slop_coder.pendelbuddy.core.Change
import io.github.slop_coder.pendelbuddy.core.Commute
import io.github.slop_coder.pendelbuddy.core.Direction
import io.github.slop_coder.pendelbuddy.core.Forecast
import io.github.slop_coder.pendelbuddy.core.Freshness
import io.github.slop_coder.pendelbuddy.core.LOOK_BACK
import io.github.slop_coder.pendelbuddy.core.Fake
import io.github.slop_coder.pendelbuddy.core.Leg
import io.github.slop_coder.pendelbuddy.core.MyTrip
import io.github.slop_coder.pendelbuddy.core.ForecastCheck
import io.github.slop_coder.pendelbuddy.core.Rating
import io.github.slop_coder.pendelbuddy.core.Ratings
import io.github.slop_coder.pendelbuddy.core.Route
import io.github.slop_coder.pendelbuddy.core.SWISS
import io.github.slop_coder.pendelbuddy.core.Settings
import io.github.slop_coder.pendelbuddy.core.Station
import io.github.slop_coder.pendelbuddy.core.TrainFormation
import io.github.slop_coder.pendelbuddy.core.Trip
import io.github.slop_coder.pendelbuddy.core.DayWeather
import io.github.slop_coder.pendelbuddy.core.DebugEntry
import io.github.slop_coder.pendelbuddy.core.ShortNames
import io.github.slop_coder.pendelbuddy.core.dayWeather
import io.github.slop_coder.pendelbuddy.core.merge
import io.github.slop_coder.pendelbuddy.core.project
import io.github.slop_coder.pendelbuddy.core.toSwiss
import io.github.slop_coder.pendelbuddy.core.upcoming
import io.github.slop_coder.pendelbuddy.data.Alerts
import io.github.slop_coder.pendelbuddy.data.ApiKey
import io.github.slop_coder.pendelbuddy.data.Backend
import io.github.slop_coder.pendelbuddy.data.TripWatch
import io.github.slop_coder.pendelbuddy.data.TransportClient
import io.github.slop_coder.pendelbuddy.data.WeatherClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
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
    /** When this trip was downloaded, epoch seconds; 0 = unknown. */
    val fetchedAt: Long = 0,
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
    /** A key for the train formation service is saved. */
    val formationKeySet: Boolean = false,
    /** What happened to the key entered last ("Checking…", "Saved for OJP 2.0 and the train map"…). */
    val keyCheck: String? = null,
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
    /** The trip you take ("I take this"), as last downloaded. */
    val myTrip: MyTrip? = null,
    val myTripLoading: Boolean = false,
    /** What changed on your trip, for the banner above the trips and the My trip tab. */
    val changes: List<Change> = emptyList(),
    /** Opening the app from a notification switches to this tab ("trips" or "my-trip"); [jumps] counts the jumps. */
    val jumpTo: String? = null,
    val jumps: Int = 0,
) {
    fun isMyTrip(t: Trip) = myTrip?.trip?.let { it.key == t.key && it.serviceDate == t.serviceDate } == true
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val backend = Backend.get(app)
    private val store = backend.store
    private val secrets = backend.secrets

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
    private val fetchLock = backend.fetchLock
    /** Background prefetches run one after another, so they never compete with what you tapped. */
    private val prefetchLock = Mutex()
    private var ticker: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch { backend.debugLog.collect { l -> _state.update { it.copy(debugLog = l) } } }
        viewModelScope.launch { backend.source.collect { s -> _state.update { it.copy(source = s.name, ojpProblem = s.ojpProblem) } } }
        viewModelScope.launch { backend.myTripLoading.collect { l -> _state.update { it.copy(myTripLoading = l) } } }
        viewModelScope.launch {
            backend.myTrip.collect { m -> _state.update { it.copy(myTrip = m, changes = backend.changes()) } }
        }
        viewModelScope.launch {
            store.dropOldFiles()
            ratings = store.ratings()
            forecasts = store.forecasts()
            backend.load()
            val keySet = withContext(Dispatchers.IO) { secrets.hasOjpKey() }
            val formationSet = withContext(Dispatchers.IO) { secrets.has(ApiKey.FORMATION) }
            _state.update { it.copy(settings = store.settings(), loaded = true, ojpKeySet = keySet, formationKeySet = formationSet) }
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
            backend.myTrip.value?.let { TripWatch.schedule(getApplication(), it) }
            refreshMyTrip(cause = "Opened app")
            updateWeather(force = true, cause = "Opened app")
            val today = nowSec().toSwiss().toLocalDate()
            prefetch(Commute.weekDays(today), force = setOf(today, today.plusDays(1)))
        }
    }

    /**
     * The app was opened or reopened: download once, then only keep the clock running (trains that left drop
     * off the list, old delays are hidden). No more downloads until reopening or pull-to-refresh, except for
     * your trip, every 5 minutes.
     */
    fun start() {
        if (_state.value.loaded) openedApp()
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                if (!_state.value.loaded) continue
                render()
                refreshMyTrip(cause = "Every 5 min", onlyIfDue = true)
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
    /**
     * A key entered in Settings: tried with both APIs and saved for each one that accepts it (opentransportdata.swiss
     * gives each API its own key, but one key may be enabled for several).
     */
    fun enterKey(key: String) {
        val k = key.trim().removePrefix("Bearer ").trim()
        if (k.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(keyCheck = "Checking the key…") }
            val s = _state.value.settings
            val from = s.home ?: Station("8507000", "Bern")
            val to = s.work ?: Station("8503000", "Zürich HB")
            val (ojp, formation) = backend.checkKey(k, from, to)
            withContext(Dispatchers.IO) {
                // Saved where it works; and where it was saved before but is refused (entered in the wrong place), removed.
                for ((which, ok) in listOf(ApiKey.OJP to ojp, ApiKey.FORMATION to formation)) {
                    if (ok == true) secrets.set(which, k)
                    else if (ok == false && secrets.get(which) == k) secrets.set(which, null)
                }
            }
            val saved = listOfNotNull("OJP 2.0".takeIf { ojp == true }, "train map".takeIf { formation == true })
            val message = when {
                saved.isNotEmpty() -> "Saved for " + saved.joinToString(" and ")
                ojp == null || formation == null -> "Could not check the key (offline?). Nothing saved."
                else -> "Neither API accepts this key. Nothing saved."
            }
            val ojpSet = withContext(Dispatchers.IO) { secrets.hasOjpKey() }
            val formationSet = withContext(Dispatchers.IO) { secrets.has(ApiKey.FORMATION) }
            _state.update { it.copy(keyCheck = message, ojpKeySet = ojpSet, formationKeySet = formationSet) }
            backend.clearSourceProblem()
            if (ojp == true) refresh(force = true, cause = "Key saved")
        }
    }

    /** Saves (or with null removes) the train formation service's key. */
    fun setFormationKey(key: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { secrets.set(ApiKey.FORMATION, key) }
            val set = withContext(Dispatchers.IO) { secrets.has(ApiKey.FORMATION) }
            _state.update { it.copy(formationKeySet = set) }
        }
    }

    fun setOjpKey(key: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { secrets.setOjpKey(key) }
            val set = withContext(Dispatchers.IO) { secrets.hasOjpKey() }
            backend.clearSourceProblem()
            _state.update { it.copy(ojpKeySet = set) }
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

    /** "I take this": [row] becomes your trip, watched until you arrive. */
    fun takeTrip(row: TripRow) {
        val route = _state.value.route ?: return
        val mine = MyTrip(row.trip, route.from, route.to, route.direction, fetchedAt = _state.value.liveAt ?: 0)
        viewModelScope.launch {
            backend.takeTrip(mine)
            refreshMyTrip(cause = "Took trip")
        }
    }

    fun dropTrip() {
        viewModelScope.launch { backend.dropTrip() }
    }

    /** Testing: made-up delays and cancellations on your trip; null clears them. */
    fun setFake(fake: Fake?) {
        viewModelScope.launch {
            backend.setFake(fake)
            render()
        }
    }

    private val _formations = MutableStateFlow<Map<String, TrainFormation>>(emptyMap())
    /** Trains as they stand where you get on, by [formationKey]; filled when a train card is shown. */
    val formations: StateFlow<Map<String, TrainFormation>> = _formations

    fun formationKey(leg: Leg) = "${leg.trainKey}|${leg.departure}"

    fun loadFormation(leg: Leg) {
        if (!_state.value.formationKeySet) return
        viewModelScope.launch {
            val f = backend.formation(leg, "Opened trip") ?: return@launch
            _formations.update { it + (formationKey(leg) to f) }
        }
    }

    /** The wagon you sat in on this train last time, to start from. */
    fun lastWagon(leg: Leg): Int? =
        ratings.filter { it.trainKey == leg.trainKey && it.wagon != null }.maxByOrNull { it.ratedAt }?.wagon

    fun pullToRefreshMyTrip() {
        viewModelScope.launch { refreshMyTrip(cause = "Pull to refresh") }
    }

    private suspend fun refreshMyTrip(cause: String, onlyIfDue: Boolean = false) {
        backend.refreshMyTrip(cause, onlyIfDue)
        render()
    }

    /**
     * Opened from a notification: [Alerts.OPEN_MY_TRIP] shows your trip; [Alerts.OPEN_ALTERNATIVES] the trips from
     * where and when your trip changed, towards where it goes.
     */
    fun openFromNotification(what: String?, station: Station?, at: Long?) {
        when (what) {
            Alerts.OPEN_MY_TRIP -> _state.update { it.copy(jumpTo = Alerts.OPEN_MY_TRIP, jumps = it.jumps + 1) }
            Alerts.OPEN_ALTERNATIVES -> if (station != null && at != null) showAlternatives(station, at)
        }
    }

    /** Other trips from [station] at [at], in the direction of your trip. */
    fun showAlternatives(station: Station, at: Long) {
        val mine = _state.value.myTrip
        val z = at.toSwiss()
        val today = nowSec().toSwiss().toLocalDate()
        _state.update {
            it.copy(
                day = z.toLocalDate().takeIf { d -> d != today },
                time = z.toLocalTime().withSecond(0).withNano(0),
                jumpTo = "trips",
                jumps = it.jumps + 1,
            )
        }
        // After the time is set, so the direction holds for that half of the day.
        mine?.let { overrideDirection(it.direction) }
        _state.update { it.copy(current = station) }
        viewModelScope.launch { refresh(cause = "Alternatives") }
    }

    /** Saves a rating of [leg] together with the forecast it showed; earlier ratings are kept for analysis. */
    fun rate(leg: Leg, percent: Int, call: Call?, wagon: Int?, sector: String?) {
        // The forecast from the stop rated from, or for the whole ride (its worst stretch).
        val forecast = call?.capacity ?: leg.capacity
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
            forecastFirst = forecast.first,
            forecastSecond = forecast.second,
            ratedAt = nowSec(),
            callId = call?.id,
            callName = call?.name,
            wagon = wagon,
            sector = sector,
        )
        ratings = ratings + r
        _state.update { it.copy(ratings = ratings.sortedByDescending { r -> r.ratedAt }) }
        viewModelScope.launch {
            store.saveRatings(ratings)
            updateRatingStats()
            render()
        }
    }

    fun ratingFor(leg: Leg, call: Call?): Rating? = Ratings.current(ratings, leg, _state.value.settings.travelClass, call?.id)

    fun averagesByStop(leg: Leg): List<Pair<Call, Average>> = Ratings.byStop(ratings, leg, _state.value.settings.travelClass)

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

    private suspend fun <T> logged(kind: String, what: String, cause: String, request: suspend () -> T, result: (T) -> String): T =
        backend.logged(kind, what, cause, request, result)

    fun clearDebugLog() {
        viewModelScope.launch { backend.clearDebugLog() }
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

    private suspend fun plan(r: Route, at: Long, limit: Int, cause: String): List<Trip> =
        backend.plan(_state.value.settings, r, at, limit, cause)

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
            .map { TripRow(it.trip, it.copiedFrom, Ratings.forTrip(ratings, it.trip, cls), Commute.earlierDepartures(it.trip, known), it.fetchedAt) }
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
                changes = backend.changes(now),
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
