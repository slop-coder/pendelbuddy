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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

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
    if (!st.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // Until home and work are set there is nothing else to show.
    val current = if (st.settings.complete) tab else Tab.SETTINGS
    if (current == Tab.TRIPS && trip != null) {
        TripScreen(vm, st, trip!!, onBack = { trip = null })
        return
    }
    if (current == Tab.SETTINGS && requestLog) {
        DebugScreen(vm, st, onBack = { requestLog = false })
        return
    }
    BackHandler(enabled = current != Tab.TRIPS && st.settings.complete) { tab = Tab.TRIPS }
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = current == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                        enabled = st.settings.complete || t == Tab.SETTINGS,
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
            when (current) {
                Tab.TRIPS -> CommuteScreen(vm, st, onTrip = { trip = it })
                Tab.MY_TRIP -> MyTripScreen(vm, st)
                Tab.RATINGS -> RatingsScreen(vm, st)
                Tab.SETTINGS -> SettingsScreen(vm, st, onDone = { tab = Tab.TRIPS }, onRequestLog = { requestLog = true })
            }
        }
    }
}
