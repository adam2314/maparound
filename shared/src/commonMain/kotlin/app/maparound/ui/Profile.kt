package app.maparound.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.maparound.i18n.T
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.routing.TrackStats
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Elevation along a route: distance on x, height on y. Draws nothing without heights. Pointed at
 * ([pointedAt]), it tells the distance there, the height and (a recorded track) the time.
 */
@Composable
fun ElevationProfile(points: List<GeoPoint>, modifier: Modifier = Modifier) {
    // Cumulative distance, elevation and time, thinned to at most ~400 for drawing.
    class Point(val d: Double, val e: Double, val time: Long?)
    val series = remember(points) {
        val out = ArrayList<Point>()
        var d = 0.0
        points.forEachIndexed { i, p ->
            if (i > 0) d += Geo.distance(points[i - 1], p)
            p.ele?.let { out += Point(d, it, p.time) }
        }
        val step = max(1, out.size / 400)
        out.filterIndexed { i, _ -> i % step == 0 || i == out.lastIndex }
    }
    if (series.size < 2) return

    val measurer = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
    val tip = graphTipStyle()
    val pointed = remember(points) { mutableStateOf<Float?>(null) }

    Canvas(modifier.pointedAt(pointed)) {
        val total = series.last().d.coerceAtLeast(1.0)
        val lo = series.minOf { it.e }
        val hi = series.maxOf { it.e }
        val pad = max((hi - lo) * 0.1, 10.0)
        val bottom = lo - pad
        val range = (hi + pad) - bottom
        fun x(d: Double) = (d / total * size.width).toFloat()
        fun y(e: Double) = (size.height - (e - bottom) / range * size.height).toFloat()

        drawLine(grid, Offset(0f, y(hi)), Offset(size.width, y(hi)), strokeWidth = 1.dp.toPx())
        drawLine(grid, Offset(0f, y(lo)), Offset(size.width, y(lo)), strokeWidth = 1.dp.toPx())

        val path = Path()
        series.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.d), y(p.e)) else path.lineTo(x(p.d), y(p.e)) }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), line.copy(alpha = 0.05f))))
        drawPath(path, line, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))

        drawText(measurer, Fmt.height(hi), topLeft = Offset(4.dp.toPx(), y(hi) + 1.dp.toPx()), style = label)
        drawText(measurer, Fmt.height(lo), topLeft = Offset(4.dp.toPx(), y(lo) - 15.dp.toPx()), style = label)
        val km = measurer.measure(Fmt.distance(total.toFloat()), label)
        drawText(km, topLeft = Offset(size.width - km.size.width - 4.dp.toPx(), size.height - km.size.height))

        // Pointed at: the height there (between the two points around it), and the nearer one's time.
        pointed.value?.let { px ->
            val d = (px / size.width).coerceIn(0f, 1f) * total
            val i = series.indexOfFirst { it.d >= d }.coerceIn(1, series.lastIndex)
            val p0 = series[i - 1]
            val p1 = series[i]
            val f = if (p1.d > p0.d) ((d - p0.d) / (p1.d - p0.d)).coerceIn(0.0, 1.0) else 0.0
            val e = p0.e + (p1.e - p0.e) * f
            val time = if (f < 0.5) p0.time else p1.time
            val text = listOfNotNull(Fmt.distance(d.toFloat()), Fmt.height(e), time?.let(Fmt::timeOfDay)).joinToString(" · ")
            graphTip(measurer, tip, x(d), y(e), listOf(AnnotatedString(text)))
        }
    }
}

/**
 * Where a graph is pointed at, as x in pixels (null: nowhere): under the mouse while it's over the
 * graph, or where a finger taps or slides sideways (it stays after the finger lifts). Up-and-down
 * drags still scroll what the graph is in.
 */
fun Modifier.pointedAt(x: MutableState<Float?>): Modifier = this
    .pointerInput(x) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull() ?: continue
                if (c.type == PointerType.Touch) continue
                when (e.type) {
                    PointerEventType.Enter, PointerEventType.Move -> x.value = c.position.x
                    PointerEventType.Exit -> x.value = null
                }
            }
        }
    }
    .pointerInput(x) { detectTapGestures { x.value = it.x } }
    .pointerInput(x) {
        detectHorizontalDragGestures(onDragStart = { x.value = it.x }) { change, _ ->
            change.consume()
            x.value = change.position.x
        }
    }

/** How a graph's tip looks: like the app's other tooltips. */
class GraphTipStyle(val text: TextStyle, val box: Color, val rule: Color, val dot: Color)

@Composable
fun graphTipStyle(): GraphTipStyle {
    val colors = MaterialTheme.colorScheme
    return GraphTipStyle(
        MaterialTheme.typography.labelMedium.copy(color = colors.inverseOnSurface),
        colors.inverseSurface, colors.onSurfaceVariant.copy(alpha = 0.6f), colors.primary,
    )
}

/** A line in a graph's tip with a [colour]ed dot before it (the graph's colour there). */
fun tipLine(text: String, colour: Color): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = colour)) { append("\u25CF ") }
    append(text)
}

/**
 * A graph's tip at [x]: a line down the graph, a dot on its line at [y] (in [dot], else the
 * style's), and [lines] in a box beside it (on the side with room; at the top unless the dot is
 * up there).
 */
fun DrawScope.graphTip(measurer: TextMeasurer, style: GraphTipStyle, x: Float, y: Float, lines: List<AnnotatedString>, dot: Color? = null) {
    drawLine(style.rule, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
    drawCircle(style.box, 5.dp.toPx(), Offset(x, y))
    drawCircle(dot ?: style.dot, 3.5.dp.toPx(), Offset(x, y))
    val texts = lines.map { measurer.measure(it, style.text, maxLines = 1) }
    val pad = 6.dp.toPx()
    val gap = 8.dp.toPx()
    val w = texts.maxOf { it.size.width } + 2 * pad
    val h = texts.sumOf { it.size.height } + 2 * pad
    val left = if (x + gap + w <= size.width) x + gap else (x - gap - w).coerceAtLeast(0f)
    val top = if (y >= h + gap) 0f else (size.height - h).coerceAtLeast(0f)
    drawRoundRect(style.box, Offset(left, top), Size(w, h), CornerRadius(4.dp.toPx()))
    var ty = top + pad
    for (t in texts) {
        drawText(t, topLeft = Offset(left + pad, ty))
        ty += t.size.height
    }
}

/** "12.4 km · ↑ 540 m ↓ 320 m · 4 h 10 min" */
@Composable
fun StatsLine(stats: TrackStats, durationS: Long?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Fmt.distance(stats.distanceM.toFloat()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        stats.ascentM?.let { ascent ->
            Text("↑ ${Fmt.height(ascent)}  ↓ ${Fmt.height(stats.descentM ?: 0.0)}", style = MaterialTheme.typography.titleMedium)
        }
        if (durationS != null && durationS > 0) {
            Text(Fmt.duration(durationS), style = MaterialTheme.typography.titleMedium)
        }
    }
}
