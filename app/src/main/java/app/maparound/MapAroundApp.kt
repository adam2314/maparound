package app.maparound

import app.maparound.places.PlaceRepository
import app.maparound.raster.RasterMapRepository
import android.app.Application
import app.maparound.data.MapAroundCatalog
import app.maparound.shared.net.userAgent
import app.maparound.data.MapDownloader
import app.maparound.data.MapFilesRepository
import app.maparound.data.Prefs
import app.maparound.data.SharedPrefsStore
import app.maparound.data.TerrainRepository
import app.maparound.routing.RoutePlanner
import app.maparound.routing.RoutingDataRepository
import app.maparound.party.PartySession
import app.maparound.routing.AndroidSpeaker
import app.maparound.routing.NavigationService
import app.maparound.routing.NavigationSession
import app.maparound.routing.PlannerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import app.maparound.data.DownloadService
import app.maparound.map.AndroidMapThemes
import app.maparound.map.BorderLines
import app.maparound.map.WorldData
import app.maparound.map.WorldTile
import app.maparound.map.gl.GlTiles
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import app.maparound.tools.ToolsSession
import app.maparound.tracks.TrackRepository
import app.maparound.data.TrailRepository

class MapAroundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}

/** Tiny service locator – the app is small enough not to need a DI framework. */
object AppGraph {
    lateinit var prefs: Prefs
        private set
    lateinit var mapFiles: MapFilesRepository
        private set
    lateinit var downloader: MapDownloader
        private set
    lateinit var terrain: TerrainRepository
        private set
    lateinit var routing: RoutingDataRepository
        private set
    lateinit var planner: RoutePlanner
        private set
    lateinit var trails: TrailRepository
        private set
    lateinit var tracks: TrackRepository
        private set
    lateinit var places: PlaceRepository
        private set
    lateinit var themes: AndroidMapThemes
        private set
    lateinit var rasters: RasterMapRepository
        private set
    val catalog = MapAroundCatalog()
    private lateinit var app: Application

    /** The world's country borders for the offline maps (assets/borders.bin), read on first use. */
    val borders: BorderLines? by lazy {
        runCatching { app.assets.open("borders.bin").use { BorderLines.decode(it.readBytes()) } }.getOrNull()
    }

    /** Land, sea, lakes, rivers and names for the offline maps (assets/world.bin), read on first use. */
    val world: WorldData? by lazy {
        runCatching { app.assets.open("world.bin").use { WorldData.decode(it.readBytes()) } }.getOrNull()
    }

    /** The built-in world per tile, for the map (vector tiles) and the 3D view (mapsforge). */
    val worldTile = WorldTile({ world }, { borders })

    fun init(app: Application) {
        this.app = app
        // Read the built-in world in the background, so the first map frame doesn't wait for it.
        MainScope().launch(Dispatchers.Default) {
            world
            borders
        }
        userAgent = USER_AGENT
        prefs = Prefs(SharedPrefsStore(app, "maparound"))
        mapFiles = MapFilesRepository(app)
        downloader = MapDownloader(app, mapFiles)
        terrain = TerrainRepository(app, prefs, mapFiles)
        themes = AndroidMapThemes(app)
        ToolsSession.terrainSource = { terrain.demDir.path to terrain.state.value.version }
        ToolsSession.terrainData = terrain
        routing = RoutingDataRepository(app)
        planner = RoutePlanner(app, routing, terrain.demDir)
        trails = TrailRepository(app, prefs)
        // A terrain or trail download started: keep it going if the app goes to the background.
        MainScope().launch {
            combine(terrain.state, trails.state) { t, r -> t.progress != null || r.progress != null }
                .distinctUntilChanged()
                .collect { running -> if (running) DownloadService.start(app) }
        }
        tracks = TrackRepository((app.getExternalFilesDir(null) ?: app.filesDir).path, SharedPrefsStore(app, "tracks"))
        places = PlaceRepository((app.getExternalFilesDir(null) ?: app.filesDir).path)
        PlannerSession.router = { points, profile -> planner.plan(points, profile) }
        PlannerSession.library = tracks
        NavigationSession.speaker = AndroidSpeaker(app)
        NavigationSession.voiceOn = { prefs.navVoice.value }
        // Navigation started on the phone or the car: keep it going with the screen off.
        MainScope().launch {
            NavigationSession.state.map { it.active }.distinctUntilChanged().collect { active ->
                if (active) NavigationService.start(app)
            }
        }
        rasters = RasterMapRepository(app)
        // The map draws with MapLibre, from tiles the app makes (needs the terrain folder and the world).
        GlTiles.init(app)
        PartySession.init(app)
    }
}

const val USER_AGENT = "MapAround/${BuildConfig.VERSION_NAME} (Android; offline OSM map viewer)"
