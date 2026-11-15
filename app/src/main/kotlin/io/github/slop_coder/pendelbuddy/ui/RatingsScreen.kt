package io.github.slop_coder.pendelbuddy.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.slop_coder.pendelbuddy.core.Rating
import io.github.slop_coder.pendelbuddy.core.Ratings
import io.github.slop_coder.pendelbuddy.core.TravelClass
import io.github.slop_coder.pendelbuddy.core.toSwiss
import java.time.LocalDate

/** Every rating you made, newest first, with the forecast at the time; summary and CSV export on top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingsScreen(vm: MainViewModel, st: CommuteState) {
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportRatings)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your ratings") },
                actions = {
                    TextButton(onClick = { export.launch("pendelbuddy-ratings-${LocalDate.now()}.csv") }, enabled = st.ratings.isNotEmpty()) {
                        Text("Export CSV")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (st.forecastChecks.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Compared with the forecast", style = MaterialTheme.typography.titleSmall)
                            for (c in st.forecastChecks) {
                                Text(
                                    (c.level?.let { capacityLabel(it) } ?: "No forecast") + ": you rated ${c.mean}% on average (${c.count}×)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            if (st.ratings.isEmpty()) {
                item {
                    Text(
                        "No ratings yet. Open a trip and rate how full a train is.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            items(st.ratings) { r ->
                RatingRow(r)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RatingRow(r: Rating) {
    val date = LocalDate.parse(r.date)
    ListItem(
        overlineContent = {
            Text(day(date) + (if (r.departure > 0) " · ${hm(r.departure)}" else "") + (r.departureDelay?.takeIf { it > 0 }?.let { " +$it'" } ?: ""))
        },
        headlineContent = { Text("${r.line} · ${r.fromName}" + if (r.toName.isNotEmpty()) " → ${r.toName}" else "") },
        supportingContent = {
            Text(
                (if (r.travelClass == TravelClass.FIRST) "1st" else "2nd") + " class · forecast: " +
                    (r.forecast()?.let { capacityLabel(it).removeSuffix(" occupancy").lowercase() } ?: "none") +
                    (if (r.ratedAt > 0) " · rated ${hm(r.ratedAt)}" + (if (r.ratedAt.toSwiss().toLocalDate() != date) " ${day(r.ratedAt.toSwiss().toLocalDate())}" else "") else ""),
            )
        },
        trailingContent = {
            Column {
                Text("${r.percent}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ratingColor(r.percent))
                Text(Ratings.label(r.percent), style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}

/** The same green / yellow / red as the occupancy badges: up to 40% many seats, up to 80% few, above that standing. */
private fun ratingColor(percent: Int) = when {
    percent <= 40 -> Color(0xFF43A047)
    percent <= 80 -> Color(0xFFF9A825)
    else -> Color(0xFFE53935)
}
