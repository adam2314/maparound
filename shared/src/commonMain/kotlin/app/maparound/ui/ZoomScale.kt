package app.maparound.ui

import app.maparound.i18n.Units
import app.maparound.i18n.DistanceUnit
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.maparound.i18n.T
import app.maparound.i18n.ltr
import app.maparound.i18n.UnitSystem
import app.maparound.i18n.HeightUnit
import app.maparound.map.ZoomTarget
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** How long the scale line may be, at most. */
private val SCALE_MAX = 100.dp

/** The scale line's height: its label, and the line under it. */
val SCALE_HEIGHT = 30.dp

/** The track's height between the + and − buttons. */
private val TRACK = 150.dp

/** The slider's thumb. */
private val THUMB_W = 28.dp
private val THUMB_H = 16.dp

/** A small floating action button's size. */
private val BUTTON = 40.dp

/**
 * The zoom of the map or the 3D view as a vertical slider for the button column: + at the top, −
 * at the bottom, and between them a track with a thumb at the zoom now. Dragging along the track
 * zooms; a tap on it jumps there. (The scale is [ScaleLine], in the map's corner.)
 *
 * It fits above [bottomLimit] (px in the root's coordinates: where the buttons or panel below it
 * start), as much of it as there's room for: the whole slider (its track shorter if needed), else
 * + and − alone.
 */
@Composable
fun ZoomScale(map: ZoomTarget, modifier: Modifier = Modifier, bottomLimit: Float? = null) {
    val range = map.zoomRange
    var zoom by remember { mutableDoubleStateOf(map.zoomLevel()) }
    // The map moves by itself too (pinch, animations): follow it.
    LaunchedEffect(map) {
        while (true) {
            zoom = map.zoomLevel()
            delay(80)
        }
    }
    fun zoomTo(z: Double) {
        val c = z.coerceIn(range.start, range.endInclusive)
        map.setZoom(c)
        zoom = c
    }

    val density = LocalDensity.current
    var topPx by remember { mutableStateOf(0f) }
    // The room below the slider's top, down to the limit (a margin kept).
    val room: Dp = bottomLimit?.let { with(density) { (it - topPx).toDp() } - 8.dp } ?: Dp.Infinity
    val buttons = BUTTON * 2 + 8.dp // + and −, and the track's padding
    val track = (room - buttons).coerceAtMost(TRACK)

    Column(modifier.onGloballyPositioned { topPx = it.positionInRoot().y }, horizontalAlignment = Alignment.End) {
        if (room < buttons) return@Column
        Tip(T.map.zoomIn) { SmallFloatingActionButton(onClick = { zoomTo(floor(zoom + 1.0001)) }) { Icon(Icons.Filled.Add, contentDescription = T.map.zoomIn) } }
        if (track >= THUMB_H + 24.dp) {
            var trackPx by remember { mutableStateOf(1) }
            val thumbPx = with(density) { THUMB_H.toPx() }
            // The thumb's middle runs from half a thumb below the top to half a thumb above the bottom.
            fun zoomAt(y: Float) =
                range.endInclusive - ((y - thumbPx / 2) / (trackPx - thumbPx).coerceAtLeast(1f)).coerceIn(0f, 1f) * (range.endInclusive - range.start)
            Box(
                Modifier.padding(vertical = 4.dp).width(BUTTON).height(track)
                    .onSizeChanged { trackPx = it.height.coerceAtLeast(1) }
                    .pointerInput(range) { detectTapGestures { zoomTo(zoomAt(it.y)) } }
                    .pointerInput(range) { detectVerticalDragGestures { change, _ -> change.consume(); zoomTo(zoomAt(change.position.y)) } },
            ) {
                // The track, under the buttons' middle: a rail that shows on light and dark maps.
                Box(
                    Modifier.align(Alignment.TopCenter).width(8.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), RoundedCornerShape(4.dp)),
                )
                val fraction = ((range.endInclusive - zoom) / (range.endInclusive - range.start)).toFloat().coerceIn(0f, 1f)
                val top: Dp = with(density) { (fraction * (trackPx - thumbPx).coerceAtLeast(0f)).toDp() }
                Surface(
                    Modifier.align(Alignment.TopCenter).offset(y = top).size(THUMB_W, THUMB_H),
                    shape = RoundedCornerShape(THUMB_H / 2),
                    color = MaterialTheme.colorScheme.primary,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                    shadowElevation = 2.dp,
                ) {}
            }
        } else {
            Box(Modifier.size(8.dp))
        }
        Tip(T.map.zoomOut) { SmallFloatingActionButton(onClick = { zoomTo(kotlin.math.ceil(zoom - 1.0001)) }) { Icon(Icons.Filled.Remove, contentDescription = T.map.zoomOut) } }
    }
}

