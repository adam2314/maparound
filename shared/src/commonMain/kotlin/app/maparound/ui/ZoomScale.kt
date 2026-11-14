package app.maparound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.maparound.i18n.T
import app.maparound.i18n.ltr
import app.maparound.map.MapSurface
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** How long the scale's bars may be, at most. */
private val SCALE_MAX = 52.dp

/** The track's height between the + and − buttons. */
private val TRACK = 150.dp

/**
 * The map's zoom as a vertical slider for the button column: + at the top, − at the bottom, and
 * between them a track whose thumb shows the scale now (a bar as long as a round distance, metric
 * above it and imperial below). Dragging along the track zooms; a tap on it jumps there.
 */
@Composable
fun ZoomScale(map: MapSurface, modifier: Modifier = Modifier) {
    val range = map.zoomRange
    var zoom by remember { mutableDoubleStateOf(map.zoomLevel()) }
    var metresPerDp by remember { mutableDoubleStateOf(map.metresPerDp()) }
    // The map moves by itself too (pinch, animations): follow it.
    LaunchedEffect(map) {
        while (true) {
            zoom = map.zoomLevel()
            metresPerDp = map.metresPerDp()
            delay(80)
        }
    }
    fun zoomTo(z: Double) {
        val c = z.coerceIn(range.start, range.endInclusive)
        map.setZoom(c)
        zoom = c
        metresPerDp = map.metresPerDp()
    }

    Column(modifier, horizontalAlignment = Alignment.End) {
        SmallFloatingActionButton(onClick = { zoomTo(floor(zoom + 1.0001)) }) { Icon(Icons.Filled.Add, contentDescription = T.map.zoomIn) }
        var trackPx by remember { mutableStateOf(1) }
        val density = LocalDensity.current
        fun zoomAt(y: Float) = range.endInclusive - (y / trackPx).coerceIn(0f, 1f) * (range.endInclusive - range.start)
        BoxWithConstraints(
            Modifier.padding(vertical = 4.dp).width(SCALE_MAX + 16.dp).height(TRACK)
                .onSizeChanged { trackPx = it.height.coerceAtLeast(1) }
                .pointerInput(range) { detectTapGestures { zoomTo(zoomAt(it.y)) } }
                .pointerInput(range) { detectVerticalDragGestures { change, _ -> change.consume(); zoomTo(zoomAt(change.position.y)) } },
        ) {
            // The track, under the buttons' middle: a rail that shows on light and dark maps.
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = 16.dp).width(8.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), RoundedCornerShape(4.dp)),
            )
            val fraction = ((range.endInclusive - zoom) / (range.endInclusive - range.start)).toFloat().coerceIn(0f, 1f)
            val thumbH = 40.dp
            val top: Dp = with(density) { (fraction * trackPx).toDp() } - thumbH / 2
            Surface(
                Modifier.align(Alignment.TopEnd).offset(y = top.coerceIn(-thumbH / 2, TRACK - thumbH / 2)),
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            ) {
                ScaleBars(metresPerDp)
            }
        }
        SmallFloatingActionButton(onClick = { zoomTo(kotlin.math.ceil(zoom - 1.0001)) }) { Icon(Icons.Filled.Remove, contentDescription = T.map.zoomOut) }
    }
}

/** A round metric distance and a round imperial one, as bars as long as they are on the map. */
@Composable
private fun ScaleBars(metresPerDp: Double) {
    val maxM = metresPerDp * SCALE_MAX.value
    val metres = nice(maxM)
    val feet = maxM / 0.3048
    val imperial = if (feet >= 5280) nice(feet / 5280) * 1609.344 else nice(feet) * 0.3048
    val color = MaterialTheme.colorScheme.onSurface
    Column(Modifier.padding(horizontal = 6.dp, vertical = 3.dp).width(SCALE_MAX + 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(ltr(metricLabel(metres)), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
        Box(Modifier.width((metres / metresPerDp).dp.coerceAtMost(SCALE_MAX)).height(2.dp).background(color))
        Box(Modifier.size(1.dp))
        Box(Modifier.width((imperial / metresPerDp).dp.coerceAtMost(SCALE_MAX)).height(2.dp).background(color.copy(alpha = 0.6f)))
        Text(ltr(imperialLabel(imperial)), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** The largest 1, 2 or 5 × 10ⁿ not over [v]. */
internal fun nice(v: Double): Double {
    if (v <= 0) return 0.0
    val p = 10.0.pow(floor(log10(v)))
    val f = v / p
    return p * when {
        f >= 5 -> 5.0
        f >= 2 -> 2.0
        else -> 1.0
    }
}

internal fun metricLabel(m: Double): String =
    if (m >= 1000) "${amount(m / 1000)} ${T.common.unitKm}" else "${amount(m)} ${T.common.unitM}"

internal fun imperialLabel(m: Double): String {
    val feet = m / 0.3048
    return if (feet >= 5280 * 0.99) "${amount(m / 1609.344)} ${T.common.unitMi}" else "${amount(feet)} ${T.common.unitFt}"
}

private fun amount(v: Double): String =
    if (v >= 1 || v == 0.0) v.roundToInt().toString() else ((v * 10).roundToInt() / 10.0).toString()
