package app.maparound.share

import app.maparound.places.PlaceRepository
import app.maparound.places.Places
import app.maparound.routing.GeoPoint
import app.maparound.shared.util.nowMillis
import app.maparound.tools.Coords
import app.maparound.tracks.Gpx
import app.maparound.tracks.SavedTrack
import app.maparound.tracks.TrackRepository
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

    /**
     * A place or route in shared text: a map link (Google, OSM, Apple, BRouter…), a geo: link, or
     * a grid reference or code on its own (UTM, MGRS, Plus Code, deg-min-sec…) read near [near].
     * Null when there's none (a short link may still lead to one: [SharedLinks.shortLink]).
     */
    fun fromText(text: String, near: GeoPoint?): Item? {
        SharedLinks.parse(text)?.let { return of(it) }
        if (text.trim().length <= 60) {
            Coords.parse(text, reference = near)?.let { return Item.Point(Shared.Point(it.point.lat, it.point.lon, null, null)) }
        }
        return null
    }

    fun of(shared: Shared): Item = when (shared) {
        is Shared.Point -> Item.Point(shared)
        is Shared.Route -> Item.Route(shared)
    }

    /**
     * A GPX file: tracks and routes go to the library (and are shown), waypoints are added to the
     * saved places; a single waypoint is just shown as a pin. Throws when there's nothing to use.
     */
    fun importGpx(bytes: ByteArray, baseName: String, tracks: TrackRepository, places: PlaceRepository): Item {
        val content = Gpx.parse(bytes)
        val hasLine = content.trackPoints.isNotEmpty() || content.routePoints.isNotEmpty()
        val waypoints = if (hasLine || content.waypoints.size >= 2) Places.readGpx(bytes) else emptyList()
        return when {
            hasLine -> Item.Track(tracks.save(Gpx.toTrack(content, baseName, nowMillis())), placesAdded = places.addAll(waypoints))
            waypoints.isNotEmpty() -> Item.Places(places.addAll(waypoints), waypoints.map { it.point })
            else -> {
                val w = content.waypoints.firstOrNull() ?: error("No track, route or waypoint in this GPX file")
                Item.Point(Shared.Point(w.lat, w.lon, w.name ?: baseName, null))
            }
        }
    }
}
