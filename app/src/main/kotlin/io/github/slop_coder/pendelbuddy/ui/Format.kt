package io.github.slop_coder.pendelbuddy.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Modifier
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbSunny
import io.github.slop_coder.pendelbuddy.core.Sky
import io.github.slop_coder.pendelbuddy.core.toSwiss
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

fun hm(epoch: Long): String = epoch.toSwiss().format(HM)
fun day(d: LocalDate): String = d.format(DAY)

fun duration(seconds: Long): String {
    val m = seconds / 60
    return if (m < 60) "$m min" else "%d h %02d".format(m / 60, m % 60)
}

fun ago(seconds: Long): String = when {
    seconds < 90 -> "just now"
    seconds < 3600 -> "${seconds / 60} min ago"
    else -> "${seconds / 3600} h ago"
}

fun temp(t: Double) = "${t.roundToInt()}°"

fun Sky.icon() = when (this) {
    Sky.SUNNY -> Icons.Filled.WbSunny
    Sky.PARTLY_CLOUDY -> Icons.Filled.FilterDrama
    Sky.CLOUDY -> Icons.Filled.Cloud
    Sky.FOG -> Icons.Filled.Dehaze
    Sky.RAIN -> Icons.Filled.Umbrella
    Sky.SNOW -> Icons.Filled.AcUnit
    Sky.THUNDER -> Icons.Filled.Thunderstorm
}

fun Sky.label() = when (this) {
    Sky.SUNNY -> "Sunny"
    Sky.PARTLY_CLOUDY -> "Partly cloudy"
    Sky.CLOUDY -> "Cloudy"
    Sky.FOG -> "Fog"
    Sky.RAIN -> "Rain"
    Sky.SNOW -> "Snow"
    Sky.THUNDER -> "Thunderstorms"
}

/** Icon for the kind of stop the station search reports ("train", "bus", "tram", "ship", "cableway"). */
fun stationIcon(kind: String?) = when (kind) {
    "bus" -> Icons.Filled.DirectionsBus
    "tram" -> Icons.Filled.Tram
    "ship" -> Icons.Filled.DirectionsBoat
    "cableway" -> Icons.Filled.Cable
    else -> Icons.Filled.Train
}

/** The occupancy forecast levels of the Swiss timetable. */
fun capacityLabel(level: Int) = when (level) {
    1 -> "Low occupancy"
    2 -> "Medium occupancy"
    else -> "High occupancy"
}

/**
 * A thick line across (cancelled, delayed or moved), easier to see than the font's own strike-through. [color]
 * defaults to the text color around it.
 */
@Composable
fun Modifier.struck(on: Boolean = true, color: Color = LocalContentColor.current): Modifier =
    if (!on) this else drawWithContent {
        drawContent()
        val y = size.height / 2
        val inset = 1.dp.toPx()
        drawLine(color, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    }
