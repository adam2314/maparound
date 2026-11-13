package app.maparound.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.maparound.AppGraph
import app.maparound.map.BaseMap
import app.maparound.map.TileSources
import app.maparound.map.categories
import app.maparound.map.gl.GlStyles
import app.maparound.map.gl.GlTiles
import app.maparound.map.satelliteSource
import app.maparound.terrain.GeoBounds
import app.maparound.three.Camera3D
import app.maparound.three.GlTileTextures
import app.maparound.three.Scene3DView
import app.maparound.three.TerrainEngine
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos

/**
 * The shared 3D screen's view on Android: OpenGL ES on the whole globe, the map drawn on the
 * terrain by MapLibre in the same style as the 2D map (without its labels: the view stands names
 * up over the terrain). The relief's elevation data comes as the view needs it (when the phone may
 * download), and the view rebuilds with it.
 */
@Composable
fun Scene3DAndroidView(args: Scene3DViewArgs) {
    var glView by remember { mutableStateOf<Scene3DView?>(null) }
    // Where the view was when it's rebuilt (with new elevation data).
    var lastCamera by remember { mutableStateOf<Camera3D?>(null) }
    val terrainVersion = AppGraph.terrain.state.collectAsState().value.version
    // Elevation around the camera, fetched as the view moves; cells that didn't come aren't asked again.
    val tried = remember { HashSet<Pair<Int, Int>>() }
    LaunchedEffect(Unit) {
        var problem: String? = null
        while (true) {
            val view = glView
            if (view != null) {
                around(view.camera)?.let { area ->
                    val terrain = AppGraph.terrain
                    val fresh = terrain.missingCells(area).filterNot { it in tried }
                    if (fresh.isNotEmpty()) {
                        problem = terrain.ensure("the 3D view", area)
                        if (problem == null) tried += fresh
                    }
                }
                val progress = AppGraph.terrain.state.value.progress
                view.note = when {
                    progress != null -> "Getting the relief data (${progress.done} of ${progress.total})\u2026"
                    else -> problem.orEmpty()
                }
            }
            delay(1_500)
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
    key(terrainVersion) {
        AndroidView(
            factory = { ctx ->
                val prefs = AppGraph.prefs
                // Same as the 2D map: an online layer, or the offline maps with their style,
                // background (satellite / raster map) and trails; the 3D button's satellite instead.
                val online = if (args.satellite) satelliteSource else TileSources.find(prefs.baseLayer.value, prefs.customSources.value)
                val maps = AppGraph.mapFiles.files.value
                val theme = prefs.mapTheme.value
                val base = online?.let { BaseMap.Online(it) } ?: BaseMap.Offline(
                    maps = maps,
                    theme = theme,
                    categories = AppGraph.themes.categories(theme, prefs.styleChoices.value),
                    contours = prefs.contours.value,
                    hillshade = false,
                    terrainVersion = AppGraph.terrain.state.value.version,
                    trails = prefs.trails.value,
                    trailsVersion = AppGraph.trails.state.value.version,
                    background = prefs.mapBackground.value,
                    vectorOnTop = prefs.vectorOnBackground.value,
                    rasterMaps = AppGraph.rasters.maps.value,
                )
                val service = GlTiles.service
                if (base is BaseMap.Offline) {
                    // Usually already so from the 2D map; quick when it is.
                    service.setMaps(maps.map { it.path })
                    if (base.contours) service.refreshTerrain()
                }
                val style = GlStyles.styleFor((base as? BaseMap.Offline)?.theme).build(base, service, forTextures = true)
                val maxZoom = online?.maxZoom?.coerceAtMost(18) ?: 17
                val textures = GlTileTextures(ctx, style, maxZoom)
                var view: Scene3DView? = null
                val engine = TerrainEngine(textures) { view?.requestRender() }
                // A rebuild (new elevation data) carries on where the view was, without the intro.
                val start = lastCamera
                Scene3DView(ctx, start ?: args.camera, engine, intro = args.intro && start == null).also {
                    view = it
                    glView = it
                    args.onCreated(it, Scene3DSetup(emptyList(), engine.hasBuildings))
                }
            },
            update = { v ->
                v.setExaggeration(args.exaggeration)
                v.setBuildingsVisible(args.buildings)
            },
            onRelease = { v ->
                lastCamera = v.camera
                args.onReleased(v)
                v.release()
                if (glView === v) glView = null
            },
            modifier = args.modifier,
        )
    }
}

/** The ground the 3D camera shows enough of to need relief, or null when it's too far out to matter. */
private fun around(c: Camera3D): GeoBounds? {
    if (c.distance > 150_000f) return null
    val r = (c.distance * 1.5).coerceIn(5_000.0, 40_000.0)
    val dLat = r / 111_320.0
    val dLon = dLat / cos(c.lat * PI / 180).coerceAtLeast(0.05)
    return GeoBounds(c.lat - dLat, c.lon - dLon, c.lat + dLat, c.lon + dLon)
}
