package app.maparound.map

import android.content.Context
import android.location.Location
import app.maparound.AppGraph
import app.maparound.data.MapPos
import app.maparound.routing.GeoPoint
import app.maparound.sensors.GeoFix
import app.maparound.three.Camera3D
import app.maparound.tools.Viewshed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import java.io.File

/** The shared map screen's [MapSurface] on Android: the mapsforge [MapController], unchanged. */
class AndroidMapSurface(context: Context) : MapSurface {
    val controller = MapController(context)

    override var listener: MapListener? = null

    init {
        controller.onUserGesture = { listener?.onUserGesture() }
        controller.onLongPress = { listener?.onLongPress(GeoPoint(it.latitude, it.longitude)) }
        controller.onTap = { listener?.onTap(GeoPoint(it.latitude, it.longitude)) ?: false }
        controller.onWaypointTap = { listener?.onWaypointTap(it) }
        controller.onPartyMarkerTap = { listener?.onPartyMarkerTap(it) }
        controller.onPlaceTap = { listener?.onPlaceTap(it) }
        controller.onMyLocationTap = { listener?.onMyLocationTap() }
    }

    override fun position(): MapPos = controller.position()
    override fun moveTo(pos: MapPos) = controller.moveTo(pos)
    override fun animateTo(lat: Double, lon: Double, minZoom: Int?) = controller.animateTo(lat, lon, minZoom)
    override fun showPoints(points: List<GeoPoint>) = controller.showPoints(points)
    override fun camera3D(): Camera3D? = controller.camera3D()

    override suspend fun setBaseMap(base: BaseMap) {
        when (base) {
            is BaseMap.Online -> controller.showOnline(base.source)
            is BaseMap.Offline -> {
                val style = MapController.OfflineStyle(
                    topo = base.topo,
                    contours = base.contours,
                    hillshade = base.hillshade,
                    demDir = AppGraph.terrain.demDir,
                    terrainVersion = base.terrainVersion,
                    trails = base.trails,
                    trailsDir = AppGraph.trails.dir,
                    trailsVersion = base.trailsVersion,
                )
                // Opening a raster map reads its tables: off the main thread.
                val background = withContext(Dispatchers.IO) { resolveBackground(base.background) }
                controller.showOffline(base.maps.map { File(it.path) }, style, background, base.vectorOnTop)
            }
        }
    }

    override fun setTrailOverlay(enabled: Boolean) = controller.setTrailOverlay(enabled)
    override fun setPlannedRoute(line: List<GeoPoint>, waypoints: List<GeoPoint>) = controller.setPlannedRoute(line, waypoints)
    override fun setShownTracks(tracks: Map<String, List<GeoPoint>>) = controller.setShownTracks(tracks)
    override fun setRecording(points: List<GeoPoint>) = controller.setRecording(points)

    override fun setTools(a: GeoPoint?, b: GeoPoint?, lines: List<ToolLine>, ring: Pair<GeoPoint, Double>?, viewshed: Viewshed?) =
        controller.setTools(a, b, lines, ring, viewshed)

    override fun setPartyMarkers(markers: List<PartyMarker>) = controller.setPartyMarkers(markers)
    override fun setPlaces(places: List<PlaceMarker>) = controller.setPlaces(places)

    override fun updateLocation(fix: GeoFix?) = controller.updateLocation(
        fix?.let { f ->
            Location("fix").apply {
                latitude = f.lat
                longitude = f.lon
                f.accuracyM?.let { accuracy = it }
            }
        },
    )

    override fun setPin(point: GeoPoint?) = controller.setPin(point?.let { LatLong(it.lat, it.lon) })

    override fun onResume() = controller.onResume()
    override fun onPause() = controller.onPause()
    override fun destroy() = controller.destroy()
}
