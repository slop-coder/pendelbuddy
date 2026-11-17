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

/** 1st class wagons: the yellow of the stripe on Swiss trains. */
private val FIRST = Color(0xFFFFD54F)

/**
 * The train along the platform where you get on, sector A on the left: wagon numbers, 1st class in yellow,
 * restaurants, closed wagons dimmed, and the sector letters under it. Tapping a wagon picks the sector you sit in
 * (tap again to clear); it is saved with your rating.
 */
@Composable
fun TrainMap(f: TrainFormation, cls: TravelClass, sector: String?, onSector: (String?) -> Unit) {
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                WagonBox(w, picked = sector != null && sector in w.sectors) {
                    val s = w.sectors.firstOrNull()
                    onSector(if (s == null || s == sector) null else s)
                }
                Text(label ?: "", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (sector != null) {
        Text("You sit in sector $sector", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun WagonBox(w: Wagon, picked: Boolean, onClick: () -> Unit) {
    val bg = when {
        w.firstClass -> FIRST
        w.passengers -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.outline
    }
    val fg = if (w.firstClass) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.width(if (w.passengers) 34.dp else 20.dp).height(26.dp)
            .alpha(if (w.closed) 0.35f else 1f)
            .background(bg, shape)
            .then(if (picked) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape) else Modifier)
            .clickable(enabled = w.sectors.isNotEmpty(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            w.restaurant -> Icon(Icons.Filled.Restaurant, "Restaurant", Modifier.size(16.dp), tint = fg)
            w.number != null -> Text("${w.number}", style = MaterialTheme.typography.labelMedium, color = fg)
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
