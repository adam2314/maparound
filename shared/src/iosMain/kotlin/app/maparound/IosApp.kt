package app.maparound

import app.maparound.data.MapCatalog
import app.maparound.data.MapPos
import app.maparound.data.OfflineData
import app.maparound.data.Prefs
import app.maparound.data.UserDefaultsStore
import app.maparound.ios.IosInstalledMaps
import app.maparound.ios.IosSearcher
import app.maparound.ios.IosSensors
import app.maparound.ios.PendingDownloads
import app.maparound.ios.PendingRouting
import app.maparound.ios.PendingTerrain
import app.maparound.ios.PendingTrails
import app.maparound.map.maplibre.BasicLayers
import app.maparound.map.maplibre.MapStyle
import app.maparound.map.maplibre.ThemeStyle
import app.maparound.shared.fileSystem
import kotlinx.serialization.json.JsonObject
import okio.Path.Companion.toPath
import app.maparound.map.maplibre.TileService
import app.maparound.places.PlaceRepository
import app.maparound.routing.PlannerSession
import app.maparound.shared.net.userAgent
import app.maparound.tools.ToolsSession
import app.maparound.tracks.TrackRepository
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * The iPhone app's objects (the counterpart of Android's AppGraph). Everything lives in the
 * app's Documents folder: maps/, dem/, places, tracks — visible in the Files app later.
 */
object IosApp {
    val documents: String = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

    val version: String = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0"

    val prefs = Prefs(UserDefaultsStore("maparound"))
    val tiles = TileService()
    /** The map style, made from the Android map's theme (topo.xml in the app bundle). */
    val style = MapStyle(themeLayers(), BasicLayers.GLYPHS)
    val maps = IosInstalledMaps("$documents/maps")
    val places = PlaceRepository(documents)
    val tracks = TrackRepository(documents, UserDefaultsStore("tracks"))
    val sensors = IosSensors()
    val searcher = IosSearcher(places, maps)
    val offline = OfflineData(
        maps = maps,
        downloads = PendingDownloads,
        catalog = MapCatalog(),
        terrain = PendingTerrain("$documents/dem"),
        trails = PendingTrails,
        routing = PendingRouting,
    )

    /** CI screenshots: MAPAROUND_DEMO starts over the test map, MAPAROUND_TAB on a tab. */
    val demo: Boolean = env("MAPAROUND_DEMO") != null
    val startTab: String? = env("MAPAROUND_TAB")

    init {
        userAgent = "MapAround/$version (iOS; offline OSM map viewer)"
        ToolsSession.terrainSource = { "$documents/dem" to 0 }
        PlannerSession.library = tracks
        if (demo) prefs.saveLastPosition(MapPos(47.141, 9.521, 14))
    }

    private fun env(name: String): String? = NSProcessInfo.processInfo.environment[name] as? String

    private fun themeLayers(): List<JsonObject> = runCatching {
        val path = NSBundle.mainBundle.pathForResource("topo", "xml") ?: error("topo.xml is not in the app")
        ThemeStyle.convert(fileSystem.read(path.toPath()) { readUtf8() }).layers
    }.getOrElse {
        println("MapAround: map theme not loaded ($it), using the basic style")
        BasicLayers.layers
    }
}
