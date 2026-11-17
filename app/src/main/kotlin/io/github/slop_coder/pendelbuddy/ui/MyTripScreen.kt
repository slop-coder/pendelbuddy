package io.github.slop_coder.pendelbuddy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.slop_coder.pendelbuddy.core.Freshness
import io.github.slop_coder.pendelbuddy.core.Ratings

/** The trip you take: refreshed every 5 minutes while the app is open, or by pulling down. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTripScreen(vm: MainViewModel, st: CommuteState) {
    val mine = st.myTrip
    Scaffold(
        topBar = {
            TopAppBar(
                title = { if (mine == null) Text("My trip") else TripTitle(mine.trip) },
                actions = { if (mine != null) TextButton(onClick = vm::dropTrip) { Text("Stop") } },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = st.myTripLoading,
            onRefresh = vm::pullToRefreshMyTrip,
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (mine == null) {
                    Text(
                        "No trip chosen. Open a trip in Trips and tap \"I take this\" to follow it here.",
                        Modifier.padding(4.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    return@Column
                }
                TripSummary(mine.trip)
                val age = st.now - mine.fetchedAt
                Text(
                    when {
                        mine.fetchedAt == 0L -> "Not downloaded yet · pull down to refresh"
                        mine.arrived(st.now) -> "Arrived · updated ${hm(mine.fetchedAt)}"
                        else -> "Updated ${hm(mine.fetchedAt)} · ${ago(age)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    // Delays older than this are no longer trustworthy.
                    color = if (mine.fetchedAt == 0L || age > Freshness.DELAYS) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                if (!mine.found) {
                    Text(
                        "Not in the latest timetable answer: it may be cancelled or changed. Shown as last known.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                val cls = st.settings.travelClass
                TripDetails(vm, TripRow(mine.trip, copiedFrom = null, mine = Ratings.forTrip(st.ratings, mine.trip, cls)), cls)
            }
        }
    }
}
