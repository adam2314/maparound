package app.maparound.map

import app.maparound.sources.Sources
import app.maparound.sources.SourceSetting
import app.maparound.sources.SourceKind
import app.maparound.data.LocalMap
import app.maparound.data.MapPos
import app.maparound.data.Prefs
import app.maparound.raster.RasterMap
import app.maparound.routing.GeoPoint
import app.maparound.sensors.GeoFix
import app.maparound.three.Camera3D
import app.maparound.tools.Viewshed

// The map as the shared map screen sees it. Android: today's mapsforge MapController behind an
// adapter; iOS: MapLibre (phase 3).

/** A party member, the meeting point or a Meshtastic radio on the map. */
data class PartyMarker(
    val key: String,
    val lat: Double,
    val lon: Double,
    val label: String,
    val color: Int,
    val kind: Kind,
    val stale: Boolean,
    /** A member's icon ([app.maparound.party.PartyIcon] code; 0: the initial). */
    val icon: Int = 0,
) {
    enum class Kind { MEMBER, SOS, MEET, RADIO }
}

/** A saved place on the map. */
data class PlaceMarker(val id: String, val lat: Double, val lon: Double, val label: String, val emoji: String, val color: Int)

/** A line of a map tool; [visible] colours line-of-sight runs (null: a plain measuring line). */
data class ToolLine(val points: List<GeoPoint>, val visible: Boolean?)

/** What the map shows underneath everything else. */
sealed class BaseMap {
    /**
     * An online tile service. A vector one ([OnlineSource.style]) is drawn from its style once it's
     * read ([vectorStyle]: its JSON), with its labels in [language].
     */
    data class Online(
        val source: OnlineSource,
        val vectorStyle: String? = null,
        val language: String = "",
    ) : BaseMap()

    /** The downloaded vector maps, with the style and background chosen in Layers. */
    data class Offline(
        val maps: List<LocalMap>,
        /** The style ([MapThemes] id). */
        val theme: String,
        /** The style's rule categories to draw (its options), or null for all. */
        val categories: Set<String>?,
        val contours: Boolean,
        val hillshade: Boolean,
        /** Changes when terrain tiles are added or removed. */
        val terrainVersion: Int,
        val trails: Boolean,
        /** Changes when trail data is added or removed. */
        val trailsVersion: Int,
        /** [Prefs.BACKGROUND_NONE], [Prefs.BACKGROUND_SATELLITE] or [Prefs.BACKGROUND_RASTER] + id. */
        val background: String,
        /** Roads, trails and labels drawn over the background. */
        val vectorOnTop: Boolean,
        /** The raster maps the background may name. */
        val rasterMaps: List<RasterMap>,
        /** Maps read over the internet where the installed ones don't reach ([app.maparound.data.MapStreaming]). */
        val streamed: List<RemoteMap> = emptyList(),
        /** The satellite imagery's address while it's the background ([satelliteSource]): a change redraws. */
        val satellite: String = "",
    ) : BaseMap() {
        /** The Topo style (MapAround's contours and trails are styled). */
        val topo: Boolean get() = theme == MapThemes.TOPO

        /** Whether contours and downloaded trails go to the renderer (see [MapThemes.drawsTerrain]). */
        val drawsTerrain: Boolean get() = MapThemes.drawsTerrain(theme)
    }
}

/** What happens on the map, for the screen to act on. Called on the main thread. */
interface MapListener {
    /** The user moved the map (stops following the location). */
    fun onUserGesture()
    fun onLongPress(point: GeoPoint)

    /** The finger moved after a long press without being lifted (the map doesn't pan meanwhile). */
    fun onLongPressMove(point: GeoPoint) {}

    /** The finger was lifted (or the gesture cancelled) after a long press. */
    fun onLongPressEnd(point: GeoPoint) {}

    /**
     * A finger just went down at [point]: true to drag what's there (a map tool's point) instead
     * of moving the map; then [onLongPressMove] and [onLongPressEnd] follow, and no tap.
     */
    fun onPress(point: GeoPoint): Boolean = false

    /** A tap on the map itself; true if the screen used it. */
    fun onTap(point: GeoPoint): Boolean

    /** A tap on a point of interest the map draws (when [onTap] didn't use it). */
    fun onPoiTap(poi: MapPoi) {}
    fun onWaypointTap(index: Int)
    fun onPartyMarkerTap(key: String)
    fun onPlaceTap(id: String)

    /** A country or locality of the data view ([app.maparound.stats.StatsLayers.TAP_COUNTRY]…). */
    fun onDataTap(key: String) {}

    /** A tap in query mode ([MapSurface.setQueryMode]): where. */
    fun onQueryTap(point: GeoPoint) {}
    fun onMyLocationTap()
}

/** What the zoom slider ([app.maparound.ui.ZoomScale]) drives: the map, the 3D view. */
interface ZoomTarget {
    /** The zooms it goes to. */
    val zoomRange: ClosedFloatingPointRange<Double>

    /** The zoom now, with its fraction. */
    fun zoomLevel(): Double

    /** Zooms about the middle of the view to [zoom], at once. */
    fun setZoom(zoom: Double)

    /** Ground metres per dp (a point on iOS) at the middle of the view: how much a finger covers. */
    fun metresPerDp(): Double
}

/** The platform's map view, driven by the shared map screen (main thread). */
interface MapSurface : ZoomTarget {
    var listener: MapListener?

    fun position(): MapPos
    fun moveTo(pos: MapPos)

