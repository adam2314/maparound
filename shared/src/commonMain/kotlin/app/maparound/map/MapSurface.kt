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
        val topo: Boolean,
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
    ) : BaseMap()
}

/** What happens on the map, for the screen to act on. Called on the main thread. */
interface MapListener {
    /** The user moved the map (stops following the location). */
    fun onUserGesture()
    fun onLongPress(point: GeoPoint)

    /** A tap on the map itself; true if the screen used it. */
    fun onTap(point: GeoPoint): Boolean
    fun onWaypointTap(index: Int)
    fun onPartyMarkerTap(key: String)
    fun onPlaceTap(id: String)
    fun onMyLocationTap()
}

/** The platform's map view, driven by the shared map screen (main thread). */
interface MapSurface {
    var listener: MapListener?

    fun position(): MapPos
    fun moveTo(pos: MapPos)
    fun animateTo(lat: Double, lon: Double, minZoom: Int? = null)

    /** Moves so that all [points] are in view. */
    fun showPoints(points: List<GeoPoint>)

    /** A 3D camera looking at the same area, or null when there is none. */
    fun camera3D(): Camera3D?

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
