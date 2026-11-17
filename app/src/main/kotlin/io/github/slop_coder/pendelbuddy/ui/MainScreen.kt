package io.github.slop_coder.pendelbuddy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StarRate
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.slop_coder.pendelbuddy.data.Alerts

private enum class Tab(val label: String, val icon: ImageVector) {
    TRIPS("Trips", Icons.Filled.Train),
    MY_TRIP("My trip", Icons.Filled.ConfirmationNumber),
    RATINGS("Ratings", Icons.Filled.StarRate),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
fun MainScreen(vm: MainViewModel) {
    val st by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.TRIPS) }
    var trip by remember { mutableStateOf<TripRow?>(null) }
    var requestLog by rememberSaveable { mutableStateOf(false) }
    val saved = rememberSaveableStateHolder()
    // Opened from a notification: to your trip, or to the trips instead of it.
    LaunchedEffect(st.jumps) {
        if (st.jumps == 0) return@LaunchedEffect
        tab = if (st.jumpTo == Alerts.OPEN_MY_TRIP) Tab.MY_TRIP else Tab.TRIPS
        trip = null
        requestLog = false
    }
    if (!st.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // Until home and work are set there is nothing else to show; My trip only while you have one.
    val tabs = Tab.entries.filter { it != Tab.MY_TRIP || st.myTrip != null }
    val current = when {
        !st.settings.complete -> Tab.SETTINGS
        tab !in tabs -> Tab.TRIPS
        else -> tab
    }
    BackHandler(enabled = current != Tab.TRIPS && st.settings.complete) { tab = Tab.TRIPS }
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (t in tabs) {
                    NavigationBarItem(
                        selected = current == t,
                        // Tapping the tab you are on goes back to its start (closes the trip or the request log).
                        onClick = { if (t == current) { trip = null; requestLog = false }; tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                        enabled = st.settings.complete || t == Tab.SETTINGS,
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
            // Each screen keeps its scroll position while you are elsewhere (a trip per trip).
            val screen = when {
                current == Tab.TRIPS && trip != null -> "trip:${trip!!.trip.serviceDate}:${trip!!.trip.key}"
                current == Tab.SETTINGS && requestLog -> "log"
                else -> current.name
            }
            saved.SaveableStateProvider(screen) {
                when (current) {
                    Tab.TRIPS -> trip?.let { TripScreen(vm, st, it, onBack = { trip = null }) }
                        ?: CommuteScreen(vm, st, onTrip = { trip = it }, onMyTrip = { tab = Tab.MY_TRIP })
                    Tab.MY_TRIP -> MyTripScreen(vm, st)
                    Tab.RATINGS -> RatingsScreen(vm, st)
                    Tab.SETTINGS -> if (requestLog) DebugScreen(vm, st, onBack = { requestLog = false })
                        else SettingsScreen(vm, st, onDone = { tab = Tab.TRIPS }, onRequestLog = { requestLog = true })
                }
            }
        }
    }
}
