package app.maparound.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.maparound.data.LocalPrefs
import app.maparound.i18n.T
import app.maparound.i18n.ltr
import app.maparound.sensors.LevelFrame
import app.maparound.sensors.LocalDeviceSensors
import app.maparound.sensors.MotionSample
import app.maparound.sensors.MotionSensor
import app.maparound.sensors.SensorInfo
import app.maparound.sensors.Vec3
import app.maparound.shared.util.localFixed
import app.maparound.shared.util.localSigned
import app.maparound.shared.util.toDegrees
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val G = 9.80665f
private val AxisX = Color(0xFFE53935)
private val AxisY = Color(0xFF43A047)
private val AxisZ = Color(0xFF1E88E5)
private val Ok = Color(0xFF2E7D32)
private val Warn = Color(0xFFF9A825)
private val Danger = Color(0xFFC62828)

/** Inclinometer, accelerometer (with a G-meter) and gyroscope. */
@Composable
fun MotionScreen() {
    val sensors = LocalDeviceSensors.current
    val accelInfo = remember { sensors.motionInfo(MotionSensor.ACCELEROMETER) }
    val gyroInfo = remember { sensors.motionInfo(MotionSensor.GYROSCOPE) }
    if (accelInfo == null && gyroInfo == null) {
        MessageScreen(Icons.Filled.ScreenRotation, T.sensors.noMotionSensors, T.sensors.noMotionSensorsBody)
        return
    }
    val flow = remember { sensors.motion() }
    val sample by flow.collectAsStateWithLifecycle(initialValue = null)
    // The last ~10 s for the charts (not state itself; [tick] triggers redraws).
    val history = remember { ArrayDeque<MotionSample>() }
    var tick by remember { mutableIntStateOf(0) }
    var peakG by remember { mutableFloatStateOf(0f) }
    val levelUp by LocalPrefs.current.levelUp.collectAsState()
    LaunchedEffect(sample) {
        val s = sample ?: return@LaunchedEffect
        history.addLast(s)
        while (history.size > 250) history.removeFirst()
        s.linear?.let { peakG = max(peakG, it.norm / G) }
        tick++
    }
    val gravity = sample?.gravity
    val frame = levelUp?.let { LevelFrame(it) } ?: gravity?.let { LevelFrame.default(it) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (gravity != null && frame != null) {
            TiltCard(frame, gravity, calibrated = levelUp != null)
        }
        if (accelInfo != null) {
            AccelCard(sample, frame, history, tick, peakG, accelInfo, onResetPeak = { peakG = 0f })
        }
        if (gyroInfo != null) {
            GyroCard(sample, frame, history, tick, gyroInfo)
        } else {
            MessageCard(Icons.Filled.ScreenRotation, T.sensors.noGyroscope, T.sensors.noGyroscopeBody)
        }
    }
}

// ---- tilt ---------------------------------------------------------------------------------------------------

