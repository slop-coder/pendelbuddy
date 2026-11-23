package io.github.slop_coder.pendelbuddy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.slop_coder.pendelbuddy.core.TrainFormation
import io.github.slop_coder.pendelbuddy.core.TravelClass
import io.github.slop_coder.pendelbuddy.core.Wagon

/**
 * The train along the platform where you get on, sector A on the left: wagon numbers, 1st class inverted (light on
 * dark, dark on light), restaurants, closed wagons dimmed, and the sector letters under it. Tapping a wagon picks
 * the one you sit in (tap again to clear); it is saved with your rating.
 */
@Composable
fun TrainMap(f: TrainFormation, cls: TravelClass, wagon: Int?, onWagon: (Wagon?) -> Unit) {
    val mine = f.sectorsOf(cls)
    Text(
        listOfNotNull(
            "Train" + (f.track?.let { " at platform $it" } ?: ""),
            mine.takeIf { it.isNotEmpty() }?.let { "${if (cls == TravelClass.FIRST) "1st" else "2nd"} class in ${ranges(it)}" },
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelLarge,
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        var shown: String? = null
        for (w in f.alongPlatform) {
            // The letter under the wagon where a new sector starts.
            val label = w.sectors.firstOrNull()?.takeIf { it != shown }
            w.sectors.lastOrNull()?.let { shown = it }
            val picked = wagon != null && w.number == wagon
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                WagonBox(w, picked) { onWagon(if (picked) null else w) }
                Text(
                    if (picked) "▲" else label ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (picked) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                )
            }
        }
    }
    val mineNow = f.wagons.firstOrNull { wagon != null && it.number == wagon }
    Text(
        if (mineNow != null) "You sit in wagon $wagon" + (mineNow.sectors.takeIf { it.isNotEmpty() }?.let { " (sector ${ranges(it)})" } ?: "")
        else "Tap the wagon you sit in",
        style = MaterialTheme.typography.bodySmall,
        color = if (mineNow != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WagonBox(w: Wagon, picked: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when {
        picked -> c.tertiary to c.onTertiary
        w.firstClass -> c.inverseSurface to c.inverseOnSurface
        w.passengers -> c.surfaceVariant to c.onSurfaceVariant
        else -> c.outlineVariant to c.onSurfaceVariant
    }
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.width(if (w.passengers) 34.dp else 20.dp).height(26.dp)
            .alpha(if (w.closed) 0.35f else 1f)
            .background(bg, shape)
            .then(if (picked) Modifier.border(BorderStroke(2.dp, c.onSurface), shape) else Modifier)
            .clickable(enabled = w.number != null && w.passengers, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            w.restaurant -> Icon(Icons.Filled.Restaurant, "Restaurant", Modifier.size(16.dp), tint = fg)
            w.number != null -> Text("${w.number}", style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.Bold)
        }
    }
}

/** "A–C, E–F" from ["A", "B", "C", "E", "F"]. */
fun ranges(sectors: List<String>): String {
    val out = mutableListOf<String>()
    var start = sectors.firstOrNull() ?: return ""
    var prev = start
    for (s in sectors.drop(1) + "") {
        if (s.isNotEmpty() && s[0] == prev[0] + 1) {
            prev = s
            continue
        }
        out += if (start == prev) start else "$start–$prev"
        start = s
        prev = s
    }
    return out.joinToString(", ")
}
