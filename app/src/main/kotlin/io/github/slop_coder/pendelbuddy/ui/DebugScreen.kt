package io.github.slop_coder.pendelbuddy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.slop_coder.pendelbuddy.core.DebugEntry
import io.github.slop_coder.pendelbuddy.core.toSwiss
import java.time.format.DateTimeFormatter

private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss")

/** Every request that was not answered from saved data: when, what, why, and what came back. Opened from Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(vm: MainViewModel, st: CommuteState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val today = st.now.toSwiss().toLocalDate()
    val todays = st.debugLog.count { (it.time / 1000).toSwiss().toLocalDate() == today }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Requests")
                        Text("$todays today · last ${st.debugLog.size} kept", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = vm::clearDebugLog, enabled = st.debugLog.isNotEmpty()) { Text("Clear") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (st.debugLog.isEmpty()) {
                item { Text("No requests yet.", Modifier.padding(16.dp)) }
            }
            items(st.debugLog) { e ->
                Entry(e, sameDay = (e.time / 1000).toSwiss().toLocalDate() == today)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Entry(e: DebugEntry, sameDay: Boolean) {
    val t = (e.time / 1000).toSwiss()
    val failed = e.result.startsWith("HTTP") || e.result.contains("Exception")
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row {
            Text(
                (if (sameDay) "" else day(t.toLocalDate()) + " ") + t.format(HMS) + " · " + e.kind,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${e.result} · ${e.millis} ms",
                style = MaterialTheme.typography.labelMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(e.what, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(e.cause, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
