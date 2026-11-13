package app.maparound.web

import app.maparound.terrain.DemIndex
import app.maparound.data.MapAroundCatalog
import app.maparound.data.OfflineData
import app.maparound.data.Prefs
import app.maparound.data.WebStorageStore
import app.maparound.map.BorderLines
import app.maparound.map.WorldData
import app.maparound.map.WorldTile
import app.maparound.map.maplibre.BasicLayers
import app.maparound.map.maplibre.MapLibreSurface
import app.maparound.map.maplibre.MapStyle
import app.maparound.map.maplibre.ThemeStyle
import app.maparound.map.maplibre.TileService
import app.maparound.places.PlaceRepository
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.routing.Guidance
import app.maparound.routing.PlannerSession
import app.maparound.routing.RouteProfile
import app.maparound.routing.RouteResult
import app.maparound.routing.RoutingException
import app.maparound.routing.TrackStats
import app.maparound.shared.WebFiles
import app.maparound.shared.fileSystem
import app.maparound.shared.net.HttpException
import app.maparound.shared.net.httpGetText
import app.maparound.shared.net.userAgent
import app.maparound.shared.toUint8Array
import app.maparound.shared.util.urlEncode
import app.maparound.tools.ToolsSession
import app.maparound.tracks.TrackRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath

/**
 * The web app's objects (Android's AppGraph, the iPhone's IosApp). Files live in [WebFiles]
 * (memory, saved to IndexedDB), settings in localStorage. [base] is the app's address, for its
 * own files (fonts, symbols).
 */
class WebApp(topoXml: String?, world: ByteArray?, borders: ByteArray?, base: String) {
    val prefs = Prefs(WebStorageStore("prefs"))

    private val demDir = "${WebFiles.ROOT}/dem"

    /** The offline map's tiles: the built-in world (and later the downloaded maps), contours, hill shading. */
    val tiles = TileService(demDir).apply {
        baseUrl = TILE_BASE
        val worldData by lazy { world?.let(WorldData::decode) }
        val borderLines by lazy { borders?.let(BorderLines::decode) }
        this.world = WorldTile({ worldData }, { borderLines })
        setMaps(emptyList())
    }

    private val glyphs = "${base}fonts/{fontstack}/{range}.pbf"
    private val symbols = "${base}mapsforge/"

    /** The map style, made from the Topo theme; its options draw it again with fewer rule categories. */
    val style: MapStyle = topoXml?.let { xml ->
        runCatching { ThemeStyle.convert(xml) }.getOrNull()?.let { t ->
            MapStyle(t.layers, glyphs, t.hillshade) { categories -> ThemeStyle.convert(xml, categories) }
        }
    } ?: MapStyle(BasicLayers.layers, glyphs)

    val themes = WebMapThemes(topoXml)
    val maps = WebInstalledMaps()
    val places = PlaceRepository(WebFiles.ROOT)
    val tracks = TrackRepository(WebFiles.ROOT, WebStorageStore("tracks"))
    val sensors = WebSensors()

    /** The party, watch only (WebParty.kt). */
    val party = WebPartyWatcher(WebStorageStore("party"))
    val searcher = WebSearcher(places, maps)
    /** Elevation data for line of sight and viewshed, fetched as they need it. */
    val terrain = WebTerrain(demDir)

    val offline = OfflineData(
        maps = maps,
        downloads = WebDownloads,
        // mapsforge.org's server doesn't let a web page read its lists: MapAround's catalog only.
        catalog = MapAroundCatalog(useMapsforge = false),
        terrain = terrain,
        trails = PendingTrails,
        routing = PendingRouting,
        builtInWorld = true,
        addOns = false,
        terrainAddOn = true,
    )

    init {
        fileSystem.createDirectories(demDir.toPath())
        userAgent = "MapAround (web; offline OSM map viewer)"
        ToolsSession.terrainSource = { demDir to terrain.state.value.version }
        ToolsSession.terrainData = terrain
        PlannerSession.library = tracks
        PlannerSession.router = { points, profile -> route(points, profile) }
    }

    private var map: MapLibreSurface? = null

    /**
     * Starts the tile worker (url: tiles-worker.js), which draws the offline maps, and gives it the
     * elevation files, now and whenever they change, for the contour lines and hill shading.
     */
    fun startTiles(url: String) {
        if (maps.start(url)) syncTerrain()
        terrain.onFilesChanged = ::syncTerrain
    }

