package app.maparound.car

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.maparound.AppGraph
import app.maparound.data.MapPos
import app.maparound.map.MapController
import app.maparound.map.TileSources
import app.maparound.party.PartySession
import app.maparound.routing.GeoPoint
import app.maparound.routing.PlannerSession
import app.maparound.routing.RouteProfile
import app.maparound.sensors.LocationSource
import app.maparound.sensors.bestAltitude
import app.maparound.sensors.hasLocationPermission
import app.maparound.tracks.TrackRecorder
import app.maparound.ui.Fmt
import app.maparound.ui.partyMapMarkers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.model.DisplayModel
import kotlin.math.roundToInt

/**
 * Draws the map on the car screen. Android Auto hands us a Surface; a private virtual display on
 * that surface hosts an ordinary mapsforge MapView (in a Presentation), fed by the same live data
 * as the phone's map. Pan and zoom come from the car's touch screen / controller.
 */
class CarMapRenderer(private val carContext: CarContext, lifecycle: Lifecycle) : SurfaceCallback, DefaultLifecycleObserver {
    /** What the template shows above the map. */
    data class Status(val title: String, val text: String, val following: Boolean, val satellite: Boolean)

    private val _status = MutableStateFlow(Status("MapAround", "Starting…", following = true, satellite = false))
    val status: StateFlow<Status> = _status.asStateFlow()

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var controller: MapController? = null
    private var scope: CoroutineScope? = null
    private var surfaceSize = 0 to 0
    private var visibleArea: Rect? = null
    private var location: Location? = null
    private var following = true
    private var satellite = false
    private var scaleAccum = 1f

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) = release()

    // ---- surface ------------------------------------------------------------------------------------

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        release()
        val surface = container.surface ?: return
        if (container.width <= 0 || container.height <= 0) return
        try {
            val dm = carContext.getSystemService(DisplayManager::class.java)
            val vd = dm.createVirtualDisplay(
                "MapAround-car", container.width, container.height, container.dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            val p = Presentation(carContext, vd.display)
            // Map text and symbols for the car's density, a bit larger for reading at a glance.
            val scale = container.dpi / 160f / DisplayModel.getDeviceScaleFactor() * 1.25f
            val c = MapController(p.context, cacheTag = "car_", scale = scale)
            p.setContentView(c.mapView)
            p.show()
            c.onResume()
            val start = location?.let { MapPos(it.latitude, it.longitude, 15) }
                ?: AppGraph.prefs.lastPosition()?.let { it.copy(zoom = it.zoom.coerceAtLeast(12)) }
                ?: MapPos(30.0, 10.0, 3)
            c.moveTo(start)
            virtualDisplay = vd
            presentation = p
            controller = c
            surfaceSize = container.width to container.height
            startFeeding(c)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the car map", e)
            release()
        }
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        if (following) location?.let { centerOn(it.latitude, it.longitude) }
    }

    override fun onStableAreaChanged(stableArea: Rect) = Unit

    // ---- gestures from the car ----------------------------------------------------------------------

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val c = controller ?: return
        setFollowing(false)
        c.mapView.model.mapViewPosition.moveCenter(-distanceX.toDouble(), -distanceY.toDouble())
    }

    override fun onFling(velocityX: Float, velocityY: Float) = Unit

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        // Pinch steps in whole zoom levels; a double tap arrives as a factor of 2.
        scaleAccum *= scaleFactor
        when {
            scaleAccum >= 1.6f -> { zoomIn(); scaleAccum = 1f }
            scaleAccum <= 0.625f -> { zoomOut(); scaleAccum = 1f }
        }
    }

    fun zoomIn() {
        controller?.zoomIn()
        if (following) location?.let { centerOn(it.latitude, it.longitude) }
    }

    fun zoomOut() {
        controller?.zoomOut()
        if (following) location?.let { centerOn(it.latitude, it.longitude) }
    }

    /** Back to following the car's position. */
    fun recenter() {
        setFollowing(true)
        location?.let { centerOn(it.latitude, it.longitude) }
    }

    /** Switches between the phone's chosen map and satellite imagery (car screen only). */
    fun toggleSatellite() {
        satellite = !satellite
        pushStatus()
        controller?.let { applyBaseLayer(it) }
    }

    fun showDestination(p: GeoPoint) {
        setFollowing(false)
        controller?.animateTo(p.lat, p.lon, minZoom = 13)
        val me = location
        if (me != null) {
            PlannerSession.load(listOf(GeoPoint(me.latitude, me.longitude), p), RouteProfile.CAR)
        }
    }

    private fun setFollowing(value: Boolean) {
        if (following == value) return
        following = value
        pushStatus()
    }

    /** Puts the position in the middle of the part of the screen not covered by the car's UI. */
    private fun centerOn(lat: Double, lon: Double) {
        val c = controller ?: return
        val model = c.mapView.model
        val tile = model.displayModel.tileSize
        val mapSize = MercatorProjection.getMapSize(model.mapViewPosition.zoomLevel, tile)
        var x = MercatorProjection.longitudeToPixelX(lon, mapSize)
        var y = MercatorProjection.latitudeToPixelY(lat, mapSize)
        visibleArea?.let { v ->
            x += surfaceSize.first / 2.0 - v.exactCenterX()
            y += surfaceSize.second / 2.0 - v.exactCenterY()
        }
        val max = mapSize.toDouble()
        model.mapViewPosition.center = LatLong(
            MercatorProjection.pixelYToLatitude(y.coerceIn(0.0, max), mapSize),
            MercatorProjection.pixelXToLongitude(x.coerceIn(0.0, max), mapSize),
        )
    }

    // ---- data -------------------------------------------------------------------------------------------

    private var baseInputs: List<Any?> = emptyList()

    private fun startFeeding(c: MapController) {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val prefs = AppGraph.prefs
        // Same base map as the phone: offline vector (topo, contours, hill shading, trails) or an online source.
        s.launch {
            combine(
                listOf(
                    prefs.baseLayer, prefs.customSources, AppGraph.mapFiles.files, prefs.topoStyle, prefs.contours,
                    prefs.hillshade, AppGraph.terrain.state, prefs.trails, AppGraph.trails.state,
                    prefs.mapBackground, prefs.vectorOnBackground, AppGraph.rasters.maps,
                ),
            ) { it.toList() }.collect {
                baseInputs = it
                applyBaseLayer(c)
            }
        }
        s.launch {
            PlannerSession.state.collect { planner ->
                val line = when {
                    !planner.active -> emptyList()
                    planner.route != null -> planner.route!!.points
                    planner.profile == RouteProfile.LINE -> planner.waypoints
                    else -> emptyList()
                }
                c.setPlannedRoute(line, if (planner.active) planner.waypoints else emptyList())
                pushStatus()
            }
        }
        s.launch {
            combine(AppGraph.tracks.tracks, AppGraph.tracks.visible) { tracks, visible ->
                tracks.filter { it.id in visible }.associate { it.id to it.track.points }
            }.collect { c.setShownTracks(it) }
        }
        s.launch { TrackRecorder.state.collect { c.setRecording(it.points) } }
        s.launch {
            val clock = flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(30_000)
                }
            }
            combine(PartySession.state, clock) { party, now -> partyMapMarkers(party, now) }.collect {
                c.setPartyMarkers(it)
                pushStatus()
            }
        }
        if (carContext.hasLocationPermission()) {
            s.launch {
                LocationSource.updates(carContext).collect { loc ->
                    location = loc
                    c.updateLocation(loc)
                    if (following) centerOn(loc.latitude, loc.longitude)
                    pushStatus()
                }
            }
        }
        pushStatus()
    }

    private fun applyBaseLayer(c: MapController) {
        val prefs = AppGraph.prefs
        val satelliteSource = if (satellite) TileSources.builtIn.firstOrNull { it.id == "esri_imagery" } else null
        val online = satelliteSource ?: TileSources.find(prefs.baseLayer.value, prefs.customSources.value)
        if (online != null) {
            c.showOnline(online)
        } else {
            c.showOffline(
                AppGraph.mapFiles.files.value.map { it.file },
                MapController.OfflineStyle(
                    topo = prefs.topoStyle.value,
                    contours = prefs.contours.value,
                    hillshade = prefs.hillshade.value,
                    demDir = AppGraph.terrain.demDir,
                    terrainVersion = AppGraph.terrain.state.value.version,
                    trails = prefs.trails.value,
                    trailsDir = AppGraph.trails.dir,
                    trailsVersion = AppGraph.trails.state.value.version,
                ),
                // Same background as the phone (satellite / raster map under the vector map).
                app.maparound.map.resolveBackground(prefs.mapBackground.value),
                prefs.vectorOnBackground.value,
            )
        }
        val localTrails = online == null && prefs.trails.value && prefs.topoStyle.value && AppGraph.trails.state.value.cells > 0
        c.setTrailOverlay(prefs.trails.value && !localTrails)
    }

    private fun pushStatus() {
        val loc = location
        val speedUnit = AppGraph.prefs.speedUnit.value
        val gpsSpeed = loc?.takeIf { it.provider == LocationManager.GPS_PROVIDER && it.hasSpeed() }?.speed
        val title = when {
            !carContext.hasLocationPermission() -> "MapAround"
            loc == null -> "Waiting for GPS…"
            gpsSpeed != null -> "${(gpsSpeed * speedUnit.perMps).roundToInt()} ${speedUnit.label}"
            else -> "MapAround"
        }
        val parts = mutableListOf<String>()
        if (!carContext.hasLocationPermission()) parts += "Allow location in MapAround on your phone"
        loc?.bestAltitude()?.let { parts += "${it.roundToInt()} m" }
        if (loc != null && loc.hasBearing() && (gpsSpeed ?: 0f) > 1f) parts += Fmt.cardinal(loc.bearing)
        PlannerSession.state.value.route?.let { parts += "route ${Fmt.distance(it.stats.distanceM.toFloat())}" }
        PartySession.state.value.takeIf { it.active }?.let { p ->
            val n = p.members.count { it.position != null }
            parts += if (p.members.any { it.sos }) "SOS in party!" else "party: $n on map"
        }
        if (TrackRecorder.state.value.recording) parts += "recording"
        if (AppGraph.mapFiles.files.value.isEmpty() && TileSources.find(AppGraph.prefs.baseLayer.value, AppGraph.prefs.customSources.value) == null && !satellite) {
            parts += "no offline maps: download them on the phone"
        }
        _status.value = Status(title, parts.joinToString(" · ").ifEmpty { "Map" }, following, satellite)
    }

    private fun release() {
        scope?.cancel()
        scope = null
        controller?.let {
            it.onPause()
            runCatching { it.destroy() }
        }
        controller = null
        runCatching { presentation?.dismiss() }
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    companion object {
        private const val TAG = "CarMapRenderer"
    }
}