    /** The zoom now with its fraction, in [position]'s terms. */
    override fun zoomLevel(): Double = position().zoom.toDouble()

    override fun setZoom(zoom: Double) = moveTo(position().copy(zoom = kotlin.math.round(zoom).toInt()))

    /** The zooms the map goes to, in [position]'s terms. */
    override val zoomRange: ClosedFloatingPointRange<Double> get() = 2.0..21.0
    fun animateTo(lat: Double, lon: Double, minZoom: Int? = null)

    /** Moves so that all [points] are in view. */
    fun showPoints(points: List<GeoPoint>)

    /**
     * Navigation's view ([app.maparound.routing.NavView]): ([lat], [lon]) low in the view, [bearing]
     * up, tilted, at [zoom] in [position]'s terms; or, not [tilted], flat and north up with the
     * position in the middle. Where the map can't, it just follows.
     */
    fun followNavigation(lat: Double, lon: Double, bearing: Double, zoom: Double, tilted: Boolean = true) = animateTo(lat, lon, zoom.toInt())

    /** Navigation's over: the flat map, north up. */
    fun endNavigation() = Unit

    /** A 3D camera looking at the same area, or null when there is none. */
    fun camera3D(): Camera3D?

    /** Back from the 3D view: the map shows where its [camera] was looking. */
    fun showFrom3D(camera: Camera3D) = Unit

    /** The points of interest drawn within [radiusDp] of [point], nearest first. */
    fun poisNear(point: GeoPoint, radiusDp: Double): List<MapPoi> = emptyList()

    /** Everything the map draws within [radiusDp] of [point], topmost first (a query); none where the platform can't say. */
    fun featuresAt(point: GeoPoint, radiusDp: Double): List<MapFeature> = emptyList()

    /** Query mode: every tap goes to [MapListener.onQueryTap], whatever it lands on. */
    fun setQueryMode(on: Boolean) {}

    /** May open files (raster backgrounds): call from a coroutine. */
    suspend fun setBaseMap(base: BaseMap)
    fun setTrailOverlay(enabled: Boolean)

    fun setPlannedRoute(line: List<GeoPoint>, waypoints: List<GeoPoint>)
    fun setShownTracks(tracks: Map<String, List<GeoPoint>>)
    fun setRecording(points: List<GeoPoint>)
    /** The map tools' points, lines, viewshed ring and image; [selected]: the point a tap moves next (ringed). */
    fun setTools(a: GeoPoint?, b: GeoPoint?, lines: List<ToolLine>, ring: Pair<GeoPoint, Double>?, viewshed: Viewshed?, selected: GeoPoint? = null)
    fun setPartyMarkers(markers: List<PartyMarker>)

    /**
     * A party member's way replayed from their history: all of it ([full]) faint, what's been
     * gone by the moment shown ([done]) in their [color] (ARGB). Empty lists: none.
     */
    fun setPartyTrail(full: List<GeoPoint>, done: List<GeoPoint>, color: Int) = Unit
    fun setPlaces(places: List<PlaceMarker>)
    fun updateLocation(fix: GeoFix?)
    fun setPin(point: GeoPoint?)

    /** What's in view (south, west, north, east), or null where the map can't say. */
    fun visibleBounds(): app.maparound.tools.MapGrid.View? = null

    /** The coordinate grid over the map, or none. */
    fun setGrid(grid: app.maparound.tools.MapGrid.Grid?) = Unit

    /**
     * The data view's layers ([app.maparound.stats.StatsLayers]' and [app.maparound.stats.TimeZones]'
     * GeoJSON), or null for none.
     */
    fun setStats(countries: String?, localities: String?, zones: String? = null) {}

    /**
     * The data view's tile layers: rasters over its colours, in this order (GPS traces, rain
     * radar), and street photos' vector tiles ([photoTiles]: their address, or null).
     */
    fun setDataTiles(rasters: List<OnlineSource>, photoTiles: String?) {}

    /** The data view's points in one of its GeoJSON sources (buses, weather); null: none. */
    fun setDataPoints(source: String, geoJson: String?) {}

    /** The data view's weather map (the web app): its spec, or null for none. */
    fun setWeatherMap(spec: String?) {}

    fun onResume()
    fun onPause()
    fun destroy()
}

/**
 * The satellite source used as a background (and in the 3D view): the user's (Sources), the
 * region's own (IGN's in France), else Esri's.
 */
val satelliteSource: OnlineSource
    get() {
        val esri = TileSources.builtIn.first { it.id == "esri_imagery" }
        return when (val s = Sources.setting(SourceKind.SATELLITE)) {
            is SourceSetting.Custom -> s.addresses.first().let { url ->
                esri.copy(id = "satellite_custom", name = TileSources.hostOf(url), urlTemplate = url, attribution = "© ${TileSources.hostOf(url)}", subdomains = listOf("a", "b", "c"))
            }
            else -> Sources.regional(SourceKind.SATELLITE) ?: esri
        }
    }

/** Attribution for the chosen background, for the map's credits line. */
fun backgroundAttribution(id: String, rasterMaps: List<RasterMap>): String? = when {
    id == Prefs.BACKGROUND_SATELLITE -> satelliteSource.attribution
    id.startsWith(Prefs.BACKGROUND_RASTER) -> rasterMaps.firstOrNull { it.id == id.removePrefix(Prefs.BACKGROUND_RASTER) }?.name
    else -> null
}
