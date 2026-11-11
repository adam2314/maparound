package app.maparound.routing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A request for the map to show some points (e.g. "show on map" in the Routes tab). */
object MapFocus {
    private val _request = MutableStateFlow<List<GeoPoint>?>(null)
    val request: StateFlow<List<GeoPoint>?> = _request.asStateFlow()

    fun show(points: List<GeoPoint>) {
        _request.value = points
    }

    fun consumed() {
        _request.value = null
    }
}
