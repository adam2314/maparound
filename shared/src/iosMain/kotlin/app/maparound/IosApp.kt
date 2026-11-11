package app.maparound

import app.maparound.data.MapCatalog
import app.maparound.data.MapPos
import app.maparound.data.OfflineData
import app.maparound.data.Prefs
import app.maparound.data.UserDefaultsStore
import app.maparound.ios.IosInstalledMaps
import app.maparound.ios.IosSearcher
import app.maparound.ios.IosSensors
import app.maparound.ios.IosMapDownloads
import app.maparound.ios.freeBytes
import app.maparound.ios.PendingRouting
import app.maparound.data.TerrainStore
import app.maparound.ios.PendingTrails
import app.maparound.map.TileSources
import app.maparound.map.maplibre.BasicLayers
import app.maparound.map.maplibre.MapStyle
import app.maparound.map.maplibre.ThemeStyle
import app.maparound.shared.fileSystem
import okio.Path.Companion.toPath
import app.maparound.map.maplibre.TileService
import app.maparound.places.PlaceRepository
import app.maparound.routing.PlannerSession
import app.maparound.shared.net.userAgent
import app.maparound.tools.ToolsSession
import app.maparound.tracks.TrackRepository
import app.maparound.routing.GeoPoint
import app.maparound.share.Incoming
import app.maparound.shared.ioDispatcher
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
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
    val tiles = TileService("$documents/dem")
    /** The map style, made from the Android map's theme (topo.xml in the app bundle). */
    val style = theme()?.let { MapStyle(it.layers, BasicLayers.GLYPHS, it.hillshade) } ?: MapStyle(BasicLayers.layers, BasicLayers.GLYPHS)
    val maps = IosInstalledMaps("$documents/maps")
    val places = PlaceRepository(documents)
    val tracks = TrackRepository(documents, UserDefaultsStore("tracks"))
    val sensors = IosSensors()
    val searcher = IosSearcher(places, maps)
    val downloads = IosMapDownloads(maps)
    val terrain = TerrainStore(
        demDir = "$documents/dem",
        prefs = prefs,
        coast = { null }, // cutting terrain at the coast comes later on iOS
        isMetered = { false },
        freeBytes = { freeBytes(documents) },
    )
    val offline = OfflineData(
        maps = maps,
        downloads = downloads,
        catalog = MapCatalog(),
        terrain = terrain,
        trails = PendingTrails,
        routing = PendingRouting,
    )

    /** CI screenshots: MAPAROUND_DEMO starts over the test map, MAPAROUND_TAB on a tab. */
    val demo: Boolean = env("MAPAROUND_DEMO") != null
    val startTab: String? = env("MAPAROUND_TAB")

    init {
        userAgent = "MapAround/$version (iOS; offline OSM map viewer)"
        ToolsSession.terrainSource = { terrain.demDir to terrain.state.value.version }
        PlannerSession.library = tracks
        if (demo) {
            prefs.saveLastPosition(MapPos(47.141, 9.521, 14))
            prefs.setBaseLayer(TileSources.OFFLINE_ID) // the offline map, drawn with the Topo style
            prefs.setContours(true)
            prefs.setHillshade(true)
        }
    }

    private fun env(name: String): String? = NSProcessInfo.processInfo.environment[name] as? String

    private val scope = MainScope()

    /**
     * A file or link opened with MapAround: GPX and .map files from Files or another app's
     * "Open in", maparound:// and map links. The map tab shows the result.
     */
    fun open(url: String) {
        if (!url.startsWith("file:")) {
            val near = prefs.lastPosition()?.let { GeoPoint(it.lat, it.lon) }
            Incoming.pending.value = Incoming.fromText(url, near) ?: Incoming.Item.Message("No location found in what was shared")
            return
        }
        val nsUrl = NSURL(string = url)
        val path = nsUrl.path ?: return
        val name = nsUrl.lastPathComponent ?: "Imported"
        scope.launch {
            Incoming.pending.value = Incoming.Item.Message("Opening $name…")
            Incoming.pending.value = withContext(ioDispatcher) {
                // Files opened in place (from Files) need permission for the time we read them.
                val scoped = nsUrl.startAccessingSecurityScopedResource()
                try {
                    openFile(path, name)
                } finally {
                    if (scoped) nsUrl.stopAccessingSecurityScopedResource()
                    // Copies iOS made for us ("Open in") go after the import.
                    if ("/Documents/Inbox/" in path) fileSystem.delete(path.toPath(), mustExist = false)
                }
            }
        }
    }

    private fun openFile(path: String, name: String): Incoming.Item {
        if (name.endsWith(".map", ignoreCase = true)) {
            return maps.import(path, name, move = false).fold(
                { Incoming.Item.Message("Offline map ${it.title} added") },
                { Incoming.Item.Message("Couldn't add the map: ${it.message}") },
            )
        }
        return runCatching {
            Incoming.importGpx(fileSystem.read(path.toPath()) { readByteArray() }, name.substringBeforeLast('.'), tracks, places)
        }.getOrElse {
            Incoming.Item.Message(if (name.endsWith(".gpx", ignoreCase = true)) "Couldn't read the GPX: ${it.message}" else "That file isn't a GPX file")
        }
    }

    private fun theme(): ThemeStyle.Result? = runCatching {
        val path = NSBundle.mainBundle.pathForResource("topo", "xml") ?: error("topo.xml is not in the app")
        ThemeStyle.convert(fileSystem.read(path.toPath()) { readUtf8() })
    }.onFailure { println("MapAround: map theme not loaded ($it), using the basic style") }.getOrNull()
}