/**
 * The map's scale as a line in its corner (the side away from the buttons): a round distance in the
 * distance unit chosen (Settings → Units & formats) above a line as long as it is on the map at the
 * view's middle, with a tick at each end; black on a white halo so it reads on any map.
 */
@Composable
fun ScaleLine(map: ZoomTarget, modifier: Modifier = Modifier) {
    var metresPerDp by remember { mutableDoubleStateOf(map.metresPerDp()) }
    LaunchedEffect(map) {
        while (true) {
            metresPerDp = map.metresPerDp()
            delay(80)
        }
    }
    if (!(metresPerDp > 0) || metresPerDp.isInfinite()) return
    val (length, label) = scaleLength(metresPerDp * SCALE_MAX.value)
    val w = (length / metresPerDp).toFloat().coerceAtMost(SCALE_MAX.value)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.size(SCALE_MAX + 8.dp, SCALE_HEIGHT)) {
        Canvas(Modifier.matchParentSize()) {
            val start = 3.dp.toPx()
            val base = size.height - 4.dp.toPx()
            val tick = 8.dp.toPx()
            // From the corner's side: the right in a right-to-left language.
            fun x(dp: Float) = if (rtl) size.width - start - dp.dp.toPx() else start + dp.dp.toPx()
            for ((color, width) in listOf(Color.White to 5.dp.toPx(), Color.Black to 2.dp.toPx())) {
                drawLine(color, Offset(x(0f), base), Offset(x(w), base), width, StrokeCap.Square)
                drawLine(color, Offset(x(0f), base), Offset(x(0f), base - tick), width, StrokeCap.Square)
                drawLine(color, Offset(x(w), base), Offset(x(w), base - tick), width, StrokeCap.Square)
            }
        }
        HaloText(label, Modifier.align(Alignment.TopStart).padding(start = 8.dp))
    }
}

/**
 * The scale's length (metres) and label for a line of at most [maxM] metres, a round number in the
 * distance unit chosen: metres and km; miles, below a fifth of a mile feet (or metres, where heights
 * are in metres); nautical miles, below a tenth of one the same.
 */
fun scaleLength(maxM: Double, units: UnitSystem = Units.current): Pair<Double, String> {
    fun short(): Pair<Double, String> =
        if (units.height == HeightUnit.FT) nice(maxM / Fmt.FOOT_M).let { it * Fmt.FOOT_M to "${amount(it)} ${T.common.unitFt}" }
        else nice(maxM).let { it to metricLabel(it) }
    return when (units.distance) {
        DistanceUnit.METRIC -> nice(maxM).let { it to metricLabel(it) }
        DistanceUnit.IMPERIAL ->
            if (maxM >= 0.2 * Fmt.MILE_M) nice(maxM / Fmt.MILE_M).let { it * Fmt.MILE_M to "${amount(it)} ${T.common.unitMi}" } else short()
        DistanceUnit.NAUTICAL ->
            if (maxM >= 0.1 * Fmt.NAUTICAL_MILE_M) nice(maxM / Fmt.NAUTICAL_MILE_M).let { it * Fmt.NAUTICAL_MILE_M to "${amount(it)} ${T.common.unitNm}" } else short()
    }
}

/** Bold black text on a white halo. */
@Composable
private fun HaloText(text: String, modifier: Modifier) {
    val style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 14.sp)
    val halo = with(LocalDensity.current) { 3.dp.toPx() }
    Box(modifier) {
        Text(ltr(text), style = style.copy(color = Color.White, drawStyle = Stroke(width = halo, join = StrokeJoin.Round)), maxLines = 1)
        Text(ltr(text), style = style.copy(color = Color.Black), maxLines = 1)
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

private fun amount(v: Double): String =
    if (v >= 1 || v == 0.0) v.roundToInt().toString() else ((v * 10).roundToInt() / 10.0).toString()
