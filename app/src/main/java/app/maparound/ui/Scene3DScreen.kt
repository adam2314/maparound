package app.maparound.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.maparound.AppGraph
import app.maparound.map.MapController
import app.maparound.map.TileSources
import app.maparound.map.resolveBackground
import app.maparound.map.satelliteSource
import app.maparound.three.Camera3D
import app.maparound.three.CompositeTileTextures
import app.maparound.three.RasterTileTextures
import app.maparound.three.LocalFrame
import app.maparound.three.OfflineTileTextures
import app.maparound.three.OnlineTileTextures
import app.maparound.three.Scene3DView
import app.maparound.three.TerrainEngine
import kotlinx.coroutines.delay
import java.util.Locale

/** The map in 3D, streamed around the camera: terrain relief, the map draped on it, buildings. */
@Composable
fun Scene3DScreen(start: Camera3D, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var satellite by rememberSaveable { mutableStateOf(false) }
    var exaggeration by remember { mutableFloatStateOf(1.5f) }
    var buildings by remember { mutableStateOf(true) }
    // The camera survives rebuilding the view (satellite on/off).
    var camera by remember { mutableStateOf(start) }
    var glView by remember { mutableStateOf<Scene3DView?>(null) }
    var status by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }
    var hasBuildings by remember { mutableStateOf(false) }

    LaunchedEffect(glView) {
        while (true) {
            glView?.let { status = it.status }
            delay(400)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, glView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> glView?.onPause()
                Lifecycle.Event.ON_RESUME -> glView?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFFCCDDEF))) {
        key(satellite) {
            AndroidView(
                factory = { ctx ->
                    val prefs = AppGraph.prefs
                    val online = if (satellite) satelliteSource else TileSources.find(prefs.baseLayer.value, prefs.customSources.value)
                    val maps = AppGraph.mapFiles.files.value.map { it.file }
                    val style = MapController.OfflineStyle(
                        topo = prefs.topoStyle.value,
                        contours = prefs.contours.value,
                        hillshade = prefs.hillshade.value,
                        demDir = AppGraph.terrain.demDir,
                        terrainVersion = AppGraph.terrain.state.value.version,
                        trails = prefs.trails.value,
                        trailsDir = AppGraph.trails.dir,
                        trailsVersion = AppGraph.trails.state.value.version,
                    )
                    // Same as the 2D map: an online layer, or the offline map with its background
                    // (satellite / raster map) and the vector map on top if chosen.
                    val background = if (online == null) resolveBackground(prefs.mapBackground.value) else null
                    val textures = when {
                        online != null -> OnlineTileTextures(ctx, online)
                        background != null -> {
                            val under = when (background) {
                                is MapController.Background.Online -> OnlineTileTextures(ctx, background.source)
                                is MapController.Background.Raster -> RasterTileTextures(background.renderer)
                            }
                            if (prefs.vectorOnBackground.value && maps.isNotEmpty()) {
                                CompositeTileTextures(under, OfflineTileTextures(ctx, maps, style, hybrid = true))
                            } else {
                                under
                            }
                        }
                        else -> OfflineTileTextures(ctx, maps, style)
                    }
                    var view: Scene3DView? = null
                    val engine = TerrainEngine(LocalFrame(camera.lat, camera.lon), textures) { view?.requestRender() }
                    notes = listOfNotNull(
                        if (!engine.hasTerrain) "No terrain data: download it in Offline maps (⋮ → Terrain) for relief." else null,
                        if (online == null && maps.isEmpty()) "No offline maps: download some, or pick an online map in Layers." else null,
                    )
                    hasBuildings = engine.hasBuildings
                    Scene3DView(ctx, camera, engine).also {
                        view = it
                        glView = it
                    }
                },
                update = { v ->
                    v.setExaggeration(exaggeration)
                    v.setBuildingsVisible(buildings)
                },
                onRelease = { v ->
                    camera = v.camera
                    v.release()
                    if (glView === v) glView = null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(Modifier.align(Alignment.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallFloatingActionButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close 3D view") }
            SmallFloatingActionButton(onClick = { glView?.faceNorth() }) { Icon(Icons.Filled.Explore, contentDescription = "Face north") }
            SmallFloatingActionButton(
                onClick = {
                    glView?.let { camera = it.camera }
                    satellite = !satellite
                },
                containerColor = if (satellite) MaterialTheme.colorScheme.primary else FloatingActionButtonDefaults.containerColor,
                contentColor = if (satellite) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ) { Icon(Icons.Filled.SatelliteAlt, contentDescription = if (satellite) "Back to the map" else "Satellite imagery") }
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
                        String.format(Locale.getDefault(), "Relief ×%.1f", exaggeration),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(90.dp),
                    )
                    Slider(value = exaggeration, onValueChange = { exaggeration = it }, valueRange = 1f..4f, modifier = Modifier.weight(1f))
                }
                if (hasBuildings) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Buildings (close up)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = buildings, onCheckedChange = { buildings = it })
                    }
                }
                Text(
                    status.ifEmpty { "1 finger: move · pinch: zoom · 2 fingers: turn and tilt · double-tap: zoom in" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                notes.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
