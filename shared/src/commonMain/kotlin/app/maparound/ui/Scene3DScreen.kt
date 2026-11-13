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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.maparound.i18n.T
import app.maparound.shared.util.localFixed
import app.maparound.three.Camera3D
import kotlinx.coroutines.delay

/** What the shared 3D screen needs from the platform's 3D view once it exists. */
interface Scene3DControl {
    /** Where the camera is now (kept when the view is rebuilt). */
    val camera: Camera3D

    /** A line about loading progress, or empty. */
    val status: String

    fun faceNorth()

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
 * The platform draws it ([view]); this screen has the controls.
 */
@Composable
fun Scene3DScreen(start: Camera3D, onClose: (last: Camera3D?) -> Unit, view: @Composable (Scene3DViewArgs) -> Unit) {
    var satellite by rememberSaveable { mutableStateOf(false) }
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

    LaunchedEffect(control) {
        while (true) {
            control?.let { status = it.status }
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

        Row(Modifier.align(Alignment.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallFloatingActionButton(onClick = { close() }) { Icon(Icons.Filled.Close, contentDescription = T.map.close3d) }
            SmallFloatingActionButton(onClick = { control?.faceNorth() }) { Icon(Icons.Filled.Explore, contentDescription = T.map.faceNorth) }
            SmallFloatingActionButton(
                onClick = {
                    control?.let { camera = it.camera }
                    satellite = !satellite
                },
                containerColor = if (satellite) MaterialTheme.colorScheme.primary else FloatingActionButtonDefaults.containerColor,
                contentColor = if (satellite) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ) { Icon(Icons.Filled.SatelliteAlt, contentDescription = if (satellite) T.map.backToMap else T.map.satelliteImagery) }
        }

        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
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
                    Slider(value = exaggeration, onValueChange = { exaggeration = it }, valueRange = 1f..4f, modifier = Modifier.weight(1f))
                }
                if (hasBuildings) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(T.map.buildingsCloseUp, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = buildings, onCheckedChange = { buildings = it })
                    }
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
}
