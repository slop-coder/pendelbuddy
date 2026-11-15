package io.github.slop_coder.pendelbuddy.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.slop_coder.pendelbuddy.core.DayWeather
import io.github.slop_coder.pendelbuddy.core.Freshness
import io.github.slop_coder.pendelbuddy.core.Intensity
import io.github.slop_coder.pendelbuddy.core.Sky
import io.github.slop_coder.pendelbuddy.core.toSwiss

/**
 * The picked day's weather at the destination: a summary line, an icon and temperature every two hours,
 * the temperature curve and rain or snow as bars (darker = heavier), with a marker at the time shown.
 */
@Composable
fun WeatherCard(w: DayWeather, place: String, now: Long, at: Long) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(w.sky.icon(), w.sky.label(), Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "${shortPlace(place)} · ${w.sky.label()} · ${temp(w.min)} – ${temp(w.max)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            for (p in w.precipitation) {
                Text(
                    "${if (p.snow) "Snow" else "Rain"} %02d–%02d h, %s".format(p.fromHour, p.toHour, intensityLabel(p.intensity)),
                    style = MaterialTheme.typography.bodySmall,
                    color = rainColor(p.intensity),
                )
            }
            Spacer(Modifier.size(6.dp))
            // Every two hours: weather icon and temperature, centred over that hour in the chart below.
            val labelled = w.hours.filter { it.time.toSwiss().hour % 2 == 0 }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(0.5f))
                for (h in labelled) {
                    Column(Modifier.weight(2f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Sky.ofWmo(h.code).icon(), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(temp(h.temp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.weight(0.5f))
            }
            Chart(w, at, Modifier.fillMaxWidth().height(56.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(0.5f))
                for (h in labelled) {
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
            if (now - w.fetchedAt > Freshness.WEATHER) {
                Text(
                    "Forecast from ${hm(w.fetchedAt)}" + if (w.fetchedAt.toSwiss().toLocalDate() != now.toSwiss().toLocalDate()) " yesterday" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The place name without its town part ("Town, Stop" → "Stop"), so the line stays short. */
private fun shortPlace(name: String) = name.substringAfter(", ")

@Composable
private fun Chart(w: DayWeather, at: Long, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.onSurfaceVariant
    val first = DayWeather.FIRST_HOUR
    val span = (DayWeather.LAST_HOUR - first + 1).toFloat()
    val lo = w.hours.minOf { it.temp } - 1
    val hi = w.hours.maxOf { it.temp } + 1
    Canvas(modifier) {
        // x of the middle of an hour; the chart spans FIRST_HOUR:00 to LAST_HOUR+1:00.
        fun x(hour: Float) = (hour - first + 0.5f) / span * size.width
        val slot = size.width / span
        for (h in w.hours) {
            val i = h.intensity() ?: continue
            val mm = h.precipitation.coerceIn(0.3, 6.0)
            val barH = (mm / 6.0 * size.height * 0.6).toFloat()
            val cx = x(h.time.toSwiss().hour.toFloat())
            drawRect(rainColor(i), Offset(cx - slot * 0.35f, size.height - barH), androidx.compose.ui.geometry.Size(slot * 0.7f, barH))
        }
        val path = Path()
        w.hours.forEachIndexed { idx, h ->
            val px = x(h.time.toSwiss().hour.toFloat())
            val py = (size.height * 0.9f - ((h.temp - lo) / (hi - lo) * size.height * 0.8f)).toFloat()
            if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
        // The time the trips are shown for.
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
