package app.maparound.car

import app.maparound.routing.NavView
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
import app.maparound.map.LiveMapFeed
import app.maparound.map.MapNight
import app.maparound.map.TileSources
import app.maparound.map.maplibre.MapLibreSurface
import app.maparound.party.PartySession
import app.maparound.routing.GeoPoint
import app.maparound.routing.NavigationSession
import app.maparound.routing.PlannerSession
import app.maparound.routing.RouteProfile
import app.maparound.share.GeoRequest
import app.maparound.sensors.LocationSource
import app.maparound.sensors.bestAltitude
import app.maparound.sensors.hasLocationPermission
import app.maparound.sensors.toGeoFix
import app.maparound.tracks.TrackRecorder
import app.maparound.ui.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    data class Status(val title: String, val text: String, val following: Boolean, val satellite: Boolean, val view3d: Boolean = true)

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
            c.night = night()
            // 3D, tilted: the buildings stand up.
            c.buildings3d = AppGraph.prefs.navigation3d.value
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

    /** The car switched between day and night. */
    fun onCarConfigurationChanged() {
        map?.night = night()
    }

    /**
     * The map dark at night as the car says (its light sensor, the time, its own setting), light text
     * on it, as Android Auto requires; and by day too when the phone's map is set to always dark.
     */
    private fun night() = carContext.isDarkMode || AppGraph.prefs.mapNight.value == MapNight.DARK

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

    /**
     * 2D / 3D (the same choice as the phone's navigation): tilted with the way ahead up, the car low
     * on the screen; or flat, north up. Following the car again either way.
     */
    fun toggle3d() {
        AppGraph.prefs.setNavigation3d(!AppGraph.prefs.navigation3d.value)
        setFollowing(true)
        location?.let { centerOn(it.latitude, it.longitude) } ?: map?.native?.let { n ->
            val c = n.camera()
            centerOn(c.lat, c.lon)
        }
        pushStatus()
    }

    /** Switches between the phone's chosen map and satellite imagery (car screen only). */
    fun toggleSatellite() {
        satellite = !satellite
        pushStatus()
        feed?.satellite = satellite
    }

    /** Satellite imagery on or off (Assistant's show/hide satellite). */
    fun setSatellite(on: Boolean) {
        if (satellite != on) toggleSatellite()
    }

    /** The whole route in view (Assistant's route overview); false with no route. */
    fun overview(): Boolean {
        val route = NavigationSession.state.value.route ?: PlannerSession.state.value.route ?: return false
        setFollowing(false)
        map?.showPoints(route.points)
        return true
    }

    /** Where the car is, else where the map last was: to search around. */
    fun here(): GeoPoint? =
        location?.let { GeoPoint(it.latitude, it.longitude) } ?: AppGraph.prefs.lastPosition()?.let { GeoPoint(it.lat, it.lon) }

    /** A place asked for before the car's position was known: gone to with the first fix. */
    private var pending: Triple<GeoPoint, RouteProfile?, GeoRequest.Mode>? = null

    /**
     * Somewhere to go (a navigation request, a place found): a route from here by [profile] (by car
     * when the request doesn't say), guidance as soon as it's calculated ([GeoRequest.Mode.NAVIGATE]),
     * or only shown with its Start ([GeoRequest.Mode.DIRECTIONS]); [GeoRequest.Mode.ADD_STOP] while
     * navigating goes by [p] on the way to where it was going.
     */
    fun goTo(p: GeoPoint, profile: RouteProfile?, mode: GeoRequest.Mode) {
        setFollowing(false)
        map?.animateTo(p.lat, p.lon, minZoom = 13)
        val me = location
        if (me == null) {
            pending = Triple(p, profile, mode)
            return
        }
        pending = null
        val nav = NavigationSession.state.value
        val from = GeoPoint(me.latitude, me.longitude)
        val via = mode == GeoRequest.Mode.ADD_STOP && nav.active
        val stops = if (via) listOfNotNull(from, p, nav.route?.points?.lastOrNull()) else listOf(from, p)
        val by = profile ?: if (via) nav.profile else RouteProfile.CAR
        NavigationSession.stop()
        PlannerSession.load(stops, by)
        startWhenRouted = mode != GeoRequest.Mode.DIRECTIONS
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

    /**
     * Puts the position in the part of the screen not covered by the car's UI: in 3D low in it, the
     * way the car goes up, tilted; in 2D in its middle, north up.
     */
    private fun centerOn(lat: Double, lon: Double) {
        val n = map?.native ?: return
        val (w, h) = surfaceSize
        if (w <= 0 || h <= 0) return
        val v = visibleArea ?: Rect(0, 0, w, h)
        val left = v.left
        val right = (w - v.right).coerceAtLeast(0)
        val bottom = (h - v.bottom).coerceAtLeast(0)
        val cam = n.camera()
        if (AppGraph.prefs.navigation3d.value) {
            // The way the car goes when it moves (else as it was); its place three quarters down.
            val bearing = location?.takeIf { it.hasBearing() && it.hasSpeed() && it.speed > 1.5f }?.bearing?.toDouble() ?: cam.bearing
            val top = v.top + (v.bottom - v.top).coerceAtLeast(0) / 2
            n.driveCamera(lat, lon, cam.zoom, bearing, NavView.PITCH, left, top, right, bottom, 900)
        } else {
            n.driveCamera(lat, lon, cam.zoom, 0.0, 0.0, left, v.top, right, bottom, 600)
        }
    }

    // ---- data -------------------------------------------------------------------------------------------

    /** The phone's map, route, tracks, recording and party on the car's map. */
    private var feed: LiveMapFeed? = null

    private fun startFeeding(c: AndroidMapSurface) {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        feed = LiveMapFeed(c, s, onChange = ::pushStatus).also {
            it.satellite = satellite
            it.start()
        }
        s.launch { AppGraph.prefs.mapNight.collect { c.night = night() } }
        // 2D / 3D, switched here or on the phone: the buildings stand up in 3D.
        s.launch { AppGraph.prefs.navigation3d.collect { c.buildings3d = it } }
        s.launch {
            // "Navigate to" from the car: guidance once the route is there.
            combine(PlannerSession.state, NavigationSession.state) { planner, nav -> planner to nav }.collect { (planner, nav) ->
                if (startWhenRouted && !planner.busy) {
                    startWhenRouted = false
                    if (planner.route != null && !nav.active) startNavigation()
                }
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
        if (carContext.hasLocationPermission()) {
            s.launch {
                LocationSource.updates(carContext).collect { loc ->
                    location = loc
                    pending?.let { (p, profile, mode) -> goTo(p, profile, mode) }
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
        loc?.bestAltitude()?.let { parts += Fmt.height(it) }
        if (loc != null && loc.hasBearing() && (gpsSpeed ?: 0f) > 1f) parts += Fmt.heading(loc.bearing)
        PlannerSession.state.value.route?.let { parts += T.nav.carRoute(Fmt.distance(it.stats.distanceM.toFloat())) }
        // Every party that's on: its members on the map, and an SOS in any.
        PartySession.parties.value.filter { it.active }.takeIf { it.isNotEmpty() }?.let { on ->
            val members = on.flatMap { it.members }
            parts += if (members.any { it.sos }) T.nav.carPartySos else T.nav.carPartyOnMap(members.count { it.position != null })
        }
        if (TrackRecorder.state.value.recording) parts += T.nav.carRecording
        if (AppGraph.mapFiles.files.value.isEmpty() && TileSources.find(AppGraph.prefs.baseLayer.value, AppGraph.prefs.customSources.value) == null && !satellite) {
            parts += T.nav.carNoOfflineMaps
        }
        _status.value = Status(title, parts.joinToString(" · ").ifEmpty { T.nav.carMap }, following, satellite, AppGraph.prefs.navigation3d.value)
    }

    private fun release() {
        scope?.cancel()
        scope = null
        feed = null
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
