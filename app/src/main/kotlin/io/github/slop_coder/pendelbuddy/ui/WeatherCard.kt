package io.github.slop_coder.pendelbuddy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.slop_coder.pendelbuddy.core.DayWeather
import io.github.slop_coder.pendelbuddy.core.Freshness
import io.github.slop_coder.pendelbuddy.core.Intensity
import io.github.slop_coder.pendelbuddy.core.Sky
import io.github.slop_coder.pendelbuddy.core.toSwiss

/** Width of the place names on the left; the chart starts after it so the hours line up. */
private val LABEL = 44.dp

/**
 * The picked day's weather at the start and at the destination in one chart: per place a row of icons and
 * temperatures every two hours, a temperature curve in that place's color, and rain or snow as bars (darker =
 * heavier, one half per place). A dashed line marks the time shown.
 */
@Composable
fun WeatherCard(places: List<Pair<String, DayWeather>>, now: Long, at: Long) {
    // Start and destination: two colors that stay apart in light and dark mode.
    val colors = listOf(Color(0xFFFF9800), Color(0xFF29B6F6))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            places.forEachIndexed { i, (name, w) -> IconRow(name, w, colors[i % colors.size]) }
            Row {
                Spacer(Modifier.width(LABEL))
                Chart(places.map { it.second }, colors, at, Modifier.weight(1f).height(48.dp))
            }
            HourLabels(places.first().second)
            Legend(places.map { it.second })
            val oldest = places.minOf { it.second.fetchedAt }
            if (now - oldest > Freshness.WEATHER) {
                Text(
                    "Forecast from ${hm(oldest)}" + if (oldest.toSwiss().toLocalDate() != now.toSwiss().toLocalDate()) " yesterday" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Place name in its curve's color, then an icon and the temperature every two hours. */
@Composable
private fun IconRow(name: String, w: DayWeather, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            Modifier.width(LABEL),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(0.5f))
        for (h in w.hours.filter { it.time.toSwiss().hour % 2 == 0 }) {
            Row(Modifier.weight(2f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Sky.ofWmo(h.code).icon(), null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${Math.round(h.temp)}", style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.weight(0.5f))
    }
}

@Composable
private fun HourLabels(w: DayWeather) {
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(LABEL))
        Spacer(Modifier.weight(0.5f))
        for (h in w.hours.filter { it.time.toSwiss().hour % 2 == 0 }) {
            Text(
                "%02d".format(h.time.toSwiss().hour),
                Modifier.weight(2f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(0.5f))
    }
}

/** Only on wet days: what the bar colors mean and the heaviest hour. */
@Composable
private fun Legend(days: List<DayWeather>) {
    val wet = days.flatMap { it.hours }.filter { it.intensity() != null }
    if (wet.isEmpty()) return
    val snow = wet.any { Sky.ofWmo(it.code) == Sky.SNOW }
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (snow) "Rain/snow" else "Rain", style = MaterialTheme.typography.labelSmall)
        for (i in Intensity.entries) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(8.dp).background(rainColor(i)))
            Spacer(Modifier.width(3.dp))
            Text(intensityLabel(i), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(8.dp))
        Text("· max %.1f mm/h".format(wet.maxOf { it.precipitation }), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Chart(days: List<DayWeather>, colors: List<Color>, at: Long, modifier: Modifier) {
    val marker = MaterialTheme.colorScheme.onSurfaceVariant
    val first = DayWeather.FIRST_HOUR
    val span = (DayWeather.LAST_HOUR - first + 1).toFloat()
    val all = days.flatMap { it.hours }
    val lo = all.minOf { it.temp } - 1
    val hi = all.maxOf { it.temp } + 1
    Canvas(modifier) {
        // x of the middle of an hour; the chart spans FIRST_HOUR:00 to LAST_HOUR+1:00.
        fun x(hour: Float) = (hour - first + 0.5f) / span * size.width
        val slot = size.width / span
        // Rain bars: one half of each hour per place, scaled to the wettest hour (at least 2 mm/h).
        val half = slot * 0.8f / days.size
        val top = maxOf(2.0, all.maxOf { it.precipitation })
        days.forEachIndexed { p, w ->
            for (h in w.hours) {
                val i = h.intensity() ?: continue
                val barH = (h.precipitation.coerceIn(0.15, top) / top * size.height * 0.6).toFloat()
                val left = x(h.time.toSwiss().hour.toFloat()) - slot * 0.4f + p * half
                drawRect(rainColor(i), Offset(left, size.height - barH), Size(half, barH))
            }
        }
        days.forEachIndexed { p, w ->
            val path = Path()
            w.hours.forEachIndexed { idx, h ->
                val px = x(h.time.toSwiss().hour.toFloat())
                val py = (size.height * 0.9f - ((h.temp - lo) / (hi - lo) * size.height * 0.8f)).toFloat()
                if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, colors[p % colors.size], style = Stroke(width = 2.dp.toPx()))
        }
        val t = at.toSwiss()
        val hour = t.hour + t.minute / 60f
        if (hour >= first && hour <= DayWeather.LAST_HOUR + 1) {
            val mx = x(hour - 0.5f)
            drawLine(marker, Offset(mx, 0f), Offset(mx, size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
    }
}

fun intensityLabel(i: Intensity) = when (i) {
    Intensity.LIGHT -> "light"
    Intensity.MODERATE -> "moderate"
    Intensity.HEAVY -> "heavy"
}

fun rainColor(i: Intensity) = when (i) {
    Intensity.LIGHT -> Color(0xFF90CAF9)
    Intensity.MODERATE -> Color(0xFF42A5F5)
    Intensity.HEAVY -> Color(0xFF1565C0)
}
