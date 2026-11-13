package app.maparound.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.maparound.data.LocalPrefs
import app.maparound.i18n.T
import app.maparound.sensors.LocalDeviceSensors
import app.maparound.sensors.SpeedSession
import app.maparound.sensors.SpeedUnit
import app.maparound.sensors.TripStats
import app.maparound.shared.util.localFixed
import app.maparound.shared.util.padded
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Ground speed from GPS, direction of travel and trip statistics. Keeps the screen on while open. */
@Composable
fun SpeedScreen(hasLocation: Boolean, requestLocation: () -> Unit) {
    if (!hasLocation) {
        MessageScreen(
            Icons.Filled.LocationOff,
            T.sensors.locationNeeded,
            T.sensors.speedNeedsLocation,
            action = T.sensors.allowLocation,
            onAction = requestLocation,
        )
        return
    }
    val sensors = LocalDeviceSensors.current
    val prefs = LocalPrefs.current
    KeepScreenOn()
    val locations = remember { sensors.locations() }
    val location by locations.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(location) {
        val l = location ?: return@LaunchedEffect
        if (!l.satellite) return@LaunchedEffect // network fixes have no usable speed
        SpeedSession.add(
            TripStats.Fix(l.timeMs, l.lat, l.lon, l.speedMps?.toDouble(), l.accuracyM?.toDouble()),
            elapsedMs = l.elapsedMs,
            speedAccuracyMps = l.speedAccuracyMps,
            courseDeg = l.bearingDeg,
        )
    }
    val now by produceState(sensors.elapsedMs()) {
        while (true) {
            delay(1000)
            value = sensors.elapsedMs()
        }
    }
    val s by SpeedSession.state.collectAsState()
    val unit by prefs.speedUnit.collectAsState()
    val fresh = s.fixAtElapsedMs > 0 && now - s.fixAtElapsedMs < 5_000

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(T.sensors.groundSpeedGps, style = MaterialTheme.typography.labelLarge)
                val speed = s.speedMps?.takeIf { fresh }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        speed?.let { fmtSpeed(it, unit) } ?: "—",
                        fontSize = 88.sp,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.displayLarge,
                    )
                    Text(unit.label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp, bottom = 18.dp))
                }
                Text(
                    when {
                        !fresh && s.fixAtElapsedMs == 0L -> T.sensors.waitingForGps
                        !fresh -> T.sensors.gpsFixLost((now - s.fixAtElapsedMs) / 1000)
                        else -> listOfNotNull(
                            s.courseDeg?.let { T.sensors.heading(it.roundToInt(), Fmt.cardinal(it)) },
                            s.speedAccuracyMps?.let { "±${fmtSpeed(it.toDouble(), unit)} ${unit.label}" },
                            speed?.takeIf { it in 0.3..8.0 }?.let { T.sensors.pace(pace(it)) },
                        ).joinToString(" · ").ifEmpty { T.sensors.stopped }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                SingleChoiceSegmentedButtonRow(Modifier.padding(top = 12.dp)) {
                    SpeedUnit.entries.forEachIndexed { i, u ->
                        SegmentedButton(
                            selected = unit == u,
                            onClick = { prefs.setSpeedUnit(u) },
                            shape = SegmentedButtonDefaults.itemShape(i, SpeedUnit.entries.size),
                            icon = {},
                        ) { Text(u.label) }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(T.sensors.trip, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { SpeedSession.reset() }) { Text(T.sensors.reset) }
                }
                InfoRow(T.sensors.distance, Fmt.distance(s.distanceM.toFloat()))
                InfoRow(T.sensors.maxSpeed, "${fmtSpeed(s.maxMps, unit)} ${unit.label}")
                InfoRow(T.sensors.averageWhileMoving, s.averageMovingMps?.let { "${fmtSpeed(it, unit)} ${unit.label}" } ?: "—")
                InfoRow(T.sensors.movingTime, Fmt.clock(s.movingMs / 1000))
                InfoRow(T.sensors.totalTime, Fmt.clock(s.elapsedMs / 1000))
                Text(
                    T.sensors.tripHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(T.sensors.speedLast10Minutes, style = MaterialTheme.typography.labelLarge)
                SpeedChart(s.history, unit, now, Modifier.fillMaxWidth().height(160.dp))
            }
        }
    }
}

private fun fmtSpeed(mps: Double, unit: SpeedUnit): String {
    val v = mps * unit.perMps
    return if (v < 10) localFixed(v, 1) else v.roundToInt().toString()
}

/** Minutes per kilometre, e.g. "12:30 /km". */
private fun pace(mps: Double): String {
    val secPerKm = (1000 / mps).roundToInt()
    return T.sensors.perKm("${secPerKm / 60}:${padded(secPerKm % 60, 2)}")
}

@Composable
private fun SpeedChart(history: List<Pair<Long, Float>>, unit: SpeedUnit, now: Long, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val waiting = T.sensors.waitingForData
    val nowLabel = T.sensors.now
    Canvas(modifier) {
        if (history.size < 2) {
            drawText(measurer, waiting, topLeft = Offset(0f, size.height / 2 - 8.dp.toPx()), style = label)
            return@Canvas
        }
        val window = 10 * 60_000f
        val top = max(history.maxOf { it.second } * unit.perMps.toFloat() * 1.15f, 5f)
        fun x(t: Long) = size.width - (now - t) / window * size.width
        fun y(v: Float) = size.height - v * unit.perMps.toFloat() / top * size.height
        for (i in 0..2) drawLine(grid, Offset(0f, size.height * i / 2), Offset(size.width, size.height * i / 2), strokeWidth = 1.dp.toPx())
        val path = Path()
        history.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
        val fill = Path().apply {
            addPath(path)
            lineTo(x(history.last().first), size.height)
            lineTo(x(history.first().first), size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.3f), line.copy(alpha = 0.03f))))
        drawPath(path, line, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
        drawText(measurer, "${top.roundToInt()} ${unit.label}", topLeft = Offset(4.dp.toPx(), 2.dp.toPx()), style = label)
        val t = measurer.measure(nowLabel, label)
        drawText(t, topLeft = Offset(size.width - t.size.width - 4.dp.toPx(), size.height - t.size.height))
    }
}
