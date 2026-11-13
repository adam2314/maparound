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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.maparound.data.LocalPrefs
import app.maparound.data.Prefs
import app.maparound.i18n.T
import app.maparound.i18n.ltr
import app.maparound.sensors.Barometric
import app.maparound.sensors.GeoFix
import app.maparound.sensors.LocalDeviceSensors
import app.maparound.sensors.PressureHistory
import app.maparound.shared.util.localFixed
import app.maparound.shared.util.localSigned
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun BarometerScreen(hasLocation: Boolean, requestLocation: () -> Unit) {
    val sensors = LocalDeviceSensors.current
    val available = remember { sensors.hasBarometer }
    if (!available) {
        MessageScreen(
            Icons.Filled.Speed,
            T.sensors.noBarometer,
            T.sensors.noBarometerBody,
        )
        return
    }
    val prefs = LocalPrefs.current
    val pressureFlow = remember { sensors.pressure() }
    val raw by pressureFlow.collectAsStateWithLifecycle(initialValue = null)
    var smoothed by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(raw) {
        val r = raw ?: return@LaunchedEffect
        val s = smoothed?.let { it + 0.2f * (r - it) } ?: r
        smoothed = s
        PressureHistory.add(s)
    }
    val seaLevel by prefs.seaLevelPressure.collectAsState()
    val history by PressureHistory.samples.collectAsState()
    var showCalibrate by remember { mutableStateOf(false) }

    val pressure = smoothed
    val altitude = pressure?.let { Barometric.altitude(seaLevel, it) }
    val calibrated = abs(seaLevel - Prefs.STANDARD_PRESSURE) > 0.01f

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text(T.sensors.airPressure, style = MaterialTheme.typography.labelLarge)
                Text(
                    pressure?.let { "${Fmt.oneDecimal(it)} hPa" } ?: "—",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (pressure != null) {
                    Text(
                        T.sensors.otherPressureUnits(localFixed(pressure * 0.02953, 2), localFixed(pressure * 0.750062, 0)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(T.sensors.barometricAltitude, style = MaterialTheme.typography.labelLarge)
                Text(
                    altitude?.let { "${ltr(it.roundToInt().toString())} ${T.common.unitM}" } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    T.sensors.seaLevelReference(Fmt.oneDecimal(seaLevel), calibrated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { showCalibrate = true }, enabled = pressure != null) { Text(T.sensors.calibrate) }
                    if (calibrated) {
                        TextButton(onClick = { prefs.setSeaLevelPressure(Prefs.STANDARD_PRESSURE) }) { Text(T.sensors.reset) }
                    }
                }
                if (!calibrated) {
                    Text(
                        T.sensors.uncalibratedWarning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(T.sensors.trend, style = MaterialTheme.typography.labelLarge)
                val trend = PressureHistory.trendPerHour(history)
                Text(
                    if (trend == null) {
                        T.sensors.collectingTrend
                    } else {
                        T.sensors.trendPerHour(localSigned(trend.toDouble(), 1), trendLabel(trend))
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                PressureChart(history, Modifier.fillMaxWidth().height(180.dp))
                Text(
                    T.sensors.trendHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showCalibrate && pressure != null) {
        CalibrateDialog(
            pressure = pressure,
            hasLocation = hasLocation,
            requestLocation = requestLocation,
            onDismiss = { showCalibrate = false },
            onApply = {
                prefs.setSeaLevelPressure(it)
                showCalibrate = false
            },
        )
    }
}

private fun trendLabel(hPaPerHour: Float) = when {
    hPaPerHour <= -2f -> T.sensors.fallingFast
    hPaPerHour <= -0.5f -> T.sensors.falling
    hPaPerHour < 0.5f -> T.sensors.steady
    hPaPerHour < 2f -> T.sensors.rising
    else -> T.sensors.risingFast
}

/** Sea-level pressure that makes the current reading correspond to [altitudeM] (inverse barometric formula). */
private fun seaLevelFor(pressure: Float, altitudeM: Double): Float =
    (pressure / (1.0 - altitudeM / 44330.0).pow(5.255)).toFloat()

@Composable
private fun CalibrateDialog(
    pressure: Float,
    hasLocation: Boolean,
    requestLocation: () -> Unit,
    onDismiss: () -> Unit,
    onApply: (Float) -> Unit,
) {
    val sensors = LocalDeviceSensors.current
    var altitudeText by remember { mutableStateOf("") }
    var qnhText by remember { mutableStateOf("") }
    val locationFlow: Flow<GeoFix?> = remember(hasLocation) {
        if (hasLocation) sensors.locations() else flowOf(null)
    }
    val location by locationFlow.collectAsStateWithLifecycle(initialValue = null)
    val gpsAltitude = location?.takeIf { it.satellite }?.altitudeM

    val altitude = altitudeText.replace(',', '.').toDoubleOrNull()?.takeIf { it in -500.0..9000.0 }
    val qnh = qnhText.replace(',', '.').toFloatOrNull()?.takeIf { it in 850f..1100f }
    val result = qnh ?: altitude?.let { seaLevelFor(pressure, it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(T.sensors.calibrateAltimeter) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    T.sensors.calibrateIntro,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    altitudeText, { altitudeText = it; qnhText = "" },
                    label = { Text(T.sensors.knownElevation) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                when {
                    gpsAltitude != null -> TextButton(onClick = {
                        altitudeText = gpsAltitude.roundToInt().toString()
                        qnhText = ""
                    }) { Text(T.sensors.useGpsAltitude(gpsAltitude.roundToInt())) }
                    !hasLocation -> TextButton(onClick = requestLocation) { Text(T.sensors.allowLocationForGpsAltitude) }
                    else -> Text(T.sensors.waitingForGpsAltitude, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    qnhText, { qnhText = it; altitudeText = "" },
                    label = { Text(T.sensors.orSeaLevelPressure) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = result != null, onClick = { result?.let(onApply) }) { Text(T.sensors.apply) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(T.common.cancel) } },
    )
}

@Composable
private fun PressureChart(samples: List<PressureHistory.Sample>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val texts = T.sensors

    Canvas(modifier) {
        if (samples.size < 2) {
            drawText(measurer, texts.waitingForData, topLeft = Offset(0f, size.height / 2f - 8.dp.toPx()), style = labelStyle)
            return@Canvas
        }
        val minP = samples.minOf { it.hPa }
        val maxP = samples.maxOf { it.hPa }
        val mid = (minP + maxP) / 2f
        val half = max((maxP - minP) / 2f * 1.2f, 0.5f)
        val lo = mid - half
        val hi = mid + half
        val t0 = samples.first().timeMs
        val span = (samples.last().timeMs - t0).coerceAtLeast(1L).toFloat()

        for (i in 0..2) {
            val y = size.height * i / 2f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        val path = Path()
        samples.forEachIndexed { i, s ->
            val x = (s.timeMs - t0) / span * size.width
            val y = size.height - (s.hPa - lo) / (hi - lo) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        drawText(measurer, Fmt.oneDecimal(hi), topLeft = Offset(4.dp.toPx(), 2.dp.toPx()), style = labelStyle)
        drawText(measurer, Fmt.oneDecimal(lo), topLeft = Offset(4.dp.toPx(), size.height - 16.dp.toPx()), style = labelStyle)
        val minutes = (span / 60_000f).roundToInt()
        val spanLabel = if (minutes >= 90) texts.lastHours(Fmt.oneDecimal(minutes / 60f)) else texts.lastMinutes(minutes)
        val layout = measurer.measure(spanLabel, labelStyle)
        drawText(layout, topLeft = Offset(size.width - layout.size.width - 4.dp.toPx(), 2.dp.toPx()))
    }
}
