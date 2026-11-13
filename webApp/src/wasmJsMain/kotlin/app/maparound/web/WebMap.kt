package app.maparound.web

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import app.maparound.map.maplibre.NativeCamera
import app.maparound.map.maplibre.NativeMap
import app.maparound.map.maplibre.NativeMapCallbacks

// ---- mapview.js ------------------------------------------------------------------------------------

private fun mlCreate(containerId: String, style: String, symbols: String, onTile: (String) -> JsAny?, onGesture: () -> Unit): Unit =
    js("{ MapAroundMap.create(containerId, style, symbols, onTile, onGesture); }")

private fun mlSetStyle(json: String): Unit = js("{ MapAroundMap.setStyle(json); }")
private fun mlSetGeoJson(id: String, json: String): Unit = js("{ MapAroundMap.setGeoJson(id, json); }")
private fun mlCamera(): String = js("MapAroundMap.camera()")
private fun mlHeight(): Double = js("MapAroundMap.height()")
private fun mlMove(lat: Double, lon: Double, zoom: Double, animated: Boolean): Unit = js("{ MapAroundMap.move(lat, lon, zoom, animated); }")
private fun mlFit(s: Double, w: Double, n: Double, e: Double, animated: Boolean): Unit = js("{ MapAroundMap.fitBounds(s, w, n, e, animated); }")
private fun mlPlace(left: Double, top: Double, width: Double, height: Double): Unit = js("{ MapAroundMap.place(left, top, width, height); }")
private fun mlHide(): Unit = js("{ MapAroundMap.hide(); }")
private fun mlClaim(): Unit = js("{ MapAroundMap.claim(); }")
private fun mlHold(on: Boolean): Unit = js("{ MapAroundMap.hold(on); }")
private fun mlMoved(): Boolean = js("MapAroundMap.moved()")
private fun mlZoomAt(delta: Double, x: Double, y: Double, animated: Boolean): Unit = js("{ MapAroundMap.zoomAt(delta, x, y, animated); }")
private fun mlLngLat(x: Double, y: Double): String = js("MapAroundMap.lngLat(x, y)")
private fun mlTapped(x: Double, y: Double): String = js("MapAroundMap.tapped(x, y)")

/**
 * The MapLibre GL JS map as the shared [NativeMap] (there's one map on the page, in `#map`).
 * [tiles] answers the offline map's tile requests (`maparound://…` URLs) with bytes, or null.
 */
class WebNativeMap(style: String, symbolsUrl: String, tiles: (String) -> JsAny?) : NativeMap {
    var callbacks: NativeMapCallbacks? = null
        private set

    init {
        mlCreate("map", style, symbolsUrl, tiles) { callbacks?.onUserGesture() }
    }

    override fun setStyle(json: String) = mlSetStyle(json)
    override fun setGeoJson(sourceId: String, json: String) = mlSetGeoJson(sourceId, json)

    override fun camera(): NativeCamera {
        val v = mlCamera().split(',').map { it.toDoubleOrNull() ?: 0.0 }
        return NativeCamera(v[0], v[1], v[2], v[3])
    }

    override fun viewHeight(): Double = mlHeight()
    override fun moveCamera(lat: Double, lon: Double, zoom: Double, animated: Boolean) = mlMove(lat, lon, zoom, animated)

    override fun fitBounds(south: Double, west: Double, north: Double, east: Double, animated: Boolean) =
        mlFit(south, west, north, east, animated)

    override fun setCallbacks(callbacks: NativeMapCallbacks?) {
        this.callbacks = callbacks
    }

    fun place(left: Double, top: Double, width: Double, height: Double) = mlPlace(left, top, width, height)
    fun hide() = mlHide()

    /** "lat,lon" → pair. */
    fun lngLat(x: Double, y: Double): Pair<Double, Double> {
        val v = mlLngLat(x, y).split(',')
        return (v[0].toDoubleOrNull() ?: 0.0) to (v[1].toDoubleOrNull() ?: 0.0)
    }

    fun tapped(x: Double, y: Double): String? = mlTapped(x, y).ifEmpty { null }
}

/**
 * The map on the map screen: a transparent hole in the app's canvas with the MapLibre map behind
 * it, placed to the same spot. Compose gets the touches first (so buttons and sheets over the map
 * get theirs); a press that reaches the map lets the map follow the fingers (mapview.js).
 */
