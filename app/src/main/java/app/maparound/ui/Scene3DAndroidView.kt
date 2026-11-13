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
import app.maparound.map.BaseMap
import app.maparound.map.TileSources
import app.maparound.map.categories
import app.maparound.map.gl.GlStyles
import app.maparound.map.gl.GlTiles
import app.maparound.map.satelliteSource
import app.maparound.three.GlTileTextures
import app.maparound.three.Scene3DView
import app.maparound.three.TerrainEngine

/**
 * The shared 3D screen's view on Android: OpenGL ES on the whole globe, the map drawn on the
 * terrain by MapLibre in the same style as the 2D map (without its labels: the view stands names
 * up over the terrain).
 */
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
            val notes = listOfNotNull(
                if (!engine.hasTerrain) "No terrain data: download it in Offline maps (⋮ → Terrain) for relief." else null,
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
