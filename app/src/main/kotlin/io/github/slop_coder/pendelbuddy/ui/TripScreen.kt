package io.github.slop_coder.pendelbuddy.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.slop_coder.pendelbuddy.core.Call
import io.github.slop_coder.pendelbuddy.core.Change
import io.github.slop_coder.pendelbuddy.core.Freshness
import io.github.slop_coder.pendelbuddy.core.Leg
import io.github.slop_coder.pendelbuddy.core.Ratings
import io.github.slop_coder.pendelbuddy.core.SWISS
import io.github.slop_coder.pendelbuddy.core.TravelClass
import io.github.slop_coder.pendelbuddy.core.Trip
import io.github.slop_coder.pendelbuddy.core.toSwiss
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Everything about one trip, train by train, each with a bar to rate how full it is. The trip is looked up in the
 * list on every update, so delays and platforms stay current while it is open; your trip shows its own, fresher copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripScreen(vm: MainViewModel, st: CommuteState, row: TripRow, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val live = st.rows.firstOrNull { it.trip.key == row.trip.key && it.trip.serviceDate == row.trip.serviceDate } ?: row
    val mine = st.myTrip?.takeIf { st.isMyTrip(live.trip) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { TripTitle(live.trip) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = if (mine != null) st.myTripLoading else st.loading,
            onRefresh = if (mine != null) vm::pullToRefreshMyTrip else vm::pullToRefresh,
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (mine != null) TripBody(vm, st, live.copy(trip = mine.trip), mine.fetchedAt, st.changes, missing = !mine.found)
                else TripBody(vm, st, live, live.fetchedAt, emptyList(), missing = false)
            }
        }
    }
}

/**
 * The same view in the trip screen and in My trip: times, how old the data is, what changed, the "I take this"
 * button and the trains.
 */