@Composable
fun WebMapView(map: WebNativeMap, modifier: Modifier) {
    val density = LocalDensity.current.density
    DisposableEffect(map) { onDispose { map.hide() } }
    Box(
        modifier
            .onGloballyPositioned { c ->
                val b = c.boundsInWindow()
                map.place(b.left / density.toDouble(), b.top / density.toDouble(), b.width / density.toDouble(), b.height / density.toDouble())
            }
            .drawBehind { drawRect(Color.Black, blendMode = BlendMode.Clear) }
            .pointerInput(map) { mapGestures(map) }
            .pointerInput(map) { wheelZoom(map) },
    )
}

private const val LONG_PRESS_MS = 500L
private const val DOUBLE_TAP_MS = 280L

/**
 * The map's side of a gesture. Moving the map (pan, pinch, twist, fling) is mapview.js's, which
 * sees every finger; here the press is claimed for the map (it reached the map, not something
 * over it), and taps, double taps and long presses go to the map screen.
 */
private suspend fun PointerInputScope.mapGestures(map: WebNativeMap) {
    val slop = viewConfiguration.touchSlop
    fun css(p: Offset) = (p.x / density).toDouble() to (p.y / density).toDouble()

    awaitPointerEventScope {
        while (true) {
            val first = awaitPointerEvent().changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
            mlClaim()
            val start = first.position
            val startTime = first.uptimeMillis
            var moved = false
            var longPress = false

            gesture@ while (true) {
                val event = if (!moved && !longPress) {
                    withTimeoutOrNull(maxOf(1L, LONG_PRESS_MS - (currentEvent.changes.first().uptimeMillis - startTime))) { awaitPointerEvent() }
                        ?: run {
                            if (mlMoved()) {
                                moved = true
                                return@run null
                            }
                            // Held still: a long press; the map stays put while the finger drags on.
                            longPress = true
                            mlHold(true)
                            val (x, y) = css(start)
                            val (lat, lon) = map.lngLat(x, y)
                            map.callbacks?.onLongPress(lat, lon)
                            null
                        }
                        ?: continue@gesture
                } else {
                    awaitPointerEvent()
                }
                event.changes.forEach { it.consume() }
                // A second finger (or the map moving under one) makes it a map gesture, not a tap.
                if (event.changes.any { it.pressed && !it.previousPressed } ||
                    event.changes.any { (it.position - start).getDistance() > slop } || mlMoved()
                ) {
                    if (!longPress) moved = true
                }
                val c = event.changes.first()
                if (longPress && c.pressed && c.positionChanged()) {
                    val (x, y) = css(c.position)
                    map.lngLat(x, y).let { (lat, lon) -> map.callbacks?.onLongPressMove(lat, lon) }
                }
                if (event.changes.any { it.pressed }) continue@gesture

                // All up.
                val (x, y) = css(c.position)
                when {
                    longPress -> {
                        mlHold(false)
                        map.lngLat(x, y).let { (lat, lon) -> map.callbacks?.onLongPressEnd(lat, lon) }
                    }
                    moved -> Unit
                    else -> {
                        // A tap, unless a second one follows soon: then zoom in there.
                        // (Compose's own timeout here: kotlinx's can't interrupt waiting for pointer events.)
                        val second = withTimeoutOrNull(DOUBLE_TAP_MS) {
                            var d: PointerInputChange?
                            do {
                                d = awaitPointerEvent().changes.firstOrNull { it.pressed && !it.previousPressed }
                            } while (d == null)
                            d
                        }
                        if (second == null) {
                            map.callbacks?.onTap(map.lngLat(x, y).first, map.lngLat(x, y).second, map.tapped(x, y))
                        } else {
                            mlClaim()
                            map.callbacks?.onUserGesture()
                            val (sx, sy) = css(second.position)
                            mlZoomAt(1.0, sx, sy, animated = true)
                            // Let the second tap's finger go.
                            do {
                                val e = awaitPointerEvent()
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.pressed })
                        }
                    }
                }
                break@gesture
            }
        }
    }
}

/** The mouse wheel (or a touchpad's two-finger scroll) zooms where the pointer is. */
private suspend fun PointerInputScope.wheelZoom(map: WebNativeMap) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Main)
            if (e.type != PointerEventType.Scroll) continue
            // Ctrl + wheel (a trackpad pinch) is mapview.js's, which keeps the page from zooming.
            if (e.keyboardModifiers.isCtrlPressed) continue
            val c = e.changes.firstOrNull() ?: continue
            val dy = c.scrollDelta.y
            if (dy == 0f) continue
            map.callbacks?.onUserGesture()
            val delta = (-dy * 0.5f).coerceIn(-1f, 1f)
            mlZoomAt(delta.toDouble(), (c.position.x / density).toDouble(), (c.position.y / density).toDouble(), animated = false)
            c.consume()
        }
    }
}