@Composable
private fun TiltCard(frame: LevelFrame, gravity: Vec3, calibrated: Boolean) {
    val prefs = LocalPrefs.current
    val tilt = frame.tilt(gravity)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(T.sensors.tiltTitle, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TiltGauge(
                    T.sensors.pitch, tilt.pitchDeg,
                    when {
                        tilt.pitchDeg > 0.5f -> T.sensors.noseUp
                        tilt.pitchDeg < -0.5f -> T.sensors.noseDown
                        else -> T.sensors.level
                    },
                    side = true, modifier = Modifier.weight(1f),
                )
                TiltGauge(
                    T.sensors.roll, tilt.rollDeg,
                    when {
                        tilt.rollDeg > 0.5f -> T.sensors.rightSideDown
                        tilt.rollDeg < -0.5f -> T.sensors.leftSideDown
                        else -> T.sensors.level
                    },
                    side = false, modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { prefs.setLevelUp(gravity) }) { Text(T.sensors.setAsLevel) }
                if (calibrated) TextButton(onClick = { prefs.setLevelUp(null) }) { Text(T.sensors.reset) }
            }
            Text(
                T.sensors.tiltHint(calibrated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun severity(deg: Float) = when {
    abs(deg) >= 25f -> Danger
    abs(deg) >= 15f -> Warn
    else -> Ok
}

/** A vehicle seen from the side (pitch) or from behind (roll), tilted by the angle. */
@Composable
private fun TiltGauge(title: String, deg: Float, caption: String, side: Boolean, modifier: Modifier) {
    val color = severity(deg)
    val outline = MaterialTheme.colorScheme.outline
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val w = size.width
            val h = size.height
            val c = Offset(w / 2, h * 0.55f)
            drawLine(outline, Offset(0f, c.y + h * 0.28f), Offset(w, c.y + h * 0.28f), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            // Side view: nose to the right, nose-up turns it counter-clockwise. Rear view: right side down = clockwise.
            rotate(if (side) -deg else deg, pivot = Offset(c.x, c.y + h * 0.28f)) {
                val u = h * 0.22f
                if (side) {
                    drawRoundRect(color, Offset(c.x - 2.6f * u, c.y - 0.2f * u), Size(5.2f * u, 0.9f * u), CornerRadius(0.2f * u))
                    drawRoundRect(color, Offset(c.x - 1.9f * u, c.y - 1.1f * u), Size(2.6f * u, 1f * u), CornerRadius(0.25f * u))
                    for (x in listOf(c.x - 1.6f * u, c.x + 1.6f * u)) {
                        drawCircle(Color.DarkGray, 0.55f * u, Offset(x, c.y + 0.75f * u))
                    }
                } else {
                    drawRoundRect(color, Offset(c.x - 1.9f * u, c.y - 0.3f * u), Size(3.8f * u, 1f * u), CornerRadius(0.2f * u))
                    drawRoundRect(color, Offset(c.x - 1.4f * u, c.y - 1.25f * u), Size(2.8f * u, 1f * u), CornerRadius(0.3f * u))
                    for (x in listOf(c.x - 1.6f * u, c.x + 1.15f * u)) {
                        drawRoundRect(Color.DarkGray, Offset(x, c.y + 0.6f * u), Size(0.45f * u, 0.95f * u), CornerRadius(0.1f * u))
                    }
                }
            }
        }
        Text(
            ltr("${abs(deg).roundToInt()}°"),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(caption, style = MaterialTheme.typography.bodySmall)
    }
}

// ---- accelerometer ----------------------------------------------------------------------------------------------

@Composable
private fun AccelCard(
    sample: MotionSample?,
    frame: LevelFrame?,
    history: ArrayDeque<MotionSample>,
    tick: Int,
    peakG: Float,
    info: SensorInfo,
    onResetPeak: () -> Unit,
) {
    val a = sample?.accel
    val lin = sample?.linear
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(T.sensors.accelerometer, style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    a?.let { "${localFixed((it.norm / G).toDouble(), 2)} g" } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(T.sensors.totalWithGravity, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
            }
            a?.let { AxisLine(it, T.sensors.unitMps2) }
            if (lin != null) {
                Text(
                    T.sensors.movement(localFixed((lin.norm / G).toDouble(), 2), localFixed(peakG.toDouble(), 2)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (frame != null) {
                        GMeter(history, frame, tick, Modifier.size(140.dp))
                        Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val (fwd, right) = frame.horizontal(lin)
                            Text(
                                T.sensors.forwardG(localFixed((abs(fwd) / G).toDouble(), 2), speedingUp = fwd >= 0),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                T.sensors.sidewaysG(localFixed((abs(right) / G).toDouble(), 2), right = right >= 0),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onResetPeak) { Text(T.sensors.resetPeak) }
                        }
                    }
                }
                LiveChart(history.mapNotNull { it.linear }, tick, T.sensors.accelGravityRemoved, Modifier.fillMaxWidth().height(110.dp))
            }
            SensorDetails(info, T.sensors.unitMps2)
        }
    }
}

@Composable
private fun AxisLine(v: Vec3, unit: String, scale: Float = 1f) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("x" to v.x, "y" to v.y, "z" to v.z).forEachIndexed { i, (name, value) ->
            Text(
                "$name ${localSigned((value * scale).toDouble(), 2)}",
                style = MaterialTheme.typography.bodyMedium,
                color = listOf(AxisX, AxisY, AxisZ)[i],
                fontWeight = FontWeight.Medium,
            )
        }
        Text(unit, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Forward/braking (up/down) and sideways (left/right) acceleration, with a 2-second trail. */
@Composable
private fun GMeter(history: ArrayDeque<MotionSample>, frame: LevelFrame, tick: Int, modifier: Modifier) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val dot = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(modifier) {
        tick.let { } // redraw on every new sample
        val r = size.minDimension / 2
        val c = center
        for (g in listOf(0.25f, 0.5f, 0.75f, 1f)) drawCircle(ring, r * g, c, style = Stroke(1.dp.toPx()))
        drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y))
        drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r))
        drawText(measurer, "0.5 g", topLeft = Offset(c.x + 2.dp.toPx(), c.y - r * 0.5f), style = label)
        val recent = history.toList().takeLast(50).mapNotNull { it.linear }
        recent.forEachIndexed { i, lin ->
            val (fwd, right) = frame.horizontal(lin)
            val p = Offset(c.x + (right / G).coerceIn(-1f, 1f) * r, c.y - (fwd / G).coerceIn(-1f, 1f) * r)
            val last = i == recent.lastIndex
            drawCircle(dot.copy(alpha = if (last) 1f else 0.1f + 0.4f * i / recent.size), if (last) 6.dp.toPx() else 2.5.dp.toPx(), p)
        }
    }
}

