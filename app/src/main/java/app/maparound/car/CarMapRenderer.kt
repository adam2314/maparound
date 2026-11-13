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
import app.maparound.i18n.T
import app.maparound.map.AndroidMapSurface
import app.maparound.map.BaseMap
import app.maparound.map.MapThemes
import app.maparound.map.TileSources
import app.maparound.map.categories
import app.maparound.map.maplibre.MapLibreSurface
import app.maparound.map.satelliteSource
import app.maparound.party.PartySession
import app.maparound.routing.GeoPoint
import app.maparound.routing.NavigationSession
import app.maparound.routing.PlannerSession
import app.maparound.routing.RouteProfile
import app.maparound.sensors.LocationSource
import app.maparound.sensors.bestAltitude
import app.maparound.sensors.hasLocationPermission
import app.maparound.sensors.toGeoFix
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Draws the map on the car screen. Android Auto hands us a Surface; a private virtual display on
 * that surface hosts the same MapLibre map as the phone's (in a Presentation), fed by the same live
 * data. Pan and zoom come from the car's touch screen / controller.
 */
class CarMapRenderer(private val carContext: CarContext, lifecycle: Lifecycle) : SurfaceCallback, DefaultLifecycleObserver {
    /** What the template shows above the map. */
    data class Status(val title: String, val text: String, val following: Boolean, val satellite: Boolean)

