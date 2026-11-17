package io.github.slop_coder.pendelbuddy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.slop_coder.pendelbuddy.core.Commute
import io.github.slop_coder.pendelbuddy.core.Direction
import io.github.slop_coder.pendelbuddy.core.Leg
import io.github.slop_coder.pendelbuddy.core.Ratings
import io.github.slop_coder.pendelbuddy.core.ShortNames
import io.github.slop_coder.pendelbuddy.core.Station
import io.github.slop_coder.pendelbuddy.core.Trip
import io.github.slop_coder.pendelbuddy.core.TravelClass
import io.github.slop_coder.pendelbuddy.core.toSwiss
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommuteScreen(vm: MainViewModel, st: CommuteState, onTrip: (TripRow) -> Unit, onMyTrip: () -> Unit) {
    var searching by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Pendel Buddy") },
                    actions = {
                        val set = st.settings
                        IconButton(onClick = { vm.selectTime(LocalTime.of(set.morningMinute / 60, set.morningMinute % 60)) }) {
                            Icon(Icons.Filled.WbTwilight, "Morning")
                        }
                        IconButton(onClick = { vm.selectTime(LocalTime.of(set.eveningMinute / 60, set.eveningMinute % 60)) }) {
                            Icon(Icons.Filled.NightsStay, "Evening")
                        }
                        AssistChip(
                            onClick = { pickingTime = true },
                            label = { Text(st.time?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "Now") },
                            leadingIcon = { Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp)) },
                        )
                    },
                )
                DayTabs(st, vm::selectDay)
                // The shown day's commute is being fetched in the background.
                if ((st.day ?: st.now.toSwiss().toLocalDate()) in st.updating) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            StationBar(
                picks = Commute.quickPicks(st.settings, st.route?.direction ?: Direction.TO_WORK),
                route = st.route,
                current = st.current,
                onPick = vm::setCurrent,
                onSearch = { searching = true },
            )
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = st.loading,
            onRefresh = vm::pullToRefresh,
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { RouteHeader(st, onFlip = vm::flipDirection) }
                if (st.myTrip != null && st.changes.isNotEmpty()) {
                    item { ChangesCard(st.changes, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), onAlternatives = vm::showAlternatives, onMyTrip = onMyTrip) }
                }
                if (st.weather.isNotEmpty()) item { WeatherCard(st.weather, st.now, st.at) }
                if (st.rows.isEmpty() && !st.loading) {
                    item {
                        Text(
                            if (st.offline) "No internet and nothing saved for this route yet."
                            else "No trips found.",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                val durations = st.rows.filter { !it.trip.cancelled }.map { it.trip.expectedArrival() - it.trip.expectedDeparture() }
                items(st.rows) { row ->
                    TripItem(row, st.settings.travelClass, st.now, durationColor(row.trip, durations), mine = st.isMyTrip(row.trip), onClick = { onTrip(row) })
                    HorizontalDivider()
                }
            }
        }
    }

    if (pickingTime) {
        TimeDialog(st, onPick = { vm.selectTime(it); pickingTime = false }, onDismiss = { pickingTime = false })
    }
    if (searching) {
        StationSearchDialog(
            vm = vm,
            title = "Where are you?",
            onPick = { vm.setCurrent(it); searching = false },
            onDismiss = { searching = false },
        )
    }
}

/** Mo–Su; each is the next such day from today on (on a Wednesday, Mo and Tu are next week's). */
@Composable
private fun DayTabs(st: CommuteState, onPick: (LocalDate) -> Unit) {
    val today = st.now.toSwiss().toLocalDate()
    val selected = st.day ?: today
    val days = Commute.weekDays(today)
    PrimaryTabRow(selectedTabIndex = days.indexOf(selected).coerceAtLeast(0)) {
        for (d in days) {
            Tab(
                selected = d == selected,
                onClick = { onPick(d) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).take(2), fontWeight = if (d == today) FontWeight.Bold else null)
                        Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
        }
    }
}

/** Time picker for the trips shown, starting at the picked (or current) time, with a way back to "now". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(st: CommuteState, onPick: (LocalTime?) -> Unit, onDismiss: () -> Unit) {
    val start = st.time ?: st.now.toSwiss().toLocalTime()
    val state = rememberTimePickerState(start.hour, start.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trips from") },
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) { Text("Now") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun RouteHeader(st: CommuteState, onFlip: () -> Unit) {
    val route = st.route ?: return
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (route.direction == Direction.TO_WORK) "To work" else "Home",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            status(st)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            st.ojpProblem?.let { Text("$it · using transport.opendata.ch", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        IconButton(onClick = onFlip) { Icon(Icons.Filled.SwapVert, "Other direction") }
    }
}

/** Only when something is off: offline, another day's timetable, or data that is getting old. */
private fun status(st: CommuteState): String? {
    val copied = st.rows.firstNotNullOfOrNull { it.copiedFrom }
    val live = st.liveAt
    return when {
        st.offline && copied != null -> "Offline · timetable of ${day(copied)}, no delays or occupancy"
        st.offline -> "Offline · saved trips"
        copied != null -> "Saved timetable of ${day(copied)}"
        live != null && st.now - live > 10 * 60 && st.day == null && st.time == null ->
            "Updated ${ago(st.now - live)} · pull down to refresh"
        else -> null
    }
}