    /** The elevation files the tile worker has: name → size. */
    private val terrainSent = HashMap<String, Long>()

    /** Hands the tile worker the elevation files it doesn't have yet, and tells it of those gone. */
    private fun syncTerrain() {
        if (!maps.available) return
        val dir = demDir.toPath()
        val now = fileSystem.list(dir).filter { DemIndex.parseName(it.name) != null }
            .associate { it.name to (fileSystem.metadataOrNull(it)?.size ?: 0L) }
        for ((name, size) in now) {
            if (terrainSent[name] == size) continue
            tilesTerrain(name, fileSystem.read(dir / name) { readByteArray() }.toUint8Array())
            terrainSent[name] = size
        }
        for (name in terrainSent.keys - now.keys) {
            tilesTerrain(name, null)
            terrainSent.remove(name)
        }
    }

    /** A new map for the map screen (one on the page). */
    fun newMap(): MapLibreSurface {
        // Tiles come from the tile worker; this answers only where there's none (the built-in world).
        val native = WebNativeMap(EMPTY_STYLE, symbols) { url ->
            runCatching { tiles.respond(url.removePrefix(TILE_BASE))?.bytes?.toUint8Array() }.getOrNull()
        }
        return MapLibreSurface(native, tiles, style).also { map = it }
    }

    /**
     * The page is hidden or closed: keep where the map was and save files now. (The map screen
     * saves its position when the app pauses; a browser tab may close without telling.)
     */
    fun onHide() {
        map?.let { prefs.saveLastPosition(it.position()) }
        WebFiles.flush()
    }

    companion object {
        /** The offline map's tile addresses (mapview.js answers the "maparound:" protocol from [tiles]). */
        const val TILE_BASE = "maparound://t"
        private const val EMPTY_STYLE = """{"version":8,"sources":{},"layers":[]}"""
    }
}

private fun tilesTerrain(name: String, bytes: JsAny?): Unit = js("{ MapAroundTiles.dem(name, bytes); }")

/** Routes from the public brouter.de server (as Android's online fallback); "Line" measures straight lines. */
private fun route(points: List<GeoPoint>, profile: RouteProfile): RouteResult {
    if (points.size < 2) throw RoutingException("Add at least two points")
    if (profile == RouteProfile.LINE) {
        val stats = TrackStats.of(points)
        return RouteResult(points, stats, Geo.hikingTimeSeconds(stats.distanceM, stats.ascentM, stats.descentM), RouteResult.Source.STRAIGHT)
    }
    val lonlats = points.joinToString("|") { "${round6(it.lon)},${round6(it.lat)}" }
    val url = "https://brouter.de/brouter?lonlats=${urlEncode(lonlats)}&profile=${profile.brouterProfile}" +
        "&alternativeidx=0&format=geojson&timode=2"
    val body = try {
        httpGetText(url, timeoutMs = 60_000)
    } catch (e: HttpException) {
        throw RoutingException("No route here (a point may be away from roads and paths, or at sea)")
    } catch (e: Exception) {
        throw RoutingException("Couldn't reach the routing server: ${e.message}")
    }
    val feature = Json.parseToJsonElement(body).jsonObject["features"]!!.jsonArray[0].jsonObject
    val pts = feature["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { c ->
        val a = c.jsonArray
        GeoPoint(a[1].jsonPrimitive.double, a[0].jsonPrimitive.double, if (a.size > 2) a[2].jsonPrimitive.double else null)
    }
    val props = feature["properties"]?.jsonObject
    val stats = TrackStats.of(pts).let { s ->
        s.copy(distanceM = props?.get("track-length")?.jsonPrimitive?.content?.toDoubleOrNull() ?: s.distanceM)
    }
    val duration = if (profile == RouteProfile.HIKE) {
        Geo.hikingTimeSeconds(stats.distanceM, stats.ascentM, stats.descentM)
    } else {
        props?.get("total-time")?.jsonPrimitive?.content?.toLongOrNull()
    }
    return RouteResult(pts, stats, duration, RouteResult.Source.ONLINE, Guidance.brouterHints(body))
}

private fun round6(v: Double): String {
    val scaled = kotlin.math.round(v * 1e6) / 1e6
    return scaled.toString()
}
