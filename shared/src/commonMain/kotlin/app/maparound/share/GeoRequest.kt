package app.maparound.share

import app.maparound.routing.GeoPoint
import app.maparound.routing.RouteProfile
import app.maparound.shared.util.urlDecode

/**
 * What a request to the car's map asks: Google Assistant's and other apps' navigation intents for
 * navigation apps (developer.android.com/develop/devices/assistant/intents-assistant-nav-app).
 *
 * - "Navigate to X": action `androidx.car.app.action.NAVIGATE`, `geo:0,0?q=X` (text to search),
 *   `geo:lat,lon` (a point), `geo:lat,lon?q=X` (X near there), with `mode=` (w walk, b bike,
 *   others by car) and `intent=` (navigation, directions: the route without starting, add_a_stop).
 * - "Find X": action VIEW, `geo:0,0?q=X`: places to choose from.
 * - Commands: `geo.action:?act=…` (exit_navigation, mute, show_satellite, select_search_result&id=n…).
 *
 * The offline variants (`geo.offline:`, `geo.action.offline:`) read the same.
 */
sealed class GeoRequest {
    /** Where to go: a [point] (with its [label]), or [query] text to search, near [near] when given. */
    data class Navigate(
        val point: GeoPoint?,
        val label: String?,
        val query: String?,
        val near: GeoPoint?,
        /** How the request travels (null: it doesn't say). */
        val profile: RouteProfile?,
        val mode: Mode,
    ) : GeoRequest()

    /** Navigate now; only the route (started by the driver); or a stop on the way. */
    enum class Mode { NAVIGATE, DIRECTIONS, ADD_STOP }

    /** Places that fit [query], near [near] when given, to choose from. */
    data class Search(val query: String, val near: GeoPoint?) : GeoRequest()

    /** A command ([act]: exit_navigation, mute…); [id]: select_search_result's (0 the first). */
    data class Command(val act: String, val id: Int?) : GeoRequest()

    companion object {
        const val ACTION_NAVIGATE = "androidx.car.app.action.NAVIGATE"
        private const val ACTION_NAVIGATE_PHONE = "android.intent.action.NAVIGATE"

        /** The request in an intent's [action] and [data]; null when it isn't one. */
        fun parse(action: String?, data: String?): GeoRequest? {
            val uri = data?.trim().orEmpty()
            val colon = uri.indexOf(':')
            if (colon <= 0) return null
            val scheme = uri.substring(0, colon).lowercase().removeSuffix(".offline")
            val rest = uri.substring(colon + 1)
            val params = params(rest.substringAfter('?', ""))
            if (scheme == "geo.action") {
                val act = params["act"]?.takeIf { it.isNotBlank() } ?: return null
                return Command(act, params["id"]?.toIntOrNull())
            }
            if (scheme != "geo") return null
            val at = coordinates(rest.substringBefore('?'))
            val q = params["q"]?.trim()?.takeIf { it.isNotEmpty() }
            // "q=lat,lon(label)": a point after all.
            val qPoint = q?.let { COORDS_LABEL.matchEntire(it) }
            val point = qPoint?.let { m -> coordinates("${m.groupValues[1]},${m.groupValues[2]}") } ?: at.takeIf { q == null }
            val label = qPoint?.groupValues?.get(3)?.trim()?.takeIf { it.isNotEmpty() }
            val query = q?.takeIf { qPoint == null }
            if (point == null && query == null) return null
            val navigate = action == ACTION_NAVIGATE || action == ACTION_NAVIGATE_PHONE
            // "Find X" (VIEW with text): places to choose from; a point is shown with its route.
            if (!navigate && query != null) return Search(query, at)
            val mode = when {
                !navigate -> Mode.DIRECTIONS
                params["intent"] == "directions" -> Mode.DIRECTIONS
                params["intent"] == "add_a_stop" -> Mode.ADD_STOP
                else -> Mode.NAVIGATE
            }
            val profile = when (params["mode"]) {
                null -> null
                "w" -> RouteProfile.HIKE
                "b" -> RouteProfile.BIKE
                else -> RouteProfile.CAR
            }
            return Navigate(point, label, query, if (query != null) at else null, profile, mode)
        }

        private val COORDS_LABEL = Regex("""(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:\((.*)\))?""")

        /** "lat,lon" (maybe more after, ",alt" or ";u=…"); null when not there or 0,0 (no place). */
        private fun coordinates(s: String): GeoPoint? {
            val parts = s.split(',', ';')
            val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
            val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
            if (lat == 0.0 && lon == 0.0) return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return GeoPoint(lat, lon)
        }

        /** a=1&b=two+words → its values decoded ('+' a space). */
        private fun params(query: String): Map<String, String> =
            query.split('&').mapNotNull { kv ->
                val k = kv.substringBefore('=').trim()
                if (k.isEmpty()) return@mapNotNull null
                val v = kv.substringAfter('=', "")
                k to runCatching { urlDecode(v.replace('+', ' ')) }.getOrDefault(v.replace('+', ' '))
            }.toMap()
    }
}
