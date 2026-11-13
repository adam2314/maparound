package app.maparound.map.gl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.Gravity
import app.maparound.map.maplibre.MapStyle
import app.maparound.map.maplibre.NativeCamera
import app.maparound.map.maplibre.NativeMap
import app.maparound.map.maplibre.NativeMapCallbacks
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.InputStream

/**
 * MapLibre's [MapView] behind the shared map code (the counterpart of the iPhone's
 * MapLibreMap.swift): the shared MapLibreSurface decides everything (style, overlays as GeoJSON,
 * camera); this class passes it on and reports taps and gestures back.
 */
class AndroidNativeMap(
    context: Context,
    /** Draws text, symbols and lines this much larger (the car screen, read at a glance). */
    textScale: Float = 1f,
) : NativeMap {
    private val density = context.resources.displayMetrics.density * textScale
    private val markers = MarkerBitmaps(density)

    val view: MapView = MapView(
        context,
        MapLibreMapOptions.createFromAttributes(context)
            .logoEnabled(false)
            .attributionEnabled(false)
            .compassEnabled(true)
            .pixelRatio(density)
            // Chinese, Japanese and Korean labels come from the phone's own fonts.
            .localIdeographFontFamily("sans-serif"),
    )

    /** Opens a symbol of the current map style ("symbols/peak.svg"). */
    var symbols: (String) -> InputStream? = { null }

    private var map: MapLibreMap? = null
    private var callbacks: NativeMapCallbacks? = null

    /** The latest GeoJSON of every overlay source, applied again whenever a style has loaded. */
    private val geoJson = LinkedHashMap<String, String>()
    private var styleLoaded = false
    private var pendingStyle: String? = null
    private var pendingCamera: NativeCamera? = null
    private var pendingBounds: DoubleArray? = null

    private var longPressing = false

    private val scaleBar = ScaleBarView(context)

    init {
        view.onCreate(null)
        view.addView(
            scaleBar,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply { setMargins((8 * density).toInt(), (8 * density).toInt(), 0, 0) },
        )
        // Marker and symbol images are drawn from their names when the map first needs them.
        view.addOnStyleImageMissingListener { id ->
            val style = map?.style ?: return@addOnStyleImageMissingListener
            markers.image(id) { path -> symbols(path) }?.let { style.addImage(id, it, false) }
        }
        view.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isTiltGesturesEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = false
                // Under the scale bar, clear of the buttons on the right.
                compassGravity = Gravity.TOP or Gravity.START
                setCompassMargins((12 * density).toInt(), (112 * density).toInt(), 0, 0)
            }
            // Fewer lower-zoom tiles fetched ahead: the app makes its tiles on the phone.
            m.prefetchZoomDelta = 2
            m.setMinZoomPreference(MIN_ZOOM)
            m.setMaxZoomPreference(MAX_ZOOM)
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) callbacks?.onUserGesture()
            }
            val scale = { m.cameraPosition.let { c -> c.target?.let { scaleBar.update(it.latitude, c.zoom) } } }
            m.addOnCameraMoveListener { scale() }
            m.addOnCameraIdleListener { scale() }
            m.addOnMapClickListener { p ->
                val screen = m.projection.toScreenLocation(p)
                callbacks?.onTap(p.latitude, p.longitude, tapped(m, screen))
                true
            }
            m.addOnMapLongClickListener { p ->
                // Until the finger is lifted, moves go to the screen (dragging a route stop), not the map.
                longPressing = true
                m.uiSettings.isScrollGesturesEnabled = false
                callbacks?.onLongPress(p.latitude, p.longitude)
                true
            }
            pendingCamera?.let { c -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c.lat, c.lon), c.zoom)) }
            pendingCamera = null
            pendingStyle?.let { applyStyle(m, it) }
            pendingStyle = null
            pendingBounds?.let { b -> fitBounds(b[0], b[1], b[2], b[3], animated = false) }
            pendingBounds = null
        }
        trackLongPress()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun trackLongPress() {
        view.setOnTouchListener { _, ev ->
            if (longPressing) {
                val p = map?.projection?.fromScreenLocation(PointF(ev.x, ev.y))
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> p?.let { callbacks?.onLongPressMove(it.latitude, it.longitude) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressing = false
                        map?.uiSettings?.isScrollGesturesEnabled = true
                        p?.let { callbacks?.onLongPressEnd(it.latitude, it.longitude) }
                    }
                }
            }
            false
        }
    }

    /** The `tap` property of the topmost marker near the finger (layers named "tap-…", see MapStyle.kt). */
    private fun tapped(m: MapLibreMap, p: PointF): String? {
        val style = m.style ?: return null
        val ids = style.layers.map { it.id }.filter { it.startsWith(MapStyle.TAP_PREFIX) }
        if (ids.isEmpty()) return null
        val r = 22 * density
        for (f in m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), *ids.toTypedArray())) {
            if (f.hasNonNullValueForProperty("tap")) return f.getStringProperty("tap")
        }
        return null
    }

    // ---- NativeMap ------------------------------------------------------------------------------------

    override fun setStyle(json: String) {
        val m = map
        if (m == null) {
            pendingStyle = json
            return
        }
        applyStyle(m, json)
    }

    private fun applyStyle(m: MapLibreMap, json: String) {
        styleLoaded = false
        m.setStyle(Style.Builder().fromJson(json)) { style ->
            styleLoaded = true
            for ((id, data) in geoJson) apply(style, id, data)
        }
    }

    override fun setGeoJson(sourceId: String, json: String) {
        geoJson[sourceId] = json
        val style = map?.style ?: return
        if (styleLoaded) apply(style, sourceId, json)
    }

    private fun apply(style: Style, id: String, json: String) {
        style.getSourceAs<GeoJsonSource>(id)?.setGeoJson(json)
    }

    override fun camera(): NativeCamera {
        val m = map ?: return pendingCamera ?: NativeCamera(0.0, 0.0, 1.0, 0.0)
        val c = m.cameraPosition
        val t = c.target ?: return NativeCamera(0.0, 0.0, c.zoom, c.bearing)
        return NativeCamera(t.latitude, t.longitude, c.zoom, c.bearing)
    }

    override fun viewHeight(): Double = view.height / density.toDouble()

    override fun moveCamera(lat: Double, lon: Double, zoom: Double, animated: Boolean) {
        val m = map
        if (m == null) {
            pendingCamera = NativeCamera(lat, lon, zoom, 0.0)
            return
        }
        val update = CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom)
        if (animated) m.animateCamera(update, ANIMATION_MS) else m.moveCamera(update)
    }

    override fun fitBounds(south: Double, west: Double, north: Double, east: Double, animated: Boolean) {
        val m = map
        if (m == null) {
            pendingBounds = doubleArrayOf(south, west, north, east)
            return
        }
        val bounds = LatLngBounds.from(north, east, south, west)
        val d = density
        val update = CameraUpdateFactory.newLatLngBounds(bounds, (48 * d).toInt(), (100 * d).toInt(), (48 * d).toInt(), (160 * d).toInt())
        if (animated) m.animateCamera(update, ANIMATION_MS) else m.moveCamera(update)
    }

    override fun setCallbacks(callbacks: NativeMapCallbacks?) {
        this.callbacks = callbacks
    }

    // ---- the car screen's controls ------------------------------------------------------------------------

    /** Moves the map's content by screen pixels (a drag on the car's screen), as MapLibre's own pan does. */
    fun panBy(dx: Float, dy: Float) {
        map?.scrollBy(dx, dy)
    }

    /** Zooms in (+1) or out (−1) a whole level. */
    fun zoomBy(levels: Double) {
        map?.animateCamera(CameraUpdateFactory.zoomBy(levels), 300)
    }

    /** Keeps the camera's target in the middle of what's left inside these margins (pixels). */
    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        map?.moveCamera(CameraUpdateFactory.paddingTo(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble()))
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    fun onResume() {
        view.onStart()
        view.onResume()
    }

    fun onPause() {
        view.onPause()
        view.onStop()
    }

    fun onDestroy() {
        callbacks = null
        view.onDestroy()
    }

    private companion object {
        const val ANIMATION_MS = 600

        // mapsforge's limits (2…21), in MapLibre's zooms (one lower).
        const val MIN_ZOOM = 1.0
        const val MAX_ZOOM = 20.0
    }
}
