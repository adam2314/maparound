package app.maparound

import app.maparound.map.MapRegions
import app.maparound.sources.Sources
import app.maparound.i18n.Units
import app.maparound.data.MapAroundCatalog
import app.maparound.i18n.T
import app.maparound.i18n.ltr
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
import app.maparound.map.MapTheme
import app.maparound.map.MapThemeLibrary
import app.maparound.map.MapThemes
import app.maparound.map.StyleMenu
import app.maparound.map.TileSources
import app.maparound.map.maplibre.BasicLayers
import app.maparound.map.maplibre.ThemeStyles
import app.maparound.shared.fileSystem
import okio.Path.Companion.toPath
import app.maparound.map.maplibre.TileService
import app.maparound.places.PlaceRepository
import app.maparound.terrain.MapCoastMasks
import app.maparound.routing.PlannerSession
import app.maparound.shared.net.userAgent
import app.maparound.tools.ToolsSession
import app.maparound.tracks.TrackRepository
import app.maparound.routing.GeoPoint
import app.maparound.share.Incoming
import app.maparound.shared.ioDispatcher
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    val prefs = Prefs(UserDefaultsStore("maparound"), onUnits = Units::set, onSources = Sources::set)

    init {
        // The download regions (regions.bin in the app bundle): the offer of the map made for where
        // the map looks, and the sources a region has of its own.
        MapRegions.source = {
            NSBundle.mainBundle.pathForResource("regions", "bin")?.let { path -> fileSystem.read(path.toPath()) { readByteArray() } }
        }
    }
    /** Label fonts in the app bundle (iosApp/fetch-fonts.sh); online fonts when they're missing. */
    private val fonts: String? = NSBundle.mainBundle.resourcePath?.let { "$it/Fonts" }?.takeIf { fileSystem.exists(it.toPath()) }
    val tiles = TileService("$documents/dem", fonts)
    /** One of the Android map's themes in the app bundle (topo.xml, topo2.xml). */
    private fun bundledTheme(name: String): String? = runCatching {
        val path = NSBundle.mainBundle.pathForResource(name, "xml") ?: error("$name.xml is not in the app")
        fileSystem.read(path.toPath()) { readUtf8() }
    }.onFailure { println("MapAround: map theme $name not loaded ($it)") }.getOrNull()

    /** Topo and Topo v2: their rules become MapLibre layers. Importing styles is Android-only for now. */
    val themes: MapThemeLibrary = IosMapThemes(bundledTheme("topo"), bundledTheme("topo2"))

    /** The map styles, one per style, made from their themes when first shown (the basic one if a theme can't be read). */
    val styles = ThemeStyles(BasicLayers.GLYPHS, themes::themeXml)
    val maps = IosInstalledMaps("$documents/maps")
    val places = PlaceRepository(documents)
    val tracks = TrackRepository(documents, UserDefaultsStore("tracks"))
    val sensors = IosSensors()
    val searcher = IosSearcher(places, maps)
    val downloads = IosMapDownloads(maps)
    val terrain = TerrainStore(
        demDir = "$documents/dem",
        prefs = prefs,
        // Terrain ends at the coastline of the installed maps.
        coast = { maps.files.value.filterNot { it.isWorld }.map { it.path }.takeIf { it.isNotEmpty() }?.let(::MapCoastMasks) },
        isMetered = { false },
        freeBytes = { freeBytes(documents) },
    )
    val offline = OfflineData(
        maps = maps,
        downloads = downloads,
        catalog = MapAroundCatalog(),
        terrain = terrain,
        routing = PendingRouting,
    )

    /** CI screenshots: MAPAROUND_DEMO starts over the test map, MAPAROUND_TAB on a tab. */
    val demo: Boolean = env("MAPAROUND_DEMO") != null
    val startTab: String? = env("MAPAROUND_TAB")

    init {
        userAgent = "MapAround/$version (iOS; offline OSM map viewer)"
        ToolsSession.terrainSource = { terrain.demDir to terrain.state.value.version }
        ToolsSession.terrainData = terrain
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
            Incoming.pending.value = Incoming.fromText(url, near) ?: Incoming.Item.Message(T.map.noLocationShared)
            return
        }
        val nsUrl = NSURL(string = url)
        val path = nsUrl.path ?: return
        val name = nsUrl.lastPathComponent ?: T.map.imported
        scope.launch {
            Incoming.pending.value = Incoming.Item.Message(T.map.opening(ltr(name)))
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
                { Incoming.Item.Message(T.map.offlineMapAdded(it.title)) },
                { Incoming.Item.Message(T.map.couldntAddMap(it.message)) },
            )
        }
        return runCatching {
            Incoming.importGpx(fileSystem.read(path.toPath()) { readByteArray() }, name.substringBeforeLast('.'), tracks, places)
        }.getOrElse {
            Incoming.Item.Message(if (name.endsWith(".gpx", ignoreCase = true)) T.map.couldntReadGpx(it.message) else T.map.notGpx)
        }
    }
}

/** The styles on iPhone: Topo and Topo v2, with their options. */
private class IosMapThemes(private val topoXml: String?, private val topo2Xml: String?) : MapThemeLibrary {

    override fun themeXml(themeId: String): String? = when (themeId) {
        MapThemes.TOPO -> topoXml
        MapThemes.TOPO2 -> topo2Xml
        else -> null
    }

    private val menus = mapOf(MapThemes.TOPO to topoXml, MapThemes.TOPO2 to topo2Xml)
        .mapValues { (_, xml) -> xml?.let { runCatching { StyleMenu.parse(it) }.getOrNull() } }
    override val themes: StateFlow<List<MapTheme>> =
        // Their names and descriptions in the app's language: MapThemes.title / about.
        MutableStateFlow(listOfNotNull(
            // Topo v2 first: the default (MapThemes.DEFAULT).
            topo2Xml?.let { MapTheme(MapThemes.TOPO2, "Topo v2", "") },
            MapTheme(MapThemes.TOPO, "Topo", ""),
        ))
    override fun menu(themeId: String): StyleMenu? = menus[themeId]
    override val canImport = false
    override fun import(name: String?, bytes: ByteArray): Result<List<MapTheme>> =
        Result.failure(UnsupportedOperationException(T.map.styleImportOnIphoneLater))
    override fun delete(themeId: String) = Unit
}