    private val _status = MutableStateFlow(Status("MapAround", T.nav.carStarting, following = true, satellite = false))
    val status: StateFlow<Status> = _status.asStateFlow()

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var map: AndroidMapSurface? = null
    private var scope: CoroutineScope? = null
    private var surfaceSize = 0 to 0
    private var visibleArea: Rect? = null
    private var location: Location? = null
    private var following = true
    private var satellite = false
    private var scaleAccum = 1f
    private var startWhenRouted = false

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
            val c = AndroidMapSurface(p.context, textScale = 1.25f)
            p.setContentView(c.native.view)
            p.show()
            c.onResume()
            val start = location?.let { MapPos(it.latitude, it.longitude, 15) }
                ?: AppGraph.prefs.lastPosition()?.let { it.copy(zoom = it.zoom.coerceAtLeast(12)) }
                ?: MapPos(30.0, 10.0, 3)
            c.moveTo(start)
            virtualDisplay = vd
            presentation = p
            map = c
            surfaceSize = container.width to container.height
            visibleArea?.let(::applyVisibleArea)
            startFeeding(c)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the car map", e)
            release()
        }
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        applyVisibleArea(visibleArea)
        if (following) location?.let { centerOn(it.latitude, it.longitude) }
    }

    /** The map centres in the part of the screen the car's own controls leave free. */
    private fun applyVisibleArea(v: Rect) {
        val (w, h) = surfaceSize
        if (w <= 0 || h <= 0) return
        map?.native?.setPadding(v.left, v.top, (w - v.right).coerceAtLeast(0), (h - v.bottom).coerceAtLeast(0))
    }

    override fun onStableAreaChanged(stableArea: Rect) = Unit

    // ---- gestures from the car ----------------------------------------------------------------------

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val c = map ?: return
        setFollowing(false)
        c.native.panBy(-distanceX, -distanceY)
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
        map?.native?.zoomBy(1.0)
    }

    fun zoomOut() {
        map?.native?.zoomBy(-1.0)
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
        map?.let { m -> scope?.launch { applyBaseLayer(m) } }
    }

    /** "Navigate to" from the car: a car route from here, and guidance as soon as it is calculated. */
    fun showDestination(p: GeoPoint) {
        setFollowing(false)
        map?.animateTo(p.lat, p.lon, minZoom = 13)
        val me = location
        if (me != null) {
            NavigationSession.stop()
            PlannerSession.load(listOf(GeoPoint(me.latitude, me.longitude), p), RouteProfile.CAR)
            startWhenRouted = true
        }
    }

    /** Guidance along the planned route. */
    fun startNavigation() {
        val planner = PlannerSession.state.value
        val route = planner.route ?: return
        NavigationSession.start(route, planner.waypoints, planner.profile)
    }

    private fun setFollowing(value: Boolean) {
        if (following == value) return
        following = value
        pushStatus()
    }

    /** Puts the position in the middle of the part of the screen not covered by the car's UI (the padding). */
    private fun centerOn(lat: Double, lon: Double) {
        val n = map?.native ?: return
        n.moveCamera(lat, lon, n.camera().zoom, animated = true)
    }

    // ---- data -------------------------------------------------------------------------------------------

    private var baseInputs: List<Any?> = emptyList()

    private fun startFeeding(c: AndroidMapSurface) {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val prefs = AppGraph.prefs
        // Same base map as the phone: offline vector (topo, contours, hill shading, trails) or an online source.
        s.launch {
            combine(
                listOf(
                    prefs.baseLayer, prefs.customSources, AppGraph.mapFiles.files, prefs.mapTheme, prefs.styleChoices, prefs.contours,
                    prefs.hillshade, AppGraph.terrain.state, prefs.trails, AppGraph.trails.state,
                    prefs.mapBackground, prefs.vectorOnBackground, AppGraph.rasters.maps,
                ),
            ) { it.toList() }.collect {
                baseInputs = it
                applyBaseLayer(c)
            }
        }
        s.launch {
            combine(PlannerSession.state, NavigationSession.state) { planner, nav -> planner to nav }.collect { (planner, nav) ->
                val line = when {
                    nav.route != null -> nav.route!!.points
                    !planner.active -> emptyList()
                    planner.route != null -> planner.route!!.points
                    planner.profile == RouteProfile.LINE -> planner.waypoints
                    else -> emptyList()
                }
                c.setPlannedRoute(line, if (planner.active) planner.waypoints else emptyList())
                if (startWhenRouted && !planner.busy) {
                    startWhenRouted = false
                    if (planner.route != null && !nav.active) startNavigation()
                }
                pushStatus()
            }
        }
        s.launch {
            // Navigation started (here or on the phone): follow the car, close enough to see the turns.
            NavigationSession.state.map { it.active }.distinctUntilChanged().collect { active ->
                if (active) {
                    map?.native?.let { n ->
                        val zoom = NAV_ZOOM - MapLibreSurface.ZOOM_OFFSET
                        val cam = n.camera()
                        if (cam.zoom < zoom) n.moveCamera(cam.lat, cam.lon, zoom.toDouble(), animated = true)
                    }
                    recenter()
                }
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
                    c.updateLocation(loc.toGeoFix())
                    // The navigation service feeds it too; the same fix counts once.
                    if (NavigationSession.state.value.active) NavigationSession.onFix(loc.toGeoFix())
                    if (following) centerOn(loc.latitude, loc.longitude)
                    pushStatus()
                }
            }
        }
        pushStatus()
    }

    private suspend fun applyBaseLayer(c: AndroidMapSurface) {
        val prefs = AppGraph.prefs
        val online = if (satellite) satelliteSource else TileSources.find(prefs.baseLayer.value, prefs.customSources.value)
        val theme = prefs.mapTheme.value
        val base = online?.let { BaseMap.Online(it) } ?: BaseMap.Offline(
            maps = AppGraph.mapFiles.files.value,
            theme = theme,
            categories = AppGraph.themes.categories(theme, prefs.styleChoices.value),
            contours = prefs.contours.value,
            hillshade = prefs.hillshade.value,
            terrainVersion = AppGraph.terrain.state.value.version,
            trails = prefs.trails.value,
            trailsVersion = AppGraph.trails.state.value.version,
            // Same background as the phone (satellite / raster map under the vector map).
            background = prefs.mapBackground.value,
            vectorOnTop = prefs.vectorOnBackground.value,
            rasterMaps = AppGraph.rasters.maps.value,
        )
        c.setBaseMap(base)
        val localTrails = online == null && prefs.trails.value && MapThemes.drawsTerrain(theme) && AppGraph.trails.state.value.cells > 0
        c.setTrailOverlay(prefs.trails.value && !localTrails)
    }

    private fun pushStatus() {
        val loc = location
        val speedUnit = AppGraph.prefs.speedUnit.value
        val gpsSpeed = loc?.takeIf { it.provider == LocationManager.GPS_PROVIDER && it.hasSpeed() }?.speed
        val title = when {
            !carContext.hasLocationPermission() -> "MapAround"
            loc == null -> T.nav.waitingForGps
            gpsSpeed != null -> "${(gpsSpeed * speedUnit.perMps).roundToInt()} ${speedUnit.label}"
            else -> "MapAround"
        }
        val parts = mutableListOf<String>()
        if (!carContext.hasLocationPermission()) parts += T.nav.carAllowLocation
        loc?.bestAltitude()?.let { parts += "${it.roundToInt()} ${T.common.unitM}" }
        if (loc != null && loc.hasBearing() && (gpsSpeed ?: 0f) > 1f) parts += Fmt.cardinal(loc.bearing)
        PlannerSession.state.value.route?.let { parts += T.nav.carRoute(Fmt.distance(it.stats.distanceM.toFloat())) }
        PartySession.state.value.takeIf { it.active }?.let { p ->
            val n = p.members.count { it.position != null }
            parts += if (p.members.any { it.sos }) T.nav.carPartySos else T.nav.carPartyOnMap(n)
        }
        if (TrackRecorder.state.value.recording) parts += T.nav.carRecording
        if (AppGraph.mapFiles.files.value.isEmpty() && TileSources.find(AppGraph.prefs.baseLayer.value, AppGraph.prefs.customSources.value) == null && !satellite) {
            parts += T.nav.carNoOfflineMaps
        }
        _status.value = Status(title, parts.joinToString(" · ").ifEmpty { T.nav.carMap }, following, satellite)
    }

    private fun release() {
        scope?.cancel()
        scope = null
        map?.let {
            it.onPause()
            runCatching { it.destroy() }
        }
        map = null
        runCatching { presentation?.dismiss() }
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    companion object {
        private const val TAG = "CarMapRenderer"

        /** Zoom (mapsforge's levels) to see the turns while navigating. */
        private const val NAV_ZOOM = 16
    }
}
