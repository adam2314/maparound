package app.maparound.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the map looks: centre, zoom (Web Mercator levels) and bearing (degrees from north). */
data class MapCamera(val lat: Double, val lon: Double, val zoom: Double, val bearing: Double)

/**
 * The map as the shared screens see it. Implemented by the platform's map: mapsforge on Android
 * (phase 2), MapLibre on iOS (Swift). Called on the main thread.
 */
interface SpikeMapSurface {
    fun moveTo(lat: Double, lon: Double, zoom: Double)
    fun zoomBy(delta: Double)
    fun resetNorth()
}

/** What the platform's map reports back to the shared screens (main thread). */
class MapEvents {
    private val _camera = MutableStateFlow(MapCamera(0.0, 0.0, 0.0, 0.0))
    val camera: StateFlow<MapCamera> = _camera.asStateFlow()

    fun cameraChanged(lat: Double, lon: Double, zoom: Double, bearing: Double) {
        _camera.value = MapCamera(lat, lon, zoom, bearing)
    }
}
