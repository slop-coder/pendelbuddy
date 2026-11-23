package io.github.slop_coder.pendelbuddy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.slop_coder.pendelbuddy.core.DayWeather
import io.github.slop_coder.pendelbuddy.core.Freshness
import io.github.slop_coder.pendelbuddy.core.Intensity
import io.github.slop_coder.pendelbuddy.core.Sky
import io.github.slop_coder.pendelbuddy.core.toSwiss

/** Width of the place names on the left; the chart starts after it so the hours line up. */
private val LABEL = 54.dp

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
            Spacer(Modifier.size(4.dp))
            Chart(places.map { it.second }, colors, at, Modifier.fillMaxWidth().height(110.dp))
            HourLabels(places.first().second)
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
            style = MaterialTheme.typography.titleSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(0.5f))
        for (h in w.hours.filter { it.time.toSwiss().hour % 2 == 0 }) {
            Column(Modifier.weight(2f), horizontalAlignment = Alignment.CenterHorizontally) {
                val sky = Sky.ofWmo(h.code)
                Icon(sky.icon(), sky.label(), Modifier.size(20.dp), tint = skyColor(sky))
                Text("${Math.round(h.temp)}°", style = MaterialTheme.typography.labelMedium)
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
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(0.5f))
    }
}

@Composable
private fun Chart(days: List<DayWeather>, colors: List<Color>, at: Long, modifier: Modifier) {
    val marker = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val scaleStyle = MaterialTheme.typography.labelMedium.copy(color = marker)
    val rainStyle = MaterialTheme.typography.labelSmall.copy(color = rainColor(Intensity.MODERATE))
    val first = DayWeather.FIRST_HOUR
    val span = (DayWeather.LAST_HOUR - first + 1).toFloat()
    val all = days.flatMap { it.hours }
    val lo = all.minOf { it.temp } - 1
    val hi = all.maxOf { it.temp } + 1
    Canvas(modifier) {
        // The temperature scale sits in the label column on the left; the hours start after it.
        val left = LABEL.toPx()
        val width = size.width - left
        fun x(hour: Float) = left + (hour - first + 0.5f) / span * width
        fun y(t: Double) = (size.height * 0.9f - ((t - lo) / (hi - lo) * size.height * 0.8f)).toFloat()
        val slot = width / span
        // Rain bars grow from the bottom; their scale is read off the same two guide lines as the temperature.
        val top = maxOf(2.0, all.maxOf { it.precipitation })
        fun barHeight(mm: Double) = (mm / top * size.height * 0.6).toFloat()
        val wet = all.any { it.intensity() != null }
        for (t in listOf(all.maxOf { it.temp }, all.minOf { it.temp })) {
            val ty = y(t)
            val pad = 4.dp.toPx()
            val tl = measurer.measure("${Math.round(t)}°", scaleStyle)
            drawText(tl, topLeft = Offset(left - tl.size.width - pad, ty - tl.size.height))
            if (wet) {
                val mm = (size.height - ty) / (size.height * 0.6f) * top
                val rl = measurer.measure("%.1f mm".format(mm), rainStyle)
                drawText(rl, topLeft = Offset(left - rl.size.width - pad, ty))
            }
            drawLine(marker.copy(alpha = 0.3f), Offset(left, ty), Offset(size.width, ty), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f)))
        }
        // Rain bars: one half of each hour per place.
        val half = slot * 0.8f / days.size
        days.forEachIndexed { p, w ->
            for (h in w.hours) {
                val i = h.intensity() ?: continue
                val barH = barHeight(h.precipitation.coerceIn(0.15, top))
                val left = x(h.time.toSwiss().hour.toFloat()) - slot * 0.4f + p * half
                drawRect(rainColor(i), Offset(left, size.height - barH), Size(half, barH))
            }
        }
        days.forEachIndexed { p, w ->
            val path = Path()
            w.hours.forEachIndexed { idx, h ->
                val px = x(h.time.toSwiss().hour.toFloat())
                val py = y(h.temp)
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

/** Sun yellow, rain blue, snow light blue, thunder purple, clouds and fog grey. */
fun skyColor(sky: Sky) = when (sky) {
    Sky.SUNNY -> Color(0xFFFFC107)
    Sky.PARTLY_CLOUDY -> Color(0xFFFFB74D)
    Sky.CLOUDY, Sky.FOG -> Color(0xFF9E9E9E)
    Sky.RAIN -> Color(0xFF42A5F5)
    Sky.SNOW -> Color(0xFFB3E5FC)
    Sky.THUNDER -> Color(0xFFAB47BC)
}

fun rainColor(i: Intensity) = when (i) {
    Intensity.LIGHT -> Color(0xFF90CAF9)
    Intensity.MODERATE -> Color(0xFF42A5F5)
    Intensity.HEAVY -> Color(0xFF1565C0)
}
