package io.github.slop_coder.pendelbuddy.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun MainScreen(vm: MainViewModel) {
    val st by vm.state.collectAsState()
    var settings by rememberSaveable { mutableStateOf(false) }
    var trip by remember { mutableStateOf<TripRow?>(null) }
    when {
        !st.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        settings || !st.settings.complete -> SettingsScreen(vm, st, onDone = { settings = false })
        trip != null -> TripScreen(vm, trip!!, st.settings.travelClass, onBack = { trip = null })
        else -> CommuteScreen(vm, st, onSettings = { settings = true }, onTrip = { trip = it })
    }
}
