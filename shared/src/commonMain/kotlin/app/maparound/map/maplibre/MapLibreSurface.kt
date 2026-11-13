package app.maparound.map.maplibre

import app.maparound.shared.util.deviceLanguage
import app.maparound.data.MapPos
import app.maparound.map.BaseMap
import app.maparound.map.MapListener
import app.maparound.map.MapPoi
import app.maparound.map.MapSurface
import app.maparound.map.PartyMarker
import app.maparound.map.PlaceMarker
import app.maparound.map.ToolLine
import app.maparound.routing.GeoPoint
import app.maparound.sensors.GeoFix
import app.maparound.three.Camera3D
import app.maparound.tools.Viewshed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * The shared map screen's [MapSurface] on a MapLibre view ([native]): the base map as a style
 * from [style], the overlays as GeoJSON, taps and gestures back to the [listener].
 *
 * Zoom levels in [MapPos] are mapsforge's (256-point tiles, as on Android); MapLibre's are one
 * lower for the same scale.
 */
class MapLibreSurface(
    val native: NativeMap,
    private val tiles: TileService,
    /** The style for a theme ([BaseMap.Offline.theme]; null: no offline map). */
    private val styleFor: (themeId: String?) -> MapStyle,
) : MapSurface {
    /** One style for every map (iOS: Topo). */
    constructor(native: NativeMap, tiles: TileService, style: MapStyle) : this(native, tiles, { style })

    override var listener: MapListener? = null

    private var lastStyle: String? = null
    private var base: BaseMap? = null
    private var trailOverlay = false

    init {
        native.setCallbacks(object : NativeMapCallbacks {
            override fun onUserGesture() {
                listener?.onUserGesture()
            }

            override fun onTap(lat: Double, lon: Double, tapped: String?) {
                val l = listener ?: return
                when {
                    tapped == null -> tapMap(l, GeoPoint(lat, lon))
                    tapped == TAP_ME -> l.onMyLocationTap()
                    tapped.startsWith(TAP_WAYPOINT) -> tapped.removePrefix(TAP_WAYPOINT).toIntOrNull()?.let(l::onWaypointTap)
                    tapped.startsWith(TAP_PARTY) -> l.onPartyMarkerTap(tapped.removePrefix(TAP_PARTY))
                    tapped.startsWith(TAP_PLACE) -> l.onPlaceTap(tapped.removePrefix(TAP_PLACE))
                    else -> tapMap(l, GeoPoint(lat, lon))
                }
            }

            override fun onLongPress(lat: Double, lon: Double) {
                listener?.onLongPress(GeoPoint(lat, lon))
            }

            override fun onLongPressMove(lat: Double, lon: Double) {
                listener?.onLongPressMove(GeoPoint(lat, lon))
            }

            override fun onLongPressEnd(lat: Double, lon: Double) {
                listener?.onLongPressEnd(GeoPoint(lat, lon))
            }
        })
        applyStyle(styleFor(null).build(null, tiles))
    }

    // ---- camera ---------------------------------------------------------------------------------

    override fun position(): MapPos {
        val c = native.camera()
        return MapPos(c.lat, c.lon, (c.zoom + ZOOM_OFFSET).roundToInt())
    }

    override fun moveTo(pos: MapPos) {
        native.moveCamera(
            pos.lat.coerceIn(-85.0, 85.0), pos.lon.coerceIn(-180.0, 180.0),
            (pos.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM) - ZOOM_OFFSET).toDouble(), animated = false,
        )
    }

    override fun animateTo(lat: Double, lon: Double, minZoom: Int?) {
        val zoom = native.camera().zoom
        val target = if (minZoom != null && zoom + ZOOM_OFFSET < minZoom) (minZoom - ZOOM_OFFSET).toDouble() else zoom
        native.moveCamera(lat, lon, target, animated = true)
    }

    override fun showPoints(points: List<GeoPoint>) {
        when (points.size) {
            0 -> Unit
            1 -> animateTo(points[0].lat, points[0].lon, minZoom = 15)
            else -> native.fitBounds(
                points.minOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lat }, points.maxOf { it.lon }, animated = true,
            )
        }
    }

    override fun camera3D(): Camera3D? {
        val h = native.viewHeight()
        if (h <= 0) return null
        val c = native.camera()
        return Camera3D(c.lat, c.lon, (metresPerPoint(c) * h * DISTANCE_PER_GROUND).toFloat())
    }

    /** A tap on the map: the screen's (tools, route planner), else the point of interest under it. */
    private fun tapMap(l: MapListener, p: GeoPoint) {
        if (l.onTap(p)) return
        poisNear(p, TAP_RADIUS_DP).firstOrNull()?.let(l::onPoiTap)
    }

    override fun poisNear(point: GeoPoint, radiusDp: Double): List<MapPoi> =
        MapPoi.parseList(runCatching { native.poisNear(point.lat, point.lon, radiusDp) }.getOrDefault("[]"), point.lat, point.lon, deviceLanguage())

    override fun showFrom3D(camera: Camera3D) {
        val h = native.viewHeight()
        if (h <= 0) return
        val zoom = zoomFor(camera, h).takeIf { it.isFinite() } ?: return
        native.moveCamera(camera.lat, camera.lon, zoom.coerceIn((MIN_ZOOM - ZOOM_OFFSET).toDouble(), (MAX_ZOOM - ZOOM_OFFSET).toDouble()), animated = false)
    }

    override fun metresPerDp(): Double = metresPerPoint(native.camera())

    private fun metresPerPoint(c: NativeCamera) = EARTH_CIRCUMFERENCE_M * cos(c.lat * PI / 180) / (512 * 2.0.pow(c.zoom))

    // ---- base map ---------------------------------------------------------------------------------

    override suspend fun setBaseMap(base: BaseMap) {
        if (base is BaseMap.Offline) {
            val paths = base.maps.map { it.path }
            withContext(Dispatchers.Default) {
                tiles.setMaps(paths)
                if (base.contours || base.hillshade) tiles.refreshTerrain()
            }
        }
        this.base = base
        rebuild()
    }

    /** The style for what's shown now, applied when it changed. */
    fun rebuild() {
        val b = base
        applyStyle(styleFor((b as? BaseMap.Offline)?.theme).build(b, tiles, trailOverlay))
    }

    private fun applyStyle(json: String) {
        if (json == lastStyle) return
        lastStyle = json
        native.setStyle(json)
    }

    /** Waymarked Trails' online overlay over the map (the screen decides when). */
    override fun setTrailOverlay(enabled: Boolean) {
        if (enabled == trailOverlay) return
        trailOverlay = enabled
        rebuild()
    }

    // ---- overlays ---------------------------------------------------------------------------------

    override fun setPlannedRoute(line: List<GeoPoint>, waypoints: List<GeoPoint>) {
        val features = ArrayList<JsonObject>()
        GeoJson.line(line, mapOf("role" to "line"))?.let(features::add)
        waypoints.forEachIndexed { i, p ->
            val kind = when (i) {
                0 -> 0
                waypoints.lastIndex -> 2
                else -> 1
            }
            features += GeoJson.point(p.lat, p.lon, mapOf("role" to "wp", "icon" to MarkerIcons.waypoint(i + 1, kind), "tap" to "$TAP_WAYPOINT$i"))
        }
        native.setGeoJson(MapStyle.ROUTE, GeoJson.collection(features))
    }

    override fun setShownTracks(tracks: Map<String, List<GeoPoint>>) {
        native.setGeoJson(MapStyle.TRACKS, GeoJson.collection(tracks.mapNotNull { (id, pts) -> GeoJson.line(pts, mapOf("id" to id)) }))
    }

    override fun setRecording(points: List<GeoPoint>) {
        native.setGeoJson(MapStyle.RECORDING, GeoJson.collection(listOfNotNull(GeoJson.line(points, emptyMap()))))
    }

    /**
     * The tools' lines, ring and points as GeoJSON; the viewshed as an image the platform's tile
     * server draws (Android; iOS draws none yet), which takes a new style.
     */
    override fun setTools(a: GeoPoint?, b: GeoPoint?, lines: List<ToolLine>, ring: Pair<GeoPoint, Double>?, viewshed: Viewshed?) {
        if (viewshed !== tiles.viewshed) {
            tiles.viewshed = viewshed
            rebuild()
        }
        val features = ArrayList<JsonObject>()
        ring?.let { (c, r) -> features += GeoJson.ring(GeoJson.circle(c.lat, c.lon, r), mapOf("role" to "ring"), polygon = false) }
        for (l in lines) {
            val vis = when (l.visible) {
                true -> "yes"
                false -> "no"
                null -> "plain"
            }
            GeoJson.line(l.points, mapOf("role" to "line", "vis" to vis))?.let(features::add)
        }
        a?.let { features += GeoJson.point(it.lat, it.lon, mapOf("role" to "letter", "icon" to MarkerIcons.letter("A", COLOR_A))) }
        b?.let { features += GeoJson.point(it.lat, it.lon, mapOf("role" to "letter", "icon" to MarkerIcons.letter("B", COLOR_B))) }
        native.setGeoJson(MapStyle.TOOLS, GeoJson.collection(features))
    }

    override fun setPartyMarkers(markers: List<PartyMarker>) {
        native.setGeoJson(MapStyle.PARTY, GeoJson.collection(markers.map { m ->
            GeoJson.point(m.lat, m.lon, mapOf("icon" to MarkerIcons.party(m.kind, m.color, m.label, m.stale, m.icon), "tap" to "$TAP_PARTY${m.key}"))
        }))
    }

    override fun setPlaces(places: List<PlaceMarker>) {
        native.setGeoJson(MapStyle.PLACES, GeoJson.collection(places.map { p ->
            GeoJson.point(p.lat, p.lon, mapOf("icon" to MarkerIcons.place(p.color, p.emoji, p.label), "tap" to "$TAP_PLACE${p.id}"))
        }))
    }

    override fun updateLocation(fix: GeoFix?) {
        val features = ArrayList<JsonObject>()
        if (fix != null) {
            val accuracy = fix.accuracyM?.toDouble() ?: 0.0
            if (accuracy > 0) features += GeoJson.ring(GeoJson.circle(fix.lat, fix.lon, accuracy), mapOf("role" to "accuracy"), polygon = true)
            features += GeoJson.point(fix.lat, fix.lon, mapOf("role" to "dot", "icon" to MarkerIcons.ME, "tap" to TAP_ME))
        }
        native.setGeoJson(MapStyle.ME, GeoJson.collection(features))
    }

    override fun setPin(point: GeoPoint?) {
        native.setGeoJson(MapStyle.PIN, GeoJson.collection(listOfNotNull(point?.let { GeoJson.point(it.lat, it.lon, mapOf("icon" to MarkerIcons.PIN)) })))
    }

    override fun onResume() = Unit
    override fun onPause() = Unit

    override fun destroy() {
        native.setCallbacks(null)
        listener = null
    }

    companion object {
        /** mapsforge zoom = MapLibre zoom + this (256- vs 512-point tiles). */
        const val ZOOM_OFFSET = 1

        // The same limits as the Android map.
        const val MIN_ZOOM = 2
        const val MAX_ZOOM = 21

        const val TAP_ME = "me"
        const val TAP_WAYPOINT = "wp:"
        const val TAP_PARTY = "party:"
        const val TAP_PLACE = "place:"

        private const val COLOR_A = 0xFF1565C0.toInt()
        private const val COLOR_B = 0xFFEF6C00.toInt()
        private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

        /** How near a tap must be to a point of interest's symbol (as the markers'). */
        const val TAP_RADIUS_DP = 22.0

        /**
         * The 3D camera's distance per metre of ground across the 2D view's height: the distance
         * that shows it at a 45° field of view, a little closer (×0.8).
         */
        private val DISTANCE_PER_GROUND = 0.8 / (2 * tan(22.5 * PI / 180))

        /**
         * Back from 3D: the 2D zoom (MapLibre's) whose [camera3D] is [camera] in a view [viewHeight]
         * points tall, so the 2D map shows what the 3D view was looking at.
         */
        fun zoomFor(camera: Camera3D, viewHeight: Double): Double {
            val metresPerPoint = camera.distance / (viewHeight * DISTANCE_PER_GROUND)
            return log2(EARTH_CIRCUMFERENCE_M * cos(camera.lat * PI / 180) / (512 * metresPerPoint))
        }
    }
}
