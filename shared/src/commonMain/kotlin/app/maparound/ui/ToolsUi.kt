package app.maparound.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.maparound.data.LocalPrefs
import app.maparound.routing.Geo
import androidx.compose.ui.text.style.TextOverflow
import app.maparound.routing.GeoPoint
import app.maparound.sensors.DeviceSensors
import app.maparound.sensors.GeoFix
import app.maparound.sensors.LocalDeviceSensors
import app.maparound.share.Shared
import app.maparound.share.SharedLinks
import app.maparound.shared.util.fixed
import app.maparound.shared.util.localFixed
import app.maparound.shared.util.localSigned
import app.maparound.shared.util.nowMillis
import app.maparound.shared.util.toDegrees
import app.maparound.tools.CoordFormat
import app.maparound.tools.Coords
import app.maparound.tools.Geodesy
import app.maparound.tools.LineOfSight
import app.maparound.tools.ToolsSession
import app.maparound.tools.ToolsSession.Mode
import app.maparound.tools.ToolsSession.Slot
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val Visible = Color(0xFF2E7D32)
private val Hidden = Color(0xFFC62828)
private val Partial = Color(0xFFE08600)
/** The Fresnel zone on the profile. */
private val ZoneColor = Color(0xFF1E88E5)

/** Magnetic declination (degrees east) at a point, from the World Magnetic Model built into Android. */
fun declinationAt(sensors: DeviceSensors, p: GeoPoint): Float = sensors.declination(p.lat, p.lon, 0.0, nowMillis())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsPanel(
    state: ToolsSession.State,
    myLocation: GeoFix?,
    mapCenter: () -> GeoPoint,
    onShowPoint: (GeoPoint) -> Unit,
    onShare: (GeoPoint) -> Unit,
    /** Goes to where terrain is downloaded; null where the platform has no such place yet. */
    onGetTerrain: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    Mode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = state.mode == m,
                            onClick = { ToolsSession.setMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
                            icon = {},
                        ) { Text(m.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                    }
                }
                IconButton(onClick = { ToolsSession.close() }) { Icon(Icons.Filled.Close, contentDescription = "Close map tools") }
            }
            when (state.mode) {
                Mode.MEASURE -> MeasureBody(state, myLocation)
                Mode.SIGHT -> SightBody(state, myLocation, onGetTerrain)
                Mode.VIEW -> ViewshedBody(state, myLocation, onGetTerrain)
                Mode.COORDS -> CoordsBody(state, myLocation, mapCenter, onShowPoint, onShare)
            }
        }
    }
}

// ---- A / B points ------------------------------------------------------------------------------------

@Composable
private fun PointsRow(state: ToolsSession.State, myLocation: GeoFix?, onProject: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PointChip("A", state.a, state.aGround, state.next == Slot.A) { ToolsSession.select(Slot.A) }
        PointChip("B", state.b, state.bGround, state.next == Slot.B) { ToolsSession.select(Slot.B) }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { myLocation?.let { val p = GeoPoint(it.lat, it.lon); if (state.next == Slot.A) ToolsSession.setA(p) else ToolsSession.setB(p) } },
            enabled = myLocation != null,
        ) { Icon(Icons.Filled.MyLocation, contentDescription = "Put ${state.next} at my location") }
        IconButton(onClick = { ToolsSession.swap() }, enabled = state.a != null || state.b != null) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap A and B")
        }
        if (onProject != null) {
            IconButton(onClick = onProject, enabled = state.a != null) {
                Icon(Icons.Filled.Explore, contentDescription = "Place B by bearing and distance from A")
            }
        }
    }
}

@Composable
private fun PointChip(name: String, p: GeoPoint?, ground: Double?, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                when {
                    p == null -> "$name: tap map"
                    ground != null -> "$name · ${ground.roundToInt()} m"
                    else -> name
                },
                maxLines = 1,
            )
        },
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---- azimuth & range ----------------------------------------------------------------------------------

