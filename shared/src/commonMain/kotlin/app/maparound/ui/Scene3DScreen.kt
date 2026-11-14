package app.maparound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maparound.i18n.T
import app.maparound.map.satelliteSource
import app.maparound.shared.util.localFixed
import app.maparound.three.Camera3D
import kotlinx.coroutines.delay

/** What the shared 3D screen needs from the platform's 3D view once it exists; its zoom for the slider. */
interface Scene3DControl : app.maparound.map.ZoomTarget {
    /** Where the camera is now (kept when the view is rebuilt). */
    val camera: Camera3D

    /** A line about loading progress, or empty. */
    val status: String

    fun faceNorth()

    /** Moves the view to look at ([lat], [lon]) (a search's result). */
    fun lookAt(lat: Double, lon: Double) {}

    /** Closing: the view eases back to the 2D map's look (straight down, north up), then [done] on the main thread. */
    fun leave(done: () -> Unit) = done()
}

/** What the platform's 3D view found when it was set up. */
class Scene3DSetup(val notes: List<String>, val hasBuildings: Boolean)

/** How the shared screen wants the platform's 3D view (Android: OpenGL ES; iOS: Metal). */
class Scene3DViewArgs(
    val camera: Camera3D,
    /** Satellite imagery instead of the map (the view is rebuilt when this changes). */
    val satellite: Boolean,
    val exaggeration: Float,
    val buildings: Boolean,
    /** The screen's first view: it eases in from the 2D map (straight down) to [camera]; rebuilt views don't. */
    val intro: Boolean,
    val onCreated: (Scene3DControl, Scene3DSetup) -> Unit,
    val onReleased: (Scene3DControl) -> Unit,
    val modifier: Modifier,
)

/**
 * The map in 3D, streamed around the camera: terrain relief, the map draped on it, buildings.
 * The platform draws it ([view]). The map's own button column ([buttons]: given the view's zoom,
 * whether the relief's settings are open and their toggle, the way back to 2D, and where the panel
 * below starts) is where it is in 2D; this screen has the relief's settings (relief, buildings,
 * satellite), a compass while the view is turned, and the credits of what's shown ([mapCredits]: the
 * map's, as in 2D). [onControl]: the platform's view once it's there (null when it goes).
 */
@Composable
fun Scene3DScreen(
    start: Camera3D,
    mapCredits: String,
    onClose: (last: Camera3D?) -> Unit,
    onControl: (Scene3DControl?) -> Unit = {},
    buttons: @Composable (zoom: app.maparound.map.ZoomTarget?, relief: Boolean, onRelief: () -> Unit, close: () -> Unit, bottomLimit: Float?) -> Unit,
    view: @Composable (Scene3DViewArgs) -> Unit,
) {
    var satellite by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    // Where the panel at the bottom starts: the zoom slider fits above it.
    var bottomTop by remember { mutableStateOf<Float?>(null) }
    var exaggeration by remember { mutableFloatStateOf(1.5f) }
    var buildings by remember { mutableStateOf(true) }
    // The camera survives rebuilding the view (satellite on/off).
    var camera by remember { mutableStateOf(start) }
    var control by remember { mutableStateOf<Scene3DControl?>(null) }
    var status by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }
    var hasBuildings by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    // The view eases back to the 2D map's look, then the screen closes with where it was.
    fun close() {
        if (leaving) return
        leaving = true
        val c = control
        if (c == null) {
            onClose(null)
        } else {
            // Where it was looking, before it eases back.
            val last = c.camera
            c.leave { onClose(last) }
        }
    }
    PlatformBackHandler(onBack = { close() })

    var turned by remember { mutableStateOf(false) }
    LaunchedEffect(control) {
        onControl(control)
        while (true) {
            control?.let {
                status = it.status
                // Not facing north: the compass shows (a few degrees either way don't count).
                val yaw = ((it.camera.yaw % 360f) + 360f) % 360f
                turned = yaw in 3f..357f
            }
            delay(400)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFFCCDDEF))) {
        key(satellite) {
            view(
                Scene3DViewArgs(
                    camera = camera,
                    satellite = satellite,
                    exaggeration = exaggeration,
                    buildings = buildings,
                    intro = !shown,
                    onCreated = { c, setup ->
                        shown = true
                        control = c
                        notes = setup.notes
                        hasBuildings = setup.hasBuildings
                    },
                    onReleased = { c ->
                        camera = c.camera
                        if (control === c) control = null
                    },
                    modifier = Modifier.fillMaxSize(),
                ),
            )
        }

        // The map's own column, where it is in 2D (right; left in a right-to-left language).
        Box(Modifier.align(Alignment.TopEnd)) {
            buttons(control, settings, { settings = !settings }, { close() }, bottomTop)
        }
        // Turned away from north: a compass on the other side; a tap faces north.
        if (turned) {
            SmallFloatingActionButton(onClick = { control?.faceNorth() }, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
                Icon(Icons.Filled.Explore, contentDescription = T.map.faceNorth)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onGloballyPositioned { bottomTop = it.positionInRoot().y }.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (settings) Settings3D(
                exaggeration, { exaggeration = it }, hasBuildings, buildings, { buildings = it },
                satellite,
                {
                    control?.let { c -> camera = c.camera }
                    satellite = it
                },
                status, notes,
            )
            else if (status.isNotEmpty() || notes.isNotEmpty()) {
                // Closed: only what's loading, and problems.
                Text(
                    (listOf(status) + notes).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (notes.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            // What's shown, as on the 2D map: the map (or the imagery), the relief.
            Text(
                listOfNotNull(
                    if (satellite) satelliteSource.attribution else null,
                    mapCredits.ifBlank { null },
                    T.map.terrainAttribution,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** The relief's exaggeration, the buildings, satellite imagery, how to move, and what's loading. */
@Composable
private fun Settings3D(
    exaggeration: Float,
    onExaggeration: (Float) -> Unit,
    hasBuildings: Boolean,
    buildings: Boolean,
    onBuildings: (Boolean) -> Unit,
    satellite: Boolean,
    onSatellite: (Boolean) -> Unit,
    status: String,
    notes: List<String>,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    T.map.relief(localFixed(exaggeration.toDouble(), 1)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(90.dp),
                )
                Slider(value = exaggeration, onValueChange = onExaggeration, valueRange = 1f..4f, modifier = Modifier.weight(1f))
            }
            if (hasBuildings) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(T.map.buildingsCloseUp, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = buildings, onCheckedChange = onBuildings)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(T.map.satelliteImagery, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = satellite, onCheckedChange = onSatellite)
            }
            Text(
                status.ifEmpty { T.map.gestures3d },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notes.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
