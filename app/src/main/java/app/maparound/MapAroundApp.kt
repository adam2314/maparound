package app.maparound

import app.maparound.places.PlaceRepository
import app.maparound.raster.RasterMapRepository
import android.app.Application
import app.maparound.data.MapCatalog
import app.maparound.data.MapDownloader
import app.maparound.data.MapFilesRepository
import app.maparound.data.Prefs
import app.maparound.data.SharedPrefsStore
import app.maparound.data.TerrainRepository
import app.maparound.routing.RoutePlanner
import app.maparound.routing.RoutingDataRepository
import app.maparound.party.PartySession
import app.maparound.routing.PlannerSession
import app.maparound.tools.ToolsSession
import app.maparound.tracks.TrackRepository
import app.maparound.data.TrailRepository
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

class MapAroundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidGraphicFactory.createInstance(this)
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
    lateinit var rasters: RasterMapRepository
        private set
    val catalog = MapCatalog()

    fun init(app: Application) {
        prefs = Prefs(SharedPrefsStore(app, "maparound"))
        mapFiles = MapFilesRepository(app)
        downloader = MapDownloader(app, mapFiles)
        terrain = TerrainRepository(app, prefs, mapFiles)
        ToolsSession.terrainSource = { terrain.demDir.path to terrain.state.value.version }
        routing = RoutingDataRepository(app)
        planner = RoutePlanner(app, routing, terrain.demDir)
        trails = TrailRepository(app, prefs)
        tracks = TrackRepository((app.getExternalFilesDir(null) ?: app.filesDir).path, SharedPrefsStore(app, "tracks"))
        places = PlaceRepository((app.getExternalFilesDir(null) ?: app.filesDir).path)
        PlannerSession.router = { points, profile -> planner.plan(points, profile) }
        PlannerSession.library = tracks
        rasters = RasterMapRepository(app)
        PartySession.init(app)
    }
}

const val USER_AGENT = "MapAround/${BuildConfig.VERSION_NAME} (Android; offline OSM map viewer)"
