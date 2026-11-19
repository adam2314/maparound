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
import android.content.Context
import android.util.Log
import app.maparound.three.introReady
import app.maparound.three.introCovered
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import app.maparound.three.prepareIntro
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.maparound.AppGraph
import app.maparound.i18n.T
import app.maparound.map.BaseMap
import app.maparound.map.TileSources
import app.maparound.map.categories
import app.maparound.map.gl.GlStyles
import app.maparound.map.gl.GlTiles
import app.maparound.map.satelliteSource
import app.maparound.terrain.GeoBounds
import app.maparound.three.Camera3D
import app.maparound.three.GlTileTextures
import app.maparound.three.GridTileTextures
import java.io.File
import app.maparound.data.ElevationSources
import app.maparound.three.OnlineRelief
import app.maparound.three.FallbackTileTextures
import app.maparound.three.CachedTileTextures
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
    val context = LocalContext.current
    var glView by remember { mutableStateOf<Scene3DView?>(null) }
    // Where the view was when it's rebuilt (with new elevation data).
    var lastCamera by remember { mutableStateOf<Camera3D?>(null) }
    val terrain = AppGraph.terrain.state.collectAsState().value
    // New elevation data rebuilds the view (the meshes are made from it): once a download run is
    // over, not after every tile, carrying on from where the view is now.
    var terrainVersion by remember { mutableStateOf(terrain.version) }
    LaunchedEffect(terrain.version, terrain.progress == null) {
        if (terrain.version != terrainVersion && terrain.progress == null) {
            glView?.let { lastCamera = it.camera }
            terrainVersion = terrain.version
        }
    }
    // The coordinate grid is drawn into the map's pictures: turning it on or off, or another format, rebuilds the view.
    val showGrid = AppGraph.prefs.showGrid.collectAsState().value
    val gridFormat = AppGraph.prefs.coordFormat.collectAsState().value
    val grid = gridFormat.takeIf { showGrid }
    // Elevation around the camera, fetched as the view moves; cells that didn't come aren't asked again.
    val tried = remember { HashSet<Pair<Int, Int>>() }

    // Opening (the intro): the relief there, then what the view shows first, before it appears;
    // the 2D map shows meanwhile. Then it turns into the view in one go, with nothing popping in.
    var ready by remember { mutableStateOf(!args.intro) }
    val first = remember { FirstEngine() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(size.width > 0 && size.height > 0) {
        if (ready || size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val terrain = AppGraph.terrain
        around(args.camera)?.let { area ->
            val missing = terrain.missingCells(area)
            if (missing.isNotEmpty() && terrain.ensure(T.map.the3dView, area) == null) {
                tried += missing
                args.onPreparing(T.map.loadingRelief)
                // Until it's in, or the download ended without it (offline…), or too long.
                val since = System.currentTimeMillis()
                var downloading = false
                while (System.currentTimeMillis() - since < RELIEF_WAIT_MS && terrain.missingCells(area).isNotEmpty()) {
                    val p = terrain.state.value.progress
                    if (p != null) {
                        downloading = true
                        args.onPreparing(T.map.gettingRelief(minOf(p.done + 1, p.total), p.total))
                    } else if (downloading || System.currentTimeMillis() - since > 2_000) {
                        break
                    }
                    delay(250)
                }
            }
        }
        // The engine reads the relief that's there now; what the view shows first loads.
        args.onPreparing(T.map.loadingRelief)
        val e3 = makeEngine(context, args.satellite, grid)
        first.engine = e3
        val plan = e3.engine.prepareIntro(args.camera, size.width.toFloat() / size.height, args.exaggeration, args.buildings)
        val since = System.currentTimeMillis()
        // Sharp enough first (straight down all in, the turned view at most one level coarser); then
        // a moment for the finest ones, which otherwise sharpen in place once the view is there.
        val covered = withTimeoutOrNull(TILES_WAIT_MS) { while (!e3.engine.introCovered(plan)) delay(100) } != null
        val coveredMs = System.currentTimeMillis() - since
        val sharp = covered && withTimeoutOrNull(FINE_WAIT_MS) { while (!e3.engine.introReady(plan)) delay(100) } != null
        Log.i("Scene3D", "First view: covered ${if (covered) "after $coveredMs ms" else "not in time"}, finest tiles ${if (sharp) "in" else "still coming"} " +
            "after ${System.currentTimeMillis() - since} ms (${plan.down.leaves.size} + ${plan.ahead.leaves.size}); ${e3.engine.pending} loading")
        // The relief that came is the version the view starts with (no rebuild right away).
        terrainVersion = terrain.state.value.version
        args.onPreparing(null)
        ready = true
    }
    // Closed before the view came: the engine made for it goes too.
    DisposableEffect(Unit) { onDispose { first.engine?.engine?.close(); first.engine = null } }
    LaunchedEffect(Unit) {
        var problem: String? = null
        while (true) {
            val view = glView
            if (view != null) {
                around(view.camera)?.let { area ->
                    val terrain = AppGraph.terrain
                    val fresh = terrain.missingCells(area).filterNot { it in tried }
                    if (fresh.isNotEmpty()) {
                        problem = terrain.ensure(T.map.the3dView, area)
                        if (problem == null) tried += fresh
                    }
                }
                val progress = AppGraph.terrain.state.value.progress
                view.note = when {
                    progress != null -> T.map.gettingRelief(progress.done, progress.total)
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
    Box(args.modifier.onSizeChanged { size = it }) {
    if (ready) key(terrainVersion, grid) {
        AndroidView(
            factory = { ctx ->
                // The engine that loaded the first view, else a new one (a rebuild, satellite on/off).
                val e3 = first.engine?.also { first.engine = null } ?: makeEngine(ctx, args.satellite, grid)
                // A rebuild (new elevation data) carries on where the view was, without the intro.
                val start = lastCamera
                Scene3DView(ctx, start ?: args.camera, e3.engine, intro = args.intro && start == null).also {
                    e3.view = it
                    glView = it
                    it.onFirstFrame = args.onFirstFrame
                    args.onCreated(it, Scene3DSetup(emptyList(), e3.engine.hasBuildings, firstFrameLater = true))
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
            modifier = Modifier.fillMaxSize(),
        )
    }
    }
}

/** The 3D view's engine, and the view it wakes when tiles are ready (set once the view exists). */
private class Engine3D(val engine: TerrainEngine) {
    @Volatile var view: Scene3DView? = null
}

/** The engine made before the first view, until the view takes it. */
private class FirstEngine {
    var engine: Engine3D? = null
}

/**
 * The 3D view's engine, drawing the map as the 2D map does: an online layer, or the offline maps
 * with their style, background (satellite / raster map) and trails; the 3D button's satellite instead.
 */
private fun makeEngine(ctx: Context, satellite: Boolean, grid: app.maparound.tools.CoordFormat?): Engine3D {
    val prefs = AppGraph.prefs
    val online = if (satellite) satelliteSource else TileSources.find(prefs.baseLayer.value, prefs.customSources.value)
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
        streamed = AppGraph.streaming.maps.value,
    )
    val service = GlTiles.service
    if (base is BaseMap.Offline) {
        // Usually already so from the 2D map; quick when it is.
        service.setMaps(maps.map { it.path }, base.streamed)
        if (base.contours) service.refreshTerrain()
    }
    val style = GlStyles.styleFor((base as? BaseMap.Offline)?.theme).build(base, service, forTextures = true)
    val maxZoom = online?.maxZoom?.coerceAtMost(18) ?: 17
    // An online map that isn't the world's (a region's, swisstopo, or IGN's imagery) hasn't got every
    // tile: the world's (OSM France, or Esri's imagery) where it hasn't.
    val world = TileSources.builtIn.first { it.id == if (satellite) "esri_imagery" else TileSources.DEFAULT_ID }
    val worldStyle = online?.takeIf { it.id != world.id }?.let {
        GlStyles.styleFor(null).build(BaseMap.Online(world), service, forTextures = true)
    }
    val drawn = GlTileTextures(ctx, style, maxZoom)
    // Kept on disk by style (its tiles' addresses carry their data's version): seen before, shown at once.
    val pictures = if (worldStyle == null) drawn else FallbackTileTextures(drawn) { GlTileTextures.createFrom(ctx, worldStyle, maxZoom) }
    // The grid on them, as on the 2D map (kept apart from pictures without it).
    val textures = CachedTileTextures(
        if (grid == null) pictures else GridTileTextures(pictures, grid),
        File(ctx.cacheDir, "3d-pictures"),
        style + "\n" + worldStyle.orEmpty() + "\ngrid=" + grid?.name.orEmpty() + GRID_DRAWING,
        incomplete = { GlTiles.incompleteTiles },
    )
    // Relief where no elevation has been downloaded: the 3D relief source (Settings → Sources).
    val reliefSource = prefs.terrain3dSource.value
    val relief = OnlineRelief(File(ctx.cacheDir, "3d-relief"), reliefSource?.template ?: ElevationSources.DEFAULT_3D, reliefSource?.mapbox == true)
    lateinit var e3: Engine3D
    e3 = Engine3D(TerrainEngine(textures, relief) { e3.view?.requestRender() })
    return e3
}

/**
 * How long opening the 3D view waits for its relief to download, then for its first view without
 * holes, then for that view's finest tiles.
 */
private const val RELIEF_WAIT_MS = 20_000L

/** Changes when the grid is drawn differently, so pictures kept with the old drawing aren't used. */
private const val GRID_DRAWING = "/2"
private const val TILES_WAIT_MS = 8_000L
private const val FINE_WAIT_MS = 1_500L

/** The ground the 3D camera shows enough of to need relief, or null when it's too far out to matter. */
private fun around(c: Camera3D): GeoBounds? {
    if (c.distance > 150_000f) return null
    val r = (c.distance * 1.5).coerceIn(5_000.0, 40_000.0)
    val dLat = r / 111_320.0
    val dLon = dLat / cos(c.lat * PI / 180).coerceAtLeast(0.05)
    return GeoBounds(c.lat - dLat, c.lon - dLon, c.lat + dLat, c.lon + dLon)
}
