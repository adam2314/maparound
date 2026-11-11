package app.maparound.map.maplibre

// The MapLibre view as the shared code drives it. The platform (iOS: Swift around MLNMapView)
// implements only these few calls; the style, the overlays as GeoJSON and the camera maths are
// shared Kotlin ([MapLibreSurface], [MapStyle]) and tested on the JVM.

/** The camera in MapLibre's terms (512-point tiles: one zoom level below mapsforge's). */
data class NativeCamera(val lat: Double, val lon: Double, val zoom: Double, val bearing: Double)

/** A MapLibre map view. Called on the main thread. */
interface NativeMap {
    /** Replaces the style (a MapLibre style JSON document). */
    fun setStyle(json: String)

    /**
     * New data for one of the style's GeoJSON sources. The view keeps the latest data of every
     * source and applies it again after [setStyle], so overlays survive style changes.
     */
    fun setGeoJson(sourceId: String, json: String)

    fun camera(): NativeCamera

    /** Height of the view in points (0 before it is laid out). */
    fun viewHeight(): Double

    fun moveCamera(lat: Double, lon: Double, zoom: Double, animated: Boolean)

    /** Shows the area, with some padding. */
    fun fitBounds(south: Double, west: Double, north: Double, east: Double, animated: Boolean)

    fun setCallbacks(callbacks: NativeMapCallbacks?)
}

/** What happens on the native map. Called on the main thread. */
interface NativeMapCallbacks {
    /** The user started moving the map (pan, pinch, rotate). */
    fun onUserGesture()

    /**
     * A tap. [tapped] is the `tap` property of the topmost marker under the finger (on the
     * style's layers whose id starts with [MapStyle.TAP_PREFIX]), or null for the map itself.
     */
    fun onTap(lat: Double, lon: Double, tapped: String?)

    fun onLongPress(lat: Double, lon: Double)
}