/** Green for the fastest trip shown, red for the slowest, yellow in between. */
private fun durationColor(t: Trip, all: List<Long>): Color? {
    if (all.size < 2 || t.cancelled) return null
    val lo = all.min()
    val hi = all.max()
    if (hi == lo) return null
    val f = ((t.expectedArrival() - t.expectedDeparture() - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
    val green = Color(0xFF43A047)
    val yellow = Color(0xFFFBC02D)
    val red = Color(0xFFE53935)
    return if (f < 0.5f) lerp(green, yellow, f * 2) else lerp(yellow, red, (f - 0.5f) * 2)
}

@Composable
private fun TripItem(row: TripRow, cls: TravelClass, now: Long, durationColor: Color?, mine: Boolean, onClick: () -> Unit) {
    val t = row.trip
    // Shown for a few minutes after it left (in case it is late); dimmed once it should be gone.
    val gone = t.expectedDeparture() < now
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).alpha(if (gone) 0.45f else 1f)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Times(t)
            Spacer(Modifier.weight(1f))
            Text(
                duration(t.expectedArrival() - t.expectedDeparture()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = durationColor ?: MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Trains(t, cls, Modifier.weight(1f))
            t.platform?.let { Text("Pl. $it", style = MaterialTheme.typography.titleMedium) }
        }
        if (t.cancelled) Text("Cancelled", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        if (mine) Text("Your trip", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Mine(row)
    }
}

@Composable
private fun Times(t: Trip) {
    val late = MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.CenterVertically) {
        val dep = t.departureDelay ?: 0
        if (dep > 0) {
            Text(hm(t.departure), style = MaterialTheme.typography.bodyMedium, textDecoration = TextDecoration.LineThrough)
            Spacer(Modifier.width(4.dp))
            Text(hm(t.expectedDeparture()), style = MaterialTheme.typography.titleLarge, color = late, fontWeight = FontWeight.Bold)
        } else {
            Text(hm(t.departure), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(horizontal = 8.dp).size(18.dp))
        val arr = t.arrivalDelay ?: 0
        Text(
            hm(t.expectedArrival()),
            style = MaterialTheme.typography.titleLarge,
            color = if (arr > 0) late else MaterialTheme.colorScheme.onSurface,
        )
        if (arr > 0) Text(" +$arr", style = MaterialTheme.typography.bodyMedium, color = late)
    }
}

/** One badge per train, colored by its occupancy forecast in your class. */
@Composable
private fun Trains(t: Trip, cls: TravelClass, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (leg in t.trains) LineBadge(leg, cls)
    }
}

@Composable
fun LineBadge(leg: Leg, cls: TravelClass) {
    val (bg, fg) = occupancyColors(leg.capacity.of(cls))
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(6.dp)) {
        Text(
            leg.line ?: "?",
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textDecoration = if (leg.cancelled) TextDecoration.LineThrough else null,
        )
    }
}

/** Green, yellow, red for low, medium, high occupancy; neutral when there is no forecast. */
@Composable
fun occupancyColors(level: Int?): Pair<Color, Color> = when (level) {
    1 -> Color(0xFF2E7D32) to Color.White
    2 -> Color(0xFFF9A825) to Color.Black
    3 -> Color(0xFFC62828) to Color.White
    else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
}

/** Your own ratings of the trip's fullest train. */
@Composable
private fun Mine(row: TripRow) {
    val mine = row.mine ?: return
    Text(
        "You: ${mine.percent}% · ${Ratings.label(mine.percent)}" + if (mine.count > 1) " (${mine.count}×)" else "",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun StationBar(
    picks: List<Station>,
    route: io.github.slop_coder.pendelbuddy.core.Route?,
    current: Station?,
    onPick: (Station?) -> Unit,
    onSearch: () -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(
                "I'm at…",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (s in picks + listOfNotNull(current?.takeIf { c -> picks.none { it.id == c.id } })) {
                    val selected = route?.from?.id == s.id
                    FilterChip(
                        selected = selected,
                        // Tapping the selected start again goes back to the normal commute.
                        onClick = { onPick(if (selected && current != null) null else s) },
                        label = { Text(ShortNames.of(s)) },
                        leadingIcon = if (selected) ({ Icon(Icons.Filled.Place, null, Modifier.size(18.dp)) }) else null,
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = onSearch,
                    label = { Icon(Icons.Filled.Search, "Other station", Modifier.size(18.dp)) },
                )
            }
        }
    }
}
