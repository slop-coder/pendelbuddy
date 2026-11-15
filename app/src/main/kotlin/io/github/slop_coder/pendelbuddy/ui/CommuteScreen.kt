package io.github.slop_coder.pendelbuddy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import io.github.slop_coder.pendelbuddy.core.WeatherAt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommuteScreen(vm: MainViewModel, st: CommuteState, onSettings: () -> Unit, onTrip: (TripRow) -> Unit) {
    var searching by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pendel Buddy") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") } },
            )
        },
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
                st.quickest?.let { q -> item { QuickestCard(q, st.weather, st.settings.travelClass, st.now, onClick = { onTrip(q) }) } }
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
                items(st.rows) { row ->
                    TripItem(row, st.settings.travelClass, st.now, onClick = { onTrip(row) })
                    HorizontalDivider()
                }
            }
        }
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

@Composable
private fun RouteHeader(st: CommuteState, onFlip: () -> Unit) {
    val route = st.route ?: return
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (route.direction == Direction.TO_WORK) "To work" else "Home",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("${route.from.name} → ${route.to.name}", style = MaterialTheme.typography.titleLarge)
            Text(status(st), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            st.ojpProblem?.let { Text("$it · using transport.opendata.ch", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        IconButton(onClick = onFlip) { Icon(Icons.Filled.SwapVert, "Other direction") }
    }
}

private fun status(st: CommuteState): String {
    val copied = st.rows.firstNotNullOfOrNull { it.copiedFrom }
    val live = st.liveAt
    return when {
        st.offline && copied != null -> "Offline · timetable of ${day(copied)}, no delays or occupancy"
        st.offline && live != null -> "Offline · last update ${ago(st.now - live)}"
        st.offline -> "Offline · saved timetable"
        live != null -> "Live" + (st.source?.let { " · $it" } ?: "") + " · updated ${ago(st.now - live)}" +
            if (st.now - live > 10 * 60) " · pull down to refresh" else ""
        copied != null -> "Saved timetable of ${day(copied)}"
        else -> "Loading…"
    }
}

@Composable
private fun QuickestCard(row: TripRow, weather: WeatherAt?, cls: TravelClass, now: Long, onClick: () -> Unit) {
    val t = row.trip
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Timer, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                val minutes = (t.expectedDeparture() - now) / 60
                Text(
                    "Fastest arrival · " + if (minutes < 1) "leave now" else "leave in $minutes min",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.size(4.dp))
            Text("Arrive ${hm(t.expectedArrival())}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Leave ${hm(t.expectedDeparture())}" + (t.platform?.let { " · Pl. $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(4.dp))
            Trains(t, cls)
            Mine(row)
            Spacer(Modifier.size(8.dp))
            if (weather != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(weather.sky.icon(), weather.sky.label())
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${temp(weather.temp)} on arrival · ${weather.sky.label()}" + (weather.max?.let { " · max ${temp(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                Text("No current weather forecast", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TripItem(row: TripRow, cls: TravelClass, now: Long, onClick: () -> Unit) {
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
            Text(duration(t.arrival - t.departure), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Trains(t, cls, Modifier.weight(1f))
            t.platform?.let { Text("Pl. $it", style = MaterialTheme.typography.titleMedium) }
        }
        if (t.cancelled) Text("Cancelled", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
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
        Column(Modifier.navigationBarsPadding().padding(vertical = 6.dp)) {
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
                    label = { Text("Other…") },
                    leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}
