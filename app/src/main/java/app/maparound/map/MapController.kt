package app.maparound.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import app.maparound.data.MapNames
import app.maparound.data.MapPos
import app.maparound.routing.GeoPoint
import app.maparound.terrain.ContourDataStore
import app.maparound.tools.Viewshed
import app.maparound.trails.TrailDataStore
import org.mapsforge.core.util.MercatorProjection
import app.maparound.trails.TrailIndex
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.Join
import org.mapsforge.core.model.Dimension
import org.mapsforge.core.util.LatLongUtils
import org.mapsforge.map.layer.overlay.Polyline
import app.maparound.terrain.DemIndex
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.layer.hills.AdaptiveClasyHillShading
import org.mapsforge.map.layer.hills.DemFolderFS
import org.mapsforge.map.layer.hills.HillsRenderConfig
import org.mapsforge.map.layer.hills.MemoryCachingHgtReaderTileSource
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.download.TileDownloadLayer
import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import org.mapsforge.map.scalebar.MapScaleBar
import java.io.File
import kotlin.math.abs

/**
 * Owns a mapsforge [MapView] and its layers: one base layer (offline vector rendering or an
 * online tile source) plus overlays for the user's position and a dropped pin.
 */
class MapController(
    private val context: Context,
    /** Prefix for tile-cache names, so a second map (the car screen) doesn't share the phone's caches. */
    private val cacheTag: String = "",
    /** Extra scale for map text and symbols (the car screen has a different density than the phone). */
    scale: Float = 1f,
) {
    val mapView: MapView = MapView(context).apply {
        if (scale != 1f) model.displayModel.setUserScaleFactor(scale)
    }

    private val graphics = AndroidGraphicFactory.INSTANCE
    private val density = context.resources.displayMetrics.density

    private var baseLayer: Layer? = null
    private var baseCache: TileCache? = null
    private var bgLayer: Layer? = null // satellite or raster map under a transparent vector map
    private var bgCache: TileCache? = null
    private var baseHills: HillsRenderConfig? = null
    private var baseKey: String? = null
    private var offlineCount = 0
    private var resumed = false

    private val accuracyCircle: Circle
    private val positionMarker: Marker
    private val pinMarker: Marker

    private var trailOverlay: TileDownloadLayer? = null
    private var trailOverlayCache: TileCache? = null

    // Route & track overlays (below the position dot and pin).
    private val shownTracks = LinkedHashMap<String, Pair<Polyline, Polyline>>()
    private val recordingLine: Polyline
    private val routeCasing: Polyline
    private val routeLine: Polyline
    private val waypointMarkers = ArrayList<Marker>()

    /** Called when the user drags/pinches the map (used to stop "follow my location"). */
    var onUserGesture: (() -> Unit)? = null
    var onLongPress: ((LatLong) -> Unit)? = null
    /** Short tap on the map; return true if handled (e.g. the route planner adds a point). */
    var onTap: ((LatLong) -> Boolean)? = null
    /** Tap on a route waypoint marker (index into the waypoint list). */
    var onWaypointTap: ((Int) -> Unit)? = null
    /** Tap on a party marker (its key). */
    var onPartyMarkerTap: ((String) -> Unit)? = null
    /** Tap on the own-position dot. */
    var onMyLocationTap: (() -> Unit)? = null

    private val partyMarkers = HashMap<String, Pair<Marker, String>>() // key -> (layer, style)

    init {
        mapView.isClickable = true
        mapView.setBuiltInZoomControls(false)
        mapView.setZoomLevelMin(MIN_ZOOM.toByte())
        mapView.setZoomLevelMax(MAX_ZOOM.toByte())
        mapView.mapScaleBar.isVisible = true
        mapView.mapScaleBar.scaleBarPosition = MapScaleBar.ScaleBarPosition.TOP_LEFT
        mapView.mapScaleBar.marginHorizontal = (12 * density).toInt()
        mapView.mapScaleBar.marginVertical = (12 * density).toInt()

        val accent = android.graphics.Color.rgb(30, 136, 229)
        accuracyCircle = Circle(
            null, 0f,
            graphics.createPaint().apply { setColor(android.graphics.Color.argb(48, 30, 136, 229)); setStyle(Style.FILL) },
            graphics.createPaint().apply {
                setColor(android.graphics.Color.argb(140, 30, 136, 229)); setStyle(Style.STROKE); setStrokeWidth(1.5f * density)
            },
        )
        positionMarker = TapMarker(dotBitmap(accent)) { onMyLocationTap?.invoke() }
        val pinBmp = pinBitmap()
        pinMarker = Marker(null, pinBmp, 0, -pinBmp.height / 2)

        recordingLine = line(android.graphics.Color.rgb(213, 0, 0), 4f)
        routeCasing = line(android.graphics.Color.WHITE, 8f)
        routeLine = line(android.graphics.Color.rgb(106, 27, 154), 5f)

        val layers = mapView.layerManager.layers
        layers.add(recordingLine)
        layers.add(routeCasing)
        layers.add(routeLine)
        listOf(accuracyCircle, positionMarker, pinMarker).forEach {
            it.setVisible(false, false)
            layers.add(it)
        }
        layers.add(GestureLayer())
        installTouchListener()
    }

    // ---- base layers ------------------------------------------------------------------------

    /** How offline vector maps are drawn. */
    data class OfflineStyle(
        val topo: Boolean,
        val contours: Boolean,
        val hillshade: Boolean,
        val demDir: File?,
        /** Changes when elevation tiles are added or removed. */
        val terrainVersion: Int,
        val trails: Boolean = false,
        val trailsDir: File? = null,
        /** Changes when trail cells are added or removed. */
        val trailsVersion: Int = 0,
    )

    /** What's drawn under the offline vector map instead of its own colours. */
    sealed class Background {
        abstract val key: String

        /** Online imagery, e.g. satellite. */
        data class Online(val source: OnlineSource) : Background() {
            override val key get() = "online:${source.id}:${source.urlTemplate}"
        }

        /** A raster map file (RMAP). */
        class Raster(val id: String, val renderer: app.maparound.raster.RasterRenderer) : Background() {
            override val key get() = "raster:$id"
        }
    }

    /**
     * Renders the given mapsforge .map files. With a [background] (satellite imagery or a raster
     * map) the vector map is drawn transparently on top of it, roads, trails, contours and labels
     * only ("hybrid"), or not at all when [vectorOnTop] is off. Returns how many maps could be opened.
     */
    fun showOffline(files: List<File>, style: OfflineStyle, background: Background? = null, vectorOnTop: Boolean = true): Int {
        val key = "offline:$style:${background?.key}:$vectorOnTop:" + files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        if (key == baseKey) return offlineCount
        removeBase()
        val model = mapView.model
        val layers = mapView.layerManager.layers

        if (background != null) {
            val layer: Layer
            val cache: TileCache
            when (background) {
                is Background.Online -> {
                    cache = AndroidUtil.createTileCache(
                        context, cacheTag + "tiles_" + background.source.id.replace(Regex("[^A-Za-z0-9_]"), "_"),
                        model.displayModel.tileSize, 1f, model.frameBufferModel.overdrawFactor, true,
                    )
                    layer = TileDownloadLayer(cache, model.mapViewPosition, UrlTemplateTileSource(background.source), graphics)
                    if (resumed) layer.onResume()
                }
                is Background.Raster -> {
                    cache = AndroidUtil.createTileCache(
                        context, cacheTag + "raster_" + background.id.take(12), model.displayModel.tileSize, 1f, model.frameBufferModel.overdrawFactor, false,
                    )
                    layer = RasterTileLayer(cache, model.mapViewPosition, background.renderer)
                }
            }
            layers.add(0, layer)
            bgLayer = layer
            bgCache = cache
        }

        var count = 0
        if (background == null || vectorOnTop) {
            val hybrid = background != null
            // Over imagery: no hill shading (the picture has its own light), and the Topo style
            // without area fills.
            val data = OfflineMapData.open(files, if (hybrid) style.copy(hillshade = false) else style)
            val cache = AndroidUtil.createTileCache(
                context, cacheTag + if (hybrid) "offline_hybrid" else "offline", model.displayModel.tileSize, 1f,
                model.frameBufferModel.overdrawFactor, false,
            )
            val layer = TileRendererLayer(cache, data.store, model.mapViewPosition, hybrid, true, false, graphics, data.hills)
            layer.setXmlRenderTheme(
                when {
                    hybrid -> TopoTheme(context, hybrid = true)
                    style.topo -> TopoTheme(context)
                    else -> MapsforgeThemes.DEFAULT
                },
            )
            layers.add(if (bgLayer != null) 1 else 0, layer)
            baseLayer = layer
            baseCache = cache
            baseHills = data.hills
            count = data.mapCount
        }
        baseKey = key
        setZoomMax(MAX_ZOOM)
        offlineCount = count
        return count
    }

    fun showOnline(source: OnlineSource) {
        val key = "online:${source.id}:${source.urlTemplate}"
        if (key == baseKey) return
        removeBase()
        val model = mapView.model
        val cacheId = cacheTag + "tiles_" + source.id.replace(Regex("[^A-Za-z0-9_]"), "_")
        val cache = AndroidUtil.createTileCache(
            context, cacheId, model.displayModel.tileSize, 1f, model.frameBufferModel.overdrawFactor, true,
        )
        val layer = TileDownloadLayer(cache, model.mapViewPosition, UrlTemplateTileSource(source), graphics)
        installBase(layer, cache, key)
        if (resumed) layer.onResume()
        setZoomMax(source.maxZoom)
    }

    private fun installBase(layer: Layer, cache: TileCache, key: String) {
        mapView.layerManager.layers.add(0, layer)
        baseLayer = layer
        baseCache = cache
        baseKey = key
    }

    private fun removeBase() {
        bgLayer?.let { bg ->
            if (bg is TileDownloadLayer) bg.onPause()
            mapView.layerManager.layers.remove(bg)
            bg.onDestroy()
        }
        bgCache?.destroy()
        bgLayer = null
        bgCache = null
        baseKey = null
        val layer = baseLayer ?: return
        if (layer is TileDownloadLayer) layer.onPause()
        mapView.layerManager.layers.remove(layer)
        layer.onDestroy() // also closes the map files of a TileRendererLayer
        baseCache?.destroy()
        baseHills?.interruptAndDestroy()
        baseLayer = null
        baseCache = null
        baseHills = null
        baseKey = null
    }

    private fun setZoomMax(max: Int) {
        mapView.setZoomLevelMax(max.toByte())
        val position = mapView.model.mapViewPosition
        if (position.zoomLevel > max) position.zoomLevel = max.toByte()
    }

    /** Waymarked Trails tiles over the base map (needs internet). */
    fun setTrailOverlay(enabled: Boolean) {
        if (enabled == (trailOverlay != null)) return
        val layers = mapView.layerManager.layers
        if (enabled) {
            val model = mapView.model
            val cache = AndroidUtil.createTileCache(
                context, cacheTag + "tiles_trails", model.displayModel.tileSize, 1f, model.frameBufferModel.overdrawFactor, true,
            )
            val layer = TileDownloadLayer(cache, model.mapViewPosition, UrlTemplateTileSource(TileSources.trailsOverlay), graphics)
            // Just above the base map (and its background).
            val above = listOfNotNull(bgLayer, baseLayer).maxOfOrNull { layers.indexOf(it) }?.plus(1) ?: 0
            layers.add(above, layer)
            if (resumed) layer.onResume()
            trailOverlay = layer
            trailOverlayCache = cache
        } else {
            trailOverlay?.let { it.onPause(); layers.remove(it); it.onDestroy() }
            trailOverlayCache?.destroy()
            trailOverlay = null
            trailOverlayCache = null
        }
    }

    // ---- party ------------------------------------------------------------------------------

    /** Something to show for the party: a member, an SOS, the meeting point or another radio. */
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

    fun setPartyMarkers(markers: List<PartyMarker>) {
        val layers = mapView.layerManager.layers
        val keys = markers.map { it.key }.toSet()
        for (k in partyMarkers.keys - keys) {
            partyMarkers.remove(k)?.let { (layer, _) -> layers.remove(layer, false); layer.onDestroy() }
        }
        for (m in markers) {
            val style = "${m.label}|${m.color}|${m.kind}|${m.stale}"
            val existing = partyMarkers[m.key]
            if (existing != null && existing.second == style) {
                existing.first.setLatLong(LatLong(m.lat, m.lon))
                continue
            }
            existing?.let { (layer, _) -> layers.remove(layer, false); layer.onDestroy() }
            val (bitmap, centerY) = partyBitmap(m)
            val layer = PartyMarkerLayer(m.key, LatLong(m.lat, m.lon), bitmap, bitmap.height / 2 - centerY)
            // Below the own-position dot, above tracks; taps reach it unless the planner is active.
            layers.add(layers.indexOf(accuracyCircle), layer, false)
            partyMarkers[m.key] = layer to style
        }
        mapView.layerManager.redrawLayers()
    }

    /** A marker that reports taps on itself. */
    private inner class TapMarker(bitmap: org.mapsforge.core.graphics.Bitmap, private val tapped: () -> Unit) :
        Marker(null, bitmap, 0, 0) {
        override fun onTap(tapLatLong: LatLong?, layerXY: Point?, tapXY: Point?): Boolean {
            if (!isVisible || layerXY == null || tapXY == null || !contains(layerXY, tapXY, mapView)) return false
            tapped()
            return true
        }
    }

    private inner class PartyMarkerLayer(val key: String, at: LatLong, bitmap: org.mapsforge.core.graphics.Bitmap, vOffset: Int) :
        Marker(at, bitmap, 0, vOffset) {
        override fun onTap(tapLatLong: LatLong?, layerXY: Point?, tapXY: Point?): Boolean {
            if (layerXY == null || tapXY == null || !contains(layerXY, tapXY, mapView)) return false
            onPartyMarkerTap?.invoke(key)
            return true
        }
    }

    /** Circle with an initial (or a flag) and a name tag below. Returns the bitmap and the circle centre's y. */
    private fun partyBitmap(m: PartyMarker): Pair<org.mapsforge.core.graphics.Bitmap, Int> {
        val d = density
        val r = 13 * d
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11 * d
            isFakeBoldText = true
            color = android.graphics.Color.rgb(40, 40, 40)
        }
        val label = m.label.take(18)
        val labelW = text.measureText(label) + 8 * d
        val labelH = 16 * d
        val ring = if (m.kind == PartyMarker.Kind.SOS) 4 * d else 2 * d
        val w = maxOf(2 * (r + ring), labelW).toInt() + 2
        val h = (2 * (r + ring) + 3 * d + labelH).toInt() + 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val cx = w / 2f
        val cy = r + ring
        val alpha = if (m.stale) 140 else 255
        // ring
        p.color = if (m.kind == PartyMarker.Kind.SOS) android.graphics.Color.rgb(213, 0, 0) else android.graphics.Color.WHITE
        p.alpha = alpha
        c.drawCircle(cx, cy, r + ring, p)
        // body
        p.color = when (m.kind) {
            PartyMarker.Kind.MEET -> android.graphics.Color.rgb(198, 40, 40)
            PartyMarker.Kind.RADIO -> android.graphics.Color.rgb(97, 97, 97)
            else -> m.color
        }
        p.alpha = alpha
        c.drawCircle(cx, cy, r, p)
        // symbol
        p.color = android.graphics.Color.WHITE
        p.alpha = alpha
        if (m.kind == PartyMarker.Kind.MEET) {
            p.strokeWidth = 2 * d
            c.drawLine(cx - 4 * d, cy - 7 * d, cx - 4 * d, cy + 7 * d, p)
            c.drawPath(Path().apply {
                moveTo(cx - 4 * d, cy - 7 * d); lineTo(cx + 7 * d, cy - 3.5f * d); lineTo(cx - 4 * d, cy); close()
            }, p)
        } else {
            p.textSize = 13 * d
            p.isFakeBoldText = true
            p.textAlign = android.graphics.Paint.Align.CENTER
            val initial = if (m.kind == PartyMarker.Kind.SOS) "!" else label.take(1).uppercase()
            c.drawText(initial, cx, cy - (p.descent() + p.ascent()) / 2, p)
        }
        // name tag
        val top = 2 * cy + 3 * d
        p.color = android.graphics.Color.WHITE
        p.alpha = (alpha * 0.9).toInt()
        c.drawRoundRect(cx - labelW / 2, top, cx + labelW / 2, top + labelH, 4 * d, 4 * d, p)
        text.alpha = alpha
        text.textAlign = android.graphics.Paint.Align.CENTER
        if (m.kind == PartyMarker.Kind.SOS) text.color = android.graphics.Color.rgb(213, 0, 0)
        c.drawText(label, cx, top + labelH / 2 - (text.descent() + text.ascent()) / 2, text)
        return AndroidGraphicFactory.convertToBitmap(BitmapDrawable(context.resources, bmp)) to cy.toInt()
    }

    // ---- saved places -------------------------------------------------------------------------

    data class PlaceMarker(val id: String, val lat: Double, val lon: Double, val label: String, val emoji: String, val color: Int)

    /** Tap on a saved place (its id). */
    var onPlaceTap: ((String) -> Unit)? = null
    private val placeMarkers = HashMap<String, Pair<Marker, PlaceMarker>>()

    fun setPlaces(places: List<PlaceMarker>) {
        val layers = mapView.layerManager.layers
        val ids = places.map { it.id }.toSet()
        for (id in placeMarkers.keys - ids) {
            placeMarkers.remove(id)?.let { (layer, _) -> layers.remove(layer, false); layer.onDestroy() }
        }
        for (p in places) {
            val existing = placeMarkers[p.id]
            if (existing != null && existing.second == p) continue
            existing?.let { (layer, _) -> layers.remove(layer, false); layer.onDestroy() }
            val (bitmap, centerY) = placeBitmap(p)
            val layer = PlaceMarkerLayer(p.id, LatLong(p.lat, p.lon), bitmap, bitmap.height / 2 - centerY)
            layers.add(layers.indexOf(accuracyCircle), layer, false) // under party members and the position dot
            placeMarkers[p.id] = layer to p
        }
        mapView.layerManager.redrawLayers()
    }

    private inner class PlaceMarkerLayer(val id: String, at: LatLong, bitmap: org.mapsforge.core.graphics.Bitmap, vOffset: Int) :
        Marker(at, bitmap, 0, vOffset) {
        override fun onTap(tapLatLong: LatLong?, layerXY: Point?, tapXY: Point?): Boolean {
            if (layerXY == null || tapXY == null || !contains(layerXY, tapXY, mapView)) return false
            onPlaceTap?.invoke(id)
            return true
        }
    }

    /** The category emoji in a ring of the category colour, the name below. */
    private fun placeBitmap(p: PlaceMarker): Pair<org.mapsforge.core.graphics.Bitmap, Int> {
        val d = density
        val r = 12 * d
        val ring = 2.5f * d
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11 * d
            isFakeBoldText = true
            color = android.graphics.Color.rgb(40, 40, 40)
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val label = p.label.take(24)
        val labelW = text.measureText(label) + 8 * d
        val labelH = 16 * d
        val w = maxOf(2 * (r + ring), labelW).toInt() + 2
        val h = (2 * (r + ring) + 2 * d + labelH).toInt() + 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val cx = w / 2f
        val cy = r + ring
        paint.color = p.color
        c.drawCircle(cx, cy, r + ring, paint)
        paint.color = android.graphics.Color.WHITE
        c.drawCircle(cx, cy, r, paint)
        val emoji = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14 * d
            textAlign = android.graphics.Paint.Align.CENTER
        }
        c.drawText(p.emoji, cx, cy - (emoji.descent() + emoji.ascent()) / 2, emoji)
        val top = 2 * cy + 2 * d
        paint.color = android.graphics.Color.WHITE
        paint.alpha = 225
        c.drawRoundRect(cx - labelW / 2, top, cx + labelW / 2, top + labelH, 4 * d, 4 * d, paint)
        c.drawText(label, cx, top + labelH / 2 - (text.descent() + text.ascent()) / 2, text)
        return AndroidGraphicFactory.convertToBitmap(BitmapDrawable(context.resources, bmp)) to cy.toInt()
    }

    // ---- routes & tracks ----------------------------------------------------------------------

    /** The route being planned: its line and the tapped waypoints (numbered markers). */
    fun setPlannedRoute(line: List<GeoPoint>, waypoints: List<GeoPoint>) {
        val pts = line.map { LatLong(it.lat, it.lon) }
        routeCasing.setPoints(pts)
        routeLine.setPoints(pts)
        val layers = mapView.layerManager.layers
        waypointMarkers.forEach { layers.remove(it, false) }
        waypointMarkers.clear()
        waypoints.forEachIndexed { i, p ->
            val kind = when (i) {
                0 -> 0
                waypoints.lastIndex -> 2
                else -> 1
            }
            val marker = WaypointMarker(i, LatLong(p.lat, p.lon), waypointBitmap(i + 1, kind))
            waypointMarkers += marker
            layers.add(marker, false) // above the gesture layer, so taps reach it first
        }
        mapView.layerManager.redrawLayers()
    }

    /** Saved tracks to draw, by id. */
    fun setShownTracks(tracks: Map<String, List<GeoPoint>>) {
        val layers = mapView.layerManager.layers
        val gone = shownTracks.keys - tracks.keys
        for (id in gone) {
            shownTracks.remove(id)?.let { (casing, main) -> layers.remove(casing, false); layers.remove(main, false) }
        }
        for ((id, points) in tracks) {
            if (id in shownTracks) continue
            val casing = line(android.graphics.Color.WHITE, 7f)
            val main = line(android.graphics.Color.rgb(230, 81, 0), 4f)
            val pts = points.map { LatLong(it.lat, it.lon) }
            casing.setPoints(pts)
            main.setPoints(pts)
            val at = layers.indexOf(recordingLine)
            layers.add(at, casing, false)
            layers.add(at + 1, main, false)
            shownTracks[id] = casing to main
        }
        mapView.layerManager.redrawLayers()
    }

    fun setRecording(points: List<GeoPoint>) {
        recordingLine.setPoints(points.map { LatLong(it.lat, it.lon) })
        mapView.layerManager.redrawLayers()
    }

    /** Moves the camera so all points are visible. */
    fun showPoints(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }
        val dim = mapView.model.mapViewDimension.dimension
        val zoom = if (dim == null || (maxLat - minLat < 1e-6 && maxLon - minLon < 1e-6)) {
            15
        } else {
            LatLongUtils.zoomForBounds(
                Dimension((dim.width * 0.8).toInt(), (dim.height * 0.6).toInt()),
                BoundingBox(minLat, minLon, maxLat, maxLon),
                mapView.model.displayModel.tileSize,
            ).toInt()
        }
        moveTo(MapPos((minLat + maxLat) / 2, (minLon + maxLon) / 2, zoom.coerceIn(MIN_ZOOM, 17)))
    }

    private fun line(color: Int, widthDp: Float): Polyline =
        Polyline(
            graphics.createPaint().apply {
                setColor(color)
                setStyle(Style.STROKE)
                setStrokeWidth(widthDp * density)
                setStrokeCap(Cap.ROUND)
                setStrokeJoin(Join.ROUND)
            },
            graphics,
        )

    private inner class WaypointMarker(val index: Int, at: LatLong, bitmap: org.mapsforge.core.graphics.Bitmap) :
        Marker(at, bitmap, 0, 0) {
        override fun onTap(tapLatLong: LatLong?, layerXY: Point?, tapXY: Point?): Boolean {
            if (layerXY == null || tapXY == null || !contains(layerXY, tapXY, mapView)) return false
            onWaypointTap?.invoke(index)
            return true
        }
    }

    /** Numbered circle: green start, purple via points, red end. */
    private fun waypointBitmap(number: Int, kind: Int): org.mapsforge.core.graphics.Bitmap {
        val size = (26 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        p.color = android.graphics.Color.WHITE
        c.drawCircle(r, r, r - density, p)
        p.color = when (kind) {
            0 -> android.graphics.Color.rgb(46, 125, 50)
            2 -> android.graphics.Color.rgb(198, 40, 40)
            else -> android.graphics.Color.rgb(106, 27, 154)
        }
        c.drawCircle(r, r, r - 3 * density, p)
        p.color = android.graphics.Color.WHITE
        p.textSize = 12 * density
        p.textAlign = android.graphics.Paint.Align.CENTER
        p.isFakeBoldText = true
        c.drawText(number.toString(), r, r - (p.descent() + p.ascent()) / 2, p)
        return AndroidGraphicFactory.convertToBitmap(BitmapDrawable(context.resources, bmp))
    }

    // ---- map tools ------------------------------------------------------------------------------

    /** A piece of the A–B line: plain (measuring), or coloured by whether the ground is in sight. */
    data class ToolLine(val points: List<GeoPoint>, val visible: Boolean?)

    private val toolLayers = ArrayList<Layer>()
    private var viewshedLayer: ViewshedLayer? = null

    /** Draws the map tools: points A and B, the line between them, a range ring and a viewshed. */
    fun setTools(a: GeoPoint?, b: GeoPoint?, lines: List<ToolLine>, ring: Pair<GeoPoint, Double>?, viewshed: Viewshed?) {
        val layers = mapView.layerManager.layers
        val keep = viewshedLayer?.takeIf { it.vs === viewshed }
        for (l in toolLayers) {
            layers.remove(l, false)
            if (l !== keep) l.onDestroy()
        }
        toolLayers.clear()
        viewshedLayer = null
        var at = layers.indexOf(accuracyCircle) // below the own-position dot, above tracks
        fun add(l: Layer) {
            layers.add(at++, l, false)
            toolLayers += l
        }
        if (viewshed != null) {
            val v = keep ?: ViewshedLayer(viewshed)
            viewshedLayer = v
            add(v)
        }
        ring?.let { (c, r) ->
            add(Circle(LatLong(c.lat, c.lon), r.toFloat(), null, graphics.createPaint().apply {
                setColor(android.graphics.Color.argb(200, 33, 33, 33)); setStyle(Style.STROKE); setStrokeWidth(2f * density)
            }))
        }
        for (l in lines) add(line(android.graphics.Color.WHITE, 7f).apply { setPoints(l.points.map { LatLong(it.lat, it.lon) }) })
        for (l in lines) {
            val color = when (l.visible) {
                true -> android.graphics.Color.rgb(46, 125, 50)
                false -> android.graphics.Color.rgb(198, 40, 40)
                null -> android.graphics.Color.rgb(21, 101, 192)
            }
            add(line(color, 4f).apply { setPoints(l.points.map { LatLong(it.lat, it.lon) }) })
        }
        a?.let { add(Marker(LatLong(it.lat, it.lon), letterBitmap("A", android.graphics.Color.rgb(21, 101, 192)), 0, 0)) }
        b?.let { add(Marker(LatLong(it.lat, it.lon), letterBitmap("B", android.graphics.Color.rgb(239, 108, 0)), 0, 0)) }
        mapView.layerManager.redrawLayers()
    }

    /** Visible ground green, hidden ground red; the grid is in Web Mercator so it scales linearly to map pixels. */
    private inner class ViewshedLayer(val vs: Viewshed) : Layer() {
        private val bitmap: Bitmap = Bitmap.createBitmap(vs.size, vs.size, Bitmap.Config.ARGB_8888).apply {
            val visible = android.graphics.Color.argb(80, 67, 160, 71)
            val hidden = android.graphics.Color.argb(115, 198, 40, 40)
            setPixels(IntArray(vs.size * vs.size) { i ->
                when (vs.cells[i].toInt()) { 1 -> visible; 0 -> hidden; else -> 0 }
            }, 0, vs.size, 0, 0, vs.size, vs.size)
        }
        private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        private val dst = android.graphics.RectF()

        override fun draw(boundingBox: BoundingBox?, zoomLevel: Byte, canvas: Canvas?, topLeftPoint: Point?, rotation: Rotation?) {
            val c = canvas?.let { AndroidGraphicFactory.getCanvas(it) } ?: return
            val tl = topLeftPoint ?: return
            if (bitmap.isRecycled) return
            val mapSize = MercatorProjection.getMapSize(zoomLevel, displayModel.tileSize)
            dst.set(
                (MercatorProjection.longitudeToPixelX(vs.west, mapSize) - tl.x).toFloat(),
                (MercatorProjection.latitudeToPixelY(vs.north, mapSize) - tl.y).toFloat(),
                (MercatorProjection.longitudeToPixelX(vs.east, mapSize) - tl.x).toFloat(),
                (MercatorProjection.latitudeToPixelY(vs.south, mapSize) - tl.y).toFloat(),
            )
            c.drawBitmap(bitmap, null, dst, paint)
        }

        override fun onDestroy() {
            bitmap.recycle()
        }
    }

    private fun letterBitmap(text: String, color: Int): org.mapsforge.core.graphics.Bitmap {
        val size = (28 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        p.color = android.graphics.Color.argb(70, 0, 0, 0)
        c.drawCircle(r, r + density, r - density, p)
        p.color = android.graphics.Color.WHITE
        c.drawCircle(r, r, r - 2 * density, p)
        p.color = color
        c.drawCircle(r, r, r - 4 * density, p)
        p.color = android.graphics.Color.WHITE
        p.textSize = 14 * density
        p.textAlign = android.graphics.Paint.Align.CENTER
        p.isFakeBoldText = true
        c.drawText(text, r, r - (p.descent() + p.ascent()) / 2, p)
        return AndroidGraphicFactory.convertToBitmap(BitmapDrawable(context.resources, bmp))
    }

    // ---- overlays ---------------------------------------------------------------------------

    fun updateLocation(location: Location?) {
        if (location == null) {
            accuracyCircle.setVisible(false, false)
            positionMarker.setVisible(false, false)
        } else {
            val ll = LatLong(location.latitude, location.longitude)
            accuracyCircle.setLatLong(ll)
            accuracyCircle.setRadius(if (location.hasAccuracy()) location.accuracy else 0f)
            positionMarker.setLatLong(ll)
            accuracyCircle.setVisible(true, false)
            positionMarker.setVisible(true, false)
        }
        mapView.layerManager.redrawLayers()
    }

    fun setPin(latLong: LatLong?) {
        if (latLong != null) pinMarker.setLatLong(latLong)
        pinMarker.setVisible(latLong != null, false)
        mapView.layerManager.redrawLayers()
    }

    /** A 3D camera looking at the map centre from about as far as the 2D map shows. */
    fun camera3D(): app.maparound.three.Camera3D? {
        val h = mapView.height
        if (h <= 0) return null
        val pos = mapView.model.mapViewPosition.mapPosition
        val mapSize = MercatorProjection.getMapSize(pos.zoomLevel, mapView.model.displayModel.tileSize)
        // Ground metres across the screen height, then the distance that shows that at a 45° field of view.
        val metres = MercatorProjection.calculateGroundResolution(pos.latLong.latitude, mapSize) * h
        val distance = (metres / (2 * Math.tan(Math.toRadians(22.5))) * 0.8).toFloat()
        return app.maparound.three.Camera3D(pos.latLong.latitude, pos.latLong.longitude, distance)
    }

    // ---- camera -----------------------------------------------------------------------------

    fun position(): MapPos {
        val p = mapView.model.mapViewPosition.mapPosition
        return MapPos(p.latLong.latitude, p.latLong.longitude, p.zoomLevel.toInt())
    }

    fun moveTo(pos: MapPos) {
        val lat = pos.lat.coerceIn(-85.0, 85.0)
        val lon = pos.lon.coerceIn(-180.0, 180.0)
        mapView.model.mapViewPosition.mapPosition =
            MapPosition(LatLong(lat, lon), pos.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM).toByte())
    }

    /** Pans to the location; zooms in to [minZoom] first if the map is zoomed out further. */
    fun animateTo(lat: Double, lon: Double, minZoom: Int? = null) {
        val position = mapView.model.mapViewPosition
        if (minZoom != null && position.zoomLevel < minZoom) {
            val max = position.zoomLevelMax.toInt()
            position.mapPosition = MapPosition(LatLong(lat, lon), minOf(minZoom, max).toByte())
        } else {
            position.animateTo(LatLong(lat, lon))
        }
    }

    fun zoomIn() = mapView.model.mapViewPosition.zoomIn()
    fun zoomOut() = mapView.model.mapViewPosition.zoomOut()

    // ---- lifecycle --------------------------------------------------------------------------

    fun onResume() {
        resumed = true
        (baseLayer as? TileDownloadLayer)?.onResume()
        (bgLayer as? TileDownloadLayer)?.onResume()
        trailOverlay?.onResume()
    }

    fun onPause() {
        resumed = false
        (baseLayer as? TileDownloadLayer)?.onPause()
        (bgLayer as? TileDownloadLayer)?.onPause()
        trailOverlay?.onPause()
    }

    fun destroy() {
        onPause()
        mapView.destroyAll()
        baseHills?.interruptAndDestroy()
        trailOverlayCache?.destroy()
        bgCache?.destroy()
        bgLayer = null
        bgCache = null
        baseLayer = null
        baseCache = null
        baseHills = null
        baseKey = null
        trailOverlay = null
        trailOverlayCache = null
        AndroidGraphicFactory.clearResourceMemoryCache()
    }

    // ---- internals --------------------------------------------------------------------------

    /**
     * Invisible layer that reports taps and long presses.
     *
     * All parameters of mapsforge callbacks are nullable on purpose: mapsforge passes
     * `layerXY = null` for layers without a position (like this one), and a non-null Kotlin
     * parameter would make every tap throw.
     */
    private inner class GestureLayer : Layer() {
        override fun draw(
            boundingBox: BoundingBox?,
            zoomLevel: Byte,
            canvas: Canvas?,
            topLeftPoint: Point?,
            rotation: Rotation?,
        ) = Unit

        override fun onTap(tapLatLong: LatLong?, layerXY: Point?, tapXY: Point?): Boolean {
            val ll = tapLatLong ?: return false
            return onTap?.invoke(ll) ?: false
        }

        override fun onLongPress(tapLatLong: LatLong?, layerXY: Point?, tapXY: Point?): Boolean {
            val ll = tapLatLong ?: return false
            val callback = onLongPress ?: return false
            callback(ll)
            return true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installTouchListener() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        mapView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> onUserGesture?.invoke()
                MotionEvent.ACTION_MOVE ->
                    if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) onUserGesture?.invoke()
            }
            false // let the MapView handle the gesture itself
        }
    }

    private fun dotBitmap(color: Int): org.mapsforge.core.graphics.Bitmap {
        val size = (24 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        p.color = android.graphics.Color.argb(60, 0, 0, 0)
        c.drawCircle(r, r + density, r - density, p)
        p.color = android.graphics.Color.WHITE
        c.drawCircle(r, r, r - 2 * density, p)
        p.color = color
        c.drawCircle(r, r, r - 5 * density, p)
        return AndroidGraphicFactory.convertToBitmap(BitmapDrawable(context.resources, bmp))
    }

    private fun pinBitmap(): org.mapsforge.core.graphics.Bitmap {
        val w = (28 * density).toInt()
        val h = (40 * density).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = w / 2f
        p.color = android.graphics.Color.rgb(211, 47, 47)
        val path = Path().apply {
            moveTo(r, h.toFloat())
            lineTo(r - r * 0.62f, r * 1.55f)
            lineTo(r + r * 0.62f, r * 1.55f)
            close()
        }
        c.drawPath(path, p)
        c.drawCircle(r, r, r - density, p)
        p.color = android.graphics.Color.WHITE
        c.drawCircle(r, r, r * 0.38f, p)
        return AndroidGraphicFactory.convertToBitmap(BitmapDrawable(context.resources, bmp))
    }

    companion object {
        private const val TAG = "MapController"
        const val MIN_ZOOM = 2
        const val MAX_ZOOM = 21
    }
}
