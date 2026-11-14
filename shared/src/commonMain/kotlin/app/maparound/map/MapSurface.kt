package app.maparound.map

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
    /** An online tile service. */
    data class Online(val source: OnlineSource) : BaseMap()

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

    /** A tap on the map itself; true if the screen used it. */
    fun onTap(point: GeoPoint): Boolean

    /** A tap on a point of interest the map draws (when [onTap] didn't use it). */
    fun onPoiTap(poi: MapPoi) {}
    fun onWaypointTap(index: Int)
    fun onPartyMarkerTap(key: String)
    fun onPlaceTap(id: String)

    /** A country or locality of the data view ([app.maparound.stats.StatsLayers.TAP_COUNTRY]…). */
    fun onDataTap(key: String) {}
    fun onMyLocationTap()
}

/** The platform's map view, driven by the shared map screen (main thread). */
interface MapSurface {
    var listener: MapListener?

    fun position(): MapPos
    fun moveTo(pos: MapPos)

    /** The zoom now with its fraction, in [position]'s terms. */
    fun zoomLevel(): Double = position().zoom.toDouble()

    /** Zooms about the middle of the view to [zoom] (with its fraction), at once. */
    fun setZoom(zoom: Double) = moveTo(position().copy(zoom = kotlin.math.round(zoom).toInt()))

    /** The zooms the map goes to, in [position]'s terms. */
    val zoomRange: ClosedFloatingPointRange<Double> get() = 2.0..21.0
    fun animateTo(lat: Double, lon: Double, minZoom: Int? = null)

    /** Moves so that all [points] are in view. */
    fun showPoints(points: List<GeoPoint>)

    /** A 3D camera looking at the same area, or null when there is none. */
    fun camera3D(): Camera3D?

    /** Back from the 3D view: the map shows where its [camera] was looking. */
    fun showFrom3D(camera: Camera3D) = Unit

    /** The points of interest drawn within [radiusDp] of [point], nearest first. */
    fun poisNear(point: GeoPoint, radiusDp: Double): List<MapPoi> = emptyList()

    /** Ground metres per dp (a point on iOS) at the middle of the view: how much a finger covers. */
    fun metresPerDp(): Double

    /** May open files (raster backgrounds): call from a coroutine. */
    suspend fun setBaseMap(base: BaseMap)
    fun setTrailOverlay(enabled: Boolean)

    fun setPlannedRoute(line: List<GeoPoint>, waypoints: List<GeoPoint>)
    fun setShownTracks(tracks: Map<String, List<GeoPoint>>)
    fun setRecording(points: List<GeoPoint>)
    fun setTools(a: GeoPoint?, b: GeoPoint?, lines: List<ToolLine>, ring: Pair<GeoPoint, Double>?, viewshed: Viewshed?)
    fun setPartyMarkers(markers: List<PartyMarker>)
    fun setPlaces(places: List<PlaceMarker>)
    fun updateLocation(fix: GeoFix?)
    fun setPin(point: GeoPoint?)

    /** The data view's layers ([app.maparound.stats.StatsLayers]' GeoJSON), or null for none. */
    fun setStats(countries: String?, localities: String?) {}

    fun onResume()
    fun onPause()
    fun destroy()
}

/** The satellite source used as a background (and in the 3D view). */
val satelliteSource: OnlineSource get() = TileSources.builtIn.first { it.id == "esri_imagery" }

/** Attribution for the chosen background, for the map's credits line. */
fun backgroundAttribution(id: String, rasterMaps: List<RasterMap>): String? = when {
    id == Prefs.BACKGROUND_SATELLITE -> satelliteSource.attribution
    id.startsWith(Prefs.BACKGROUND_RASTER) -> rasterMaps.firstOrNull { it.id == id.removePrefix(Prefs.BACKGROUND_RASTER) }?.name
    else -> null
}