@Composable
private fun MeasureBody(state: ToolsSession.State, myLocation: GeoFix?) {
    val sensors = LocalDeviceSensors.current
    var project by remember { mutableStateOf(false) }
    PointsRow(state, myLocation, onProject = { project = true })
    val a = state.a
    val b = state.b
    if (a == null || b == null) {
        Hint(if (a == null) "Tap the map to place A, the point you measure from." else "Now tap B. Further taps move B; tap a chip to move A instead.")
    } else {
        val dist = Geo.distance(a, b)
        val az = Geodesy.bearing(a, b)
        val back = Geodesy.bearing(b, a)
        val mag = (az - declinationAt(sensors, a) + 360) % 360
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(Fmt.distance(dist.toFloat()), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${deg(az)} ${Fmt.cardinal(az.toFloat())}", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "Magnetic ${deg(mag)} · ${Geodesy.mils(az)} mil · back ${deg(back)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        val ga = state.aGround
        val gb = state.bGround
        if (ga != null && gb != null && dist > 1) {
            val dh = gb - ga
            val angle = toDegrees(atan2(dh, dist))
            Text(
                "Height ${ga.roundToInt()} → ${gb.roundToInt()} m · Δ ${signed(dh.roundToInt())} m · " +
                    "${localSigned(angle, 1)}°" +
                    if (abs(dh) > dist * 0.05) " · slant ${Fmt.distance(sqrt(dist * dist + dh * dh).toFloat())}" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "True north bearing along the great circle; magnetic from the WMM declination at A (${localSigned(declinationAt(sensors, a).toDouble(), 1)}°).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (project && a != null) {
        ProjectDialog(a, onDismiss = { project = false }) { ToolsSession.setB(it); project = false }
    }
}

private fun deg(v: Double) = "${((v.roundToInt() % 360) + 360) % 360}°"
private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

@Composable
private fun ProjectDialog(from: GeoPoint, onDismiss: () -> Unit, onPlace: (GeoPoint) -> Unit) {
    val sensors = LocalDeviceSensors.current
    var bearing by remember { mutableStateOf("") }
    var distance by remember { mutableStateOf("") }
    var mils by remember { mutableStateOf(false) }
    var magnetic by remember { mutableStateOf(false) }
    val b = bearing.replace(',', '.').toDoubleOrNull()
    val d = parseDistance(distance)
    val max = if (mils) 6400.0 else 360.0
    val valid = b != null && b >= 0 && b < max && d != null && d > 0 && d <= 2_000_000
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Place B from A") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !mils, onClick = { mils = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text("Degrees") }
                    SegmentedButton(selected = mils, onClick = { mils = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text("Mils (6400)") }
                }
                OutlinedTextField(
                    bearing, { bearing = it.take(8) },
                    label = { Text(if (mils) "Bearing (mil)" else "Bearing (°)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Magnetic bearing (compass)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = magnetic, onCheckedChange = { magnetic = it })
                }
                OutlinedTextField(
                    distance, { distance = it.take(12) },
                    label = { Text("Distance (m, or e.g. 2.5 km)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                var trueBearing = if (mils) b!! * 360 / 6400 else b!!
                if (magnetic) trueBearing += declinationAt(sensors, from)
                onPlace(Geodesy.destination(from, (trueBearing + 360) % 360, d!!))
            }) { Text("Place B") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun parseDistance(text: String): Double? {
    val m = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(km|m|mi|nm)?\s*$""", RegexOption.IGNORE_CASE).find(text) ?: return null
    val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    return when (m.groupValues[2].lowercase()) {
        "km" -> v * 1000
        "mi" -> v * 1609.344
        "nm" -> v * 1852
        else -> v
    }
}

// ---- line of sight -------------------------------------------------------------------------------------

@Composable
private fun SightBody(state: ToolsSession.State, myLocation: GeoFix?, onGetTerrain: (() -> Unit)?) {
    PointsRow(state, myLocation)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CellTower, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Radio link", style = MaterialTheme.typography.bodyLarge)
            Text("Antennas at A and B, Fresnel zone and path loss", style = MaterialTheme.typography.bodySmall)
        }
        Switch(state.radio, ToolsSession::setRadio)
    }
    if (state.radio) {
        HeightsRow(state, "Antenna at A (m)", "Antenna at B (m)")
        FrequencyPicker(state.frequencyMHz)
    } else {
        HeightsRow(state, "Eye at A (m)", "Target at B (m)")
    }
    HigherGroundHint(state)
    val a = state.a
    val b = state.b
    when {
        a == null -> Hint(if (state.radio) "Tap the map to place A, the first antenna." else "Tap the map to place A, where you stand.")
        b == null -> Hint(if (state.radio) "Now tap B, the other antenna. Further taps move B." else "Now tap B, what you want to see. Further taps move B.")
        state.needsTerrain -> TerrainNeeded(state, onGetTerrain)
        state.los?.fresnel != null -> RadioResult(state.los, a, b)
        state.los != null -> {
            val los = state.los
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (los.targetVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = null,
                    tint = if (los.targetVisible) Visible else Hidden,
                )
                Text(
                    if (los.targetVisible) "B is in sight from A" else "B is hidden from A",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (los.targetVisible) Visible else Hidden,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text("${Fmt.distance(los.distanceM.toFloat())} · ${deg(Geodesy.bearing(a, b))}", style = MaterialTheme.typography.bodyMedium)
            }
            val o = los.obstacle
            Text(
                if (los.targetVisible) {
                    "The sight line clears the terrain by at least ${max(0, los.marginM.roundToInt())} m."
                } else if (o != null) {
                    "Blocked ${Fmt.distance(o.distanceM.toFloat())} from A by ground at ${o.groundM.roundToInt()} m, " +
                        "${(-los.marginM).roundToInt()} m above the sight line."
                } else {
                    "Blocked by terrain."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            SightProfile(los, Modifier.fillMaxWidth().height(110.dp))
            Text(
                "Bare terrain with Earth curvature and refraction; trees and buildings aren't included.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

/** A radio link's verdict: how much of the first Fresnel zone is clear, and the path loss. */
@Composable
private fun RadioResult(los: LineOfSight, a: GeoPoint, b: GeoPoint) {
    val f = los.fresnel ?: return
    val t = f.tightest
    val (title, color) = when {
        !los.targetVisible -> "Radio path blocked" to Hidden
        f.clearance >= LineOfSight.Fresnel.RULE -> "Clear radio path" to Visible
        else -> "Fresnel zone partly blocked" to Partial
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.CellTower, contentDescription = null, tint = color)
        Text(title, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text("${Fmt.distance(los.distanceM.toFloat())} · ${deg(Geodesy.bearing(a, b))}", style = MaterialTheme.typography.bodyMedium)
    }
    val where = t?.let { Fmt.distance(it.distanceM.toFloat()) + " from A" }
    val extra = f.obstructionLossDb.roundToInt()
    Text(
        when {
            t == null || f.clearance >= 1 -> "The whole first Fresnel zone is clear of the terrain."
            f.clearance >= LineOfSight.Fresnel.RULE ->
                "At least ${percent(f.clearance)} of the first Fresnel zone is clear (the rule of thumb is 60%); tightest $where."
            f.clearance > 0 ->
                "The ground $where reaches into the Fresnel zone: only ${percent(f.clearance)} of it is clear. " +
                    "Expect about $extra dB of extra loss."
            else -> {
                val above = -f.clearance * f.radiusAt(t.distanceM, los.distanceM)
                "The ground $where rises ${above.roundToInt()} m above the direct line between the antennas. " +
                    "Expect about $extra dB of extra loss (diffraction over it); the signal may still get through."
            }
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "Path loss about ${f.totalLossDb.roundToInt()} dB: free space ${f.freeSpaceLossDb.roundToInt()} dB" +
            (if (extra > 0) " + terrain $extra dB" else "") +
            ". The first Fresnel zone is ${f.midRadiusM.roundToInt()} m in radius halfway.",
        style = MaterialTheme.typography.bodyMedium,
    )
    SightProfile(los, Modifier.fillMaxWidth().height(130.dp))
    Text(
        "Bare terrain, with radio's 4/3 Earth radius; trees, buildings, antenna gain and cable loss aren't " +
            "included. Terrain loss is the tightest point as a single knife edge (ITU-R P.526).",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun percent(share: Double) = "${(share * 100).roundToInt().coerceAtLeast(0)}%"

/** "868 MHz", "2.44 GHz". */
private fun frequencyText(mhz: Double): String = when {
    mhz >= 1000 -> "${trimZeros(fixed(mhz / 1000, 2))} GHz"
    else -> "${trimZeros(fixed(mhz, 3))} MHz"
}

private fun trimZeros(s: String) = if ('.' in s) s.trimEnd('0').trimEnd('.') else s

/** Common bands in a menu, and any frequency typed in. */
@Composable
private fun FrequencyPicker(mhz: Double) {
    var open by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf(false) }
    val band = ToolsSession.BANDS.firstOrNull { it.second == mhz }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    (band?.first?.let { "$it · " } ?: "") + frequencyText(mhz),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose a frequency")
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                ToolsSession.BANDS.forEach { (label, f) ->
                    DropdownMenuItem(
                        text = { Text("$label · ${frequencyText(f)}") },
                        onClick = { open = false; other = false; ToolsSession.setFrequency(f) },
                    )
                }
                DropdownMenuItem(text = { Text("Other frequency…") }, onClick = { open = false; other = true })
            }
        }
        if (other || band == null) {
            NumberField("MHz", mhz, Modifier.width(110.dp), maxLength = 9) { ToolsSession.setFrequency(it) }
        }
    }
}

@Composable
private fun HeightsRow(state: ToolsSession.State, observerLabel: String, targetLabel: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeightField(observerLabel, state.observerHeightM, Modifier.weight(1f)) { ToolsSession.setHeights(it, state.targetHeightM) }
        HeightField(targetLabel, state.targetHeightM, Modifier.weight(1f)) { ToolsSession.setHeights(state.observerHeightM, it) }
    }
}

@Composable
private fun HeightField(label: String, value: Double, modifier: Modifier, onChange: (Double) -> Unit) =
    NumberField(label, value, modifier, maxLength = 6, onChange = onChange)

@Composable
private fun NumberField(label: String, value: Double, modifier: Modifier, maxLength: Int, onChange: (Double) -> Unit) {
    fun show(v: Double) = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else trimZeros(fixed(v, 3))
    var text by remember { mutableStateOf(show(value)) }
    // Follow outside changes (swap, mode switch) unless the field already says the same.
    LaunchedEffect(value) { if (text.replace(',', '.').toDoubleOrNull() != value) text = show(value) }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        text,
        { t ->
            text = t.take(maxLength)
            text.replace(',', '.').toDoubleOrNull()?.let(onChange)
        },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
    )
}

/** "Ground 12 m higher is 40 m away" with a button to move A onto it. */
@Composable
private fun HigherGroundHint(state: ToolsSession.State) {
    val (p, z) = state.higherNearA ?: return
    val a = state.a ?: return
    val ground = state.aGround ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Ground ${(z - ground).roundToInt()} m higher is ${Geo.distance(a, p).roundToInt()} m away and may block the view " +
                "(terrain data rounds off summits).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { ToolsSession.setA(p) }) { Text("Move A up") }
    }
}

/**
 * The terrain line of sight or the viewshed need: downloading on its own (with progress), or why
 * it couldn't be (with a retry), or where to get it.
 */
@Composable
private fun TerrainNeeded(state: ToolsSession.State, onGetTerrain: (() -> Unit)?) {
    val progress = state.terrainProgress
    if (progress != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Terrain, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Getting the terrain data for this area" +
                        (if (progress.second > 1) " (${progress.first} of ${progress.second})" else "") + "…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (progress.first == 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress.first.toFloat() / progress.second }, modifier = Modifier.fillMaxWidth())
            }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Terrain, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            state.terrainProblem ?: "This needs terrain data for the area. Download it with the map (Offline maps → ⋮ → Terrain).",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (state.terrainProblem != null) {
            TextButton(onClick = { ToolsSession.retryTerrain() }) { Text("Try again") }
        } else if (onGetTerrain != null) {
            TextButton(onClick = onGetTerrain) { Text("Get") }
        }
    }
}

/** Terrain between A and B (with Earth curvature applied) and the straight sight line. */
@Composable
private fun SightProfile(los: LineOfSight, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val ground = MaterialTheme.colorScheme.outline
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
    // Distance, ground as seen from A (curvature applied), and its colour.
    class Point(val d: Double, val e: Double, val color: Color)
    val fresnel = los.fresnel
    val series = remember(los) {
        los.samples.filter { !it.groundM.isNaN() }.map {
            val color = when {
                fresnel == null -> if (it.visible) Visible else Hidden
                it.zone.isNaN() || it.zone >= LineOfSight.Fresnel.RULE -> Visible
                it.zone > 0 -> Partial
                else -> Hidden
            }
            Point(it.distanceM, it.groundM - los.drop(it.distanceM), color)
        }
    }
    // The first Fresnel zone around the direct line, as (distance, top, bottom).
    val zone = remember(los) {
        fresnel?.let { f -> (0..80).map { i -> val d = los.distanceM * i / 80; Triple(d, los.lineAt(d) + f.radiusAt(d, los.distanceM), los.lineAt(d) - f.radiusAt(d, los.distanceM)) } }
    }
    if (series.size < 2) return
    Canvas(modifier) {
        val total = los.distanceM
        val targetEff = los.lineAt(total)
        val lo = minOf(series.minOf { it.e }, zone?.minOf { it.third } ?: Double.MAX_VALUE)
        val hi = maxOf(series.maxOf { it.e }, los.eyeM, targetEff, zone?.maxOf { it.second } ?: Double.MIN_VALUE)
        val pad = max((hi - lo) * 0.08, 5.0)
        val bottom = lo - pad
        val range = hi + pad - bottom
        fun x(d: Double) = (d / total * size.width).toFloat()
        fun y(e: Double) = (size.height - (e - bottom) / range * size.height).toFloat()

        val path = Path().apply {
            moveTo(0f, size.height)
            series.forEach { lineTo(x(it.d), y(it.e)) }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, fill)
        if (zone != null && fresnel != null) {
            // The zone as a lens, and the 60% that should stay clear (its lower edge dashed).
            val lens = Path().apply {
                moveTo(x(zone.first().first), y(zone.first().second))
                zone.forEach { (d, top, _) -> lineTo(x(d), y(top)) }
                zone.asReversed().forEach { (d, _, low) -> lineTo(x(d), y(low)) }
                close()
            }
            drawPath(lens, ZoneColor.copy(alpha = 0.16f))
            drawPath(lens, ZoneColor.copy(alpha = 0.6f), style = Stroke(1.dp.toPx()))
            val rule = Path().apply {
                zone.forEachIndexed { i, (d, _, _) ->
                    val e = los.lineAt(d) - LineOfSight.Fresnel.RULE * fresnel.radiusAt(d, total)
                    if (i == 0) moveTo(x(d), y(e)) else lineTo(x(d), y(e))
                }
            }
            drawPath(rule, ZoneColor.copy(alpha = 0.8f), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
        }
        // Ground line: green where A can see it (radio: clear of 60% of the zone), amber in the
        // zone, red where hidden (radio: above the direct line).
        for (i in 1 until series.size) {
            val p0 = series[i - 1]
            val p1 = series[i]
            drawLine(p1.color, Offset(x(p0.d), y(p0.e)), Offset(x(p1.d), y(p1.e)), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
        }
        drawLine(ground.copy(alpha = 0.4f), Offset(0f, y(lo)), Offset(size.width, y(lo)), strokeWidth = 1.dp.toPx())
        // Sight line from the eye (or antenna) to the target.
        val lineColor = when {
            !los.targetVisible -> Hidden
            fresnel != null && fresnel.clearance < LineOfSight.Fresnel.RULE -> Partial
            else -> Visible
        }
        drawLine(
            lineColor,
            Offset(0f, y(los.eyeM)), Offset(size.width, y(targetEff)),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())),
        )
        drawCircle(Color(0xFF1565C0), 4.dp.toPx(), Offset(0f + 4.dp.toPx(), y(los.eyeM)))
        drawCircle(Color(0xFFEF6C00), 4.dp.toPx(), Offset(size.width - 4.dp.toPx(), y(targetEff)))
        // What blocks the view, or for a radio link the tightest point when it's in the zone.
        val mark = if (fresnel != null) fresnel.tightest?.takeIf { fresnel.clearance < LineOfSight.Fresnel.RULE } else los.obstacle
        mark?.let { o ->
            drawCircle(lineColor, 4.dp.toPx(), Offset(x(o.distanceM), y(o.groundM - los.drop(o.distanceM))), style = Stroke(2.dp.toPx()))
        }
        drawText(measurer, "${hi.roundToInt()} m", topLeft = Offset(10.dp.toPx(), 0f), style = label)
        drawText(measurer, "${lo.roundToInt()} m", topLeft = Offset(10.dp.toPx(), y(lo) - 15.dp.toPx()), style = label)
        val km = measurer.measure(Fmt.distance(total.toFloat()), label)
        drawText(km, topLeft = Offset(size.width - km.size.width - 10.dp.toPx(), size.height - km.size.height))
    }
}

// ---- viewshed ----------------------------------------------------------------------------------------------

@Composable
private fun ViewshedBody(state: ToolsSession.State, myLocation: GeoFix?, onGetTerrain: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PointChip("Observer", state.a, state.aGround, true) {}
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { myLocation?.let { ToolsSession.setA(GeoPoint(it.lat, it.lon)) } }, enabled = myLocation != null) {
            Icon(Icons.Filled.MyLocation, contentDescription = "Observe from my location")
        }
    }
    // Logarithmic slider: fine control at short range, up to 25 km.
    val lnMin = ln(ToolsSession.MIN_RADIUS)
    val lnMax = ln(ToolsSession.MAX_RADIUS)
    var slider by remember { mutableFloatStateOf(((ln(state.radiusM) - lnMin) / (lnMax - lnMin)).toFloat()) }
    fun radiusOf(t: Float): Double {
        val r = kotlin.math.exp(lnMin + t * (lnMax - lnMin))
        val step = when { r < 1_000 -> 50.0; r < 5_000 -> 250.0; else -> 1_000.0 }
        return (r / step).roundToInt() * step
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Radius ${Fmt.distance(radiusOf(slider).toFloat())}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        Slider(
            value = slider,
            onValueChange = { slider = it },
            onValueChangeFinished = { ToolsSession.setRadius(radiusOf(slider)) },
            modifier = Modifier.weight(1f),
        )
    }
    HeightsRow(state, "Eye height (m)", "Target height (m)")
    HigherGroundHint(state)
    when {
        state.a == null -> Hint("Tap the map where you'll stand. Green ground is in sight, red is hidden.")
        state.needsTerrain -> TerrainNeeded(state, onGetTerrain)
        state.viewshed != null -> {
            val vs = state.viewshed
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${(vs.visibleFraction * 100).roundToInt()}% in sight",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text("within ${Fmt.distance(vs.radiusM.toFloat())}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Legend(Visible, "seen")
                Legend(Hidden, "hidden")
            }
            Text(
                (if (state.targetHeightM > 0) "Where something ${heightText(state.targetHeightM)} tall would be seen" else "Ground in sight") +
                    " from ${heightText(state.observerHeightM)} above A. Bare terrain only; trees and buildings aren't included.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

private fun heightText(m: Double) = if (m == m.roundToInt().toDouble()) "${m.roundToInt()} m" else "${localFixed(m, 1)} m"

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

// ---- coordinate converter ------------------------------------------------------------------------------------

@Composable
private fun CoordsBody(
    state: ToolsSession.State,
    myLocation: GeoFix?,
    mapCenter: () -> GeoPoint,
    onShowPoint: (GeoPoint) -> Unit,
    onShare: (GeoPoint) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val prefs = LocalPrefs.current
    val preferred by prefs.coordFormat.collectAsState()
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    // The format the typed text was read as, for the point it produced.
    var readAs by remember { mutableStateOf<Pair<GeoPoint, CoordFormat>?>(null) }

    fun go() {
        val text = input.trim()
        if (text.isEmpty()) return
        val parsed = Coords.parse(text, reference = state.a ?: mapCenter())
        val point = parsed?.point ?: (SharedLinks.parse(text) as? Shared.Point)?.let { GeoPoint(it.lat, it.lon) }
        if (point == null) {
            message = "Couldn't read that. Try 31.7767 35.2345, N31 46.602 E35 14.070, 36R YA 11594 17858, 222304 631558 or 8G3QQ6GM+MR."
            return
        }
        message = null
        readAs = parsed?.let { point to it.format }
        focus.clearFocus()
        ToolsSession.setA(point)
        onShowPoint(point)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            input, { input = it; message = null },
            placeholder = { Text("Type or paste coordinates, any format") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            isError = message != null,
        )
        IconButton(onClick = { go() }) { Icon(Icons.Filled.Search, contentDescription = "Find these coordinates") }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { myLocation?.let { ToolsSession.setA(GeoPoint(it.lat, it.lon)) } }, enabled = myLocation != null) {
            Icon(Icons.Filled.MyLocation, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("My location")
        }
        TextButton(onClick = { ToolsSession.setA(mapCenter()) }) {
            Icon(Icons.Filled.CenterFocusStrong, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Map centre")
        }
        Spacer(Modifier.weight(1f))
        state.a?.let { a ->
            IconButton(onClick = { onShare(a) }) { Icon(Icons.Filled.Share, contentDescription = "Share this point") }
        }
    }

    val a = state.a
    if (a == null) {
        Hint("Tap the map, use your location or the map centre, or type coordinates above.")
        return
    }
    val details = listOfNotNull(
        readAs?.takeIf { it.first == a }?.let { "Read as ${it.second.label}" },
        state.aGround?.let { "height ${it.roundToInt()} m" },
    )
    if (details.isNotEmpty()) Hint(details.joinToString(" · "))
    for (f in CoordFormat.entries) {
        val value = Coords.format(a, f) ?: continue
        Row(
            Modifier.fillMaxWidth().clickable { prefs.setCoordFormat(f) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = preferred == f, onClick = { prefs.setCoordFormat(f) })
            Column(Modifier.weight(1f)) {
                Text(f.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(value)) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy ${f.label}")
            }
        }
    }
    Text(
        "The selected format is used on pins and cards. ITM uses the EPSG Israel 1993 transformation (±0.5 m).",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A point in [format], with decimal degrees as the fallback. */
fun formatPoint(lat: Double, lon: Double, format: CoordFormat): String =
    Coords.format(GeoPoint(lat, lon), format) ?: Fmt.latLon(lat, lon)
