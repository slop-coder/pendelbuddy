package io.github.slop_coder.pendelbuddy.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.slop_coder.pendelbuddy.core.Buffer
import io.github.slop_coder.pendelbuddy.core.Commute
import io.github.slop_coder.pendelbuddy.core.Direction
import io.github.slop_coder.pendelbuddy.core.Station
import io.github.slop_coder.pendelbuddy.core.TravelClass
import java.time.LocalDate

private enum class Pick { HOME, WORK, VIA }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, status: CommuteState, onDone: () -> Unit) {
    val initial = status.settings
    var s by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf<Pick?>(null) }
    var timeDialog by remember { mutableStateOf(false) }
    var keyDialog by remember { mutableStateOf(false) }
    var bufferDialog by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportRatings)
    }

    fun done() {
        if (s != initial) vm.saveSettings(s)
        onDone()
    }
    BackHandler(enabled = initial.complete) { done() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (s.complete) IconButton(onClick = ::done) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Done") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (!s.complete) {
                Text(
                    "Set your home and work stations to get started.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            StationRow(Icons.Filled.Home, "Home", s.home) { picking = Pick.HOME }
            StationRow(Icons.Filled.Work, "Work", s.work) { picking = Pick.WORK }

            Header("Stations in between")
            Text(
                "Quick picks at the bottom of the main screen, e.g. where you get off for breakfast.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (v in Commute.viasInOrder(s)) {
                ListItem(
                    headlineContent = { Text(v.name) },
                    leadingContent = { Icon(Icons.Filled.Train, null) },
                    trailingContent = {
                        IconButton(onClick = { s = s.copy(vias = s.vias - v) }) { Icon(Icons.Filled.Close, "Remove") }
                    },
                )
            }
            TextButton(onClick = { picking = Pick.VIA }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.size(8.dp))
                Text("Add station")
            }

            Header("Class")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                TravelClass.entries.forEachIndexed { i, c ->
                    SegmentedButton(
                        selected = s.travelClass == c,
                        onClick = { s = s.copy(travelClass = c) },
                        shape = SegmentedButtonDefaults.itemShape(i, TravelClass.entries.size),
                    ) { Text(if (c == TravelClass.FIRST) "1st class" else "2nd class") }
                }
            }

            Header("Commute")
            Text("Most changes", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyLarge)
            val choices = listOf(null, 0, 1, 2, 3)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                choices.forEachIndexed { i, c ->
                    SegmentedButton(
                        selected = s.maxChanges == c,
                        onClick = { s = s.copy(maxChanges = c) },
                        shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                    ) { Text(c?.toString() ?: "Any") }
                }
            }
            ListItem(
                headlineContent = { Text("Trips home from") },
                supportingContent = { Text("%02d:%02d · before that, trips go to work".format(s.switchMinute / 60, s.switchMinute % 60)) },
                leadingContent = { Icon(Icons.Filled.Schedule, null) },
                trailingContent = { TextButton(onClick = { timeDialog = true }) { Text("Change") } },
            )

            Header("Safety margins")
            Text(
                "Extra time when changing at a station, e.g. for a bus that is often late or to buy breakfast. " +
                    "Applies to the normal commute, not when you start from a station picked under \"I'm at…\".",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (b in s.buffers) {
                ListItem(
                    headlineContent = { Text("${b.station.name}: ${b.minutes} min") },
                    supportingContent = {
                        Text(when (b.direction) { Direction.TO_WORK -> "On the way to work"; Direction.TO_HOME -> "On the way home"; null -> "Both ways" })
                    },
                    leadingContent = { Icon(Icons.Filled.Timer, null) },
                    trailingContent = {
                        IconButton(onClick = { s = s.copy(buffers = s.buffers - b) }) { Icon(Icons.Filled.Close, "Remove") }
                    },
                )
            }
            TextButton(onClick = { bufferDialog = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.size(8.dp))
                Text("Add safety margin")
            }

            Header("Data")
            ListItem(
                headlineContent = { Text("OJP 2.0 API key") },
                supportingContent = {
                    Column {
                        Text(
                            if (status.ojpKeySet) "Saved (encrypted, never shown again)"
                            else "Not set: timetable from transport.opendata.ch, which has no occupancy"
                        )
                        status.ojpProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (status.ojpKeySet && status.ojpProblem == null && status.source == "OJP 2.0") Text("Working")
                    }
                },
                leadingContent = { Icon(Icons.Filled.Key, null) },
                trailingContent = {
                    if (status.ojpKeySet) TextButton(onClick = { vm.setOjpKey(null) }) { Text("Remove") }
                    else TextButton(onClick = { keyDialog = true }) { Text("Paste") }
                },
            )

            Header("Your fullness ratings")
            ListItem(
                headlineContent = { Text("${status.ratingCount} rating${if (status.ratingCount == 1) "" else "s"} saved") },
                supportingContent = {
                    Column {
                        Text("All ratings are kept, with the forecast shown when you rated.")
                        for (c in status.forecastChecks) {
                            Text(
                                (c.level?.let { capacityLabel(it) } ?: "No forecast") + ": you rated ${c.mean}% on average (${c.count}×)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                leadingContent = { Icon(Icons.Filled.Groups, null) },
                trailingContent = {
                    TextButton(onClick = { export.launch("pendelbuddy-ratings-${LocalDate.now()}.csv") }, enabled = status.ratingCount > 0) {
                        Text("Export CSV")
                    }
                },
            )
            Spacer(Modifier.size(24.dp))
            Text(
                "Timetable and occupancy: OJP 2.0 (opentransportdata.swiss) or transport.opendata.ch · " +
                    "Weather: Open-Meteo, MeteoSwiss ICON-CH (CC BY 4.0)",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    picking?.let { p ->
        StationSearchDialog(
            vm = vm,
            title = when (p) { Pick.HOME -> "Home station"; Pick.WORK -> "Work station"; Pick.VIA -> "Station in between" },
            onPick = { st: Station ->
                s = when (p) {
                    Pick.HOME -> s.copy(home = st)
                    Pick.WORK -> s.copy(work = st)
                    Pick.VIA -> if (s.vias.any { it.id == st.id }) s else s.copy(vias = s.vias + st)
                }
                picking = null
                // First setup: go straight to the trips once both ends are known.
                if (!initial.complete && s.complete) done()
            },
            onDismiss = { picking = null },
        )
    }
    if (bufferDialog) {
        BufferDialog(
            vm = vm,
            stations = Commute.viasInOrder(s) + listOfNotNull(s.home, s.work),
            onSave = { b -> s = s.copy(buffers = s.buffers.filterNot { it.station.id == b.station.id && it.direction == b.direction } + b); bufferDialog = false },
            onDismiss = { bufferDialog = false },
        )
    }
    if (keyDialog) KeyDialog(onSave = { vm.setOjpKey(it); keyDialog = false }, onDismiss = { keyDialog = false })
    if (timeDialog) {
        val state = rememberTimePickerState(s.switchMinute / 60, s.switchMinute % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timeDialog = false },
            title = { Text("Trips home from") },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = { s = s.copy(switchMinute = state.hour * 60 + state.minute); timeDialog = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { timeDialog = false }) { Text("Cancel") } },
        )
    }
}

/** Pick a station, the minutes to keep there and the direction. */
@Composable
private fun BufferDialog(vm: MainViewModel, stations: List<Station>, onSave: (Buffer) -> Unit, onDismiss: () -> Unit) {
    var station by remember { mutableStateOf(stations.firstOrNull()) }
    var minutes by remember { mutableStateOf(10) }
    var direction by remember { mutableStateOf<Direction?>(Direction.TO_WORK) }
    var searching by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Safety margin") },
        text = {
            Column {
                Text("Change at", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (st in (stations + listOfNotNull(station)).distinctBy { it.id }) {
                        FilterChip(selected = station?.id == st.id, onClick = { station = st }, label = { Text(st.name) })
                    }
                    FilterChip(selected = false, onClick = { searching = true }, label = { Text("Other…") })
                }
                Spacer(Modifier.size(12.dp))
                Text("Keep at least", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { minutes = (minutes - 5).coerceAtLeast(5) }) { Icon(Icons.Filled.Remove, "Less") }
                    Text("$minutes min", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { minutes = (minutes + 5).coerceAtMost(60) }) { Icon(Icons.Filled.Add, "More") }
                }
                Spacer(Modifier.size(8.dp))
                val dirs = listOf(Direction.TO_WORK, Direction.TO_HOME, null)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    dirs.forEachIndexed { i, d ->
                        SegmentedButton(
                            selected = direction == d,
                            onClick = { direction = d },
                            shape = SegmentedButtonDefaults.itemShape(i, dirs.size),
                        ) { Text(when (d) { Direction.TO_WORK -> "To work"; Direction.TO_HOME -> "Home"; null -> "Both" }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { station?.let { onSave(Buffer(it, minutes, direction)) } }, enabled = station != null) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (searching) {
        StationSearchDialog(vm, "Change at", onPick = { station = it; searching = false }, onDismiss = { searching = false })
    }
}

/** The ZXing scanner screen, but (like the key dialog) not capturable by screenshots or screen recording. */
class SecureScanActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }
}

/**
 * Masked input in a window that cannot be screenshotted or screen-recorded, or a QR code scanned in the app
 * (ZXing, works without Google Play services). A scanned key is saved without being shown.
 */
@Composable
private fun KeyDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents?.trim()?.removePrefix("Bearer ")?.trim()
        scanError = when {
            // Cancelled, or the camera permission was refused.
            result.contents == null -> if (result.originalIntent?.hasExtra(Intents.Scan.MISSING_CAMERA_PERMISSION) == true)
                "Scanning needs the camera permission." else null
            scanned.isNullOrEmpty() || scanned.any { it.isWhitespace() } -> "That QR code does not hold a key."
            else -> null.also { onSave(scanned) }
        }
    }
    fun scan() {
        scanError = null
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the QR code of your OJP key")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setCaptureActivity(SecureScanActivity::class.java)
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("OJP 2.0 API key") },
        text = {
            Column {
                Text(
                    "From api-manager.opentransportdata.swiss. Long-press the field to paste. " +
                        "It is stored encrypted on this phone only.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    label = { Text("Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = ::scan, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.QrCodeScanner, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Scan QR code")
                }
                scanError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key) }, enabled = key.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StationRow(icon: ImageVector, label: String, station: Station?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(station?.name ?: "Not set") },
        overlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { TextButton(onClick = onClick) { Text(if (station == null) "Set" else "Change") } },
    )
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
