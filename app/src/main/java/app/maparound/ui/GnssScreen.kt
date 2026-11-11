package app.maparound.ui

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.maparound.sensors.Constellations
import app.maparound.sensors.GnssSource
import app.maparound.sensors.GnssState
import app.maparound.sensors.Satellite
import app.maparound.sensors.bestAltitude
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun GnssScreen(hasLocation: Boolean, requestLocation: () -> Unit) {
    if (!hasLocation) {
        MessageScreen(
            Icons.Filled.SatelliteAlt,
            "Location permission needed",
            "Satellite reception data comes from the phone's GNSS (GPS) receiver, which requires location access.",
            "Allow location",
            requestLocation,
        )
        return
    }
    val context = LocalContext.current
    val gnssFlow = remember { GnssSource.updates(context) }
    val state by gnssFlow.collectAsStateWithLifecycle(initialValue = GnssState())
    // Ticks once a second so "fix age" stays current even when no new fix arrives.
    val nowNanos by produceState(SystemClock.elapsedRealtimeNanos()) {
        while (true) {
            delay(1000)
            value = SystemClock.elapsedRealtimeNanos()
        }
    }

    val sats = state.satellites
    val sorted = remember(sats) {
        sats.sortedWith(compareByDescending<Satellite> { it.usedInFix }.thenByDescending { it.cn0 })
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.gpsEnabled) {
            item {
                MessageCard(
                    Icons.Filled.LocationOff,
                    "Location is turned off",
                    "Turn on location to start the satellite receiver.",
                    "Open settings",
                    { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                )
            }
        }
        item { FixCard(state, nowNanos) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Sky view", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    SkyPlot(sats, Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f).padding(8.dp))
                    ConstellationLegend(sats)
                    Text(
                        "Filled = used in fix · rings at 0°, 30°, 60° elevation",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        item {
            Text("Signal strength (C/N₀, dB‑Hz)", style = MaterialTheme.typography.titleMedium)
        }
        if (sats.isEmpty()) {
            item {
                Text(
                    if (state.engineRunning) {
                        "Searching… go outdoors with a clear view of the sky. A cold start can take a minute or two."
                    } else {
                        "Starting the GNSS receiver…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        // No item keys: some chipsets report the same satellite twice.
        items(sorted) { SatelliteRow(it) }
    }
}

@Composable
private fun FixCard(state: GnssState, nowNanos: Long) {
    val loc = state.location
    val ageSec = loc?.let { (nowNanos - it.elapsedRealtimeNanos) / 1e9 }
    val hasFix = ageSec != null && ageSec < 5
    val used = state.satellites.count { it.usedInFix }
    val fixLabel = when {
        !hasFix -> "No fix"
        loc?.hasAltitude() == true -> "3D fix"
        else -> "2D fix"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(12.dp).background(
                        if (hasFix) Color(0xFF43A047) else if (state.satellites.isNotEmpty()) Color(0xFFFFA000) else Color(0xFFE53935),
                        CircleShape,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Text(fixLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("$used / ${state.satellites.size}", style = MaterialTheme.typography.headlineSmall)
                    Text("in use / in view", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (loc != null) {
                InfoRow("Position", Fmt.latLon(loc.latitude, loc.longitude))
                InfoRow("", Fmt.dms(loc.latitude, loc.longitude))
                loc.bestAltitude()?.let { alt ->
                    val msl = Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()
                    InfoRow("Altitude", "${alt.roundToInt()} m" + if (msl) " (MSL)" else " (GPS)")
                }
                if (loc.hasAccuracy()) {
                    val vertical = if (loc.hasVerticalAccuracy()) " · ±${loc.verticalAccuracyMeters.roundToInt()} m vert." else ""
                    InfoRow("Accuracy", "±${loc.accuracy.roundToInt()} m$vertical")
                }
                if (loc.hasSpeed()) {
                    InfoRow("Speed", String.format(Locale.getDefault(), "%.1f km/h", loc.speed * 3.6f))
                }
                if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.5f) {
                    InfoRow("Course", "${loc.bearing.roundToInt()}° ${Fmt.cardinal(loc.bearing)}")
                }
                ageSec?.let { InfoRow("Last fix", if (it < 1.5) "now" else "${it.roundToInt()} s ago") }
            }
            state.ttffMs?.let { InfoRow("Time to first fix", String.format(Locale.getDefault(), "%.1f s", it / 1000f)) }
            if (loc == null) {
                Text("Waiting for the first fix…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConstellationLegend(sats: List<Satellite>) {
    val groups = sats.groupBy { it.constellation }.toSortedMap()
    if (groups.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        groups.forEach { (type, list) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Constellations.color(type), CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(
                    "${Constellations.name(type)} ${list.count { it.usedInFix }}/${list.size}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun SkyPlot(sats: List<Satellite>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val face = MaterialTheme.colorScheme.surfaceVariant
    val ring = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val labelStyle = MaterialTheme.typography.labelMedium.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
    )
    val svStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)

    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f - 16.dp.toPx()
        drawCircle(face, r, c)
        listOf(1f, 2f / 3f, 1f / 3f).forEach { drawCircle(ring, r * it, c, style = Stroke(1.dp.toPx())) }
        drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
        listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (t, az) ->
            val p = polar(c, r + 9.dp.toPx(), az)
            val layout = measurer.measure(t, labelStyle)
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        }

        val dot = 9.dp.toPx()
        sats.forEach { s ->
            if (s.elevation < 0f || (s.elevation == 0f && s.azimuth == 0f)) return@forEach // position unknown
            val p = polar(c, r * (90f - s.elevation) / 90f, s.azimuth)
            val color = Constellations.color(s.constellation)
            if (s.usedInFix) {
                drawCircle(color, dot, p)
            } else {
                drawCircle(color.copy(alpha = 0.18f), dot, p)
                drawCircle(color, dot, p, style = Stroke(1.5.dp.toPx()))
            }
            val layout = measurer.measure(s.svid.toString(), if (s.usedInFix) svStyle else svStyle.copy(color = color))
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        }
    }
}

private fun polar(c: Offset, distance: Float, azimuthDeg: Float): Offset {
    val a = Math.toRadians(azimuthDeg.toDouble())
    return Offset(c.x + distance * sin(a).toFloat(), c.y - distance * cos(a).toFloat())
}

@Composable
private fun SatelliteRow(s: Satellite) {
    val color = Constellations.color(s.constellation)
    val strength = when {
        s.cn0 >= 35f -> Color(0xFF43A047)
        s.cn0 >= 25f -> Color(0xFFC0CA33)
        s.cn0 >= 15f -> Color(0xFFFFA000)
        else -> Color(0xFFE53935)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(120.dp)) {
            val band = Constellations.band(s.carrierHz)?.let { " · $it" }.orEmpty()
            Text(
                "${Constellations.flag(s.constellation)} ${Constellations.name(s.constellation)} ${s.svid}$band",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                "el ${s.elevation.roundToInt()}° · az ${s.azimuth.roundToInt()}°",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { (s.cn0 / 50f).coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(8.dp),
            color = strength,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
        Text(
            s.cn0.roundToInt().toString(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
        )
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (s.usedInFix) {
                Icon(Icons.Filled.Check, contentDescription = "Used in fix", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
