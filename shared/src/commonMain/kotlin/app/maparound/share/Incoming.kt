package app.maparound.share

import app.maparound.routing.GeoPoint
import app.maparound.tracks.SavedTrack
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Something another app shared with MapAround (a link, text with coordinates, a GPX file),
 * waiting in [pending] until the UI shows it: points, routes and tracks on the map, messages
 * as a snackbar.
 */
object Incoming {
    sealed class Item {
        data class Point(val point: Shared.Point) : Item()
        data class Route(val route: Shared.Route) : Item()
        data class Track(val saved: SavedTrack, val placesAdded: Int = 0) : Item()

        /** Waypoints from a GPX file, added to the saved places. */
        data class Places(val added: Int, val points: List<GeoPoint>) : Item()
        data class Message(val text: String) : Item()
    }

    val pending = MutableStateFlow<Item?>(null)

    fun consume() {
        pending.value = null
    }
}
