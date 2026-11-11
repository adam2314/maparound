package app.maparound.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.routing.TrackStats
import kotlin.math.max
import kotlin.math.roundToInt

/** Elevation along a route: distance on x, height on y. Draws nothing without heights. */
@Composable
fun ElevationProfile(points: List<GeoPoint>, modifier: Modifier = Modifier) {
    // (cumulative distance, elevation) pairs, thinned to at most ~400 for drawing.
    val series = remember(points) {
        val out = ArrayList<Pair<Double, Double>>()
        var d = 0.0
        points.forEachIndexed { i, p ->
            if (i > 0) d += Geo.distance(points[i - 1], p)
            p.ele?.let { out += d to it }
        }
        val step = max(1, out.size / 400)
        out.filterIndexed { i, _ -> i % step == 0 || i == out.lastIndex }
    }
    if (series.size < 2) return

    val measurer = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)

    Canvas(modifier) {
        val total = series.last().first.coerceAtLeast(1.0)
        val lo = series.minOf { it.second }
        val hi = series.maxOf { it.second }
        val pad = max((hi - lo) * 0.1, 10.0)
        val bottom = lo - pad
        val range = (hi + pad) - bottom
        fun x(d: Double) = (d / total * size.width).toFloat()
        fun y(e: Double) = (size.height - (e - bottom) / range * size.height).toFloat()

        drawLine(grid, Offset(0f, y(hi)), Offset(size.width, y(hi)), strokeWidth = 1.dp.toPx())
        drawLine(grid, Offset(0f, y(lo)), Offset(size.width, y(lo)), strokeWidth = 1.dp.toPx())

        val path = Path()
        series.forEachIndexed { i, (d, e) -> if (i == 0) path.moveTo(x(d), y(e)) else path.lineTo(x(d), y(e)) }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), line.copy(alpha = 0.05f))))
        drawPath(path, line, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))

        drawText(measurer, "${hi.roundToInt()} m", topLeft = Offset(4.dp.toPx(), y(hi) + 1.dp.toPx()), style = label)
        drawText(measurer, "${lo.roundToInt()} m", topLeft = Offset(4.dp.toPx(), y(lo) - 15.dp.toPx()), style = label)
        val km = measurer.measure(Fmt.distance(total.toFloat()), label)
        drawText(km, topLeft = Offset(size.width - km.size.width - 4.dp.toPx(), size.height - km.size.height))
    }
}

/** "12.4 km · ↑ 540 m ↓ 320 m · 4 h 10 min" */
@Composable
fun StatsLine(stats: TrackStats, durationS: Long?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Fmt.distance(stats.distanceM.toFloat()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        stats.ascentM?.let { ascent ->
            Text("↑ ${ascent.roundToInt()} m  ↓ ${stats.descentM?.roundToInt() ?: 0} m", style = MaterialTheme.typography.titleMedium)
        }
        if (durationS != null && durationS > 0) {
            Text(Fmt.duration(durationS), style = MaterialTheme.typography.titleMedium)
        }
    }
}
