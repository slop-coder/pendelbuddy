package io.github.slop_coder.pendelbuddy.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.slop_coder.pendelbuddy.core.Leg
import io.github.slop_coder.pendelbuddy.core.Ratings
import io.github.slop_coder.pendelbuddy.core.SWISS
import io.github.slop_coder.pendelbuddy.core.TravelClass
import io.github.slop_coder.pendelbuddy.core.toSwiss
import java.time.LocalDate
import kotlin.math.roundToInt

/** Everything about one trip, train by train, each with a bar to rate how full it is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripScreen(vm: MainViewModel, row: TripRow, cls: TravelClass, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val t = row.trip
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("${t.legs.first().fromName} → ${t.legs.last().toName}", style = MaterialTheme.typography.titleMedium)
                        Text(day(t.serviceDate), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${hm(t.expectedDeparture())} → ${hm(t.expectedArrival())} · ${duration(t.arrival - t.departure)} · " +
                    if (t.transfers == 0) "direct" else "${t.transfers} change${if (t.transfers > 1) "s" else ""}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            row.copiedFrom?.let {
                Text(
                    "Offline: timetable of ${day(it)}, without delays or occupancy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            for (leg in t.legs) {
                if (leg.walk) WalkRow(leg) else TrainCard(vm, leg, cls, canRate = row.copiedFrom == null)
            }
        }
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
private fun TrainCard(vm: MainViewModel, leg: Leg, cls: TravelClass, canRate: Boolean) {
    Card(Modifier.fillMaxWidth()) {
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
            if (leg.cancelled) Text("Cancelled", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            Stop(leg.departure, leg.departureDelay, leg.fromName, leg.platform)
            Stop(leg.arrival, leg.arrivalDelay, leg.toName, leg.arrivalPlatform)
            Spacer(Modifier.size(8.dp))
            Forecast(leg, cls)
            vm.averageFor(leg)?.let {
                Text(
                    "Your ratings of this train: ${it.percent}% · ${Ratings.label(it.percent)} (${it.count}×)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (canRate) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                RatingBar(vm, leg)
            }
        }
    }
}

/** Time (crossed out and corrected when late), station and platform. */
@Composable
private fun Stop(time: Long, delay: Int?, station: String, platform: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val late = (delay ?: 0) > 0
        Text(hm(time), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            textDecoration = if (late) TextDecoration.LineThrough else null)
        if (late) {
            Spacer(Modifier.width(4.dp))
            Text(hm(time + 60L * delay!!), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(station, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        platform?.let { Text("Pl. $it", style = MaterialTheme.typography.titleMedium) }
    }
}

/** The occupancy forecast for both classes, yours in bold. */
@Composable
private fun Forecast(leg: Leg, cls: TravelClass) {
    val levels = listOf(TravelClass.FIRST to leg.capacity.first, TravelClass.SECOND to leg.capacity.second)
    if (levels.all { it.second == null }) {
        Text("No occupancy forecast", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    for ((c, level) in levels) {
        if (level == null) continue
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (level) { 1 -> Icons.Filled.Person; 2 -> Icons.Filled.Group; else -> Icons.Filled.Groups },
                null,
                Modifier.size(18.dp),
                tint = when (level) {
                    1 -> MaterialTheme.colorScheme.primary
                    2 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${if (c == TravelClass.FIRST) "1st" else "2nd"} class: ${capacityLabel(level)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (c == cls) FontWeight.Bold else null,
            )
        }
    }
}

/** Rate this train from 10% (almost empty) to 110% (no seats, many standing). */
@Composable
private fun RatingBar(vm: MainViewModel, leg: Leg) {
    var saved by remember(leg) { mutableStateOf(vm.ratingFor(leg)?.percent) }
    var value by remember(leg) { mutableFloatStateOf((saved ?: 50).toFloat()) }
    val percent = (value / Ratings.STEP).roundToInt() * Ratings.STEP
    Text("How full is it?", style = MaterialTheme.typography.labelLarge)
    Text("$percent% · ${Ratings.label(percent)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Slider(
        value = value,
        onValueChange = { value = it },
        valueRange = Ratings.SCALE.first.toFloat()..Ratings.SCALE.last.toFloat(),
        steps = (Ratings.SCALE.last - Ratings.SCALE.first) / Ratings.STEP - 1,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            saved?.let { "Saved: $it%" } ?: "Not rated yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { vm.rate(leg, percent); saved = percent }, enabled = saved != percent) {
            Text(if (saved == null) "Save" else "Update")
        }
    }
    // Rating a train hours before or after it runs is allowed, but say which run it is.
    if (leg.departure.toSwiss().toLocalDate() != LocalDate.now(SWISS)) {
        Text("For the train of ${day(leg.departure.toSwiss().toLocalDate())}", style = MaterialTheme.typography.bodySmall)
    }
}
