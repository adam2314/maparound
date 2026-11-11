package app.maparound.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.maparound.AppGraph
import app.maparound.map.MapController
import app.maparound.map.TileSources
import app.maparound.map.resolveBackground
import app.maparound.map.satelliteSource
import app.maparound.three.CompositeTileTextures
import app.maparound.three.LocalFrame
import app.maparound.three.OfflineTileTextures
import app.maparound.three.OnlineTileTextures
import app.maparound.three.RasterTileTextures
import app.maparound.three.Scene3DView
import app.maparound.three.TerrainEngine
import app.maparound.data.file

/** The shared 3D screen's view on Android: OpenGL ES, textures from the same map as in 2D. */
@Composable
fun Scene3DAndroidView(args: Scene3DViewArgs) {
    var glView by remember { mutableStateOf<Scene3DView?>(null) }
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
    AndroidView(
        factory = { ctx ->
            val prefs = AppGraph.prefs
            val online = if (args.satellite) satelliteSource else TileSources.find(prefs.baseLayer.value, prefs.customSources.value)
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
            val engine = TerrainEngine(LocalFrame(args.camera.lat, args.camera.lon), textures) { view?.requestRender() }
            val notes = listOfNotNull(
                if (!engine.hasTerrain) "No terrain data: download it in Offline maps (⋮ → Terrain) for relief." else null,
                if (online == null && maps.isEmpty()) "No offline maps: download some, or pick an online map in Layers." else null,
            )
            Scene3DView(ctx, args.camera, engine).also {
                view = it
                glView = it
                args.onCreated(it, Scene3DSetup(notes, engine.hasBuildings))
            }
        },
        update = { v ->
            v.setExaggeration(args.exaggeration)
            v.setBuildingsVisible(args.buildings)
        },
        onRelease = { v ->
            args.onReleased(v)
            v.release()
            if (glView === v) glView = null
        },
        modifier = args.modifier,
    )
}
