package app.maparound.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.maparound.data.LocalPrefs
import app.maparound.i18n.T
import app.maparound.i18n.ltr
import app.maparound.sensors.GeoFix
import app.maparound.sensors.LocalDeviceSensors
import app.maparound.sensors.Orientation
import app.maparound.sensors.SensorAccuracy
import app.maparound.shared.util.localFixed
import app.maparound.shared.util.nowMillis
import app.maparound.shared.util.toRadians
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompassScreen(hasLocation: Boolean, requestLocation: () -> Unit) {
    val sensors = LocalDeviceSensors.current
    val available = remember { sensors.hasCompass }
    if (!available) {
        MessageScreen(Icons.Filled.Explore, T.sensors.noCompass, T.sensors.noCompassBody)
        return
    }
    val prefs = LocalPrefs.current
    val orientationFlow = remember { sensors.orientation() }
    val reading: Orientation? by orientationFlow.collectAsStateWithLifecycle(initialValue = null)
    val trueNorth by prefs.trueNorth.collectAsState()

    val locationFlow: Flow<GeoFix?> = remember(hasLocation) {
        if (hasLocation) sensors.locations(5_000L) else flowOf(null)
    }
    val location by locationFlow.collectAsStateWithLifecycle(initialValue = null)
    val declination: Float? = location?.let { sensors.declination(it.lat, it.lon, it.altitudeM ?: 0.0, nowMillis()) }
    val heading: Float? = reading?.let { r ->
        val correction = if (trueNorth) declination ?: 0f else 0f
        ((r.azimuth + correction) % 360f + 360f) % 360f
    }

    // Unwrapped angle so the dial turns the short way round across 0°/360°.
    var unwrapped by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(heading) {
        val h = heading ?: return@LaunchedEffect
        val delta = ((h - unwrapped) % 360f + 540f) % 360f - 180f
        unwrapped += delta
    }
    val dialRotation by animateFloatAsState(
        targetValue = -unwrapped,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dial",
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            heading?.let { ltr("${it.roundToInt() % 360}°") } ?: "—",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            heading?.let { T.sensors.headingFrom(Fmt.cardinal(it), trueNorth && declination != null) }
                ?: T.sensors.waitingForSensor,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CompassDial(dialRotation, Modifier.fillMaxWidth(0.9f).widthIn(max = 420.dp).aspectRatio(1f))

        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = !trueNorth,
                onClick = { prefs.setTrueNorth(false) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text(T.sensors.magneticNorth) }
            SegmentedButton(
                selected = trueNorth,
                onClick = { prefs.setTrueNorth(true) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text(T.sensors.trueNorth) }
        }
        if (trueNorth && declination == null) {
            Text(
                T.sensors.trueNorthNeedsLocation,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!hasLocation) TextButton(onClick = requestLocation) { Text(T.sensors.allowLocation) }
        }

        val r = reading
        if (r != null && r.accuracy <= SensorAccuracy.LOW) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    T.sensors.needsCalibration,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                InfoRow(T.sensors.sensorAccuracy, r?.let { accuracyLabel(it.accuracy) } ?: "—")
                InfoRow(
                    T.sensors.declination,
                    declination?.let { T.sensors.declinationValue(localFixed(abs(it).toDouble(), 1), east = it >= 0) }
                        ?: T.sensors.needsLocation,
                )
            }
        }
        Text(
            T.sensors.holdFlat,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun accuracyLabel(accuracy: Int) = when (accuracy) {
    SensorAccuracy.HIGH -> T.sensors.accuracyHigh
    SensorAccuracy.MEDIUM -> T.sensors.accuracyMedium
    SensorAccuracy.LOW -> T.sensors.accuracyLow
    SensorAccuracy.UNRELIABLE -> T.sensors.accuracyUnreliable
    else -> T.sensors.accuracyUnknown
}

@Composable
private fun CompassDial(rotation: Float, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val face = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val minor = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val north = Color(0xFFD32F2F)
    val cardinalStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = ink)
    val northStyle = cardinalStyle.copy(color = north)
    val degreeStyle = MaterialTheme.typography.labelMedium.copy(color = minor, fontSize = 12.sp)
    val cardinals = listOf(0, 90, 180, 270).zip(T.sensors.dialCardinals)

    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        drawCircle(face, r, c)
        drawCircle(minor.copy(alpha = 0.4f), r, c, style = Stroke(1.dp.toPx()))

        rotate(rotation, c) {
            for (deg in 0 until 360 step 5) {
                val len = when {
                    deg % 90 == 0 -> 0.13f
                    deg % 30 == 0 -> 0.09f
                    deg % 10 == 0 -> 0.06f
                    else -> 0.035f
                } * r
                val a = toRadians(deg.toDouble())
                val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                val outer = c + dir * (r * 0.97f)
                val inner = c + dir * (r * 0.97f - len)
                drawLine(
                    color = if (deg == 0) north else if (deg % 30 == 0) ink else minor,
                    start = inner,
                    end = outer,
                    strokeWidth = (if (deg % 30 == 0) 2.5f else 1.2f).dp.toPx(),
                )
            }
            cardinals.forEach { (deg, label) ->
                rotate(deg.toFloat(), c) {
                    val layout = measurer.measure(label, if (deg == 0) northStyle else cardinalStyle)
                    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - r * 0.80f))
                }
            }
            for (deg in 30 until 360 step 30) {
                if (deg % 90 == 0) continue
                rotate(deg.toFloat(), c) {
                    val layout = measurer.measure(deg.toString(), degreeStyle)
                    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - r * 0.78f))
                }
            }
            // Needle: red half points north, grey half south.
            val w = r * 0.07f
            val northTip = Path().apply {
                moveTo(c.x, c.y - r * 0.55f); lineTo(c.x - w, c.y); lineTo(c.x + w, c.y); close()
            }
            val southTip = Path().apply {
                moveTo(c.x, c.y + r * 0.55f); lineTo(c.x - w, c.y); lineTo(c.x + w, c.y); close()
            }
            drawPath(northTip, north)
            drawPath(southTip, minor.copy(alpha = 0.6f))
        }
        drawCircle(ink, r * 0.04f, c)

        // Fixed lubber mark: where the top of the phone points.
        val mark = Path().apply {
            moveTo(c.x, c.y - r * 0.985f)
            lineTo(c.x - r * 0.05f, c.y - r * 0.87f)
            lineTo(c.x + r * 0.05f, c.y - r * 0.87f)
            close()
        }
        drawPath(mark, accent)
    }
}