@Composable
fun TripBody(vm: MainViewModel, st: CommuteState, row: TripRow, fetchedAt: Long, changes: List<Change>, missing: Boolean) {
    val t = row.trip
    TripSummary(t)
    if (row.copiedFrom == null) Updated(fetchedAt, st.now, arrived = st.now > t.expectedArrival())
    if (changes.isNotEmpty()) ChangesCard(changes, onAlternatives = vm::showAlternatives)
    if (missing) {
        Text(
            "Shown as last known.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    TakeButton(vm, st, row)
    TripDetails(vm, row, st.settings.travelClass)
}

/** When the trip was downloaded; red once delays are too old to trust. */
@Composable
private fun Updated(fetchedAt: Long, now: Long, arrived: Boolean) {
    val age = now - fetchedAt
    Text(
        when {
            fetchedAt == 0L -> "Not downloaded yet · pull down to refresh"
            arrived -> "Arrived · updated ${hm(fetchedAt)}"
            else -> "Updated ${hm(fetchedAt)} · ${ago(age)}"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (!arrived && (fetchedAt == 0L || age > Freshness.DELAYS)) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** "I take this", or for your trip, stop watching it. */
@Composable
private fun TakeButton(vm: MainViewModel, st: CommuteState, row: TripRow) {
    // Alerts need the notification permission (Android 13+); the trip is taken either way.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.takeTrip(row) }
    if (st.isMyTrip(row.trip)) {
        OutlinedButton(onClick = vm::dropTrip, modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Your trip · stop watching")
        }
    } else {
        FilledTonalButton(
            onClick = { if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.takeTrip(row) },
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Icon(Icons.Filled.ConfirmationNumber, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (st.myTrip == null) "I take this" else "I take this instead")
        }
    }
}

@Composable
fun TripTitle(t: Trip) {
    Column {
        Text("${t.legs.first().fromName} → ${t.legs.last().toName}", style = MaterialTheme.typography.titleMedium)
        Text(day(t.serviceDate), style = MaterialTheme.typography.bodySmall)
    }
}

/** Leave and arrival time, travel time and changes. */
@Composable
fun TripSummary(t: Trip) {
    Text(
        "${hm(t.expectedDeparture())} → ${hm(t.expectedArrival())} · ${duration(t.arrival - t.departure)} · " +
            if (t.transfers == 0) "direct" else "${t.transfers} change${if (t.transfers > 1) "s" else ""}",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** The trains and changes of a trip, for the trip screen and My trip. */
@Composable
fun TripDetails(vm: MainViewModel, row: TripRow, cls: TravelClass) {
    val t = row.trip
    row.copiedFrom?.let {
        Text(
            "Offline: timetable of ${day(it)}, without delays or occupancy",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    for (leg in t.legs) {
        val earlier = if (leg == t.trains.firstOrNull()) row.earlier else emptyList()
        if (leg.walk) WalkRow(leg) else TrainCard(vm, leg, cls, canRate = row.copiedFrom == null, earlier = earlier)
    }
}

@Composable
private fun WalkRow(leg: Leg) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            // A wait added by a safety margin has the same station at both ends.
            (if (leg.fromId.isNotEmpty() && leg.fromId == leg.toId) "Margin at " else "Change at ") +
                "${leg.fromName} · ${(leg.arrival - leg.departure) / 60} min",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrainCard(vm: MainViewModel, leg: Leg, cls: TravelClass, canRate: Boolean, earlier: List<Long>) {
    val colors = if (leg.cancelled) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
    } else CardDefaults.cardColors()
    val formations by vm.formations.collectAsState()
    val formation = formations[vm.formationKey(leg)]
    LaunchedEffect(vm.formationKey(leg)) { if (!leg.walk) vm.loadFormation(leg) }
    var wagon by remember(leg.trainKey) { mutableStateOf(vm.lastWagon(leg)) }
    val sector = formation?.wagons?.firstOrNull { wagon != null && it.number == wagon }?.sectors?.firstOrNull()
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(leg, cls)
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(leg.direction?.let { "towards $it" }, leg.trainNumber?.let { "no. $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            leg.problem?.let {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Cancel, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.size(8.dp))
            Stop(leg.departure, leg.departureDelay, leg.fromName, leg.platform, leg.plannedPlatform)
            if (earlier.isNotEmpty()) {
                // Leaving earlier for safety or to buy food: the same line's departures before this one.
                Text(
                    "Also at " + earlier.joinToString(", ") { hm(it) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                )
            }
            Stop(leg.arrival, leg.arrivalDelay, leg.toName, leg.arrivalPlatform, leg.plannedArrivalPlatform)
            Spacer(Modifier.size(8.dp))
            Forecast(leg, cls)
            formation?.let {
                Spacer(Modifier.size(8.dp))
                TrainMap(it, cls, wagon) { w -> wagon = w?.number }
            }
            vm.averageFor(leg)?.let {
                Text(
                    "Your ratings of this train: ${it.percent}% · ${Ratings.label(it.percent)} (${it.count}×)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Stretch by stretch, where you rated from a stop.
            val byStop = vm.averagesByStop(leg)
            if (byStop.size > 1 || byStop.any { it.first.id != leg.calls.firstOrNull()?.id }) {
                for ((call, avg) in byStop) {
                    Text(
                        "From ${call.name}: ${avg.percent}% (${avg.count}×)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (canRate) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                RatingBar(vm, leg, wagon, sector)
            }
        }
    }
}

/** Time (crossed out and corrected when late), station and platform. */
@Composable
private fun Stop(time: Long, delay: Int?, station: String, platform: String?, planned: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val late = (delay ?: 0) > 0
        Text(hm(time), Modifier.struck(late), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (late) {
            Spacer(Modifier.width(4.dp))
            Text(hm(time + 60L * delay!!), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(station, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Platform(platform, planned)
    }
}

/**
 * The occupancy forecast in your class stretch by stretch (it changes where people get on and off), and the
 * fullest stretch of the other class.
 */
@Composable
private fun Forecast(leg: Leg, cls: TravelClass) {
    val sections = leg.sections(cls)
    if (leg.capacity.of(cls) == null) {
        Text("No occupancy forecast", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text("Occupancy, ${className(cls)}", style = MaterialTheme.typography.labelLarge)
    if (sections.isEmpty()) {
        // No stops in between known (e.g. data from transport.opendata.ch): just the one value.
        SectionRow("${leg.fromName} → ${leg.toName}", leg.capacity.of(cls))
    } else {
        for (sec in sections) SectionRow("${sec.from} → ${sec.to}", sec.level)
    }
}

@Composable
private fun SectionRow(stretch: String, level: Int?) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val (bg, _) = occupancyColors(level)
        Box(Modifier.size(12.dp).background(bg, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(stretch, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(level?.let { capacityLabel(it).removeSuffix(" occupancy") } ?: "Unknown", style = MaterialTheme.typography.bodyMedium)
    }
}

private fun className(c: TravelClass) = if (c == TravelClass.FIRST) "1st class" else "2nd class"

/**
 * Rate this train from 10% (almost empty) to 110% (no seats, many standing), for the stretch from the stop it last
 * left (picked from the clock, can be changed).
 */
@Composable
private fun RatingBar(vm: MainViewModel, leg: Leg, wagon: Int?, sector: String?) {
    var call by remember(leg.trainKey, leg.departure) { mutableStateOf(leg.callAt(System.currentTimeMillis() / 1000)) }
    var saved by remember(leg, call) { mutableStateOf(vm.ratingFor(leg, call)?.percent) }
    var value by remember(leg, call) { mutableFloatStateOf((saved ?: 50).toFloat()) }
    val percent = (value / Ratings.STEP).roundToInt() * Ratings.STEP
    // Rating a train before or after the day it runs is allowed, but say which run it is.
    val other = leg.departure.toSwiss().toLocalDate().takeIf { it != LocalDate.now(SWISS) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$percent% · ${Ratings.label(percent)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                (saved?.let { "Saved: $it%" } ?: "How full is it?") + (other?.let { " · ${day(it)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(
            onClick = { vm.rate(leg, percent, call, wagon, sector); saved = percent },
            enabled = saved != percent,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            modifier = Modifier.height(34.dp),
        ) { Text(if (saved == null) "Save" else "Update") }
    }
    Slider(
        value = value,
        onValueChange = { value = it },
        valueRange = Ratings.SCALE.first.toFloat()..Ratings.SCALE.last.toFloat(),
        steps = (Ratings.SCALE.last - Ratings.SCALE.first) / Ratings.STEP - 1,
        modifier = Modifier.height(32.dp),
    )
    if (leg.calls.size > 1) StopPicker(leg, call) { call = it }
}

/** Which stretch the rating is for: from the stop the train last left. */
@Composable
private fun StopPicker(leg: Leg, call: Call?, onPick: (Call) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text("From ${call?.name ?: leg.fromName}", style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Filled.ArrowDropDown, "Pick the stop")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in leg.calls) {
                DropdownMenuItem(
                    text = { Text("${hm(c.departure)}  ${c.name}") },
                    onClick = { onPick(c); open = false },
                )
            }
        }
    }
}