// ---- gyroscope -----------------------------------------------------------------------------------------------------

@Composable
private fun GyroCard(sample: MotionSample?, frame: LevelFrame?, history: ArrayDeque<MotionSample>, tick: Int, info: SensorInfo) {
    val g = sample?.gyro
    val deg = toDegrees(1.0).toFloat()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(T.sensors.gyroscope, style = MaterialTheme.typography.labelLarge)
            val yaw = if (g != null && frame != null) frame.yawRate(g) * deg else null
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    yaw?.let { T.sensors.degPerS(abs(it).roundToInt().toString()) } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        yaw == null -> ""
                        yaw > 2f -> T.sensors.turningLeft
                        yaw < -2f -> T.sensors.turningRight
                        else -> T.sensors.notTurning
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            g?.let {
                AxisLine(it, T.sensors.unitDegPerS, deg)
                Text(
                    T.sensors.totalRotation(localFixed((it.norm * deg).toDouble(), 0)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LiveChart(history.mapNotNull { s -> s.gyro?.let { it * deg } }, tick, T.sensors.unitDegPerS, Modifier.fillMaxWidth().height(110.dp), minRange = 20f)
            SensorDetails(info, T.sensors.unitRadPerS)
        }
    }
}

@Composable
private fun SensorDetails(info: SensorInfo, unit: String) {
    Text(
        listOfNotNull(
            info.name,
            info.vendor.takeIf { it.isNotBlank() },
            T.sensors.range(localFixed(info.maxRange.toDouble(), 0), unit).takeIf { info.maxRange > 0 },
            info.maxRateHz?.let { T.sensors.upToHz(it) },
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** x/y/z over time (red/green/blue), symmetric around zero. */
@Composable
fun LiveChart(series: List<Vec3>, tick: Int, unit: String, modifier: Modifier, minRange: Float = 2f) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val spanLabel = T.sensors.lastSeconds(10)
    Canvas(modifier) {
        tick.let { }
        if (series.size < 2) return@Canvas
        val maxAbs = max(minRange, series.maxOf { maxOf(abs(it.x), abs(it.y), abs(it.z)) } * 1.15f)
        val mid = size.height / 2
        drawLine(grid, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
        val n = 250 // fixed time axis (~10 s) so the trace scrolls
        val dx = size.width / (n - 1)
        val x0 = size.width - (series.size - 1) * dx
        for ((axis, color) in listOf<Pair<(Vec3) -> Float, Color>>({ v: Vec3 -> v.x } to AxisX, { v: Vec3 -> v.y } to AxisY, { v: Vec3 -> v.z } to AxisZ)) {
            val path = Path()
            series.forEachIndexed { i, v ->
                val p = Offset(x0 + i * dx, mid - axis(v) / maxAbs * mid)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, color, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
        }
        drawText(measurer, "±${localFixed(maxAbs.toDouble(), 1)} $unit", topLeft = Offset(4.dp.toPx(), 0f), style = label)
        val t = measurer.measure(spanLabel, label)
        drawText(t, topLeft = Offset(size.width - t.size.width - 4.dp.toPx(), size.height - t.size.height))
    }
}
